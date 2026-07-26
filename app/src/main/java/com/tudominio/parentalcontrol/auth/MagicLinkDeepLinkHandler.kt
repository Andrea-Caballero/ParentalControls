package com.tudominio.parentalcontrol.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Functional interface the [MagicLinkDeepLinkHandler] uses to exchange a
 * magic-link `token_hash` for a real [ParentSession]. Lets the plain-JVM
 * handler test stub the verify call without going through
 * `mockk<DeviceAuthManager>` (the project's pre-existing MockK + JDK 21
 * incompatibility; see `MagicLinkDeepLinkHandlerTest` docs). Mirrors the
 * [com.tudominio.parentalcontrol.ui.auth.MagicLinkSender] seam used by the
 * send path.
 *
 * The signature matches [DeviceAuthManager.verifyMagicLinkOtp] verbatim so
 * the production [DeviceAuthManagerMagicLinkVerifier] wrapper can forward
 * to it without modifying `DeviceAuthManager.kt`.
 */
fun interface MagicLinkVerifier {
    suspend fun verifyMagicLinkOtp(token: String, email: String): Result<ParentSession>
}

/**
 * Thin adapter that forwards [MagicLinkVerifier.verifyMagicLinkOtp] to
 * [DeviceAuthManager.verifyMagicLinkOtp]. Wrapping the singleton keeps
 * `DeviceAuthManager.kt` untouched (out-of-scope per the apply contract;
 * its existing `verifyMagicLinkOtp` API already matches the
 * [MagicLinkVerifier] signature verbatim, including the atomic
 * `persistParentSession` write on success).
 */
class DeviceAuthManagerMagicLinkVerifier(
    private val authManager: DeviceAuthManager
) : MagicLinkVerifier {
    override suspend fun verifyMagicLinkOtp(
        token: String,
        email: String
    ): Result<ParentSession> = authManager.verifyMagicLinkOtp(token, email)
}

/**
 * Pure, `Context`-free handler that closes the magic-link round-trip.
 *
 * When the parent taps the magic-link in their inbox, Android dispatches
 * an `ACTION_VIEW` intent whose data is
 * `parentalcontrol://magic-link?token=<hash>&email=<addr>`. This handler
 * parses that URL, extracts the `token` + `email` query params, and calls
 * [MagicLinkVerifier.verifyMagicLinkOtp] to exchange the hash for a real
 * [ParentSession] (the verifier persists the session as a side effect via
 * `DeviceAuthManager`). The NavGraph then routes the parent to the
 * dashboard on success.
 *
 * Kept as a plain class (no Android `Context`, `Uri`, or Hilt dependency)
 * so it is trivially unit-testable — URL parsing uses [java.net.URI] which
 * is available in plain JVM unit tests (unlike `android.net.Uri`, which is
 * stubbed to throw outside Robolectric).
 *
 * [handle] returns:
 *  - `null` — the URL is NOT a `parentalcontrol://magic-link` deep-link
 *    (different scheme/host, e.g. `parentalcontrol://pair`); the caller
 *    should let another handler try it (pass-through).
 *  - `Result.success(ParentSession)` — verify succeeded.
 *  - `Result.failure(IllegalArgumentException("missing_param"))` — the
 *    magic-link host matched but `token` or `email` was absent/blank; the
 *    verifier is NOT called.
 *  - `Result.failure(<verifier error>)` — the verifier returned a failure
 *    (e.g. `invalid_otp`, `token_expired`); the error is surfaced verbatim.
 */
class MagicLinkDeepLinkHandler(
    private val verifier: MagicLinkVerifier
) {
    /**
     * Parses [url] and, when it is a magic-link deep-link, verifies the
     * OTP. Returns `null` for any non-magic-link URL (pass-through). See
     * the class KDoc for the full result contract.
     */
    suspend fun handle(url: String): Result<ParentSession>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null

        if (uri.scheme != SCHEME) return null
        // `authority` (not `host`) so URLs whose host parsing is finicky
        // still resolve; for `parentalcontrol://magic-link` both return
        // "magic-link".
        val host = uri.host ?: uri.authority
        if (host != MAGIC_LINK_HOST) return null

        val params = parseQuery(uri.rawQuery)
        val token = params[PARAM_TOKEN]
        val email = params[PARAM_EMAIL]
        if (token.isNullOrBlank() || email.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("missing_param"))
        }

        return verifier.verifyMagicLinkOtp(token, email)
    }

    /**
     * Splits a raw `a=1&b=2` query string into a decoded key→value map.
     * Empty/blank raw query yields an empty map. Values are URL-decoded
     * (so `parent%40example.com` becomes `parent@example.com`).
     */
    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = pair.substring(0, idx)
                val value = decode(pair.substring(idx + 1))
                key to value
            }
            .toMap()
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }.getOrDefault(s)

    companion object {
        private const val SCHEME = "parentalcontrol"
        private const val MAGIC_LINK_HOST = "magic-link"
        private const val PARAM_TOKEN = "token"
        private const val PARAM_EMAIL = "email"
    }
}
