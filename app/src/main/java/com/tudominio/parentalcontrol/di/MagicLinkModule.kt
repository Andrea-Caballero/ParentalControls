package com.tudominio.parentalcontrol.di

import com.tudominio.parentalcontrol.ui.auth.DeviceAuthManagerMagicLinkSender
import com.tudominio.parentalcontrol.ui.auth.MagicLinkSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the `ui/auth` package (parent magic-link sign-in
 * flow). `@Binds` declares that the production
 * [MagicLinkSender] is [DeviceAuthManagerMagicLinkSender] — a thin
 * wrapper that forwards to `DeviceAuthManager.signInWithMagicLink`
 * without modifying `DeviceAuthManager.kt` (out-of-scope per the
 * apply contract).
 *
 * Why a wrapper class instead of `@Binds` on `DeviceAuthManager`
 * directly: Hilt's `@Binds` requires the target class to be in the
 * same compilation unit as the binding, and `DeviceAuthManager` is
 * not annotated with the magic-link path as a separate interface
 * (Slice A added the method but did not introduce an interface).
 * The wrapper keeps the binding local to this change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MagicLinkModule {

    @Binds
    @Singleton
    abstract fun bindMagicLinkSender(
        impl: DeviceAuthManagerMagicLinkSender
    ): MagicLinkSender
}
