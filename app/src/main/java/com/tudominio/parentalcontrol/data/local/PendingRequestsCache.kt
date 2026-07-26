package com.tudominio.parentalcontrol.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DataStore-backed cache for the parent's pending time requests.
 *
 * The Solicitudes tab in the parent dashboard reads from
 * [com.tudominio.parentalcontrol.data.repository.ParentRepository.pendingRequestsFlow],
 * which holds the most-recently-fetched list of pending time requests.
 * Without a disk-backed cache, the flow re-initializes to `emptyList()` on
 * every cold start, so the parent sees a "Sin solicitudes" flicker for up
 * to 5-15 minutes (until the next `SolicitudesPollingWorker` tick) even
 * though the Supabase `time_requests` table still has rows.
 *
 * This cache is the cold-start source of truth:
 *  - `read()` is called once from `ParentRepository.init {}` so the first
 *    collector of `pendingRequestsFlow` gets the persisted list, not the
 *    default `emptyList()`.
 *  - `write(list)` is called from `ParentRepository.publishPendingRequests`
 *    after a network fetch so the next cold start restores the same list.
 *  - `observe()` is exposed for future consumers that want to react to
 *    cache changes (e.g., cross-tab sync); today only the init-time
 *    hydration uses it.
 *
 * Format: a JSON-encoded `List<TimeRequest>` stored under [KEY_REQUESTS]
 * in the [NAME] Preferences file. The serializer is lenient and ignores
 * unknown keys (mirrors `ParentRepository.json` at `ParentRepository.kt`)
 * so a future server-side field addition does not break deserialization
 * of an existing cache file.
 *
 * **One DataStore per file per process.** `androidx.datastore` enforces
 * this via a registry that throws `IllegalStateException` when a second
 * DataStore is created against an active file. To honor that constraint,
 * this class uses a per-application-context factory (`[get]`) that
 * memoizes a single [PendingRequestsCache] (and its underlying
 * [DataStore]) for any caller that hands us the same application
 * context. Two `ParentRepository` instances constructed against the same
 * `context` — e.g., the warm and cold instances in
 * `ParentRepositoryColdStartTest` — share the same DataStore handle,
 * which is what the registry expects.
 *
 * Construction: callers use the [get] factory in production and in
 * tests. There is no Hilt binding — `ParentRepository` builds and owns
 * its instance. This avoids cascading test-signature changes elsewhere
 * in the test set.
 */
class PendingRequestsCache private constructor(private val appContext: Context) {

    init {
        // DataStore requires a real, non-null application context with a
        // real on-disk file path. Probing `preferencesDataStoreFile` here
        // surfaces a meaningful error at the construction site rather than
        // letting an NPE blow up 12 stack frames deep in `java.io.File`
        // with a confusing `parent.path is null` message. The probe is
        // captured into a local val so the JIT does not elide it.
        @Suppress("UNUSED_VARIABLE")
        val probe = runCatching { appContext.preferencesDataStoreFile(NAME) }
            .getOrElse {
                error(
                    "PendingRequestsCache requires a real Context " +
                        "with non-null filesDir (got $appContext)"
                )
            }
        require(probe.parentFile != null) {
            "PendingRequestsCache requires a real Context with non-null filesDir (got $appContext)"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { appContext.preferencesDataStoreFile(NAME) }
        )
    }

    /**
     * Reads the cached list from disk. Returns an empty list when the
     * cache file is missing or the JSON cannot be parsed (e.g., a partial
     * write from a previous crash). IO is dispatched to [Dispatchers.IO]
     * so callers in a Main-thread context do not block on disk.
     */
    suspend fun read(): List<TimeRequest> = withContext(Dispatchers.IO) {
        decodeList(dataStore.data.first())
    }

    /**
     * Reactive view of the cached list. Re-emits on every successful
     * [write]. Used by [com.tudominio.parentalcontrol.data.repository.ParentRepository]
     * for cold-start hydration.
     */
    fun observe(): Flow<List<TimeRequest>> = dataStore.data.map { prefs ->
        decodeList(prefs)
    }

    /**
     * Persists the given list, replacing the previous snapshot. The write
     * is atomic from the caller's perspective: by the time this `suspend`
     * returns, a subsequent [read] from a different [PendingRequestsCache]
     * instance pointing at the same file returns [list].
     */
    suspend fun write(list: List<TimeRequest>) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_REQUESTS)] = json.encodeToString(
                ListSerializer(TimeRequest.serializer()),
                list
            )
        }
    }

    private fun decodeList(prefs: Preferences): List<TimeRequest> {
        val raw = prefs[stringPreferencesKey(KEY_REQUESTS)] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TimeRequest.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        /**
         * DataStore namespace for the pending-requests cache. Pinned to
         * the same value the existing `ParentRepositoryColdStartTest`'s
         * `PendingRequestsPrefs.NAME` references; promoted from test-side
         * to production-side in this change so callers (Production + test)
         * share the source of truth.
         */
        const val NAME = "parent_pending_requests_cache"

        /**
         * Preference key holding the JSON-encoded list. Versioned in the
         * key (the `_v1` suffix) so a future schema-breaking change can
         * migrate without colliding with old payloads.
         */
        const val KEY_REQUESTS = "requests_json_v1"

        /**
         * Per-application-context cache. Each unique application context
         * (typically one per Robolectric sandbox, one per real Android
         * app process) maps to one [PendingRequestsCache] and therefore
         * one [DataStore]. The map is intentionally JVM-bounded — it
         * accumulates one entry per sandbox; for tests that is O(number
         * of tests run).
         */
        private val instances = mutableMapOf<String, PendingRequestsCache>()

        /**
         * Returns the [PendingRequestsCache] for [context], creating it
         * on first use. Multiple calls with the same application context
         * return the same instance, so the underlying [DataStore] is
         * exactly one per file per process — satisfying the
         * `androidx.datastore` registry invariant.
         */
        fun get(context: Context): PendingRequestsCache {
            val appCtx = context.applicationContext
            val key = System.identityHashCode(appCtx).toString()
            return synchronized(instances) {
                instances.getOrPut(key) { PendingRequestsCache(appCtx) }
            }
        }

        /**
         * Test-only helper: deletes the on-disk cache file. Called from
         * each test's `@Before` to guarantee isolation across tests in
         * the same JVM (Robolectric does not wipe app data between tests
         * unless explicitly told).
         */
        fun clearForTest(context: Context) {
            context.applicationContext.preferencesDataStoreFile(NAME).delete()
        }
    }
}
