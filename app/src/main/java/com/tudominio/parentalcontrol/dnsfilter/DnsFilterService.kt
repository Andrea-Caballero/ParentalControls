package com.tudominio.parentalcontrol.dnsfilter

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tudominio.parentalcontrol.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Servicio VPN local para filtrado DNS.
 * 
 * T35: Filtra dominios prohibidos vía VPN local.
 * 
 * Características:
 * - Túnel VPN local (no conecta a servidor externo)
 * - Filtra consultas DNS a dominios bloqueados
 * - Advertir al padre sobre límite de VPN única
 * 
 * §0.6: No monetizar tráfico, solo filtrado local
 */
class DnsFilterService : VpnService() {

    companion object {
        private const val TAG = "DnsFilterService"
        
        const val ACTION_START = "com.tudominio.parentalcontrol.START_DNS_FILTER"
        const val ACTION_STOP = "com.tudominio.parentalcontrol.STOP_DNS_FILTER"
        
        // DNS upstream público (sin logging)
        private const val DNS_UPSTREAM = "8.8.8.8" // Google DNS (ejemplo)
        private const val DNS_PORT = 53
        
        // Direcciones VPN
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "10.0.0.1"
        
        // Tamaño del buffer
        private const val PACKET_SIZE = 4096
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var vpnThread: Thread? = null
    
    // Dominios bloqueados (desde la política)
    private val blockedDomains = mutableSetOf<String>()

    /**
     * Inicia el servicio VPN.
     */
    fun startVpn(blockedDomains: Set<String>) {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return
        }
        
        this.blockedDomains.clear()
        this.blockedDomains.addAll(blockedDomains.map { it.lowercase() })
        
        try {
            configureVpn()
            isRunning = true
            
            vpnThread = Thread {
                runVpnLoop()
            }.apply {
                name = "DnsFilterThread"
                start()
            }
            
            Log.d(TAG, "DNS filter VPN started with ${blockedDomains.size} blocked domains")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}")
            stopVpn()
        }
    }

    /**
     * Detiene el servicio VPN.
     */
    fun stopVpn() {
        isRunning = false
        
        vpnThread?.interrupt()
        vpnThread = null
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }
        
        Log.d(TAG, "DNS filter VPN stopped")
    }

    /**
     * Configura la interfaz VPN.
     */
    private fun configureVpn() {
        val builder = Builder()
            .setSession("DNS Filter")
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, 0)
            .addDnsServer(VPN_DNS)
            .setMtu(PACKET_SIZE)
            .setBlocking(true)
        
        // Permitir que la app propia atraviese el túnel
        builder.addDisallowedApplication(packageName)
        
        // Intent para cuando se desconecte
        val disconnectIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP
        }
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        
        vpnInterface = builder.establish()
    }

    /**
     * Loop principal del VPN.
     */
    private fun runVpnLoop() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        
        val packet = DatagramPacket(ByteArray(PACKET_SIZE), PACKET_SIZE)
        val socket = DatagramSocket()
        
        try {
            socket.soTimeout = 1000
        } catch (e: SocketException) {
            Log.e(TAG, "Failed to set socket timeout: ${e.message}")
        }
        
        while (isRunning) {
            try {
                // Leer del túnel VPN
                inputStream.read(packet.data)
                
                // Procesar paquete DNS
                val dnsResponse = processDnsPacket(packet.data, packet.length)
                
                if (dnsResponse != null) {
                    // Escribir respuesta al túnel
                    outputStream.write(dnsResponse)
                    outputStream.flush()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.v(TAG, "VPN loop exception: ${e.message}")
                }
            }
        }
        
        socket.close()
    }

    /**
     * Procesa un paquete DNS.
     */
    private fun processDnsPacket(data: ByteArray, length: Int): ByteArray? {
        try {
            // Verificar que es una consulta DNS (QR bit = 0)
            if (length < 12 || (data[2].toInt() and 0x80) != 0) {
                // No es una consulta DNS o es una respuesta, reenviar tal cual
                return forwardDns(data, length)
            }
            
            // Extraer el dominio de la consulta
            val domain = extractDomainFromDns(data, length)
            
            if (domain == null) {
                return forwardDns(data, length)
            }
            
            // Verificar si está bloqueado
            val isBlocked = isDomainBlocked(domain)
            
            if (isBlocked) {
                Log.d(TAG, "Blocked domain: $domain")
                return createBlockedDnsResponse(data)
            }
            
            // Reenviar al upstream
            return forwardDns(data, length)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing DNS packet: ${e.message}")
            return forwardDns(data, length)
        }
    }

    /**
     * Extrae el dominio de una consulta DNS.
     */
    private fun extractDomainFromDns(data: ByteArray, length: Int): String? {
        try {
            // Saltar header (12 bytes)
            var offset = 12
            
            // Parsear nombre de dominio
            val parts = mutableListOf<String>()
            
            while (offset < length) {
                val labelLength = data[offset].toInt() and 0xFF
                
                if (labelLength == 0) {
                    break
                }
                
                // Verificar si es un puntero (compresión DNS)
                if ((labelLength and 0xC0) == 0xC0) {
                    // Es un puntero, seguirlo
                    offset = ((labelLength and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    break
                }
                
                offset++
                
                if (offset + labelLength > length) {
                    return null
                }
                
                val label = String(data, offset, labelLength)
                parts.add(label)
                offset += labelLength
            }
            
            return if (parts.isNotEmpty()) {
                parts.joinToString(".")
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Verifica si un dominio está bloqueado.
     */
    private fun isDomainBlocked(domain: String): Boolean {
        val domainLower = domain.lowercase()
        
        // Verificar match exacto
        if (blockedDomains.contains(domainLower)) {
            return true
        }
        
        // Verificar match de subdominio
        for (blocked in blockedDomains) {
            if (domainLower.endsWith(".$blocked") || domainLower == blocked) {
                return true
            }
        }
        
        return false
    }

    /**
     * Reenvía una consulta DNS al upstream.
     */
    private fun forwardDns(data: ByteArray, length: Int): ByteArray? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            
            val upstream = InetAddress.getByName(DNS_UPSTREAM)
            val packet = DatagramPacket(data, length, upstream, DNS_PORT)
            
            socket.send(packet)
            
            val response = ByteArray(PACKET_SIZE)
            val responsePacket = DatagramPacket(response, response.size)
            
            socket.receive(responsePacket)
            socket.close()
            
            response.copyOf(responsePacket.length)
        } catch (e: Exception) {
            Log.e(TAG, "DNS forward failed: ${e.message}")
            null
        }
    }

    /**
     * Crea una respuesta DNS bloqueada (NXDOMAIN).
     */
    private fun createBlockedDnsResponse(request: ByteArray): ByteArray {
        val response = request.copyOf()
        
        // Set QR bit (response)
        response[2] = (response[2].toInt() or 0x80).toByte()
        
        // Set RA bit (recursion available)
        response[3] = (response[3].toInt() or 0x80).toByte()
        
        // Set RCODE to NXDOMAIN (3)
        response[3] = (response[3].toInt() and 0xF0 or 0x03).toByte()
        
        // Eliminar registros de pregunta
        response[7] = 0
        
        // Zero Answer/Authority/Additional sections
        response[9] = 0
        
        return response
    }

    /**
     * Actualiza la lista de dominios bloqueados.
     */
    fun updateBlockedDomains(domains: Set<String>) {
        this.blockedDomains.clear()
        this.blockedDomains.addAll(domains.map { it.lowercase() })
        Log.d(TAG, "Updated blocked domains: ${domains.size} domains")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}

/**
 * Estados del servicio DNS filter.
 */
enum class DnsFilterState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Resultado del filtro DNS.
 */
data class DnsFilterResult(
    val domain: String,
    val blocked: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
