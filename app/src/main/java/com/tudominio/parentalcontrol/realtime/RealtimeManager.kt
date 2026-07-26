package com.tudominio.parentalcontrol.realtime

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Manager de Realtime que se suscribe a cambios solo cuando la app está en foreground.
 * 
 * §0.1: Realtime es para UI, no para control en background.
 * - El control va por FCM (T19)
 * - Realtime solo refresca la UI cuando es visible
 */
class RealtimeManager private constructor(
    private val context: Context
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "RealtimeManager"
        
        @Volatile
        private var instance: RealtimeManager? = null

        fun getInstance(context: Context): RealtimeManager {
            return instance ?: synchronized(this) {
                instance ?: RealtimeManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val authManager = DeviceAuthManager.getInstance(context)
    private val clientProvider = SupabaseClientProvider.getInstance(context)
    
    // WebSocket
    private var webSocketJob: Job? = null
    private var httpClient: HttpClient? = null
    
    // Estado
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()
    
    // Eventos de cambios
    private val _policyChange = MutableSharedFlow<PolicyChangeEvent>(replay = 1)
    val policyChange: SharedFlow<PolicyChangeEvent> = _policyChange.asSharedFlow()
    
    private val _grantChange = MutableSharedFlow<GrantChangeEvent>(replay = 1)
    val grantChange: SharedFlow<GrantChangeEvent> = _grantChange.asSharedFlow()
    
    private val _requestChange = MutableSharedFlow<RequestChangeEvent>(replay = 1)
    val requestChange: SharedFlow<RequestChangeEvent> = _requestChange.asSharedFlow()
    
    // Lifecyle observer
    private var lifecycleOwner: LifecycleOwner? = null

    init {
        // Registrar como observer del lifecycle global
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Conecta al canal de Realtime.
     * Se llama automáticamente cuando la app pasa a foreground.
     */
    fun connect() {
        if (_isConnected.value) {
            Log.d(TAG, "Ya conectado")
            return
        }
        
        _connectionState.value = RealtimeConnectionState.CONNECTING
        
        webSocketJob = scope.launch {
            try {
                connectWebSocket()
            } catch (e: Exception) {
                Log.e(TAG, "Error conectando: ${e.message}")
                _connectionState.value = RealtimeConnectionState.ERROR
                _isConnected.value = false
            }
        }
    }

    /**
     * Desconecta del canal de Realtime.
     * Se llama automáticamente cuando la app pasa a background.
     */
    fun disconnect() {
        Log.d(TAG, "Desconectando Realtime (background)")
        
        webSocketJob?.cancel()
        webSocketJob = null
        
        httpClient?.close()
        httpClient = null
        
        _isConnected.value = false
        _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    /**
     * Conecta al WebSocket de Supabase Realtime.
     */
    private suspend fun connectWebSocket() {
        val accessToken = authManager.getAccessToken()
            ?: throw IllegalStateException("No access token")
        
        val supabaseUrl = SupabaseClientProvider.SUPABASE_URL
            .replace("https://", "")
            .replace("http://", "")
        
        // Crear cliente HTTP con WebSocket
        httpClient = HttpClient(OkHttp) {
            install(WebSockets) {
                pingInterval = 30_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        
        // URL con parámetros de autenticación
        val wsUrl = "wss://$supabaseUrl/realtime/v1/websocket?api_key=${SupabaseClientProvider.SUPABASE_ANON_KEY}&Authorization=Bearer%20$accessToken"
        
        try {
            httpClient?.webSocket(wsUrl) {
                _isConnected.value = true
                _connectionState.value = RealtimeConnectionState.CONNECTED
                Log.d(TAG, "Conectado a Realtime")
                
                // Suscribirse a canales relevantes
                sendSubscription("device:${authManager.deviceId.value}:policy", "1")
                sendSubscription("device:${authManager.deviceId.value}:grants", "2")
                sendSubscription("device:${authManager.deviceId.value}:requests", "3")
                
                // Escuchar mensajes
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> handleMessage(frame.readText())
                        is Frame.Close -> {
                            Log.d(TAG, "WebSocket cerrado")
                            break
                        }
                        else -> { /* Ignorar otros frames */ }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en WebSocket: ${e.message}")
        }
        
        _isConnected.value = false
        _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }
    
    /**
     * Envía mensaje de suscripción a un canal.
     */
    private suspend fun DefaultClientWebSocketSession.sendSubscription(topic: String, ref: String) {
        val message = RealtimeMessage(
            type = "phx_join",
            topic = topic,
            event = "phx_join",
            payload = emptyMap(),
            ref = ref
        )
        val jsonString = """{"type":"${message.type}","topic":"${message.topic}","event":"${message.event}","ref":"${message.ref}"}"""
        send(Frame.Text(jsonString))
    }



    /**
     * Maneja mensajes entrantes del WebSocket.
     *
     * Marked `internal` so `RealtimeManagerTest` can drive the parser
     * directly without spinning up a real WS/HTTP stack.
     */
    internal suspend fun handleMessage(message: String) {
        try {
            val json = Json.parseToJsonElement(message).jsonObject

            when (json["event"]?.jsonPrimitive?.content) {
                "policy_updated" -> {
                    val payload = json["payload"]?.jsonObject
                    val version = payload?.get("version")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    _policyChange.emit(PolicyChangeEvent(version))
                }
                "grant_created", "grant_revoked" -> {
                    val payload = json["payload"]?.jsonObject
                    val grantId = payload?.get("grant_id")?.jsonPrimitive?.content
                    val event = if (json["event"]?.jsonPrimitive?.content == "grant_created") {
                        GrantChangeEvent.Type.CREATED
                    } else {
                        GrantChangeEvent.Type.REVOKED
                    }
                    _grantChange.emit(GrantChangeEvent(event, grantId))
                }
                "request_created", "request_approved", "request_denied" -> {
                    val payload = json["payload"]?.jsonObject
                    val requestId = payload?.get("request_id")?.jsonPrimitive?.content
                    // Parent-fix — a `request_created` event fires the
                    // moment a child POSTs `/rest/v1/time_requests`,
                    // matching the event name pushed by both production
                    // Supabase Realtime and `tools/shared-mock-server`.
                    val event = when (json["event"]?.jsonPrimitive?.content) {
                        "request_created" -> RequestChangeEvent.Type.CREATED
                        "request_approved" -> RequestChangeEvent.Type.APPROVED
                        "request_denied" -> RequestChangeEvent.Type.DENIED
                        else -> RequestChangeEvent.Type.UPDATED
                    }
                    _requestChange.emit(RequestChangeEvent(event, requestId))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando mensaje: ${e.message}")
        }
    }

    // ============ LifecycleObserver ============

    override fun onStart(owner: LifecycleOwner) {
        lifecycleOwner = owner
        Log.d(TAG, "Foreground detectado, conectando Realtime")
        connect()
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "Background detectado, desconectando Realtime")
        disconnect()
        lifecycleOwner = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        disconnect()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
}

// ============ Tipos de datos ============

enum class RealtimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

@Serializable
data class RealtimeMessage(
    val type: String,
    val topic: String,
    val event: String,
    val payload: Map<String, String> = emptyMap(),
    val ref: String? = null
)

data class PolicyChangeEvent(
    val newVersion: Long
)

data class GrantChangeEvent(
    val type: Type,
    val grantId: String?
) {
    enum class Type {
        CREATED,
        REVOKED
    }
}

data class RequestChangeEvent(
    val type: Type,
    val requestId: String?
) {
    enum class Type {
        APPROVED,
        DENIED,
        UPDATED,
        // Parent-fix — new realtime push fired by both production
        // Supabase Realtime and the shared mock when a child posts
        // `/rest/v1/time_requests`. The parent's dashboard uses this to
        // surface the Solicitudes chip within seconds (was: up to 5
        // min via SolicitudesPollingWorker).
        CREATED
    }
}
