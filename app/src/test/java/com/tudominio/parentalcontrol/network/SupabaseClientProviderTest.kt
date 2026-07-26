package com.tudominio.parentalcontrol.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.BuildConfig
import io.ktor.client.engine.mock.MockEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED coverage for the legacy `SupabaseClientProvider.getInstance(context)`
 * mock gate (`openspec/changes/fix-supabase-client-provider-legacy-mock-gate`).
 *
 * The spec mandates that `getInstance(context)` SHALL return a provider
 * whose `httpClient` is backed by `MockSupabaseEngine` when
 * `BuildConfig.USE_MOCK_SUPABASE == true`. Today the legacy
 * `internal constructor`'s `httpClient` lazy initializer unconditionally
 * builds a real OkHttp client (gated only on `injectedClient == null`),
 * so the 5 non-Hilt managers (`PairingManager`, `FcmPushService`,
 * `RealtimeManager`, `SyncManager`, `PlayIntegrityManager`) hit a
 * placeholder Supabase URL and surface as `NETWORK_ERROR` under the
 * debug-flag build. This test pins the contract.
 *
 * **Why Robolectric**: we need a real `Context` so the singleton's
 * `DeviceAuthManager` and `MockSupabaseEngine(context).httpClient` can
 * initialize. Robolectric provides the Android-framework shim without
 * requiring an emulator.
 *
 * **Why one test, not two**: per `sdd-tasks` caveat, `BuildConfig`
 * fields are `final` and cannot be flipped from inside a unit test, so
 * the flag-false case is gated by the existing `ParentRepositoryTest` +
 * `PairingManagerTest` regression (both inject `SupabaseClientProvider`
 * via mockk and never exercise the real lazy branch). Toggling
 * `local.properties` mid-suite is not an honest test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SupabaseClientProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Reset the singleton field so this test class always observes the
        // freshly-built lazy client (Robolectric reuses the JVM across test
        // methods; other tests do not call the real `getInstance()` today,
        // but resetting keeps the contract explicit). The companion's
        // `private var instance` compiles to a `static` field on the outer
        // class — same pattern `PairingManagerTest` uses.
        val instanceField = SupabaseClientProvider::class.java
            .getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    @Test
    fun getInstance_with_mock_flag_true_returns_provider_with_mock_engine_client() {
        // Precondition: the debug variant must wire the flag from
        // `local.properties`. If a future contributor flips the wiring
        // off, fail loudly with a clear message instead of a confusing
        // "engine is not MockEngine" assertion failure.
        assertEquals(
            "this test only applies when USE_MOCK_SUPABASE=true (debug " +
                "variant must read local.properties); got " +
                "BuildConfig.USE_MOCK_SUPABASE=${BuildConfig.USE_MOCK_SUPABASE}",
            true,
            BuildConfig.USE_MOCK_SUPABASE
        )

        val provider = SupabaseClientProvider.getInstance(context)
        val engine = provider.httpClient.engine

        assertTrue(
            "expected httpClient.engine to be a Ktor MockEngine when " +
                "BuildConfig.USE_MOCK_SUPABASE=true (legacy getInstance " +
                "must honor the flag), got ${engine::class.qualifiedName}",
            engine is MockEngine
        )
    }
}
