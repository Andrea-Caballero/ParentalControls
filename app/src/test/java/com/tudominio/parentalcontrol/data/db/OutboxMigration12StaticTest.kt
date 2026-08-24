package com.tudominio.parentalcontrol.data.db

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxMigration12StaticTest {
    @Test
    fun `migration 11 to 12 adds nullable claim token without rewriting rows`() {
        val source = File("src/main/java/com/tudominio/parentalcontrol/data/db/ParentalDatabase.kt").readText()
        assertTrue(source.contains("Migration(11, 12)"))
        assertTrue(source.contains("ALTER TABLE outbox ADD COLUMN claim_token TEXT"))
        assertTrue(source.contains("version = 12"))
        assertTrue(source.contains("MIGRATION_11_12"))
    }
}
