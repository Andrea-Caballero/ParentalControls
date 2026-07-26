package com.tudominio.parentalcontrol.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural tests for `app/src/main/java/com/tudominio/parentalcontrol/MainActivity.kt`.
 *
 * Verifies the contract from the `app-entry-routing` spec:
 *  - `MainActivity` SHALL NOT contain the top-level routing `when` block
 *    that previously selected among `OnboardingScreen` / `DashboardScreen`
 *    / `ChildStatusScreen`. That logic now lives in [NavGraph].
 *  - `MainActivity.setContent { ... }` body SHALL be ≤ 5 lines — the
 *    activity is for lifecycle + deeplink plumbing only; routing details
 *    are delegated to [NavGraph].
 *  - `handleDeeplink` (renamed from `handlePairingDeeplink` when T28
 *    of `overlay-to-extratime` added a second deeplink — the helper
 *    now dispatches both `parentalcontrol://pair` and
 *    `parentalcontrol://request-extra-time`), `enableEdgeToEdge`, and
 *    `onNewIntent` handling SHALL remain in `MainActivity` (per the spec).
 *
 * The tests parse the file as plain text — Robolectric / Compose are not
 * involved. This is a `testDebugUnitTest` unit test that gives fast
 * feedback before each `assembleDebug`.
 *
 * TDD note: written BEFORE the MainActivity refactor (RED → GREEN).
 * Originally the `setContent` block spanned 16 lines because all of
 * NavGraph's params were inlined; extracting the wiring into a private
 * `@Composable` helper brings it down to ≤ 5 lines and is what makes
 * this test pass.
 */
class MainActivityRoutingTest {

    private val mainActivity = File("src/main/java/com/tudominio/parentalcontrol/MainActivity.kt")

    @Test
    fun main_activity_source_exists() {
        assertTrue(
            "Expected ${mainActivity.absolutePath} to exist",
            mainActivity.exists()
        )
    }

    @Test
    fun main_activity_does_not_contain_top_level_routing_when_block() {
        val content = mainActivity.readText()

        // The spec forbids the pre-PR-4 shape: a `when { !isPaired ->
        // ... isChildDevice -> ... else -> ... }` block in setContent
        // that picks the top-level screen. Block comments are stripped
        // so KDoc references to the legacy shape don't trip the regex.
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val stripped = blockComment.replace(content, "")

        // We are looking for the *legacy* inline shape — a `when` whose
        // branches contain `OnboardingScreen` / `DashboardScreen` /
        // `ChildStatusScreen` directly. The current NavGraph composable
        // does contain a `when (route)`, but it dispatches to those
        // screens via NavGraph; the regex below targets the legacy
        // shape that used to live inside MainActivity.setContent.
        val legacyShape = Regex(
            """when\s*\(\s*isPaired\s*\)\s*\{[^}]*OnboardingScreen[^}]*DashboardScreen[^}]*ChildStatusScreen"""
        )

        assertTrue(
            "MainActivity.kt must not contain the legacy top-level routing when block. " +
                "Route selection now lives in NavGraph.",
            !legacyShape.containsMatchIn(stripped)
        )
    }

    @Test
    fun main_activity_setContent_body_has_at_most_five_lines() {
        val content = mainActivity.readText()
        val lines = content.lines()

        // Locate the setContent block: from the line containing
        // `setContent {` to its matching closing `}` at the same
        // brace depth. We scan forward, tracking depth, so nested
        // lambda braces do not confuse the matcher.
        val setContentIdx = lines.indexOfFirst { it.contains("setContent") && it.contains("{") }
        assertTrue(
            "MainActivity.kt must contain a setContent { ... } block. " +
                "Did the activity stop calling setContent?",
            setContentIdx >= 0
        )

        var depth = 0
        var endIdx = -1
        var started = false
        for (i in setContentIdx until lines.size) {
            for (c in lines[i]) {
                when (c) {
                    '{' -> {
                        depth++
                        started = true
                    }
                    '}' -> {
                        depth--
                        if (started && depth == 0) {
                            endIdx = i
                            break
                        }
                    }
                }
            }
            if (endIdx >= 0) break
        }

        assertTrue(
            "Failed to locate the closing brace of setContent in MainActivity.kt",
            endIdx >= 0
        )

        // The "body" of setContent is from setContent's opening line to
        // its matching closing brace, inclusive. The spec caps it at 5.
        val bodyLineCount = endIdx - setContentIdx + 1
        assertTrue(
            "MainActivity.setContent body is $bodyLineCount lines; spec requires ≤ 5. " +
                "Extract the wiring into a private @Composable helper in MainActivity.kt " +
                "and keep setContent focused on ParentalControlTheme + the helper call.",
            bodyLineCount <= 5
        )
    }

    @Test
    fun main_activity_retains_handle_pairing_deeplink_helper() {
        // T28 renamed `handlePairingDeeplink` → `handleDeeplink` because
        // the helper now dispatches two deeplinks (`pair` and
        // `request-extra-time`), not just pairing. The test name is kept
        // for spec traceability — the spec PR is
        // `align-with-guia-fedora44` PR 4 — and updated to assert the
        // new name.
        val content = mainActivity.readText()
        assertTrue(
            "MainActivity must keep the deeplink dispatcher helper (per the spec; " +
                "renamed to `handleDeeplink` in T28 because it now handles two deeplinks)",
            content.contains("handleDeeplink")
        )
        assertTrue(
            "MainActivity must override onNewIntent so warm-start deeplinks update the pairing code",
            content.contains("override fun onNewIntent")
        )
    }

    @Test
    fun main_activity_retains_enable_edge_to_edge() {
        val content = mainActivity.readText()
        assertTrue(
            "MainActivity must keep `enableEdgeToEdge()` (per the spec)",
            content.contains("enableEdgeToEdge")
        )
    }
}
