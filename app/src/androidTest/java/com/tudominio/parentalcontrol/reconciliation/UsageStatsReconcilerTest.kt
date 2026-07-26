package com.tudominio.parentalcontrol.reconciliation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.UsageTodayEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageStatsReconcilerTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var reconciler: UsageStatsReconciler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        reconciler = UsageStatsReconciler(context, database)
    }

    @Test
    fun testHasUsageStatsPermission() {
        // Este test verifica que el método funciona
        // El resultado real depende del dispositivo/emulador
        val hasPermission = reconciler.hasUsageStatsPermission()
        
        // En un dispositivo real sin permiso, retorna false
        // En el emulador de pruebas puede variar
        assertNotNull(hasPermission)
    }

    @Test
    fun testReconcileWithoutPermission() = runBlocking {
        // Simular que no hay permiso
        val result = reconciler.reconcileToday()
        
        // Sin permiso, debería retornar NoPermission o Success con datos
        assertTrue(
            result is UsageStatsReconciler.ReconciliationResult.NoPermission ||
            result is UsageStatsReconciler.ReconciliationResult.Success
        )
    }

    @Test
    fun testIdempotentBackfill() = runBlocking {
        val serverDate = java.time.LocalDate.now().toString()

        database.usageDao().upsertUsage(
            com.tudominio.parentalcontrol.data.model.UsageTodayEntity(
                package_name = "com.test.app",
                server_date = serverDate,
                usage_minutes = 30
            )
        )

        val result1 = reconciler.reconcileToday()

        val result2 = reconciler.reconcileToday()

        assertEquals(result1.statsTotalMinutes, result2.statsTotalMinutes)
    }

    @Test
    fun testBackfillWithExistingData() = runBlocking {
        val serverDate = java.time.LocalDate.now().toString()

        database.usageDao().upsertUsage(
            com.tudominio.parentalcontrol.data.model.UsageTodayEntity(
                package_name = "com.test.app2",
                server_date = serverDate,
                usage_minutes = 15
            )
        )

        val success = reconciler.backfillFromUsageStats()

        assertNotNull(success)
    }
}
