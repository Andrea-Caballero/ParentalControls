package com.tudominio.parentalcontrol.di

import javax.inject.Qualifier

/**
 * Hilt qualifier for the Ktor `HttpClient` that talks to Supabase.
 *
 * Per design §D1 of `openspec/changes/hotfix-parent-auth-session/design.md`:
 * the qualifier marks what the CONSUMER wants (a Supabase `HttpClient`),
 * not how it was built. The mock-vs-real decision lives entirely inside
 * [NetworkModule]; consumers should never need to know which engine is
 * bound at runtime.
 *
 * Usage at a consumer site:
 * ```kotlin
 * class Foo @Inject constructor(
 *     @SupabaseClient private val httpClient: HttpClient
 * ) { ... }
 * ```
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SupabaseClient
