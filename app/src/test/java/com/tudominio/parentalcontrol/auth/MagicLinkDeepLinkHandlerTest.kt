package com.tudominio.parentalcontrol.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED→GREEN coverage for [MagicLinkDeepLinkHandler] (Continuation #2 of
 * `openspec/changes/feat-cross-device-pairing-and-approval`). Closes the
 * magic-link round-trip: Slice A shipped `signInWithMagicLink` +
 * `verifyMagicLinkOtp` on [DeviceAuthManager], and the follow-up shipped
 * the `MagicLinkSignInScreen` that lands the parent in the "Revisa tu
 * email" state. But tapping the magic-link in the inbox had no handler —
 * the parent was stranded. This handler parses the
 * `parentalcontrol://magic-link?token=…&email=…` deep-link, calls
 * `verifyMagicLinkOtp`, and surfaces the resulting [ParentSession] so the
 * NavGraph can route to the dashboard.
 *
 * # Implementation note: FakeMagicLinkVerifier instead of MockK
 *
 * This class is a PLAIN JVM unit test (NOT a Robolectric/Compose test) —
 * [MagicLinkDeepLinkHandler] is a pure class with NO Android `Context`
 * dependency, so no Robolectric runtime is needed. It deliberately avoids
 * `mockk<DeviceAuthManager>()` to side-step the project-wide MockK + JDK 21
 * incompatibility documented at
 * `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`
 * §Blockers. Instead the handler takes a [MagicLinkVerifier] functional
 * interface, and the test injects a hand-rolled [FakeMagicLinkVerifier]
 * that captures the `(token, email)` pair + returns a configurable
 * [Result]. This mirrors the `FakeMagicLinkSender` seam used by
 * `MagicLinkSignInScreenTest` for the send path.
 *
 * Four cases pin the contract:
 *
 *  1. valid token → `verifyMagicLinkOtp` invoked, `Result.success`
 *     surfaced with the [ParentSession].
 *  2. invalid token → verifier's `Result.failure` surfaced with the
 *     sanitized error message.
 *  3. missing query params → `Result.failure(IllegalArgumentException)`
 *     with `missing_param`; the verifier is NEVER called.
 *  4. non-magic-link host (`parentalcontrol://pair`) → `null`
 *     pass-through; the verifier is NEVER called (URL routing is
 *     specific to the `magic-link` host).
 */
class MagicLinkDeepLinkHandlerTest {

    /**
     * Hand-rolled test double for [MagicLinkVerifier]. Captures every
     * `(token, email)` pair the handler forwarded and returns a
     * configurable [nextResult]. Co-located with the test so the seam
     * lives next to its only consumer.
     */
    private class FakeMagicLinkVerifier(
        var nextResult: Result<ParentSession>
    ) : MagicLinkVerifier {
        val calls: MutableList<Pair<String, String>> = mutableListOf()

        override suspend fun verifyMagicLinkOtp(
            token: String,
            email: String
        ): Result<ParentSession> {
            calls += token to email
            return nextResult
        }
    }

    @Test
    fun deep_link_magic_link_with_valid_token_invokes_verifyMagicLinkOtp_and_returns_Success() =
        runBlocking {
            val session = ParentSession(
                parentId = "11111111-2222-3333-4444-555555555555",
                accessToken = "jwt-access",
                refreshToken = "jwt-refresh"
            )
            val verifier = FakeMagicLinkVerifier(Result.success(session))
            val handler = MagicLinkDeepLinkHandler(verifier)

            val result = handler.handle(
                "parentalcontrol://magic-link?token=tok123&email=parent%40example.com"
            )

            assertNotNull("magic-link host must be handled (non-null result)", result)
            assertTrue("valid token must yield Result.success", result!!.isSuccess)
            assertEquals(session, result.getOrNull())

            // verifier invoked exactly once with the decoded params.
            assertEquals(1, verifier.calls.size)
            assertEquals("tok123", verifier.calls.first().first)
            assertEquals("parent@example.com", verifier.calls.first().second)
        }

    @Test
    fun deep_link_magic_link_with_invalid_token_returns_Failure_with_sanitized_error() =
        runBlocking {
            val verifier = FakeMagicLinkVerifier(
                Result.failure(IllegalStateException("invalid_otp"))
            )
            val handler = MagicLinkDeepLinkHandler(verifier)

            val result = handler.handle(
                "parentalcontrol://magic-link?token=bad&email=parent%40example.com"
            )

            assertNotNull(result)
            assertTrue("invalid token must yield Result.failure", result!!.isFailure)
            assertEquals("invalid_otp", result.exceptionOrNull()?.message)
        }

    @Test
    fun deep_link_magic_link_with_missing_query_param_returns_Failure() = runBlocking {
        val verifier = FakeMagicLinkVerifier(
            Result.success(ParentSession("x", "y", "z"))
        )
        val handler = MagicLinkDeepLinkHandler(verifier)

        val result = handler.handle("parentalcontrol://magic-link")

        assertNotNull("magic-link host must be handled even when params are missing", result)
        assertTrue("missing params must yield Result.failure", result!!.isFailure)
        assertTrue(
            "missing params must fail with IllegalArgumentException",
            result.exceptionOrNull() is IllegalArgumentException
        )
        assertEquals("missing_param", result.exceptionOrNull()?.message)

        // verifier must NOT be called when params are missing.
        assertEquals(0, verifier.calls.size)
    }

    @Test
    fun deep_link_non_magic_link_scheme_returns_null_pass_through() = runBlocking {
        val verifier = FakeMagicLinkVerifier(
            Result.success(ParentSession("x", "y", "z"))
        )
        val handler = MagicLinkDeepLinkHandler(verifier)

        val result = handler.handle("parentalcontrol://pair?code=ABC123")

        assertNull("non-magic-link host must pass through (null)", result)
        assertEquals(0, verifier.calls.size)
    }
}
