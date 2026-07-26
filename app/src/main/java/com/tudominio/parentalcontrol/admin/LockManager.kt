package com.tudominio.parentalcontrol.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Administrador de bloqueo de dispositivo.
 * Expone lockNow() para bloqueo inmediato del dispositivo.
 */
class LockManager(private val context: Context) {

    companion object {
        private const val ADMIN_PKG = "com.tudominio.parentalcontrol"
        private const val ADMIN_CLASS = "com.tudominio.parentalcontrol.admin.ParentalDeviceAdminReceiver"
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    val adminComponent: ComponentName by lazy {
        ComponentName(ADMIN_PKG, ADMIN_CLASS)
    }

    /**
     * Verifica si el Device Admin está activo.
     */
    fun isAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    /**
     * Obtiene el intent para activar el Device Admin.
     */
    fun getEnableAdminIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Activa el control parental para permitir bloquear el dispositivo remotamente."
            )
        }
    }

    /**
     * Bloquea inmediatamente el dispositivo.
     * Requiere que el Device Admin esté activo.
     * 
     * @return true si el bloqueo fue exitoso, false si no hay permisos.
     */
    fun lockNow(): Boolean {
        if (!isAdminActive()) {
            return false
        }

        return try {
            devicePolicyManager.lockNow()
            true
        } catch (e: SecurityException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
    }

    /**
     * Verifica si el dispositivo permite lock task.
     */
    fun isLockTaskPermitted(): Boolean {
        return try {
            devicePolicyManager.isLockTaskPermitted(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtiene el estado del Lock Task (kiosk mode).
     */
    fun isLockTaskActive(): Boolean {
        return try {
            devicePolicyManager.isLockTaskPermitted(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Solicita iniciar Lock Task Mode (requiere FLAG_KEEP_SCREEN_ON en activity).
     * Usar solo para casos de bloqueo total de dispositivo.
     */
    fun startLockTask() {
        // El activity llamante debe llamar startLockTask()
    }

    /**
     * Detiene Lock Task Mode.
     */
    fun stopLockTask() {
        // El activity llamante debe llamar stopLockTask()
    }

    /**
     * Verifica si el usuario es un admin de dispositivo.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            devicePolicyManager.isDeviceOwnerApp(ADMIN_PKG)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtiene información del usuario actual.
     */
    fun getUserHandle(): android.os.UserHandle {
        return android.os.Process.myUserHandle()
    }
}
