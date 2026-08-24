package com.tudominio.parentalcontrol.reward

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.time.TimeProvider
import com.tudominio.parentalcontrol.domain.toCanonicalGrantTimestamp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manager para el banco de tiempo / recompensas.
 *
 * §0.3: Los grants de recompensa tienen source='reward'.
 * §0.9: Sin saldo infinito, respeta topes del padre.
 *
 * `database` is Hilt-injected (PR 4 of `align-with-guia-fedora44`). Use the
 * [RewardManagerEntryPoint] bridge only for non-Hilt call sites.
 */
@Singleton
class RewardManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase,
    private val timeProvider: TimeProvider,
) {

    companion object {
        private const val TAG = "RewardManager"

        // Scope para grants de recompensa
        const val REWARD_SCOPE = "reward"

        // Máximo acumulado (2 horas por defecto)
        private const val DEFAULT_MAX_BALANCE_MINUTES = 120L

        /**
         * Convenience accessor for non-Hilt call sites. Production code
         * inside `@AndroidEntryPoint` / `@HiltViewModel` should inject the
         * manager directly via `@Inject RewardManager`.
         */
        fun getInstance(context: Context): RewardManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                RewardManagerEntryPoint::class.java
            )
            return entryPoint.rewardManager()
        }
    }

    private val grantDao = database.grantDao()
    // Prefs para el tope máximo
    private val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)

    /**
     * Obtiene el saldo total de recompensas disponibles.
     *
     * Scoped to [deviceId] so two paired devices in the same family
     * never see each other's rewards. The pre-fix code queried the DAO
     * with `scope` only — that summed grants across every paired device
     * and surfaced a phantom balance on devices that had never earned
     * any reward time.
     */
    suspend fun getRewardBalance(deviceId: String): Long {
        val now = timeProvider.wallInstant().toString()
        val maxBalance = getMaxBalanceMinutes()

        return try {
            val grants = grantDao.getActiveGrantsForScopeOnce(deviceId, REWARD_SCOPE, now)
            val totalMinutes = grants.sumOf { it.minutes }.toLong()
            minOf(totalMinutes, maxBalance)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reward balance: ${e.message}")
            0
        }
    }

    /**
     * Obtiene todos los grants de recompensa activos.
     *
     * Scoped to [deviceId]; see [getRewardBalance] for the
     * cross-device rationale.
     *
     * Uses the unfiltered deviceId+scope Flow plus an in-memory
     * `expires_at > now` filter so the wall-clock is re-evaluated on
     * every emission. The pre-fix code did the same staleness-recovery
     * trick; switching to a SQL-bound `:now` parameter would freeze
     * the cutoff at subscription time and silently keep an expired
     * grant in the list until the next table change.
     */
    fun getActiveRewardGrants(deviceId: String): Flow<List<RewardGrantUi>> {
        return grantDao.getGrantsForScopeFlow(deviceId, REWARD_SCOPE).map { grants ->
            val now = timeProvider.wallInstant().toString()
            grants.filter { it.expires_at > now }
                .sortedBy { it.expires_at }
                .map { it.toUi() }
        }
    }

    /**
     * Obtiene el historial de recompensas.
     *
     * Scoped to [deviceId]; see [getRewardBalance]. Note: this returns
     * ALL grants (including expired ones) for the requested device so
     * the UI can show the past timeline. Expired rows are flagged via
     * [RewardHistoryItem.isExpired] rather than filtered out.
     */
    suspend fun getRewardHistory(deviceId: String): List<RewardHistoryItem> {
        return try {
            // Use the unfiltered deviceId+scope Flow — history is
            // expected to include both active and expired rows, and
            // the SQL filter used by the active-only view is not
            // appropriate here.
            val grants = grantDao.getGrantsForScopeFlow(deviceId, REWARD_SCOPE).first()

            val now = timeProvider.wallInstant().toString()

            grants.map { grant ->
                val isActive = grant.expires_at > now
                val expiresAt = try {
                    Instant.parse(grant.expires_at)
                } catch (e: Exception) {
                    null
                }

                RewardHistoryItem(
                    id = grant.id,
                    minutes = grant.minutes,
                    grantedAt = grant.granted_at,
                    expiresAt = expiresAt,
                    isActive = isActive,
                    isExpired = expiresAt != null && expiresAt.isBefore(timeProvider.wallInstant())
                )
            }.sortedByDescending { it.grantedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reward history: ${e.message}")
            emptyList()
        }
    }

    /**
     * Obtiene el tope máximo de saldo.
     */
    fun getMaxBalanceMinutes(): Long {
        return prefs.getLong("max_balance", DEFAULT_MAX_BALANCE_MINUTES)
    }

    /**
     * Establece el tope máximo de saldo (solo desde settings del padre).
     */
    fun setMaxBalanceMinutes(maxMinutes: Long) {
        prefs.edit().putLong("max_balance", maxMinutes).apply()
        Log.d(TAG, "Max balance set to $maxMinutes minutes")
    }

    /**
     * Verifica si hay saldo disponible.
     *
     * Scoped to [deviceId]; see [getRewardBalance].
     */
    suspend fun hasRewardBalance(deviceId: String): Boolean {
        return getRewardBalance(deviceId) > 0
    }

    /**
     * Consume tiempo del saldo de recompensa.
     * Devuelve true si se pudo consumir.
     *
     * Scoped to [deviceId]; see [getRewardBalance].
     */
    suspend fun consumeRewardMinutes(deviceId: String, minutes: Int): Boolean {
        val currentBalance = getRewardBalance(deviceId)

        if (currentBalance < minutes) {
            Log.w(TAG, "Not enough reward balance: $currentBalance < $minutes")
            return false
        }

        // En una implementación real, decrementaríamos el grant activo
        // Por ahora, simplemente lo marcamos como usado
        Log.d(TAG, "Consumed $minutes reward minutes, remaining: ${currentBalance - minutes}")
        return true
    }

    /**
     * Procesa un grant de recompensa desde el servidor.
     *
     * §0.3: El grant tiene source='reward'.
     *
     * [deviceId] MUST be the real paired device id, not a placeholder
     * like `"reward_device"` — the previous implementation wrote that
     * magic string to `grants.device_id` and silently orphaned the row
     * from every deviceId-scoped read, so the UI could never see the
     * reward the parent had actually granted.
     */
    suspend fun processRewardGrant(
        grantId: String,
        deviceId: String,
        minutes: Int,
        expiresAt: Instant,
        grantedAt: Instant = timeProvider.wallInstant(),
    ): Boolean {
        if (deviceId.isBlank()) {
            Log.w(TAG, "processRewardGrant called with blank deviceId — refusing to write an orphaned row")
            return false
        }
        return try {
            val grant = GrantEntity(
                id = "reward_$grantId",
                device_id = deviceId,
                request_id = null,
                scope = REWARD_SCOPE,
                minutes = minutes,
                source = "reward",
                granted_at = grantedAt.toCanonicalGrantTimestamp(),
                expires_at = expiresAt.toCanonicalGrantTimestamp()
            )

            grantDao.insertGrant(grant)

            Log.d(TAG, "Reward grant created: $grantId, $minutes min, expires at $expiresAt")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error processing reward grant: ${e.message}")
            false
        }
    }

    /**
     * Limpia grants expirados.
     */
    suspend fun cleanupExpiredGrants() {
        // Room debería manejar esto automáticamente,
        // pero podemos hacer limpieza manual si es necesario
        Log.d(TAG, "Cleaning up expired reward grants")
    }

    private fun GrantEntity.toUi(): RewardGrantUi {
        val expiresAt = try {
            Instant.parse(expires_at)
        } catch (e: Exception) {
            null
        }

        val now = timeProvider.wallInstant()
        val minutesRemaining = if (expiresAt != null) {
            val seconds = java.time.Duration.between(now, expiresAt).seconds
            maxOf(0, seconds / 60)
        } else {
            minutes
        }

        return RewardGrantUi(
            id = id,
            minutes = minutes,
            minutesRemaining = minutesRemaining.toInt(),
            expiresAt = expiresAt,
            grantedAt = granted_at
        )
    }
}

/**
 * UI model para grant de recompensa.
 */
data class RewardGrantUi(
    val id: String,
    val minutes: Int,
    val minutesRemaining: Int,
    val expiresAt: Instant?,
    val grantedAt: String
) {
    val isExpiringSoon: Boolean
        get() {
            val expires = expiresAt ?: return false
            val minutesLeft = java.time.Duration.between(
                Instant.now(), expires
            ).toMinutes()
            return minutesLeft in 0..5
        }

    val isExpired: Boolean
        get() {
            val expires = expiresAt ?: return false
            return expires.isBefore(Instant.now())
        }
}

/**
 * Item de historial de recompensas.
 */
data class RewardHistoryItem(
    val id: String,
    val minutes: Int,
    val grantedAt: String,
    val expiresAt: Instant?,
    val isActive: Boolean,
    val isExpired: Boolean
)

/**
 * Hilt [EntryPoint] that exposes [RewardManager] from the
 * `SingletonComponent` to non-Hilt call sites (legacy Workers, plain
 * unit tests). Production code MUST inject the manager directly via
 * `@Inject RewardManager` to avoid the runtime lookup cost.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RewardManagerEntryPoint {
    fun rewardManager(): RewardManager
}
