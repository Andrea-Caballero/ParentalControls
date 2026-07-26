package com.tudominio.parentalcontrol.pairing

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the `parentalcontrol://pair?code=...` deeplink intent-filter
 * (PR 1 task #8 of `openspec/changes/wire-pairing-and-approval-end-to-end`).
 *
 * Verifies that the manifest's intent-filter for `parentalcontrol://pair`
 * matches the deeplink with a `code` query parameter, and that the
 * `code` can be extracted from the intent's data URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingDeeplinkTest {

    @Test
    fun deeplink_intent_with_code_query_param_routes_to_pairing_screen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager

        // Build the deeplink intent
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("parentalcontrol://pair?code=ABCDEFGH"))
        intent.setPackage(context.packageName)

        // Resolve the activity that should handle this intent
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        assertTrue(
            "Expected at least one Activity to handle the deeplink, got 0",
            resolveInfos.isNotEmpty()
        )

        // The MainActivity should be one of the resolvers
        val mainActivityResolved = resolveInfos.any { ri ->
            ri.activityInfo.name.endsWith(".MainActivity")
        }
        assertTrue("Expected MainActivity to handle the deeplink", mainActivityResolved)
    }

    @Test
    fun deeplink_code_query_parameter_is_extractable() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("parentalcontrol://pair?code=ABCDEFGH"))

        val code = intent.data?.getQueryParameter("code")

        assertEquals("ABCDEFGH", code)
    }

    @Test
    fun deeplink_scheme_and_host_are_correct() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("parentalcontrol://pair?code=TEST1234"))

        assertEquals("parentalcontrol", intent.data?.scheme)
        assertEquals("pair", intent.data?.host)
    }

    @Test
    fun deeplink_handles_missing_code_query_param() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("parentalcontrol://pair"))

        val code = intent.data?.getQueryParameter("code")

        assertNotNull(intent.data)
        assertEquals(null, code)
    }
}
