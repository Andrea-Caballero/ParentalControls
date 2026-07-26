package com.tudominio.parentalcontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairedDevicesStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "paired_devices"
        private const val KEY_DEVICES = "devices"
    }
    
    private val _devices = MutableStateFlow<List<ChildDevice>>(loadDevices())
    val devices: StateFlow<List<ChildDevice>> = _devices.asStateFlow()
    
    fun addPairedDevice(deviceId: String, deviceName: String, parentId: String) {
        val currentDevices = _devices.value.toMutableList()
        
        // Verificar si ya existe
        if (currentDevices.any { it.id == deviceId }) return
        
        val newDevice = ChildDevice(
            id = deviceId,
            name = deviceName.ifBlank { "Dispositivo de $parentId" },
            model = android.os.Build.MODEL,
            appVersion = "1.0.0",
            policyVersion = 1,
            state = DeviceState.ACTIVE,
            lastSeenAt = java.time.Instant.now().toString(),
            isOnline = true
        )
        
        currentDevices.add(newDevice)
        _devices.value = currentDevices
        saveDevices(currentDevices)
    }
    
    fun removeDevice(deviceId: String) {
        val currentDevices = _devices.value.toMutableList()
        currentDevices.removeAll { it.id == deviceId }
        _devices.value = currentDevices
        saveDevices(currentDevices)
    }
    
    fun getDevice(deviceId: String): ChildDevice? {
        return _devices.value.find { it.id == deviceId }
    }
    
    private fun loadDevices(): List<ChildDevice> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ChildDevice(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    model = obj.optString("model", ""),
                    appVersion = obj.optString("appVersion", "1.0.0"),
                    policyVersion = obj.optLong("policyVersion", 1).toInt(),
                    state = DeviceState.valueOf(obj.optString("state", "ACTIVE")),
                    lastSeenAt = obj.optString("lastSeenAt", ""),
                    isOnline = obj.optBoolean("isOnline", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveDevices(devices: List<ChildDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            val obj = JSONObject().apply {
                put("id", device.id)
                put("name", device.name)
                put("model", device.model)
                put("appVersion", device.appVersion)
                put("policyVersion", device.policyVersion)
                put("state", device.state.name)
                put("lastSeenAt", device.lastSeenAt)
                put("isOnline", device.isOnline)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }
}
