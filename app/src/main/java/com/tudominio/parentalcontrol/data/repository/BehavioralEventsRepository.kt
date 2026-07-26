package com.tudominio.parentalcontrol.data.repository

import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.BehavioralEventDao
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.data.remote.BehavioralEventFixture
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parent-facing reader for `behavioral_events`.
 *
 * Change A of `feat-parent-behavioral-event-log` (PR A only). Mirrors
 * `ParentRepository.getPendingRequests` for the wire shape (Bearer + apikey
 * headers, `bodyAsText()` decode, `Result.success`/`Transient` failure)
 * and `BehavioralEventDao` for the local cache (Flow + `upsertAll`).
 *
 * PR B wraps this with the `BehaviorLogViewModel` + screen; PR A only
 * ships the data-layer API.
 */
@Singleton
class BehavioralEventsRepository @Inject constructor(
    private val clientProvider: SupabaseClientProvider,
    private val dao: BehavioralEventDao,
    private val authManager: DeviceAuthManager
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Pass-through Flow to the DAO. The PR B VM collects this and
     * joins against the cached child list to attach `childId`.
     */
    fun observe(parentId: String): Flow<List<BehavioralEventEntity>> =
        dao.flowByParent(parentId)

    /**
     * Pulls the parent's most-recent events from Supabase and upserts
     * them into Room so the next [observe] emission reflects the
     * server snapshot.
     *
     * Wire: `GET /rest/v1/behavioral_events?parent_id=eq.<id>&order=created_at.desc&limit=50`.
     * Returns `Result.success(Unit)` after the DAO upsert, or
     * `Result.failure(DeviceListError.AuthMissing / Transient)` on auth
     * failure or non-2xx response (matches `ParentRepository.renameChild`).
     */
    suspend fun refresh(parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val url = "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/behavioral_events" +
                "?parent_id=eq.$parentId&order=created_at.desc&limit=50"

            val response = clientProvider.httpClient.get(url) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${response.status}")
                )
            }

            val body = json.decodeFromString(
                ListSerializer(BehavioralEventFixture.serializer()),
                response.bodyAsText()
            )
            dao.upsertAll(body.map { it.toEntity() })
            Result.success(Unit)
        } catch (e: IllegalStateException) {
            // Typed AuthMissing contract: any IllegalStateException with
            // "not authenticated" message bubbles as AuthMissing.
            if (e.message?.contains("not authenticated") == true) {
                Result.failure(DeviceListError.AuthMissing)
            } else {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
    }
}

/**
 * Test-side seam alias. The screen test (`BehaviorLogScreenTest.kt:79`)
 * imports `BehaviorLogRepository`; the canonical data-layer class is
 * [BehavioralEventsRepository] (per `proposal.md` open question #1).
 * PR B may replace this alias with a real `interface` mirroring the Q2
 * picker test-seam pattern.
 */
typealias BehaviorLogRepository = BehavioralEventsRepository
