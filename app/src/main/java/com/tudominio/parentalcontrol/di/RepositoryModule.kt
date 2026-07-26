package com.tudominio.parentalcontrol.di

import android.content.Context
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptCoordinator
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

/**
 * Hilt bindings for infra-level singletons that are NOT yet Hilt-managed
 * (time, auth, Supabase client). Singletons that have been migrated to
 * `@Singleton @Inject constructor` — [com.tudominio.parentalcontrol.sync.SyncManager],
 * [com.tudominio.parentalcontrol.outbox.OutboxManager],
 * [com.tudominio.parentalcontrol.health.HealthMonitor],
 * [com.tudominio.parentalcontrol.analytics.AnalyticsManager],
 * [com.tudominio.parentalcontrol.reward.RewardManager],
 * [com.tudominio.parentalcontrol.data.repository.TimeExtraRepository] —
 * are bound automatically by Hilt via their `@Inject` constructors and
 * need no provider here.
 *
 * Split from the former [AppModule] so DB wiring lives in [DatabaseModule]
 * and the rest of the application infra lives here.
 *
 * T5 of `hotfix-parent-auth-session` injects the `@SupabaseClient`
 * `HttpClient` from `NetworkModule` into the Hilt-managed
 * [SupabaseClientProvider] so the parent dashboard renders fixture
 * devices when `BuildConfig.USE_MOCK_SUPABASE=true` (instead of hitting
 * the placeholder Supabase URL and failing with a network error).
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideTimeProvider(@ApplicationContext context: Context): TimeProvider =
        DefaultTimeProvider(context)

    @Provides
    @Singleton
    fun provideDeviceAuthManager(@ApplicationContext context: Context): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSupabaseClientProvider(
        @ApplicationContext context: Context,
        @SupabaseClient httpClient: HttpClient
    ): SupabaseClientProvider {
        // The Hilt-managed instance uses the @SupabaseClient binding so the
        // mock engine (when the flag is true) or the real engine (when
        // false) is bound here. Callers that still use
        // `SupabaseClientProvider.getInstance(context)` get the legacy
        // real-engine path via the secondary constructor.
        return SupabaseClientProvider(
            context = context,
            injectedClient = httpClient
        )
    }

    /**
     * WU-D — Device Admin prompt coordinator. Hilt-managed singleton
     * so the same state machine is shared across the pairing screen
     * (which gates the NavigateToHome) and the child status screen
     * (which renders the one-time banner). The coordinator owns
     * the state — neither the Compose layers nor the ViewModel
     * ViewModel touch the lifecycle directly.
     */
    @Provides
    @Singleton
    fun provideDeviceAdminPromptCoordinator(): DeviceAdminPromptCoordinator =
        DeviceAdminPromptCoordinator()
}
