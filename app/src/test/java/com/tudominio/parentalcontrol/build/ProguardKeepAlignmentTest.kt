package com.tudominio.parentalcontrol.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Static checks for `app/proguard-rules.pro`.
 *
 * Verifies the contract from `proguard-keep-alignment` spec:
 *  - Every `-keep class com.tudominio.parentalcontrol.X { *; }` line under the
 *    `# ===== MAINTAIN CLASS NAMES (for tampered APK detection) =====` block
 *    must point to a real Kotlin source file.
 *  - No `-keep` rule may reference the pre-PR-2 stale class names
 *    (`ForegroundAppService`, `UsageTrackingService`,
 *    `DeviceAdminReceiver`, `LockScreenReceiver`).
 *
 * The test parses the file as plain text — R8 / Gradle are not involved.
 * This is a `testDebugUnitTest` unit test that does not need Robolectric.
 */
class ProguardKeepAlignmentTest {

    // Gradle's test worker sets the working directory to `app/`, so paths
    // are relative to that. The proguard file lives one level up.
    private val proguardFile = File("proguard-rules.pro")
    private val sourceRoot = File("src/main/java/com/tudominio/parentalcontrol")

    @Test
    fun proguard_file_exists() {
        assertTrue(
            "Expected app/proguard-rules.pro to exist at ${proguardFile.absolutePath}",
            proguardFile.exists()
        )
    }

    @Test
    fun no_stale_pre_pr2_class_names_in_proguard() {
        val content = proguardFile.readText()
        val staleNames = listOf(
            "ForegroundAppService",
            "UsageTrackingService",
            "DeviceAdminReceiver",
            "LockScreenReceiver"
        )
        val violations = staleNames.filter { content.contains(it) }
        assertEquals(
            "proguard-rules.pro must not reference pre-PR-2 class names: $violations",
            emptyList<String>(),
            violations
        )
    }

    @Test
    fun every_kept_class_resolves_to_an_existing_source_file() {
        val content = proguardFile.readText()
        val lines = content.lines()

        // Find the entry-point block — the spec scopes `-keep` to the section
        // titled `# ===== MAINTAIN CLASS NAMES (for tampered APK detection) =====`
        val startMarker = "# ===== MAINTAIN CLASS NAMES (for tampered APK detection) ====="
        val startIdx = lines.indexOfFirst { it.trim() == startMarker }
        assertNotNull(
            "Expected to find entry-point section in proguard-rules.pro",
            startIdx.takeIf { it >= 0 }
        )
        val block = lines.subList(startIdx + 1, lines.size)

        // Regex captures `-keep class com.tudominio.parentalcontrol.X.Y.Z { *; }`
        val keepRegex = Regex("""-keep class (com\.tudominio\.parentalcontrol[\w.$]+)\s*\{\s*\*;\s*\}""")

        val missing = mutableListOf<String>()
        val keptClasses = mutableListOf<String>()

        for (line in block) {
            // Stop scanning at the next section header — `-keep` rules outside
            // this block (e.g. framework rules) are out of scope for this test.
            if (line.trim().startsWith("# =====")) break

            val match = keepRegex.find(line) ?: continue
            val fqn = match.groupValues[1]
            keptClasses += fqn

            // Convert FQN to relative path under app/src/main/java/...
            val relativePath = fqn.removePrefix("com.tudominio.parentalcontrol.")
                .replace('.', '/') + ".kt"
            val sourceFile = File(sourceRoot, relativePath)
            if (!sourceFile.exists()) {
                missing += "$fqn (expected at ${sourceFile.path})"
            }
        }

        assertTrue(
            "Expected proguard entry-point section to define at least one -keep rule",
            keptClasses.isNotEmpty()
        )
        assertEquals(
            "All -keep entry-point classes must resolve to existing .kt files. Missing: $missing",
            emptyList<String>(),
            missing
        )
    }

    @Test
    fun app_monitor_service_and_monitor_foreground_service_are_kept() {
        val content = proguardFile.readText()
        assertTrue(
            "AppMonitorService must be kept for tamper detection",
            content.contains("-keep class com.tudominio.parentalcontrol.accessibility.AppMonitorService")
        )
        assertTrue(
            "MonitorForegroundService must be kept for tamper detection",
            content.contains("-keep class com.tudominio.parentalcontrol.service.MonitorForegroundService")
        )
    }

    @Test
    fun manifest_referenced_services_have_source_files() {
        // AndroidManifest references these FQNs. If their .kt files are
        // missing, the manifest would fail to resolve them at install time.
        val manifestClasses = listOf(
            "com.tudominio.parentalcontrol.MainActivity",
            "com.tudominio.parentalcontrol.accessibility.AppMonitorService",
            "com.tudominio.parentalcontrol.service.MonitorForegroundService",
            "com.tudominio.parentalcontrol.overlay.BlockOverlayService"
        )
        for (fqn in manifestClasses) {
            val relativePath = fqn.removePrefix("com.tudominio.parentalcontrol.")
                .replace('.', '/') + ".kt"
            val sourceFile = File(sourceRoot, relativePath)
            assertTrue(
                "Manifest references $fqn but ${sourceFile.path} does not exist",
                sourceFile.exists()
            )
        }
    }
}
