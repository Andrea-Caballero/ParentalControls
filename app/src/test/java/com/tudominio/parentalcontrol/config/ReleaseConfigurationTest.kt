package com.tudominio.parentalcontrol.config

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseConfigurationTest {
    @Test
    fun `release configuration has no shipping Supabase placeholders`() {
        val source = File("build.gradle.kts").readText()
        assertFalse(source.contains("your-project.supabase.co"))
        assertFalse(source.contains("your-anon-key"))
        assertTrue(source.contains("requireReleaseSupabaseConfiguration"))
        assertTrue(source.contains("beforeVariants(selector().withBuildType(\"release\"))"))
        assertTrue(source.contains("supabase\\.co"))
        assertTrue(source.contains("releasePinPattern"))
        assertTrue(source.contains("SUPABASE_PIN_BACKUP_CA"))
    }

    @Test
    fun `release pin validation rejects placeholders duplicates and invalid decoded lengths`() {
        val source = File("build.gradle.kts").readText()
        assertTrue(source.contains("java.util.Base64.getDecoder().decode"))
        assertTrue(source.contains("pins.toSet().size == pins.size"))
        assertTrue(source.contains("toSet().size > 1"))
        assertTrue(source.contains("it?.size == 32"))
    }
}
