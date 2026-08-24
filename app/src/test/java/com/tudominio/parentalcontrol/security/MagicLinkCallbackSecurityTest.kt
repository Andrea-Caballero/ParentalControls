package com.tudominio.parentalcontrol.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static regression tests for the disabled custom-scheme callback boundary. */
class MagicLinkCallbackSecurityTest {

    private val appRoot = File("src")
    private val manifestFiles = listOf(
        File(appRoot, "main/AndroidManifest.xml"),
        File(appRoot, "androidTest/AndroidManifest.xml")
    )
    private val mainManifest = manifestFiles.first()
    private val legacyRules = File(appRoot, "main/res/xml/secure_storage_backup_rules.xml")
    private val extractionRules = File(appRoot, "main/res/xml/data_extraction_rules.xml")

    @Test
    fun all_variant_manifests_keep_callback_route_removed() {
        manifestFiles.forEach { manifest ->
            assertTrue("Missing manifest: ${manifest.path}", manifest.exists())
            val source = manifest.readText()
            assertFalse(source.contains("android:host=\"magic-link\""))
            assertFalse(source.contains("parentalcontrol://magic-link"))
        }
    }

    @Test
    fun main_manifest_references_both_backup_rule_formats_and_keeps_routes() {
        val source = mainManifest.readText()

        assertTrue(source.contains("android:fullBackupContent=\"@xml/secure_storage_backup_rules\""))
        assertTrue(source.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(source.contains("android.intent.action.MAIN"))
        assertTrue(source.contains("android.intent.category.LAUNCHER"))
        assertTrue(source.contains("android:host=\"pair\""))
        assertTrue(source.contains("android:host=\"request-extra-time\""))
    }

    @Test
    fun backup_rules_use_valid_domains_and_exclude_auth_preferences() {
        val validDomains = setOf("sharedpref", "database")
        assertBackupRules(legacyRules, "full-backup-content", validDomains)
        assertBackupRules(extractionRules, "data-extraction-rules", validDomains)
    }

    @Test
    fun callback_source_has_no_handler_verifier_or_sensitive_logging_plumbing() {
        val sourceFiles = listOf(
            File(appRoot, "main/java/com/tudominio/parentalcontrol/MainActivity.kt"),
            File(appRoot, "main/java/com/tudominio/parentalcontrol/ui/navigation/AppNavHost.kt"),
            File(appRoot, "main/java/com/tudominio/parentalcontrol/ui/navigation/NavGraph.kt"),
            File(appRoot, "main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt")
        )
        val source = sourceFiles.joinToString("\n") { it.readText() }

        listOf(
            "MagicLinkDeepLinkHandler",
            "MagicLinkVerifier",
            "pendingMagicLinkUrl",
            "getQueryParameter(\"token\")",
            "getQueryParameter(\"email\")"
        ).forEach { forbidden ->
            assertFalse("Forbidden callback plumbing: $forbidden", source.contains(forbidden))
        }

        val logLines = source.lines().filter { it.contains("Log.") }
        assertTrue(logLines.isNotEmpty())
        logLines.forEach { line ->
            assertFalse(line.contains("e.message"))
            assertFalse(line.contains(", e)"))
            assertFalse(line.contains("parent_id=\""))
            assertFalse(line.contains("token="))
            assertFalse(line.contains("email="))
        }
    }

    private fun assertBackupRules(file: File, rootName: String, validDomains: Set<String>) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        assertEquals(rootName, document.documentElement.nodeName)

        val excludes = document.getElementsByTagName("exclude")
        assertTrue("${file.name} must exclude device_auth_prefs.xml", (0 until excludes.length).any { index ->
            val element = excludes.item(index) as org.w3c.dom.Element
            element.getAttribute("domain") == "sharedpref" &&
                element.getAttribute("path") == "device_auth_prefs.xml"
        })
        assertTrue("${file.name} must exclude secure_storage.xml", (0 until excludes.length).any { index ->
            val element = excludes.item(index) as org.w3c.dom.Element
            element.getAttribute("domain") == "sharedpref" &&
                element.getAttribute("path") == "secure_storage.xml"
        })
        for (index in 0 until excludes.length) {
            val element = excludes.item(index) as org.w3c.dom.Element
            assertTrue(
                "Invalid backup domain in ${file.name}",
                element.getAttribute("domain") in validDomains
            )
        }
    }
}
