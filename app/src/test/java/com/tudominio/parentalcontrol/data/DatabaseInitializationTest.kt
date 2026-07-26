package com.tudominio.parentalcontrol.data

import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural tests for the `database-initialization` spec.
 *
 * Verifies that `ParentalDatabase` no longer exposes a `getInstance`
 * static constructor — Hilt's `DatabaseModule` is now the sole provider.
 *
 * The migration constants (`DATABASE_NAME`, `MIGRATION_4_5`, `MIGRATION_5_6`)
 * MUST stay reachable from the companion object because the instrumented
 * migration tests (`OutboxMigrationTest`, `AppPolicyMigrationTest`)
 * reference them directly.
 *
 * Static grep across `app/src/main/java/` proves that no caller is using
 * the removed singleton — every consumer must receive `ParentalDatabase`
 * via Hilt.
 */
class DatabaseInitializationTest {

    private val sourceRoot = File("src/main/java/com/tudominio/parentalcontrol")
    private val databaseSource = File(sourceRoot, "data/db/ParentalDatabase.kt")

    @Test
    fun parental_database_source_does_not_define_getInstance() {
        val content = databaseSource.readText()
        // The companion may still exist for the constants — what we forbid
        // is a function named `getInstance` in the source.
        assertTrue(
            "ParentalDatabase.kt must not declare a getInstance function",
            !Regex("""fun\s+getInstance\s*\(""").containsMatchIn(content)
        )
        // The singleton field is gone, too.
        assertTrue(
            "ParentalDatabase.kt must not declare an INSTANCE field",
            !Regex("""@Volatile\s+private\s+var\s+INSTANCE""").containsMatchIn(content)
        )
    }

    @Test
    fun parental_database_companion_exposes_database_name_constant() {
        val content = databaseSource.readText()
        assertTrue(
            "ParentalDatabase.DATABASE_NAME must remain on the companion " +
                "(referenced by DatabaseModule and the migration tests)",
            Regex("""const\s+val\s+DATABASE_NAME\s*=\s*"parental_control.db"""").containsMatchIn(content)
        )
    }

    @Test
    fun parental_database_companion_exposes_migration_4_5() {
        val content = databaseSource.readText()
        assertTrue(
            "ParentalDatabase.MIGRATION_4_5 must remain on the companion " +
                "(referenced by OutboxMigrationTest and DatabaseModule)",
            Regex("""val\s+MIGRATION_4_5\s*:""").containsMatchIn(content)
        )
    }

    @Test
    fun parental_database_companion_exposes_migration_5_6() {
        val content = databaseSource.readText()
        assertTrue(
            "ParentalDatabase.MIGRATION_5_6 must remain on the companion " +
                "(referenced by AppPolicyMigrationTest and DatabaseModule)",
            Regex("""val\s+MIGRATION_5_6\s*:""").containsMatchIn(content)
        )
    }

    @Test
    fun no_static_getInstance_call_sites_remain_in_main_source() {
        val violations = mutableListOf<String>()
        // Strip block comments from each file before scanning so docstrings
        // and KDoc references to the removed API don't trigger false positives.
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val stripped = blockComment.replace(file.readText(), "")
                stripped.lines().forEachIndexed { idx, line ->
                    if (line.trimStart().startsWith("//")) return@forEachIndexed
                    if ("ParentalDatabase.getInstance(" in line ||
                        "ParentalDatabase.INSTANCE" in line ||
                        "ParentalDatabase.Companion.getInstance" in line
                    ) {
                        violations += "${file.name}:${idx + 1}: ${line.trim()}"
                    }
                }
            }
        assertEquals(
            "No static call sites for ParentalDatabase may remain. Violations: $violations",
            emptyList<String>(),
            violations
        )
    }
}
