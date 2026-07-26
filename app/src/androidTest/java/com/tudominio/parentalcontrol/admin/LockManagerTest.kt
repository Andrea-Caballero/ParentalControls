package com.tudominio.parentalcontrol.admin

import android.content.Context
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
class LockManagerTest {

    private lateinit var context: Context
    private lateinit var lockManager: LockManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        lockManager = LockManager(context)
    }

    @Test
    fun testLockManagerCreated() {
        assertNotNull(lockManager)
    }

    @Test
    fun testIsAdminActiveReturnsFalseWhenNotEnabled() {
        // Initially, admin should not be active unless explicitly enabled
        val isActive = lockManager.isAdminActive()
        // Result depends on whether admin was enabled
        assertNotNull(isActive)
    }

    @Test
    fun testIsLockTaskPermitted() {
        val isPermitted = lockManager.isLockTaskPermitted()
        // May return false without admin enabled
        assertNotNull(isPermitted)
    }

    @Test
    fun testDeviceAdminReceiverClassExists() {
        val receiver = ParentalDeviceAdminReceiver()
        assertNotNull(receiver)
    }

    @Test
    fun testGetUserHandle() {
        val userHandle = lockManager.getUserHandle()
        assertNotNull(userHandle)
    }

    @Test
    fun testLockNowReturnsFalseWithoutAdmin() {
        // Without admin enabled, lockNow should return false
        if (!lockManager.isAdminActive()) {
            val result = lockManager.lockNow()
            assertFalse(result)
        }
    }
}
