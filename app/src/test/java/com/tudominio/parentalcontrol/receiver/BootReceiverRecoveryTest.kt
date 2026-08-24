package com.tudominio.parentalcontrol.receiver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverRecoveryTest {
    @Test
    fun `boot delegates network auth recovery to WorkManager`() {
        val source = File("src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt").readText()
        assertTrue(source.contains("WorkScheduler.scheduleAuthRestore(context)"))
        assertFalse(source.contains("authenticateOrCreate()"))
        assertFalse(source.contains("goAsync()"))
        val worker = File("src/main/java/com/tudominio/parentalcontrol/workers/Workers.kt").readText()
        assertTrue(worker.contains("catch (cancellation: CancellationException)"))
        assertTrue(worker.contains("Auth restore worker failed; retry requested"))
        assertTrue(worker.contains("is AuthResult.NeedsPairing -> Result.failure()"))
    }
}
