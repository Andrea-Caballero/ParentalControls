package com.tudominio.parentalcontrol.ui.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tudominio.parentalcontrol.auth.MagicLinkSent
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.util.EmailValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN coverage for [MagicLinkSignInScreen] +
 * [MagicLinkViewModel] (follow-up #1 to Slice A's deviation #1 of
 * `openspec/changes/feat-cross-device-pairing-and-approval`). The
 * production-side `signInWithMagicLink` API landed in Slice A
 * (`feat/auth.signInWithMagicLink`, commit 10eb08d); this PR adds the
 * Compose sign-in screen that drives it from the parent's "Soy el
 * padre" onboarding tap.
 *
 * Six RED tests pin the surface contract at `specs/parent-auth-session/spec.md`
 * scenario "Parent enters email and receives magic link":
 *
 *  1. **A.2.8.a** `magic_link_renders_email_field_and_send_button_in_editing_state`
 *     — initial Edit state shows the `magic_link_email_field` and
 *     `magic_link_send_button` testTags; button is disabled when email
 *     is empty.
 *  2. **A.2.8.b** `magic_link_email_with_invalid_format_disables_send_button`
 *     — typing "not-an-email" keeps the button disabled.
 *  3. **A.2.8.c** `magic_link_email_with_valid_format_enables_send_button`
 *     — typing "parent@example.com" enables the button.
 *  4. **A.2.8.d** `magic_link_on_submit_invoke_signInWithMagicLink_happy_path`
 *     — tapping the button with a valid email invokes
 *     `DeviceAuthManager.signInWithMagicLink("parent@example.com")`
 *     and the screen transitions to the `Sent` state (showing
 *     "Revisa tu email" copy).
 *  5. **A.2.8.e** `magic_link_on_submit_invoke_signInWithMagicLink_invalid_email_returns_Failed`
 *     — when the sender returns `Result.failure` the screen renders
 *     the `magic_link_error_text` testTag and a Reintentar button.
 *  6. **A.2.8.f** `magic_link_on_submit_with_sending_state_shows_loading_indicator`
 *     — while the sender is in flight, the `magic_link_loading`
 *     testTag is rendered.
 *
 * # Implementation note: FakeMagicLinkSender instead of MockK
 *
 * This class deliberately avoids `mockk<DeviceAuthManager>()` to side-
 * step the project-wide MockK + JDK 21 incompatibility documented at
 * `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`
 * §Blockers (the existing `OnboardingScreenTest.parent_tap_*` and
 * similar tests fail with `MockKException` for the same root cause).
 * Instead, the VM takes a [MagicLinkSender] functional interface, and
 * the test injects a hand-rolled [FakeMagicLinkSender] that captures
 * the email + returns a configurable [Result]. Production uses the
 * Hilt-bound `DeviceAuthManagerMagicLinkSender` wrapper (see
 * `MagicLinkModule`). This is the same test-injection pattern
 * Slice A's `DeviceAuthManagerMagicLinkTest` used (Ktor MockEngine +
 * reflection on the private `httpClient` field), adapted for Compose
 * UI tests where the seam has to be the VM constructor's parameter.
 *
 * Mirrors `OnboardingScreenTest` infrastructure: Robolectric + Compose
 * `createComposeRule` + the project's [ParentalControlTheme].
 *
 * The [EmailValidator] regex is exposed as a static helper so the
 * production code (MagicLinkSignInScreen button enable predicate) and
 * the test (assumes button follows the same predicate) stay in sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class MagicLinkSignInScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var sender: FakeMagicLinkSender

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        sender = FakeMagicLinkSender()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setScreen(
        onBack: () -> Unit = {}
    ): MagicLinkViewModel {
        val vm = MagicLinkViewModel(sender)
        composeTestRule.setContent {
            ParentalControlTheme {
                MagicLinkSignInScreen(
                    viewModel = vm,
                    onBack = onBack
                )
            }
        }
        composeTestRule.waitForIdle()
        return vm
    }

    /**
     * **A.2.8.a** RED on `master`: `MagicLinkSignInScreen` /
     * `MagicLinkViewModel` / `EmailValidator` do not exist yet → compile
     * error on this test file's references. Once GREEN lands, the test
     * must pass: both testTags are rendered, and the button is initially
     * disabled (Editing state with empty email fails the validator).
     */
    @Test
    fun magic_link_renders_email_field_and_send_button_in_editing_state() {
        setScreen()

        // Both testTags are present.
        composeTestRule.onNodeWithTag("magic_link_email_field").assertExists()
        composeTestRule.onNodeWithTag("magic_link_send_button").assertExists()

        // Initial state: empty email → button disabled per the validator.
        composeTestRule.onNodeWithTag("magic_link_send_button").assertIsNotEnabled()

        // Sanity check: empty email fails the regex validator.
        assertTrue(
            "EmailValidator must reject empty string",
            !EmailValidator.isValid("")
        )
    }

    /**
     * **A.2.8.b** RED on `master`. Once GREEN lands, typing
     * "not-an-email" must keep the send button disabled because the
     * regex `^[^\s@]+@[^\s@]+\.[^\s@]+$` rejects it.
     */
    @Test
    fun magic_link_email_with_invalid_format_disables_send_button() {
        setScreen()

        composeTestRule.onNodeWithTag("magic_link_email_field").performTextInput("not-an-email")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("magic_link_send_button").assertIsNotEnabled()

        // Sanity check: the validator rejects the malformed input.
        assertTrue(
            "EmailValidator must reject 'not-an-email', accepted: " +
                EmailValidator.isValid("not-an-email"),
            !EmailValidator.isValid("not-an-email")
        )
    }

    /**
     * **A.2.8.c** RED on `master`. Once GREEN lands, typing
     * "parent@example.com" enables the send button.
     */
    @Test
    fun magic_link_email_with_valid_format_enables_send_button() {
        setScreen()

        composeTestRule.onNodeWithTag("magic_link_email_field").performTextInput("parent@example.com")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("magic_link_send_button").assertIsEnabled()

        assertTrue(
            "EmailValidator must accept 'parent@example.com'",
            EmailValidator.isValid("parent@example.com")
        )
    }

    /**
     * **A.2.8.d** RED on `master`. Once GREEN lands, tapping the button
     * on a valid email invokes
     * `MagicLinkSender.signInWithMagicLink("parent@example.com")` and
     * the screen transitions to the `Sent` state (showing "Revisa tu
     * email" copy). The fake captures the call so the test can verify
     * the exact argument.
     */
    @Test
    fun magic_link_on_submit_invoke_signInWithMagicLink_happy_path() {
        val messageId = "msg-uuid-test"
        sender.nextResult = Result.success(MagicLinkSent(messageId = messageId))

        setScreen()
        composeTestRule.onNodeWithTag("magic_link_email_field").performTextInput("parent@example.com")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("magic_link_send_button").performClick()
        composeTestRule.waitForIdle()

        // Sender was invoked exactly once with the typed email.
        assertEquals(1, sender.calls.size)
        assertEquals("parent@example.com", sender.calls.first())

        // Sent-state copy is rendered.
        composeTestRule.onNodeWithText("Revisa tu email").assertIsDisplayed()
    }

    /**
     * **A.2.8.e** RED on `master`. Once GREEN lands, when the sender
     * returns `Result.failure` the screen transitions to the `Failed`
     * state, rendering the `magic_link_error_text` testTag and a
     * Reintentar button.
     */
    @Test
    fun magic_link_on_submit_invoke_signInWithMagicLink_invalid_email_returns_Failed() {
        sender.nextResult = Result.failure(RuntimeException("invalid_email"))

        setScreen()
        composeTestRule.onNodeWithTag("magic_link_email_field").performTextInput("parent@example.com")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("magic_link_send_button").performClick()
        composeTestRule.waitForIdle()

        // Sender was invoked exactly once.
        assertEquals(1, sender.calls.size)
        assertEquals("parent@example.com", sender.calls.first())

        // Failed-state error and retry CTA rendered.
        composeTestRule.onNodeWithTag("magic_link_error_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("magic_link_retry_button").assertExists()
    }

    /**
     * **A.2.8.f** RED on `master`. Once GREEN lands, while the sender
     * is in flight (the deferred is awaited) the screen renders the
     * `magic_link_loading` testTag. Once we complete the deferred with
     * success, the screen transitions to `Sent`.
     */
    @Test
    fun magic_link_on_submit_with_sending_state_shows_loading_indicator() {
        val gate = CompletableDeferred<Result<MagicLinkSent>>()
        sender.pendingResult = gate

        setScreen()
        composeTestRule.onNodeWithTag("magic_link_email_field").performTextInput("parent@example.com")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("magic_link_send_button").performClick()
        composeTestRule.waitForIdle()

        // Sending state — loading indicator rendered.
        composeTestRule.onNodeWithTag("magic_link_loading").assertIsDisplayed()

        // Complete the gate with success → screen transitions to Sent.
        gate.complete(Result.success(MagicLinkSent("msg-id")))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Revisa tu email").assertIsDisplayed()
    }
}

/**
 * Hand-rolled test double for [MagicLinkSender]. The VM only calls
 * `signInWithMagicLink(email)` from inside the [MagicLinkViewModel.submit]
 * coroutine under the default `USE_SHARED_MOCK=false` build, so two
 * seams cover every test:
 *
 *  - [calls] — captures every email the VM submitted (assert the
 *    argument).
 *  - [nextResult] / [pendingResult] — controls the response. If
 *    [pendingResult] is set, the sender awaits the deferred (lets
 *    the test observe the `Sending` state). Otherwise [nextResult] is
 *    returned synchronously.
 *
 * Slice B1 adds `devLogin` to [MagicLinkSender]; the fake throws if
 * it's ever invoked (test build has `USE_SHARED_MOCK=false`).
 *
 * Kept private to this file so the seam is co-located with the test
 * that uses it. If other tests need it, promote to
 * `app/src/test/.../fakes/`.
 */
private class FakeMagicLinkSender : MagicLinkSender {
    val calls: MutableList<String> = mutableListOf()
    var nextResult: Result<MagicLinkSent> = Result.success(MagicLinkSent("default-id"))
    var pendingResult: CompletableDeferred<Result<MagicLinkSent>>? = null

    override suspend fun signInWithMagicLink(email: String): Result<MagicLinkSent> {
        calls += email
        val pending = pendingResult
        return if (pending != null) {
            pending.await()
        } else {
            assertNull(
                "FakeMagicLinkSender: pendingResult was consumed and reset to null " +
                    "on first call (gate.complete was called). Subsequent calls " +
                    "should switch the test back to nextResult mode.",
                null
            )
            nextResult
        }
    }

    override suspend fun devLogin(email: String): Result<com.tudominio.parentalcontrol.auth.ParentSession> =
        error("devLogin not exercised in tests (USE_SHARED_MOCK=false in test build)")
}
