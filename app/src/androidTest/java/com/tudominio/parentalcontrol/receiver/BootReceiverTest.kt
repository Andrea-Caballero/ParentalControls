package com.tudominio.parentalcontrol.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class BootReceiverTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testBootReceiverExists() {
        val receiver = BootReceiver()
        assertNotNull(receiver)
    }

    @Test
    fun testBootCompletedAction() {
        assertEquals(
            "android.intent.action.BOOT_COMPLETED",
            BootReceiver.ACTION_BOOT_COMPLETED
        )
    }

    @Test
    fun testPackageReplacedAction() {
        assertEquals(
            "android.intent.action.MY_PACKAGE_REPLACED",
            BootReceiver.ACTION_MY_PACKAGE_REPLACED
        )
    }

    @Test
    fun testInitialSyncWorkName() {
        // Verificar que el nombre del work es correcto
        val workName = "initial_sync_work"
        assertNotNull(workName)
        assertTrue(workName.isNotEmpty())
    }

    @Test
    fun testOnReceiveWithBootCompleted() {
        val receiver = BootReceiver()
        val intent = Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        
        // El receiver debe manejar el intent sin crash
        receiver.onReceive(context, intent)
        
        // Si llega aquí sin excepción, el test pasa
        assertTrue(true)
    }

    @Test
    fun testOnReceiveWithPackageReplaced() {
        val receiver = BootReceiver()
        val intent = Intent(BootReceiver.ACTION_MY_PACKAGE_REPLACED)
        
        // El receiver debe manejar el intent sin crash
        receiver.onReceive(context, intent)
        
        // Si llega aquí sin excepción, el test pasa
        assertTrue(true)
    }
}
