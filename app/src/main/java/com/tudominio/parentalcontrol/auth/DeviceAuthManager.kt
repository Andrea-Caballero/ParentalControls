package com.tudominio.parentalcontrol.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine
import com.tudominio.parentalcontrol.security.network.NetworkSecurityConfig
import com.tudominio.parentalcontrol.keystore.SecureStorage
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class AuthResult {
    data class Success(
        val deviceId: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long
    ) : AuthResult()

    data class NeedsPairing(
        val message: String
    ) : AuthResult()

    data class Retryable(
        val message: String
    ) : AuthResult()

    data class Error(
        val message: String
    ) : AuthResult()
}

enum class SessionState {
    NONE,
    ANONYMOUS,
    PAIRED,
    EXPIRED,
    INVALID
}

@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val deviceId: String?,
    val userId: String
)

internal sealed interface SessionRestoreOutcome {
    data object NoPersistedSession : SessionRestoreOutcome
    data class Restored(val session: StoredSession) : SessionRestoreOutcome
    data object TransientFailure : SessionRestoreOutcome
    data object InvalidSession : SessionRestoreOutcome
}

private class RefreshFailure(val statusCode: Int?) : IllegalStateException()

private fun Throwable.isMalformedAuthStorageFailure(): Boolean =
    this is IllegalArgumentException ||
        this is IndexOutOfBoundsException ||
        this is AEADBadTagException ||
        this is kotlinx.serialization.SerializationException

@Serializable
data class SupabaseAuthResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
    val expires_at: Long? = null,
    val user: SupabaseUser? = null
)

@Serializable
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    val app_metadata: Map<String, String>? = null,
    val user_metadata: Map<String, String>? = null
)

internal enum class DeviceAuthEngine {
    SHARED_MOCK,
    IN_PROCESS_MOCK,
    REAL
}

internal fun selectDeviceAuthEngine(
    useSharedMock: Boolean,
    useMockSupabase: Boolean
): DeviceAuthEngine = when {
    useSharedMock -> DeviceAuthEngine.SHARED_MOCK
    useMockSupabase -> DeviceAuthEngine.IN_PROCESS_MOCK
    else -> DeviceAuthEngine.REAL
}

internal fun effectiveDeviceAuthUrl(
    useSharedMock: Boolean,
    supabaseUrl: String,
    sharedMockUrl: String,
    path: String
): String {
    val baseUrl = if (useSharedMock) sharedMockUrl else supabaseUrl
    return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}

class DeviceAuthManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "DeviceAuthManager"
        // T3 wiring — read from BuildConfig so a `-PsupabaseUrl=`
        // / `-PsupabaseAnonKey=` build actually reaches real Supabase.
        // Was hardcoded placeholders that masked every real-cloud
        // request as `NETWORK_ERROR`.
        val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
        val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY
        private const val PREFS_NAME = "device_auth_prefs"
        private const val KEY_SYNTHETIC_ACCESS_TOKEN = "synthetic_access_token"

        // Slice A clean-cutover (Q2=b): the regex that distinguishes a
        // real Supabase `auth.users.id` UUID from a legacy mock-engine
        // sentinel like `"parent-demo"`. Used by `loadPersistedState` to
        // decide whether to wipe the prefs (no real cloud session).
        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        @Volatile
        private var instance: DeviceAuthManager? = null

        /**
         * Test seam (W1 cipher extraction): the JVM unit-test source
         * set writes a `TestableAuthCipher` here BEFORE constructing a
         * fresh `DeviceAuthManager`. The constructor consults this
         * static on first read and picks up the test cipher as the
         * initial value of [sessionCipher]. After construction the
         * static is cleared, so subsequent constructions revert to the
         * production `AuthCipher()` (Android Keystore-backed) — which
         * is correct because the singleton lives across the test run
         * but the static is per-test.
         *
         * Robolectric 4.10.3 cannot instantiate `AndroidKeyStore`, and
         * the production cipher requires it. The
         * JVM auth persistence tests use this seam so the production
         * `init { loadPersistedState }` decrypt path can run without
         * Android Keystore.
         *
         * Marked `@JvmStatic` for Java reflection access from the JVM
         * unit-test source set (`TestableAuthCipher` is also
         * `internal`, so only the same module sees the static).
         */
        @JvmStatic
        internal var testCipherOverride: AuthCipher? = null

        /**
         * F1 — test seam for the build-mode gate. Production reads
         * `BuildConfig.USE_MOCK_SUPABASE || BuildConfig.USE_SHARED_MOCK`
         * (see [loadPersistedState]). Unit tests set this to `false`
         * to exercise the release-like clean-cutover wipe branch
         * without relying on `Field.modifiers` reflection (which is
         * unreliable on JDK 17+). `null` = use the real `BuildConfig`
         * value; `Boolean` = override for this JVM unit-test run.
         */
        @JvmStatic
        internal var testIsMockOrSharedMockBuild: Boolean? = null

        fun getInstance(context: Context): DeviceAuthManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceAuthManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Handle to the AES/GCM cipher used by `encryptWithKeystore` /
     * `decryptWithKeystore`. Constructed by either consulting the
     * [testCipherOverride] static (test source set only) or
     * instantiating the production `AuthCipher` (Android
     * Keystore-backed).
     */
    internal var sessionCipher: AuthCipher =
        DeviceAuthManager.testCipherOverride ?: AuthCipher()

    private val secureStorage = SecureStorage.getInstance(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: HttpClient = when (
        selectDeviceAuthEngine(
            useSharedMock = BuildConfig.USE_SHARED_MOCK,
            useMockSupabase = BuildConfig.USE_MOCK_SUPABASE
        )
    ) {
        DeviceAuthEngine.SHARED_MOCK -> HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
            }
        }
        DeviceAuthEngine.IN_PROCESS_MOCK -> {
            // Per `fix-supabase-client-provider-legacy-mock-gate` family: every
            // legacy `getInstance` path in the project must honor the same flag
            // the Hilt `@SupabaseClient` binding honors in `NetworkModule`.
            // `DeviceAuthManager` has its own private `httpClient` (used by
            // `createAnonymousSession` and `completePairing`); without this
            // branch the auth call hits the placeholder Supabase URL and
            // surfaces as `NETWORK_ERROR` in `PairingManager` before the
            // pairing call can use the (already-mock'd) `SupabaseClientProvider`.
            MockSupabaseEngine(context).httpClient
        }
        DeviceAuthEngine.REAL -> HttpClient(OkHttp) {
            engine {
                preconfigured = NetworkSecurityConfig.createSecureOkHttpClient(context)
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
            }
        }
    }

    private fun requestUrl(path: String): String = effectiveDeviceAuthUrl(
        useSharedMock = BuildConfig.USE_SHARED_MOCK,
        supabaseUrl = SUPABASE_URL,
        sharedMockUrl = BuildConfig.SHARED_MOCK_URL,
        path = path
    )

    private val _sessionState = MutableStateFlow(SessionState.NONE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    private var currentAccessToken: String? = null
    private var currentRefreshToken: String? = null
    private var sessionExpiresAt: Long = 0

    private val timeProvider: TimeProvider = DefaultTimeProvider(context)

    private val sessionMutex = Mutex()

    init {
        // Reset the test override so subsequent constructions revert
        // to the production cipher. Done in `init` (after all field
        // initializers complete) so it has a chance to run without
        // racing with `loadPersistedState`'s potential NPE on
        // un-initialized `_deviceId` / `_sessionState` if we put the
        // reset earlier.
        DeviceAuthManager.testCipherOverride = null
        loadPersistedState()
    }

    suspend fun authenticateOrCreate(): AuthResult = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            when (val outcome = restoreSessionOutcome()) {
                is SessionRestoreOutcome.Restored -> {
                    val restoredSession = outcome.session
                    currentAccessToken = restoredSession.accessToken
                    currentRefreshToken = restoredSession.refreshToken
                    sessionExpiresAt = restoredSession.expiresAt
                    _deviceId.value = restoredSession.deviceId
                    _sessionState.value =
                        if (restoredSession.deviceId != null) SessionState.PAIRED else SessionState.ANONYMOUS

                    return@withContext AuthResult.Success(
                        deviceId = restoredSession.deviceId ?: "anonymous",
                        accessToken = restoredSession.accessToken,
                        refreshToken = restoredSession.refreshToken,
                        expiresAt = restoredSession.expiresAt
                    )
                }
                SessionRestoreOutcome.NoPersistedSession -> return@withContext createAnonymousSession()
                SessionRestoreOutcome.TransientFailure -> {
                    _sessionState.value = SessionState.EXPIRED
                    Log.w(TAG, "auth_session_reauthentication_unavailable")
                    return@withContext AuthResult.Retryable("La sesión requiere autenticación")
                }
                SessionRestoreOutcome.InvalidSession -> {
                    currentAccessToken = null
                    currentRefreshToken = null
                    sessionExpiresAt = 0
                    _deviceId.value = null
                    _sessionState.value = SessionState.INVALID
                    context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    Log.w(TAG, "auth_session_reauthentication_required")
                    return@withContext AuthResult.NeedsPairing("La sesión requiere autenticación")
                }
            }
        }
    }

    /**
     * Role-aware synthetic anonymous auth.
     *
     * Issues a local JWT-shaped token of the form `anon-${role}-${uuid}` and
     * persists the [role] in `device_auth_prefs` so [getRole] can return it
     * after a process restart. This is the local synthetic-auth compatibility
     * path: it
     * does NOT call Supabase, so it works even when `SUPABASE_URL` is a
     * placeholder (the current `local.properties` state).
     *
     * For the [Role.PARENT] case also persists `parent_id =
     * [MockSupabaseEngine.MOCK_PARENT_ID]` so [getParentId] returns a
     * non-null value matching the `parent_id` written by the mock-engine
     * fixtures (`parent_id = "parent-demo"`). Without this, the
     * `BehaviorLogViewModel` `.orEmpty()` coercion collapses to `""` and
     * the DAO filter `WHERE parent_id = ''` matches zero fixture rows.
     *
     * The synthetic token is acknowledged throwaway; the eventual
     * `parent-auth-flow` change will replace this with real sign-up/sign-in.
     * The [Role] flag is the seam between the synthetic hotfix and the
     * formal flow.
     *
     * Kept as an overload (alongside the no-arg `authenticateOrCreate()`
     * above) because [com.tudominio.parentalcontrol.network.SupabaseClientProvider]
     * and [DeviceAuthService] still call the no-arg shape.
     */
    suspend fun authenticateOrCreate(role: Role): Result<Unit> = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val token = "anon-${role.name}-${java.util.UUID.randomUUID()}"
                currentAccessToken = token
                currentRefreshToken = ""
                sessionExpiresAt = 0
                _deviceId.value = null
                _sessionState.value = SessionState.ANONYMOUS

                val prefsEditor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("role", role.name)
                    .putString(KEY_SYNTHETIC_ACCESS_TOKEN, token)

                if (role == Role.PARENT) {
                    // Synthetic PARENT auth must write parent_id alongside role +
                    // synthetic_access_token so BehaviorLogViewModel can read it back
                    // via getParentId() and the DAO filter matches fixture rows.
                    // CHILD path deliberately omits parent_id per proposal Q2=(n)
                    // (no child reader of parent_id exists today).
                    prefsEditor.putString("parent_id", MockSupabaseEngine.MOCK_PARENT_ID)
                }

                prefsEditor.apply()

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Returns the role persisted by [authenticateOrCreate] (PARENT or CHILD),
     * or `null` if no role has been persisted yet (fresh install, or only
     * the no-arg `authenticateOrCreate()` was used and it has not stored
     * a `role` key). The role is the only key written by the synthetic
     * hotfix path; it survives a process restart via SharedPreferences.
     */
    fun getRole(): Role? {
        val name = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("role", null) ?: return null
        return runCatching { Role.valueOf(name) }.getOrNull()
    }

    suspend fun createAnonymousSession(): AuthResult = withContext(Dispatchers.IO) {
        try {
            // R1 — official Supabase anonymous-auth entry point:
            // `POST /auth/v1/signup` with an empty JSON body returns a
            // real anonymous AccessTokenResponse with access_token /
            // refresh_token / expires_in. Per Supabase docs the project
            // MUST have `enable_anonymous_sign_ins=true` (Dashboard →
            // Authentication → Providers → Anonymous). The shared-mock
            // backend mirrors the production shape via the
            // `mock-supabase/auth-anonymous.json` fixture.
            //
            // We MUST NOT use the deprecated `/auth/v1/token?
            // grant_type=password` flow because (a) it requires a
            // server-side random email + password, which has no real
            // anonymous-user record in `auth.users`, (b) the password
            // grant is a synthetic shadow that cannot be later correlated
            // with the agent-side `user.id` that the production pairing
            // edge function validates against.
            val response = httpClient.post(requestUrl("/auth/v1/signup")) {
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            if (response.status.isSuccess()) {
                val authResponse: SupabaseAuthResponse = response.body()
                return@withContext handleAuthSuccess(authResponse)
            }

            AuthResult.Error("Error creando sesión: ${response.status}")
        } catch (e: Exception) {
            AuthResult.Error("Error creando sesión: ${e.message}")
        }
    }

    private suspend fun handleAuthSuccess(response: SupabaseAuthResponse): AuthResult {
        val previousDeviceId = _deviceId.value
        val wasPaired = _sessionState.value == SessionState.PAIRED

        currentAccessToken = response.access_token
        currentRefreshToken = response.refresh_token
                sessionExpiresAt = response.expires_at ?: (timeProvider.wallInstant().epochSecond + response.expires_in)

        val deviceId = response.user?.app_metadata?.get("device_id") ?: previousDeviceId
        _deviceId.value = deviceId
        _sessionState.value = if (wasPaired || deviceId != null) SessionState.PAIRED else SessionState.ANONYMOUS

        persistSession(
            StoredSession(
                accessToken = response.access_token,
                refreshToken = response.refresh_token,
                expiresAt = sessionExpiresAt,
                deviceId = deviceId,
                userId = response.user?.id ?: ""
            )
        )

        return AuthResult.Success(
            deviceId = deviceId ?: "anonymous",
            accessToken = response.access_token,
            refreshToken = response.refresh_token,
            expiresAt = sessionExpiresAt
        )
    }

    suspend fun completePairing(pairingCode: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post(requestUrl("/functions/v1/pairing")) {
                header("Authorization", "Bearer $currentAccessToken")
                header("Content-Type", "application/json")
                setBody(json.encodeToString(mapOf("code" to pairingCode)))
            }

            if (!response.status.isSuccess()) {
                return@withContext AuthResult.Error("Emparejamiento fallido: ${response.status}")
            }

            // R1.5 — the production pairing response shape carries only
            // { device_id, parent_id }. The agent's anon JWT (carried in
            // the Authorization header) stays valid for the post-pairing
            // session — `savePairedSession` below persists those
            // CURRENT pre-pairing tokens to disk so the next cold start
            // restores the real anon session without a second anon-auth
            // round-trip.
            //
            // `ignoreUnknownKeys = true` tolerates real-fixture additions
            // (e.g. `policy_version`, `created_at`) without a parser
            // bump.
            @Serializable
            data class PairingResponse(
                val device_id: String,
                val parent_id: String? = null,
                @Suppress("unused") val policy_version: Int? = null
            )

            val responseBody: PairingResponse = response.body()
            val newDeviceId = responseBody.device_id

            savePairedSession(newDeviceId, responseBody.parent_id)

            AuthResult.Success(
                deviceId = newDeviceId,
                accessToken = currentAccessToken ?: "",
                refreshToken = currentRefreshToken ?: "",
                expiresAt = sessionExpiresAt
            )
        } catch (e: Exception) {
            AuthResult.Error("Error en emparejamiento: ${e.message}")
        }
    }

    suspend fun refreshSession(): AuthResult = withContext(Dispatchers.IO) {
        val refreshToken = currentRefreshToken
            ?: return@withContext AuthResult.NeedsPairing("No hay sesión")

        // F2 — shared `performTokenRefresh` helper so the HTTP call +
        // JSON parse live in exactly one place (also reused by the
        // cold-start refresh path in `restoreSession`).
        val result = performTokenRefresh(refreshToken)
        return@withContext result.fold(
            onSuccess = { authResponse -> handleAuthSuccess(authResponse) },
            onFailure = { _ ->
                _sessionState.value = SessionState.INVALID
                Log.w(TAG, "auth_session_refresh_failed")
                AuthResult.NeedsPairing("La sesión requiere autenticación")
            }
        )
    }

    /**
     * F2 — shared refresh-token HTTP round-trip used by [refreshSession]
     * (active-session refresh) and [restoreSession] (cold-start refresh
     * after expiry detection). No state mutation — callers persist and
     * update in-memory fields so each path's side effects stay
     * localized. Returns [Result.success] on HTTP 2xx; [Result.failure]
     * on any other status or network error.
     */
    private suspend fun performTokenRefresh(
        refreshToken: String
    ): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post(requestUrl("/auth/v1/token?grant_type=refresh_token")) {
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(mapOf("refresh_token" to refreshToken)))
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(RefreshFailure(response.status.value))
            }

            val authResponse: SupabaseAuthResponse = response.body()
            Result.success(authResponse)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forceReauth(): AuthResult = withContext(Dispatchers.IO) {
        clearSession()
        return@withContext createAnonymousSession()
    }

    suspend fun handleIntegrityFailure(): AuthResult = withContext(Dispatchers.IO) {
        clearSession()
        _sessionState.value = SessionState.EXPIRED
        return@withContext AuthResult.NeedsPairing("Integridad comprometida, re-emparejar requerido")
    }

    suspend fun savePairedSession(deviceId: String, parentId: String?) {
        _deviceId.value = deviceId
        _sessionState.value = SessionState.PAIRED

        // Slice B1 — atomic child-pairing persistence (see
        // `completePairing` for the matching fix). Role=CHILD is
        // written in the same edit() block as is_paired+device_id
        // +parent_id so cold-start routing sees the child signal
        // atomically with the paired session.
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_paired", true)
            .putString("device_id", deviceId)
            .putString("parent_id", parentId)
            .putString("role", Role.CHILD.name)
            .apply()

        currentAccessToken?.let { access ->
            currentRefreshToken?.let { refresh ->
                persistSession(
                    StoredSession(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAt = sessionExpiresAt,
                        deviceId = deviceId,
                        userId = ""
                    )
                )

                // Post-pairing JWT refresh: rotate the anonymous bearer into
                // the paired session immediately after persisting the paired
                // identity. If the refresh endpoint fails, keep the paired
                // state and the pre-refresh token rather than downgrading the
                // session to INVALID.
                val refreshResult = performTokenRefresh(refresh)
                refreshResult.fold(
                    onSuccess = { authResponse ->
                        val refreshedDeviceId = authResponse.user?.app_metadata?.get("device_id") ?: deviceId
                        currentAccessToken = authResponse.access_token
                        currentRefreshToken = authResponse.refresh_token
                        sessionExpiresAt = authResponse.expires_at ?: (timeProvider.wallInstant().epochSecond + authResponse.expires_in)
                        _deviceId.value = refreshedDeviceId
                        _sessionState.value = SessionState.PAIRED
                        persistSession(
                            StoredSession(
                                accessToken = authResponse.access_token,
                                refreshToken = authResponse.refresh_token,
                                expiresAt = sessionExpiresAt,
                                deviceId = refreshedDeviceId,
                                userId = authResponse.user?.id ?: ""
                            )
                        )
                    },
                    onFailure = { e ->
                        Log.w(TAG, "auth_post_pairing_refresh_failed")
                    }
                )
            }
        }
    }

    fun getAccessToken(): String? = currentAccessToken

    /**
     * Returns the Supabase parent UUID persisted by [savePairedSession] in
     * `device_auth_prefs.parent_id`, or `null` if the device is not yet
     * paired. Used by the parent-side flows that need to scope a server
     * call by parent (e.g. `BehavioralEventsRepository.refresh` filters
     * by `parent_id`). Non-suspend so it can be called from a ViewModel
     * constructor.
     */
    fun getParentId(): String? =
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("parent_id", null)

    fun isPaired(): Boolean = _sessionState.value == SessionState.PAIRED

    fun isSessionExpiringSoon(): Boolean {
        val fiveMinutesFromNow = timeProvider.wallInstant().epochSecond + 300
        return sessionExpiresAt > 0 && sessionExpiresAt < fiveMinutesFromNow
    }

    fun clearSession() {
        runBlocking {
            sessionMutex.withLock {
                currentAccessToken = null
                currentRefreshToken = null
                sessionExpiresAt = 0
                _sessionState.value = SessionState.NONE
                _deviceId.value = null
                context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
        }
    }

    suspend fun <T> authenticatedRequest(
        method: HttpMethod,
        path: String,
        body: String? = null
    ): Result<io.ktor.client.statement.HttpResponse> = withContext(Dispatchers.IO) {
        try {
            val token = currentAccessToken ?: return@withContext Result.failure(
                IllegalStateException("No access token")
            )

            val response = httpClient.request(requestUrl(path)) {
                this.method = method
                header("Authorization", "Bearer $token")
                header("apikey", SUPABASE_ANON_KEY)
                header("Content-Type", "application/json")
                body?.let { setBody(it) }
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun persistSession(session: StoredSession) {
        val jsonString = json.encodeToString(session)
        val encrypted = encryptWithKeystore(jsonString)
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted_session", encrypted)
            .apply()
    }

    /**
     * F2 — Restore the persisted [StoredSession], refreshing via the
     * stored `refresh_token` if the persisted one has expired.
     *
     * # Contract
     *  - **(a)** Unexpired → returned as today (no behavior change).
     *  - **(b)** Expired + non-blank `refresh_token` → call
     *    `performTokenRefresh(...)` and, on HTTP 2xx, persist the
     *    refreshed [StoredSession] via [persistSession] (same
     *    `masterKeyAlias` via the production path) and return it.
     *  - **(c)** Expired + empty `refresh_token` or corrupt ciphertext →
     *    invalid session; unsafe state is cleared by the caller.
     *  - **(d)** Refresh HTTP 401/403 → invalid session; network/5xx →
     *    transient failure and the encrypted credentials remain intact.
     *
     * # Dispatch
     * HTTP work runs on `Dispatchers.IO` via [performTokenRefresh]. The
     * `runBlocking` wrapper is required because [restoreSession] is
     * called synchronously from `init { loadPersistedState() }` AND
     * from [authenticateOrCreate] / [BootReceiver] / [PairingManager];
     * converting to `suspend` would break the [BootReceiverTest] mockk
     * stubs (`every { mockAuthManager.restoreSession() } returns …`).
     * The unexpired path still returns synchronously without touching
     * the network.
     */
     internal fun restoreSession(): StoredSession? {
         return (restoreSessionOutcome() as? SessionRestoreOutcome.Restored)?.session
     }

     internal fun restoreSessionOutcome(): SessionRestoreOutcome {
         val encrypted = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
             .getString("encrypted_session", null) ?: return SessionRestoreOutcome.NoPersistedSession

         val stored = try {
             val jsonString = decryptWithKeystore(encrypted)
             json.decodeFromString<StoredSession>(jsonString)
         } catch (e: Exception) {
             return if (e.isMalformedAuthStorageFailure()) {
                 Log.w(TAG, "auth_session_invalid")
                 SessionRestoreOutcome.InvalidSession
             } else {
                 Log.w(TAG, "auth_session_reauthentication_unavailable")
                 SessionRestoreOutcome.TransientFailure
             }
         }

        // (a) Unexpired → return as today. The expiresAt==0 sentinel
        // covers the synthetic-anon path (no expiry persisted).
         if (stored.expiresAt <= 0 || stored.expiresAt >= timeProvider.wallInstant().epochSecond) {
             return SessionRestoreOutcome.Restored(stored)
        }

        // (c) Expired but no refresh_token → return null (defensive).
         if (stored.refreshToken.isBlank()) {
             Log.w(TAG, "auth_session_invalid")
             return SessionRestoreOutcome.InvalidSession
        }

        // (b) Expired + refresh_token → refresh round-trip via the
        // shared helper (Dispatchers.IO inside). runBlocking bridges the
        // non-suspend signature to the suspend HTTP call.
         return runBlocking {
             val refreshResult = performTokenRefresh(stored.refreshToken)
             refreshResult.fold(
                onSuccess = { authResponse ->
                    val refreshedExpiresAt = authResponse.expires_at
                        ?: (timeProvider.wallInstant().epochSecond + authResponse.expires_in)
                    val refreshed = StoredSession(
                        accessToken = authResponse.access_token,
                        refreshToken = authResponse.refresh_token,
                        expiresAt = refreshedExpiresAt,
                        // Preserve device identity — the refresh
                        // endpoint does not necessarily echo back
                        // `app_metadata.device_id`.
                        deviceId = stored.deviceId,
                        userId = authResponse.user?.id ?: stored.userId
                    )
                    persistSession(refreshed)
                     SessionRestoreOutcome.Restored(refreshed)
                },
                onFailure = { failure ->
                    if (failure is RefreshFailure && failure.statusCode in setOf(401, 403)) {
                        Log.w(TAG, "auth_session_invalid")
                        SessionRestoreOutcome.InvalidSession
                    } else {
                        Log.w(TAG, "auth_session_refresh_failed")
                        SessionRestoreOutcome.TransientFailure
                    }
                }
             )
         }
    }

    private fun loadPersistedState() {
        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val isPaired = prefs.getBoolean("is_paired", false)
        val hasRole = prefs.contains("role")

        // Slice B1 — child-install migration. Pre-fix paired-child
        // installs persisted `is_paired=true` + `parent_id` but no
        // `role`. On cold start we promote them to `role=CHILD` so the
        // role-based routing discriminator (resolveIsChildDevice)
        // classifies them correctly. Safe because in the current
        // codebase `is_paired=true` is ONLY written by `savePairedSession`
        // / `completePairing` (the child pairing paths). PARENT wins over
        // this migration — see the
        // `hasRole` branch below which is preserved.
        if (isPaired && !hasRole && deviceId != null) {
            Log.i(TAG, "auth_child_role_migration")
            prefs.edit().putString("role", Role.CHILD.name).apply()
        }

        _deviceId.value = deviceId
        _sessionState.value = when {
            isPaired && (deviceId != null || hasRole) -> SessionState.PAIRED
            deviceId != null -> SessionState.ANONYMOUS
            hasRole -> SessionState.PAIRED // OPPO: role + is_paired without device_id (best-effort, logged below)
            else -> SessionState.NONE
        }
        if (isPaired && deviceId == null) {
            Log.w(TAG, "auth_paired_device_id_missing")
        }

        // Cold-start restore: decrypt the persisted session and push it into the
        // in-memory token fields so any `getAccessToken()` consumer that runs
        // before DeviceAuthService.start() sees a non-null token. Mirrors the
        // populate block inside `authenticateOrCreate()` above.
        when (val outcome = restoreSessionOutcome()) {
            is SessionRestoreOutcome.Restored -> {
                currentAccessToken = outcome.session.accessToken
                currentRefreshToken = outcome.session.refreshToken
                sessionExpiresAt = outcome.session.expiresAt
            }
            SessionRestoreOutcome.TransientFailure -> {
                _sessionState.value = SessionState.EXPIRED
                Log.w(TAG, "auth_session_reauthentication_unavailable")
            }
            SessionRestoreOutcome.InvalidSession -> {
                prefs.edit().clear().apply()
                _deviceId.value = null
                _sessionState.value = SessionState.INVALID
                Log.w(TAG, "auth_session_reauthentication_required")
            }
            SessionRestoreOutcome.NoPersistedSession -> Unit
        }

        // Synthetic hotfix path (Q1=c cleartext SharedPreferences): when
        // `role` is persisted but no `encrypted_session` blob was ever written
        // (e.g. the role-aware `authenticateOrCreate(role: Role)` synthetic
        // path), hydrate `currentAccessToken` from the cleartext
        // `synthetic_access_token` key. The eventual `parent-auth-flow` change
        // will replace this with real Keystore-encrypted auth tokens.
        if (currentAccessToken == null && prefs.contains("role")) {
            val syntheticToken = prefs.getString(KEY_SYNTHETIC_ACCESS_TOKEN, null)
            if (syntheticToken != null) {
                currentAccessToken = syntheticToken
                currentRefreshToken = ""
                sessionExpiresAt = 0
            }
        }

        // Slice A — Clean Cutover (Q2=b) — WU-1 + F1-scoped.
        //
        // Replaces the PR-#28 `migrateStaleParentId(prefs)` helper
        // (which backfilled `parent_id = "parent-demo"` for pre-PR-#27
        // PARENT prefs). The pre-fix contract wiped the ENTIRE prefs
        // namespace when `parent_id` was non-null and not a valid
        // Supabase UUID.
        //
        // WU-1 fixes the OPPO child bug: the pre-fix wipe clobbered a
        // freshly-restored child session because `restoreSession()`
        // populated `currentAccessToken` from `encrypted_session`,
        // `savePairedSession(deviceId, parentId)` was called with the
        // mock placeholder `"parent-uuid-aaaa-bbbb-cccc"` (non-UUID),
        // and the wipe fired AFTER the restore.
        //
        // F1 extends the same protection to PARENT synthetic hotfix:
        // the `authenticateOrCreate(Role.PARENT)` path writes a
        // synthetic anon JWT + a `parent_id = "parent-demo"` sentinel.
        // In debug/mock builds (USE_MOCK_SUPABASE || USE_SHARED_MOCK)
        // the wipe may skip IF a synthetic_access_token is present —
        // the OPPO child fix needs its symmetric PARENT case.
        // In release-like builds the wipe still fires so a stale
        // `parent-demo` sentinel cannot shadow a fresh sign-in.
        //
        // Real Supabase UUIDs are still preserved (regex match against
        // the canonical 8-4-4-4-12 hex pattern) without needing this
        // branch.
        val persistedParentId = prefs.getString("parent_id", null)
        if (persistedParentId != null && !isUuid(persistedParentId)) {
            val role = prefs.getString("role", null)
            val hasEncryptedSession =
                prefs.getString("encrypted_session", null) != null
            val hasSyntheticToken =
                prefs.getString(KEY_SYNTHETIC_ACCESS_TOKEN, null) != null
            // F1 — release/build-mode gate. The narrow PARENT
            // synthetic bypass MUST NOT fire in release builds so a
            // stale `parent-demo` sentinel cannot leak into a real
            // cloud session. Mirror CHILD's synthetic-fallback with
            // the same gate.
            //
            // `testIsMockOrSharedMockBuild` (test seam) lets unit
            // tests pin release/debug without mutating the final
            // BuildConfig fields (JDK 17+ removed `Field.modifiers`
            // reflection tricks — see companion kdoc).
            val isDebugOrSharedMockBuild =
                testIsMockOrSharedMockBuild
                    ?: (BuildConfig.USE_MOCK_SUPABASE || BuildConfig.USE_SHARED_MOCK)
            val isPairedChildSession =
                role == Role.CHILD.name && hasEncryptedSession
            val isPairedChildWithSyntheticFallback =
                role == Role.CHILD.name && hasSyntheticToken
            // F1 — NARROW PARENT synthetic bypass, debug/mock only.
            // The release cutover still wipes; the OPPO PARENT analog
            // only skips when (a) we're in a debug/mock build,
            // (b) the install is role=PARENT, and (c) a
            // synthetic_access_token is present so the cold start can
            // re-hydrate the in-memory bearer.
            val isPairedParentWithSyntheticFallbackInDebug =
                role == Role.PARENT.name &&
                    hasSyntheticToken &&
                    isDebugOrSharedMockBuild

            if (
                !isPairedChildSession &&
                !isPairedChildWithSyntheticFallback &&
                !isPairedParentWithSyntheticFallbackInDebug
            ) {
                Log.w(TAG, "auth_legacy_parent_session_wiped")
                prefs.edit().clear().apply()
                // Reset in-memory state too — the wiped prefs means
                // we have no valid session until the parent re-auths.
                currentAccessToken = null
                currentRefreshToken = null
                sessionExpiresAt = 0
                _sessionState.value = SessionState.NONE
                _deviceId.value = null
            } else {
                Log.i(TAG, "auth_legacy_parent_session_preserved")
            }
        }
    }

    /**
     * True when [s] matches the canonical Supabase UUID format
     * (8-4-4-4-12 hex, case-insensitive). Used by the clean-cutover
     * wipe in [loadPersistedState] to distinguish legacy mock-engine
     * sentinels ("parent-demo", "mock-parent-legacy", etc.) from real
      * `auth.users.id` values written by ordinary authenticated pairing.
     */
    private fun isUuid(s: String): Boolean =
        UUID_REGEX.matches(s)

    /**
     * Encrypts [data] with the AES/GCM secret key in the Android Keystore
     * and base64-encodes the result (prepended with the random IV).
     *
     * Marked `internal` to expose the seam to the JVM test source set —
     * Robolectric 4.10.3 cannot instantiate `KeyStore.getInstance(
     * "AndroidKeyStore")` (the project's JVM JCA provider is BouncyCastle,
     * which lacks `AndroidKeyStore`), and the cipher requires it.
     *
     * JVM persistence tests use reflection on the `sessionCipher` field to
     * swap in a test double — see the class kdoc for the seam pattern.
     */
    internal fun encryptWithKeystore(data: String): String =
        sessionCipher.encrypt(data)

    /**
     * Reverse of [encryptWithKeystore]. Splits the IV from the payload,
     * initializes the AES/GCM cipher in decrypt mode, and returns the
     * plaintext. Throws on tampered/incorrect data (the caller's `try`
     * block catches and falls back to the clean-cutover wipe).
     *
     * Marked `internal` for the same Robolectric-driven test seam as
     * [encryptWithKeystore].
     */
    internal fun decryptWithKeystore(encryptedData: String): String =
        sessionCipher.decrypt(encryptedData)

    /**
     * Handle to the AES/GCM cipher used by [encryptWithKeystore] and
     * [decryptWithKeystore]. Defaults to the production `AuthCipher`
     * (Android Keystore-backed). The JVM unit-test source set passes
     * `TestableAuthCipher` via the internal constructor to side-step the
     * Robolectric 4.10.3 limitation that blocks AndroidKeyStore
     * instantiation (the BouncyCastle JCA provider doesn't supply
     * `AndroidKeyStore`). Marked `internal val` so the test source set
     * (same module) can pass a test cipher at construction time (before
     * `init { loadPersistedState }` runs — important: the cipher must
     * be in place before the first cold-start restore).
     */

    /**
     * Returns a working AES/GCM SecretKey for the auth storage.
     *
     * Pre-fix builds stored the key without `setEncryptionPaddings(
     * ENCRYPTION_PADDING_NONE)`, which makes it incompatible with the
     * `AES/GCM/NoPadding` cipher — `Cipher.init` then throws
     * `InvalidKeyException` → `KeyStoreException: Incompatible padding mode`.
     * A plain `getKey(...)` still returns the bad key (it's a valid SecretKey
     * instance), so the bug only surfaces on first cipher init. We detect
     * that here with a test init and migrate by deleting and re-creating.
     *
     * After the W1 cipher extraction (Slice A — WARNING-1 closure),
     * the AES/GCM key bootstrap moved into [AuthCipher] (see the
     * bottom of this file). The cipher is now a [sessionCipher]
     * delegate, and the persistent-session writes go through
     * `encryptWithKeystore` / `decryptWithKeystore` which now
     * call `sessionCipher.encrypt` / `sessionCipher.decrypt`.
     */
}

private const val AUTH_KEY_ALIAS = "parental_control_auth_key"

/**
 * Production cipher for encrypted auth session storage.
 * Encrypts/decrypts via the AES/GCM secret key in the Android Keystore
 * (see `getOrCreateAuthKey` / `createAuthKey` for the key bootstrap).
 *
 * Wrapped in its own class so the JVM unit-test source set can replace
 * it via reflection on `DeviceAuthManager.sessionCipher` — Robolectric
 * 4.10.3 cannot instantiate `KeyStore.getInstance("AndroidKeyStore")`,
 * and the cipher requires it (the project's JVM JCA provider is
 * BouncyCastle, which lacks the Android Keystore implementation). The
 * The production callsite uses `persistSession` and `loadPersistedState`,
 * which delegate
 * to `sessionCipher.encrypt` / `sessionCipher.decrypt` now.
 *
 * Marked `internal open` so test-only `TestableAuthCipher` subclasses
 * can override the cipher with a deterministic base64 round-trip for
 * round-trip JVM tests. Production code never subclasses
 * `AuthCipher` (no public API surface increase).
 */
internal open class AuthCipher {
    open fun encrypt(data: String): String {
        val secretKey = getOrCreateAuthKeyImpl()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    open fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)

        val secretKey = getOrCreateAuthKeyImpl()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /**
     * Returns the AES/GCM secret key — `AndroidKeyStore`-backed.
     * Implemented as a free function (not on `DeviceAuthManager`) so
     * `AuthCipher` is independent of the enclosing class. The key
     * bootstrap logic is the same as the previous `getOrCreateAuthKey`
     * (recreated on padding mismatch, etc.).
     */
    private fun getOrCreateAuthKeyImpl(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existingKey = keyStore.getKey(AUTH_KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try {
                cipher.init(Cipher.ENCRYPT_MODE, existingKey)
                return existingKey
            } catch (e: InvalidKeyException) {
                Log.w("AuthCipher", "auth_key_recreated_invalid_parameters")
                keyStore.deleteEntry(AUTH_KEY_ALIAS)
            }
        }

        return createAuthKeyImpl()
    }

    private fun createAuthKeyImpl(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(AUTH_KEY_ALIAS)) {
            keyStore.deleteEntry(AUTH_KEY_ALIAS)
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val keySpec = KeyGenParameterSpec.Builder(
            AUTH_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val AUTH_KEY_ALIAS = "parental_control_auth_key"
    }
}
