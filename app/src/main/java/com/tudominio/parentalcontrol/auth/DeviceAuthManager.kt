package com.tudominio.parentalcontrol.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine
import com.tudominio.parentalcontrol.keystore.SecureStorage
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

@Serializable
data class SupabaseAuthResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
    val expires_at: Long? = null,
    val user: SupabaseUser? = null
)

/**
 * Magic-link sign-in result (intermediate — no JWT yet).
 *
 * Returned by [DeviceAuthManager.signInWithMagicLink] when Supabase
 * accepted the email and dispatched the magic link. The message id is
 * the opaque Supabase identifier for the dispatched email; the user
 * must click the link in their inbox, then
 * [DeviceAuthManager.verifyMagicLinkOtp] exchanges the resulting token
 * for a real ParentSession.
 */
data class MagicLinkSent(
    val messageId: String
)

/**
 * Authenticated parent session (Slice A — `signInWithMagicLink` flow).
 *
 * `parentId` is the Supabase `auth.users.id` (mirrors `app_metadata.parent_id`
 * — the same UUID the custom-access-token-hook injects as a JWT claim).
 *
 * The `accessToken` is the JWT to use as `Authorization: Bearer <token>`
 * on every parent-scoped Supabase REST/edge-fn call.
 */
data class ParentSession(
    val parentId: String,
    val accessToken: String,
    val refreshToken: String
)

/**
 * On-disk serialization shape for [ParentSession] inside the
 * `encrypted_parent_session` blob. Kept as a separate `@Serializable`
 * data class so the JSON envelope is independent of the public
 * `ParentSession` data class — the on-disk shape can add debug fields
 * (created-at, schema version) without touching the public API.
 *
 * Mirrors [StoredSession] for the child anonymous-auth path at
 * `DeviceAuthManager.kt:69-76`. Both shapes serialize to JSON, get
 * encrypted via [encryptWithKeystore], and land as a single blob in
 * `device_auth_prefs`.
 */
@Serializable
data class ParentSessionSerializer(
    val parentId: String,
    val accessToken: String,
    val refreshToken: String
)

/**
 * Errors surfaced by [DeviceAuthManager.signInWithMagicLink] +
 * [DeviceAuthManager.verifyMagicLinkOtp]. Distinct error type from
 * [AuthResult.Error] so callers can branch on the failure mode without
 * parsing message strings.
 */
sealed class ParentAuthError(message: String) : RuntimeException(message) {
    /** Supabase returned 400 with `error: "invalid_email"` */
    object InvalidEmail : ParentAuthError("invalid_email")

    /** Supabase returned 401 with `error: "otp_expired"` (verify only) */
    object TokenExpired : ParentAuthError("token_expired")

    /** Supabase returned 422 or any other 4xx (caller surfaces to UI) */
    object InvalidRequest : ParentAuthError("invalid_request")

    /** Network / decoding / unknown server failure */
    object Unknown : ParentAuthError("unknown")
}

@Serializable
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    val app_metadata: Map<String, String>? = null,
    val user_metadata: Map<String, String>? = null
)

@Serializable
data class MagicLinkRequest(
    val email: String,
    val create_user: Boolean = true,
    val gotrue_meta_security: Map<String, String> = mapOf("captcha_token" to "")
)

@Serializable
data class MagicLinkResponse(
    val message_id: String? = null,
    val error: String? = null
)

@Serializable
data class MagicLinkVerifyRequest(
    val email: String,
    val token: String
)

@Serializable
data class DevLoginRequest(val email: String)

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

        // Slice A — pragmatic email format check used by
        // `isValidEmail` (called from `signInWithMagicLink`). Lives in
        // the companion so the regex is compiled once at class load,
        // not per-call.
        private val EMAIL_REGEX = Regex(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$"
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
         * `DeviceAuthManagerParentSessionCipherTest` tests use this
         * seam so the production `init { loadPersistedState }`
         * decrypt path can run end-to-end inside the JVM unit test.
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

    private val httpClient: HttpClient = if (BuildConfig.USE_MOCK_SUPABASE) {
        // Per `fix-supabase-client-provider-legacy-mock-gate` family: every
        // legacy `getInstance` path in the project must honor the same flag
        // the Hilt `@SupabaseClient` binding honors in `NetworkModule`.
        // `DeviceAuthManager` has its own private `httpClient` (used by
        // `createAnonymousSession` and `completePairing`); without this
        // branch the auth call hits the placeholder Supabase URL and
        // surfaces as `NETWORK_ERROR` in `PairingManager` before the
        // pairing call can use the (already-mock'd) `SupabaseClientProvider`.
        MockSupabaseEngine(context).httpClient
    } else {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
            }
        }
    }

    private val _sessionState = MutableStateFlow(SessionState.NONE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    private var currentAccessToken: String? = null
    private var currentRefreshToken: String? = null
    private var sessionExpiresAt: Long = 0

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
            val restoredSession = restoreSession()
            if (restoredSession != null) {
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

            return@withContext createAnonymousSession()
        }
    }

    /**
     * Role-aware synthetic anonymous auth.
     *
     * Issues a local JWT-shaped token of the form `anon-${role}-${uuid}` and
     * persists the [role] in `device_auth_prefs` so [getRole] can return it
     * after a process restart. This is the hotfix path described in design
     * §D4 of `openspec/changes/hotfix-parent-auth-session/design.md`: it
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
            val response = httpClient.post("$SUPABASE_URL/auth/v1/signup") {
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
        currentAccessToken = response.access_token
        currentRefreshToken = response.refresh_token
        sessionExpiresAt = response.expires_at ?: (System.currentTimeMillis() / 1000 + response.expires_in)

        val deviceId = response.user?.app_metadata?.get("device_id")
        _deviceId.value = deviceId

        persistSession(
            StoredSession(
                accessToken = response.access_token,
                refreshToken = response.refresh_token,
                expiresAt = sessionExpiresAt,
                deviceId = deviceId,
                userId = response.user?.id ?: ""
            )
        )

        _sessionState.value = SessionState.ANONYMOUS

        return AuthResult.Success(
            deviceId = deviceId ?: "anonymous",
            accessToken = response.access_token,
            refreshToken = response.refresh_token,
            expiresAt = sessionExpiresAt
        )
    }

    suspend fun completePairing(pairingCode: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post("$SUPABASE_URL/functions/v1/pairing") {
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

            _deviceId.value = newDeviceId
            _sessionState.value = SessionState.PAIRED

            // Slice B1 — atomic child-pairing persistence. `role=CHILD`
            // MUST be written in the same edit() block as `is_paired`
            // + `device_id` so a crash mid-write cannot leave a half-
            // state where the device looks paired but cold-start
            // routing (resolveIsChildDevice) cannot prove it.
            context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_paired", true)
                .putString("device_id", newDeviceId)
                .putString("parent_id", responseBody.parent_id)
                .putString("role", Role.CHILD.name)
                .apply()

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
            onFailure = { e ->
                _sessionState.value = SessionState.INVALID
                AuthResult.NeedsPairing("Error refreshing: ${e.message}")
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
            val response = httpClient.post("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token") {
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(mapOf("refresh_token" to refreshToken)))
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    IllegalStateException("HTTP ${response.status.value}")
                )
            }

            val authResponse: SupabaseAuthResponse = response.body()
            Result.success(authResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Slice A — `signInWithMagicLink(email)` (Q1=b magic-link path).
     *
     * Posts `${SUPABASE_URL}/auth/v1/magiclink` with `{ email,
     * create_user: true, gotrue_meta_security: { captcha_token: "" } }`
     * so Supabase dispatches the magic link to the parent's inbox.
     *
     * The Inbucket at `http://127.0.0.1:54324` (local Supabase) catches
     * the email; the production build routes to the real inbox.
     *
     * Returns [Result.success] with a [MagicLinkSent] carrying the
     * Supabase-assigned `message_id` (opaque). Returns
     * [Result.failure] with a [ParentAuthError] for HTTP errors; the
     * `device_auth_prefs` namespace is NEVER touched on the failure
     * path.
     *
     * Called only under `!BuildConfig.USE_MOCK_SUPABASE` (real cloud
     * path); the mock engine never receives this call.
     */
    suspend fun signInWithMagicLink(email: String): Result<MagicLinkSent> =
        withContext(Dispatchers.IO) {
            if (!isValidEmail(email)) {
                return@withContext Result.failure(ParentAuthError.InvalidEmail)
            }
            try {
                val response = httpClient.post("$SUPABASE_URL/auth/v1/magiclink") {
                    header("apikey", SUPABASE_ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(MagicLinkRequest(email = email))
                }
                if (!response.status.isSuccess()) {
                    val code = response.status.value
                    return@withContext Result.failure(
                        when (code) {
                            400 -> ParentAuthError.InvalidEmail
                            422 -> ParentAuthError.InvalidRequest
                            else -> ParentAuthError.Unknown
                        }
                    )
                }
                // Supabase returns `{}` on success in some versions OR
                // `{ message_id: "<uuid>" }` in others. Parse whichever
                // shape is present; empty object is acceptable.
                val parsed: MagicLinkResponse = response.body()
                Result.success(MagicLinkSent(messageId = parsed.message_id.orEmpty()))
            } catch (e: Exception) {
                Log.w(TAG, "signInWithMagicLink failed: ${e.message}", e)
                Result.failure(ParentAuthError.Unknown)
            }
        }

    /**
     * Slice A — `verifyMagicLinkOtp(tokenHash, email)` (Q1=b magic-link path).
     *
     * Exchanges the magic-link `token_hash` (extracted from the link URL
     * by the UI / smoke-test harness) for a real parent session by
     * POSTing `${SUPABASE_URL}/auth/v1/verify?type=magiclink` with
     * `{ email, token: tokenHash }`. The Supabase response includes the
     * `access_token` JWT (whose `parent_id` claim was injected by the
     * custom-access-token-hook from `app_metadata.parent_id`) and a
     * `user.id` (= parentId).
     *
     * On success:
     *  - Returns [ParentSession] with `parentId` = `user.id`.
     *  - Atomically persists `{ role: PARENT, parent_id, access_token }`
     *    to `device_auth_prefs` via [persistParentSession]. The atomic
     *    helper writes all keys inside a single `prefs.edit().apply()`
     *    so a crash mid-write cannot leave half-state.
     *
     * On failure:
     *  - [ParentAuthError.TokenExpired] for HTTP 401.
     *  - [ParentAuthError.InvalidRequest] for HTTP 422.
     *  - [ParentAuthError.Unknown] for any other 4xx/5xx or network error.
     *  - `device_auth_prefs` is NEVER touched (atomic-prefs invariant).
     *
     * Called only under `!BuildConfig.USE_MOCK_SUPABASE`.
     */
    suspend fun verifyMagicLinkOtp(
        tokenHash: String,
        email: String
    ): Result<ParentSession> = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post("$SUPABASE_URL/auth/v1/verify?type=magiclink") {
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(MagicLinkVerifyRequest(email = email, token = tokenHash))
            }
            if (!response.status.isSuccess()) {
                val code = response.status.value
                return@withContext Result.failure(
                    when (code) {
                        401 -> ParentAuthError.TokenExpired
                        422 -> ParentAuthError.InvalidRequest
                        else -> ParentAuthError.Unknown
                    }
                )
            }
            val authResponse: SupabaseAuthResponse = response.body()
            val parentId = authResponse.user?.id
                ?: return@withContext Result.failure(ParentAuthError.InvalidRequest)

            val session = ParentSession(
                parentId = parentId,
                accessToken = authResponse.access_token,
                refreshToken = authResponse.refresh_token
            )

            // Atomic persistence: all keys in a single edit() block so a
            // crash mid-write cannot leave half-state. The cleartext
            // `parent_id` + `role` keys are written alongside the new
            // `access_token` + `refresh_token` cleartext siblings (the
            // Keystore-encrypted `encrypted_session` path is kept for
            // the synthetic-anonymous child auth flow; the parent magic-
            // link path uses cleartext so the cold-start read in
            // `loadPersistedState` is symmetric with the synthetic
            // restoreSession flow).
            persistParentSession(session)

            Result.success(session)
        } catch (e: Exception) {
            Log.w(TAG, "verifyMagicLinkOtp failed: ${e.message}", e)
            Result.failure(ParentAuthError.Unknown)
        }
    }

    /**
     * Atomic write of a [ParentSession] to `device_auth_prefs`. Single
     * `prefs.edit().apply()` block; on any exception, no key is written
     * and the existing prefs are preserved (the atomic-prefs invariant
     * pinned by the A.1.3 test).
     *
     * WARNING-1 (W1) closure: writes the sensitive JWT tokens
     * (`accessToken`, `refreshToken`) as a single encrypted blob
     * (`encrypted_parent_session`) instead of cleartext alongside
     * `role` + `parent_id`. Mirrors the
     * [persistSession]/[encryptWithKeystore] pair used by the
     * child anonymous-auth flow at this file's `persistSession` +
     * `encryptWithKeystore` lines (the line numbers shift as the file
     * evolves, but the seam is the same).
     *
     * `role` and `parent_id` stay cleartext — `parent_id` is a UUID
     * (not sensitive) and [getParentId] + the clean-cutover wipe in
     * [loadPersistedState] both read it to detect stale state. The
     * atomically-protected cleartext is intentionally small: just a
     * role flag and a UUID, neither of which exposes the JWT.
     */
    private fun persistParentSession(session: ParentSession) {
        val parentJson = json.encodeToString(
            ParentSessionSerializer(
                parentId = session.parentId,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken
            )
        )
        val encrypted = encryptWithKeystore(parentJson)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("role", Role.PARENT.name)
            .putString("parent_id", session.parentId)
            .putString("encrypted_parent_session", encrypted)
            .apply()
    }

    // Slice B1 — shared-mock dev-login bypass. Gated on `USE_SHARED_MOCK`;
    // uses `SHARED_MOCK_URL` so the request reaches `localhost:8787`.
    // Production path unchanged: when `USE_SHARED_MOCK=false` the VM never calls this.
    suspend fun devLogin(email: String): Result<ParentSession> =
        withContext(Dispatchers.IO) {
            if (!isValidEmail(email)) {
                return@withContext Result.failure(ParentAuthError.InvalidEmail)
            }
            val baseUrl = if (BuildConfig.USE_SHARED_MOCK) BuildConfig.SHARED_MOCK_URL else SUPABASE_URL
            try {
                val response = httpClient.post("$baseUrl/auth/v1/dev-login") {
                    header("apikey", SUPABASE_ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(DevLoginRequest(email = email))
                }
                if (!response.status.isSuccess()) {
                    return@withContext Result.failure(ParentAuthError.Unknown)
                }
                val authResponse: SupabaseAuthResponse = response.body()
                val parentId = authResponse.user?.id
                    ?: return@withContext Result.failure(ParentAuthError.InvalidRequest)
                val session = ParentSession(
                    parentId = parentId,
                    accessToken = authResponse.access_token,
                    refreshToken = authResponse.refresh_token
                )
                persistParentSession(session)
                // Slice B1 — fix-1: hydrate the in-memory tokens so the
                // dashboard's loadDevices() works without a force-stop.
                // Mirrors `completePairing` (sets _sessionState=PAIRED).
                _sessionState.value = SessionState.PAIRED
                currentAccessToken = session.accessToken
                currentRefreshToken = session.refreshToken
                sessionExpiresAt = 0
                Result.success(session)
            } catch (e: Exception) {
                Log.w(TAG, "devLogin failed: ${e.message}", e)
                Result.failure(ParentAuthError.Unknown)
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
        val fiveMinutesFromNow = System.currentTimeMillis() / 1000 + 300
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

            val response = httpClient.request("$SUPABASE_URL$path") {
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
     *  - **(c)** Expired + empty `refresh_token` (defensive — should
     *    not happen for anon sessions) → return `null`.
     *  - **(d)** Refresh HTTP failure (401/4xx/5xx/network) → log
     *    `Log.w(TAG, "Refresh failed: …")` and return `null`. The
     *    on-disk blob is NOT rewritten (pre-fix behavior preserved).
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
        val encrypted = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("encrypted_session", null) ?: return null

        val stored = try {
            val jsonString = decryptWithKeystore(encrypted)
            json.decodeFromString<StoredSession>(jsonString)
        } catch (e: Exception) {
            return null
        }

        // (a) Unexpired → return as today. The expiresAt==0 sentinel
        // covers the synthetic-anon path (no expiry persisted).
        if (stored.expiresAt <= 0 || stored.expiresAt >= System.currentTimeMillis() / 1000) {
            return stored
        }

        // (c) Expired but no refresh_token → return null (defensive).
        if (stored.refreshToken.isBlank()) {
            return null
        }

        // (b) Expired + refresh_token → refresh round-trip via the
        // shared helper (Dispatchers.IO inside). runBlocking bridges the
        // non-suspend signature to the suspend HTTP call.
        return runBlocking {
            val refreshResult = performTokenRefresh(stored.refreshToken)
            refreshResult.fold(
                onSuccess = { authResponse ->
                    val refreshedExpiresAt = authResponse.expires_at
                        ?: (System.currentTimeMillis() / 1000 + authResponse.expires_in)
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
                    refreshed
                },
                onFailure = { e ->
                    // (d) Refresh failure → log and return null. The
                    // on-disk blob is NOT rewritten.
                    Log.w(TAG, "Refresh failed: ${e.message}")
                    null
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
        // / `completePairing` (the child pairing paths); parent devices
        // go through devLogin / magic-link which never set
        // `is_paired=true`. PARENT wins over this migration — see the
        // `hasRole` branch below which is preserved.
        if (isPaired && !hasRole && deviceId != null) {
            Log.i(TAG, "Migrating pre-fix paired-child install to role=CHILD")
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
            Log.w(
                TAG,
                "is_paired=true but device_id missing; falling back to role-aware PAIRED state"
            )
        }

        // Cold-start restore: decrypt the persisted session and push it into the
        // in-memory token fields so any `getAccessToken()` consumer that runs
        // before DeviceAuthService.start() sees a non-null token. Mirrors the
        // populate block inside `authenticateOrCreate()` above.
        restoreSession()?.let { stored ->
            currentAccessToken = stored.accessToken
            currentRefreshToken = stored.refreshToken
            sessionExpiresAt = stored.expiresAt
        }

        // Slice A — WARNING-1 closure (parent magic-link path). When the
        // parent authenticates via `signInWithMagicLink` + `verifyMagicLinkOtp`,
        // the JWT tokens are persisted as an AES/GCM-encrypted blob under
        // `encrypted_parent_session` (see [persistParentSession]). This block
        // mirrors the `restoreSession()?.let { stored -> ... }` above for the
        // parent path: read the blob, decrypt it, populate `currentAccessToken`
        // and `currentRefreshToken` so the cold-start manager's
        // `getAccessToken()` is non-null.
        //
        // On decryption failure (corrupted blob, AOSP version mismatch, key
        // rotation), wipe the parent-specific keys so the user lands on the
        // sign-in screen instead of a PAIRED-but-token-less state. Mirrors
        // `loadPersistedState` PSC-3 (corrupted blob control).
        val parentBlob = prefs.getString("encrypted_parent_session", null)
        if (parentBlob != null) {
            val parentSession: ParentSessionSerializer? = try {
                json.decodeFromString<ParentSessionSerializer>(decryptWithKeystore(parentBlob))
            } catch (e: Exception) {
                Log.w(TAG, "encrypted_parent_session decrypt failed: ${e.message}")
                null
            }
            if (parentSession != null) {
                currentAccessToken = parentSession.accessToken
                currentRefreshToken = parentSession.refreshToken
                // Token expiry is not stored in the parent blob — the magic-link
                // verify response doesn't carry a Supabase-shape `expires_at`
                // that round-trips through serialization. The token is
                // refreshed lazily on the next RSC.
                sessionExpiresAt = 0
                _sessionState.value = SessionState.PAIRED
            } else {
                Log.w(
                    TAG,
                    "Clean cutover: wiping parent_id due to corrupted " +
                        "encrypted_parent_session; routing to sign-in screen"
                )
                prefs.edit()
                    .remove("parent_id")
                    .remove("encrypted_parent_session")
                    .apply()
                currentAccessToken = null
                currentRefreshToken = null
                sessionExpiresAt = 0
                _sessionState.value = SessionState.NONE
                _deviceId.value = null
            }
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
                Log.w(
                    TAG,
                    "Clean cutover (Q2=b): wiping legacy parent_id=" +
                        "\"$persistedParentId\" from device_auth_prefs; " +
                        "routing to sign-in screen (role=$role, " +
                        "encrypted_session=$hasEncryptedSession, " +
                        "synthetic_token=$hasSyntheticToken, " +
                        "debug_or_shared_mock=$isDebugOrSharedMockBuild)"
                )
                prefs.edit().clear().apply()
                // Reset in-memory state too — the wiped prefs means
                // we have no valid session until the parent re-auths.
                currentAccessToken = null
                currentRefreshToken = null
                sessionExpiresAt = 0
                _sessionState.value = SessionState.NONE
                _deviceId.value = null
            } else {
                Log.i(
                    TAG,
                    "Clean cutover (Q2=b): skipping wipe for parent_id=" +
                        "\"$persistedParentId\" because a restorable " +
                        "session signal is present (role=$role, " +
                        "encrypted_session=$hasEncryptedSession, " +
                        "synthetic_token=$hasSyntheticToken, " +
                        "debug_or_shared_mock=$isDebugOrSharedMockBuild)"
                )
            }
        }
    }

    /**
     * True when [s] matches the canonical Supabase UUID format
     * (8-4-4-4-12 hex, case-insensitive). Used by the clean-cutover
     * wipe in [loadPersistedState] to distinguish legacy mock-engine
     * sentinels ("parent-demo", "mock-parent-legacy", etc.) from real
     * `auth.users.id` values written by the magic-link verify path.
     */
    private fun isUuid(s: String): Boolean =
        UUID_REGEX.matches(s)

    /**
     * Lightweight RFC-5322-ish email format check. Used by
     * [signInWithMagicLink] to fail fast on obviously-invalid input
     * before the HTTP round-trip — Supabase returns 400 for
     * `error: invalid_email` on most malformed addresses, but doing
     * the check client-side saves a network round-trip and gives a
     * deterministic `ParentAuthError.InvalidEmail` instead of relying
     * on Supabase's response shape.
     *
     * Not exhaustive — the canonical RFC-5322 grammar is a 600+-line
     * regex. This is a pragmatic check that catches the common cases
     * (no `@`, no domain, no TLD, leading/trailing whitespace) without
     * false-negatives on legitimate addresses.
     */
    private fun isValidEmail(s: String): Boolean {
        if (s.isBlank()) return false
        val trimmed = s.trim()
        if (trimmed.length > 254) return false // RFC-5321 SMTP path limit
        return EMAIL_REGEX.matches(trimmed)
    }

    /**
     * Encrypts [data] with the AES/GCM secret key in the Android Keystore
     * and base64-encodes the result (prepended with the random IV).
     *
     * Marked `internal` to expose the seam to the JVM test source set —
     * Robolectric 4.10.3 cannot instantiate `KeyStore.getInstance(
     * "AndroidKeyStore")` (the project's JVM JCA provider is BouncyCastle,
     * which lacks `AndroidKeyStore`), and the cipher requires it.
     *
     * The round-trip tests in `DeviceAuthManagerParentSessionCipherTest`
     * use reflection on the `sessionCipher` field to swap in a test
     * double — see the class kdoc for the seam pattern. Mirrors the
     * `internal` visibility of [restoreSession] — same seam pattern.
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
 * Production cipher for the parent magic-link session storage (W1).
 * Encrypts/decrypts via the AES/GCM secret key in the Android Keystore
 * (see `getOrCreateAuthKey` / `createAuthKey` for the key bootstrap).
 *
 * Wrapped in its own class so the JVM unit-test source set can replace
 * it via reflection on `DeviceAuthManager.sessionCipher` — Robolectric
 * 4.10.3 cannot instantiate `KeyStore.getInstance("AndroidKeyStore")`,
 * and the cipher requires it (the project's JVM JCA provider is
 * BouncyCastle, which lacks the Android Keystore implementation). The
 * production callsite path remains unchanged: `persistParentSession`
 * and `loadPersistedState` still call `encryptWithKeystore` /
 * `decryptWithKeystore` on the singleton manager, those just delegate
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
                Log.w("AuthCipher", "Auth key has incompatible parameters, recreating", e)
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
