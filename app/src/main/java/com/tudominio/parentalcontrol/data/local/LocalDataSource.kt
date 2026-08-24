package com.tudominio.parentalcontrol.data.local

import androidx.room.withTransaction
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.data.model.PolicySnapshotState
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.data.model.UsageTodayEntity
import com.tudominio.parentalcontrol.data.model.WindowEntity
import com.tudominio.parentalcontrol.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class LocalDataSource(
    private val database: ParentalDatabase
) {
    private val policyDao = database.policyDao()
    private val appPolicyDao = database.appPolicyDao()
    private val grantDao = database.grantDao()
    private val usageDao = database.usageDao()
    private val outboxDao = database.outboxDao()
    private val timeRequestDao = database.timeRequestDao()
    private val behavioralEventDao = database.behavioralEventDao()

    // =============================================================================
    // T03.3 — Guard de versión atómico
    // =============================================================================
    /**
     * Atomicity contract (F2 of the T03 review): the local `policy`
     * row, the `app_policies` rewrite, and the `grants` rewrite commit
     * together in a single Room `withTransaction { ... }` block. The
     * pre-fix code wrote each table in its own suspend call, so a
     * crash (or a `SQLiteException` from any DAO call) between
     * `upsertPolicyIfNewer` and `insertGrants` left the policy
     * version bumped while the previous app_policies / grants rows
     * were already deleted — a half-applied policy that
     * `getPolicyFlow` then re-emitted to every observer as the
     * "latest truth".
     *
     * The transaction boundary is the smallest clean dependency
     * change: `androidx.room.withTransaction` is already on the
     * classpath via `room-ktx` (used by
     * [com.tudominio.parentalcontrol.data.repository.TimeExtraRepository])
     * and the existing DAOs are sufficient — no new DAO method, no
     * schema change. We DO NOT change the v9 → v10 schema/migration.
     */
    suspend fun syncPolicy(policy: Policy): Boolean {
        val entity = PolicyEntity(
            device_id = policy.device_id,
            version = policy.version.toLong(),
            category_assignments = policy.category_assignments,
            device_state = policy.device_state.name,
            daily_screen_time_minutes = policy.daily_screen_time_minutes,
            schedules = policy.schedules,
            category_limits = policy.category_limits,
        )
        val appPolicyEntities = policy.app_policies.map { it.toEntity(policy.device_id) }
        val grantEntities = policy.grants.map { it.toEntity(policy.device_id) }
        return database.withTransaction {
            val updated = policyDao.upsertPolicyIfNewer(entity)
            if (updated) {
                appPolicyDao.deleteAppPoliciesForDevice(policy.device_id)
                appPolicyDao.upsertAppPolicies(appPolicyEntities)
                grantDao.deleteGrantsForDevice(policy.device_id)
                grantDao.insertGrants(grantEntities)
            }
            updated
        }
    }

    fun getPolicyFlow(deviceId: String): Flow<Policy?> {
        return combine(
            policyDao.getPolicyFlow(deviceId),
            appPolicyDao.getAppPoliciesForDeviceFlow(deviceId),
            grantDao.getGrantsForDeviceFlow(deviceId),
        ) { entity, apps, grants ->
            // F3 — `mapNotNull` instead of `map` so a row with an
            // unknown `GrantSource` (e.g. a future server that
            // introduces a new grant type) is dropped instead of
            // terminating the entire policy flow. We deliberately do
            // NOT silently coerce unknown sources to MANUAL — that
            // path would let malformed data acquire broader
            // semantics than the parent actually granted. See
            // [LocalDataSourceAtomicityTest] for the regression.
            entity?.toDomain(apps.mapNotNull { it.toDomainOrNull() }, grants.mapNotNull { it.toDomainOrNull() })
        }
    }

    fun getPolicySnapshotStateFlow(deviceId: String): Flow<PolicySnapshotState> =
        getPolicyFlow(deviceId).map { policy ->
            if (policy == null) PolicySnapshotState.Empty else PolicySnapshotState.Valid(policy)
        }

    // =============================================================================
    // T03.4 — Agregados para el motor
    // =============================================================================
    fun getUsageContextFlow(deviceId: String, serverDate: String): Flow<UsageContext> {
        val usageByPkg = usageDao.getUsageForDateFlow(serverDate).map { usages ->
            usages.associate { it.package_name to it.usage_minutes }
        }
        val globalUsage = usageDao.getGlobalUsageFlow(serverDate).map { it ?: 0 }
        val appPolicies = appPolicyDao.getAllAppPoliciesFlow()

        return combine(usageByPkg, globalUsage, appPolicies) { byPkg, global, policies ->
            val categoryAssignments = policies.mapNotNull { policy ->
                policy.category?.let { policy.package_name to it }
            }.toMap()

            val byCategory = byPkg.entries.groupBy { (pkg, _) ->
                categoryAssignments[pkg] ?: "uncategorized"
            }.mapValues { (_, entries) ->
                entries.sumOf { it.value }
            }

            UsageContext(
                usagePorApp = byPkg,
                usagePorCategoria = byCategory,
                tiempoGlobal = global
            )
        }
    }

    // =============================================================================
    // T03.5 — usage_today por fecha de servidor con rollover
    // =============================================================================
    fun getServerDate(): String {
        return LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE)
    }

    suspend fun incrementUsage(packageName: String, serverDate: String, deltaMinutes: Int) {
        usageDao.incrementUsage(packageName, serverDate, deltaMinutes)
    }

    fun getUsageForDateFlow(serverDate: String): Flow<List<UsageTodayEntity>> {
        return usageDao.getUsageForDateFlow(serverDate)
    }

    // =============================================================================
    // T03.2 — Outbox genérica
    // =============================================================================
    suspend fun enqueueOutboxItem(tipo: String, payload: String, dedupKey: String?, serverDate: String): Boolean {
        if (dedupKey != null) {
            val existing = outboxDao.findByDedupKey(dedupKey)
            if (existing != null) return false
        }
        outboxDao.insertOutboxItem(
            OutboxEntity(
                tipo = tipo,
                payload_json = payload,
                dedup_key = dedupKey,
                created_at = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME),
                server_date = serverDate
            )
        )
        return true
    }

    suspend fun drainOutbox(maxAttempts: Int = 3, batchSize: Int = 50): List<OutboxEntity> {
        val items = outboxDao.getPendingItems(maxAttempts, batchSize)
        items.forEach { item ->
            outboxDao.incrementRetries(item.id)
        }
        return items
    }

    suspend fun removeOutboxItem(id: UUID) {
        outboxDao.deleteItem(id)
    }

    fun getOutboxPendingCountFlow(): Flow<Int> {
        return outboxDao.getPendingCountFlow()
    }

    // =============================================================================
    // Time Requests
    // =============================================================================
    fun getTimeRequestsFlow(deviceId: String): Flow<List<TimeRequestEntity>> {
        return timeRequestDao.getRequestsForDeviceFlow(deviceId)
    }

    fun getPendingTimeRequestsFlow(): Flow<List<TimeRequestEntity>> {
        return timeRequestDao.getPendingRequestsFlow()
    }

    suspend fun insertTimeRequest(request: TimeRequestEntity) {
        timeRequestDao.insertRequest(request)
    }

    // =============================================================================
    // Behavioral Events
    // =============================================================================
    suspend fun insertBehavioralEvent(event: BehavioralEventEntity) {
        behavioralEventDao.insert(event)
    }

    suspend fun getUnsyncedBehavioralEvents(limit: Int = 100): List<BehavioralEventEntity> {
        return behavioralEventDao.getUnsyncedEvents(limit)
    }

    suspend fun markBehavioralEventsSynced(eventIds: List<Long>) {
        behavioralEventDao.markSynced(eventIds)
    }

    suspend fun getUnsyncedBehavioralEventsCount(): Int {
        return behavioralEventDao.getUnsyncedCount()
    }

    // =============================================================================
    // Conversions
    // =============================================================================
    private fun PolicyEntity.toDomain(appPolicies: List<AppPolicy>, grants: List<Grant>): Policy {
        return Policy(
            device_id = device_id,
            version = version.toInt(),
            device_state = runCatching { DeviceState.valueOf(device_state) }.getOrDefault(DeviceState.ACTIVE),
            daily_screen_time_minutes = daily_screen_time_minutes,
            schedules = schedules,
            category_limits = category_limits,
            app_policies = appPolicies,
            category_assignments = category_assignments,
            grants = grants,
        )
    }

    private fun AppPolicyEntity.toDomainOrNull(): AppPolicy? = runCatching {
        AppPolicy(
            package_name,
            AppPolicyState.valueOf(state),
            daily_limit_minutes,
            allowed_windows.map { Window(it.days.map(DayOfWeek::valueOf), it.from, it.to) },
            category,
        )
    }.getOrNull()

    private fun GrantEntity.toDomainOrNull(): Grant? = runCatching {
        Grant(
            id = id,
            request_id = request_id,
            scope = scope,
            minutes = minutes,
            source = GrantSource.valueOf(source.uppercase()),
            granted_at = granted_at,
            expires_at = expires_at,
        )
    }.getOrNull()

    private fun AppPolicy.toEntity(deviceId: String): AppPolicyEntity {
        return AppPolicyEntity(
            package_name = package_name,
            device_id = deviceId,
            state = state.name,
            daily_limit_minutes = daily_limit_minutes,
            allowed_windows = allowed_windows.map { WindowEntity(it.days.map { d -> d.name }, it.from, it.to) },
            category = category
        )
    }

    private fun Grant.toEntity(deviceId: String): GrantEntity {
        return GrantEntity(
            id = id,
            device_id = deviceId,
            request_id = request_id,
            scope = scope,
            minutes = minutes,
            source = source.name,
            granted_at = canonicalGrantTimestamp(granted_at),
            expires_at = canonicalGrantTimestamp(expires_at)
        )
    }
}
