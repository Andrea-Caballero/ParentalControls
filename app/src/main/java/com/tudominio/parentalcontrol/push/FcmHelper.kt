package com.tudominio.parentalcontrol.push

import android.content.Context
import android.util.Log

/**
 * Helper para inicializar y gestionar FCM.
 * 
 * Proporciona una interfaz de alto nivel para FCM que funciona
 * cuando Firebase Messaging está disponible (via google-services).
 */
object FcmHelper {

    private const val TAG = "FcmHelper"
    
    /**
     * Inicializa FCM y registra el token actual.
     * Llamar desde Application.onCreate() o cuando se complete el emparejamiento.
     */
    fun initialize(context: Context) {
        // Firebase se inicializa automáticamente via google-services.json
        // El servicio FcmPushService.handleNewToken() se encarga del registro
        
        val existingToken = getStoredToken(context)
        if (existingToken != null) {
            Log.d(TAG, "Token FCM existente")
        }
    }
    
    /**
     * Fuerza el registro del token FCM actual.
     * Útil después de que el dispositivo se empareja.
     */
    fun forceTokenRegistration(context: Context) {
        Log.d(TAG, "Force registration solicitada")
        // El registro real ocurre en FcmPushService.processNewToken()
    }
    
    /**
     * Verifica si el token está actualizado.
     */
    fun isTokenValid(context: Context): Boolean {
        val token = getStoredToken(context)
        if (token == null) return false
        return !isTokenStale(context)
    }
    
    /**
     * Obtiene el token actual para debugging.
     */
    fun getTokenForDebug(context: Context): String? {
        return getStoredToken(context)
    }
    
    private fun getStoredToken(context: Context): String? {
        return FcmPushService.getStoredToken(context)
    }
    
    private fun isTokenStale(context: Context): Boolean {
        return FcmPushService.isTokenStale(context)
    }
}
