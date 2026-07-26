package com.tudominio.parentalcontrol.di

import com.tudominio.parentalcontrol.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Build-wiring tests for the `parent-auth-session` /
 * `hotfix-parent-auth-cta-reload` requirement that
 * `BuildConfig.USE_MOCK_SUPABASE` defaults to `true` for the `debug`
 * build type (with an opt-out via the `-PuseRealSupabase=true` Gradle
 * property) and MUST be hardcoded to `false` for `release`.
 *
 * The `debug`-defaults-to-true contract was introduced by commit
 * `02c54bd` ("fix(build): default USE_MOCK_SUPABASE=true in debug,
 * override via -PuseRealSupabase"), which superseded the earlier
 * "debug reads `local.properties`" design (see
 * `openspec/changes/hotfix-parent-auth-cta-reload/design.md`,
 * Decision 2). The current resolution lives at
 * `app/build.gradle.kts:32-33`:
 *
 * ```kotlin
 * val debugUseMockSupabase: String =
 *     if (project.findProperty("useRealSupabase") == "true") "false" else "true"
 * ```
 *
 * **Why source-text assertions, not a runtime `BuildConfig` switch**: the
 * debug / release variants compile into separate `BuildConfig.java` files
 * before the test JVM starts. We cannot flip variants from inside a test,
 * so the only honest way to validate "the release build type hardcodes
 * `USE_MOCK_SUPABASE=false`" is to read `app/build.gradle.kts` and
 * assert the wiring is correct. We additionally read
 * `BuildConfig.USE_MOCK_SUPABASE` at runtime as a sanity check that the
 * wired value reaches the debug `BuildConfig` (i.e. the constant exists
 * and reflects the default-true contract under the default debug
 * variant — no `-PuseRealSupabase=true` override is applied here).
 *
 * Note: this class no longer writes a temporary `local.properties.test`.
 * The superseded "debug reads `local.properties`" parser path is gone,
 * so the `setUp` / `tearDown` file dance was removed in #30.
 *
 * Per the `parent-auth-session` / `hotfix-parent-auth-cta-reload`
 * verification hooks:
 *  - `debug_buildtype_defaults_useMockSupabase_true_unless_useRealSupabase_override`
 *  - `release_buildtype_ignores_localProperties_useMockSupabase`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkModuleTest {

    // Gradle's test worker sets the working directory to `app/`, so the
    // module-level build script is at the same path.
    private val buildScript = File("build.gradle.kts")

    @Test
    fun debug_buildtype_defaults_useMockSupabase_true_unless_useRealSupabase_override() {
        val source = buildScript.readText()

        // 1. The build script must gate `debugUseMockSupabase` on the
        //    `-PuseRealSupabase` Gradle property (the contract introduced
        //    by commit 02c54bd that superseded the `local.properties`
        //    parser). We match the gating expression loosely to allow
        //    whitespace reflows without false negatives.
        val gatesOnUseRealSupabase = Regex(
            """project\.findProperty\(\s*"useRealSupabase"\s*\)\s*==\s*"true"\s*"""
        ).containsMatchIn(source)
        assertTrue(
            "app/build.gradle.kts must gate debugUseMockSupabase on " +
                "project.findProperty(\"useRealSupabase\") == \"true\". " +
                "Source:\n$source",
            gatesOnUseRealSupabase
        )

        // 2. There must be a debug-scoped buildConfigField for
        //    USE_MOCK_SUPABASE. We accept either an explicit `debug { ... }`
        //    block or one nested inside a buildTypes container — what
        //    matters is that the wiring is gated to debug, NOT under
        //    defaultConfig.
        val debugBlock = extractDebugBlock(source)
        assertNotNull(
            "app/build.gradle.kts must define a buildTypes.debug block. " +
                "Source:\n$source",
            debugBlock
        )
        assertTrue(
            "buildTypes.debug must declare buildConfigField for USE_MOCK_SUPABASE. " +
                "Debug block:\n$debugBlock",
            debugBlock!!.contains("buildConfigField") &&
                debugBlock.contains("USE_MOCK_SUPABASE")
        )

        // 3. Sanity check: under the default debug variant (no
        //    `-PuseRealSupabase` override), the wired `debugUseMockSupabase`
        //    resolves to "true", so BuildConfig.USE_MOCK_SUPABASE must be
        //    true. If this is false the wiring isn't reaching the debug
        //    variant (or the gating expression changed).
        assertEquals(
            "BuildConfig.USE_MOCK_SUPABASE must be true for the default " +
                "debug variant (no -PuseRealSupabase override).",
            true, BuildConfig.USE_MOCK_SUPABASE
        )
    }

    @Test
    fun release_buildtype_ignores_localProperties_useMockSupabase() {
        val source = buildScript.readText()

        // The release block must hardcode "false" for USE_MOCK_SUPABASE,
        // NOT reference the local.properties parser. We assert the literal
        // string is present in the release block.
        val releaseBlock = extractReleaseBlock(source)
        assertNotNull(
            "app/build.gradle.kts must define a buildTypes.release block. " +
                "Source:\n$source",
            releaseBlock
        )
        assertTrue(
            "buildTypes.release must hardcode USE_MOCK_SUPABASE to \"false\" " +
                "(must NOT depend on local.properties). Release block:\n$releaseBlock",
            releaseBlock!!.contains("buildConfigField") &&
                releaseBlock.contains("USE_MOCK_SUPABASE") &&
                releaseBlock.contains("\"false\"")
        )

        // The release block must not reference the local.properties parser
        // variable (`localPropertiesForMock` or `debugUseMockSupabase`).
        // Comments mentioning "local.properties" are fine — we strip them
        // before checking to avoid false positives from documentation.
        val codeOnly = releaseBlock.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "buildTypes.release must NOT reference local.properties parser " +
                "(localPropertiesForMock / debugUseMockSupabase). " +
                "Code-only release block:\n$codeOnly",
            codeOnly.contains("localPropertiesForMock") ||
                codeOnly.contains("debugUseMockSupabase") ||
                codeOnly.contains("rootProject.file(\"local.properties\")")
        )

        // Sanity check: we are running under the debug variant, so
        // BuildConfig.USE_MOCK_SUPABASE is whatever the debug wiring
        // produced. This test asserts the SOURCE WIRING is correct, so
        // a release build would resolve USE_MOCK_SUPABASE=false. We don't
        // re-run the release variant here; we trust the source assertion.
        // The point of this test is to lock in the structural contract so
        // a future contributor can't accidentally make release read
        // local.properties.
        assertNotNull(BuildConfig.USE_MOCK_SUPABASE)
    }

    /**
     * Extract the text of the `debug { ... }` block inside `buildTypes`.
     * Matches the first `debug {` and walks braces until balanced close.
     * Returns `null` if not found.
     */
    private fun extractReleaseBlock(source: String): String? =
        extractBuildTypeBlock(source, "release")

    private fun extractDebugBlock(source: String): String? =
        extractBuildTypeBlock(source, "debug")

    private fun extractBuildTypeBlock(source: String, name: String): String? {
        val marker = Regex("""\b$name\s*\{\s*""")
        val match = marker.find(source)
        if (match == null) return null
        val openIdx = match.range.last
        var depth = 1
        var i = openIdx + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return if (depth != 0) null else source.substring(openIdx, i)
    }
}
