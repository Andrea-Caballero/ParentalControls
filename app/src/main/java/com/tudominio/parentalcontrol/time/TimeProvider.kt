package com.tudominio.parentalcontrol.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Interfaz inyectable y fakeable para proveedura de tiempo.
 * Separa la lógica de tiempo de las dependencias de Android para facilitar testing.
 *
 * JVM pura - sin dependencias android.* en la interfaz ni implementaciones fake.
 */
interface TimeProvider {
    /**
     * Hora monotónica en milisegundos (desde boot).
     * Útil para medir duraciones sin afectarse por cambios de reloj.
     */
    fun elapsedRealtime(): Long

    /**
     * Hora actual de pared en milisegundos (UTC).
     */
    fun wallTimeMillis(): Long

    /**
     * Instante actual basado en reloj de pared.
     */
    fun wallInstant(): Instant

    /**
     * Zona horaria actual del sistema.
     */
    fun currentZoneId(): ZoneId

    /**
     * Fecha actual (basada en fecha de servidor si está configurada).
     */
    fun currentDate(): LocalDate

    /**
     * Fecha y hora actual (basada en fecha de servidor si está configurada).
     */
    fun currentDateTime(): LocalDateTime

    /**
     * Indica si la fecha/hora proviene de una fuente remota (servidor).
     */
    fun isServerTimeActive(): Boolean

    /**
     * Flow que emite cuando cambia la zona horaria.
     * Implementación JVM pura compara el ZoneId actual con el anterior en cada llamada.
     */
    fun zoneChanges(): Flow<ZoneId>

    /**
     * Detecta saltos de reloj (diferencia inconsistente entre reloj monotónico y de pared).
     * Distingue entre salto de reloj (manipulación) y cambio de zona horaria.
     * @return ClockSkewSignal con el tipo de salto detectado, o null si no hay salto.
     */
    fun detectClockSkew(): ClockSkewSignal?

    /**
     * Resetea el baseline para recalibrar detección de saltos.
     */
    fun resetBaseline()

    /**
     * Tipo de señal de salto de reloj detectada.
     */
    data class ClockSkewSignal(
        val type: SignalType,
        val wallTimeBefore: Long,
        val wallTimeAfter: Long,
        val monotonicDelta: Long,
        val timestamp: Long
    ) {
        enum class SignalType {
            FORWARD_JUMP,
            BACKWARD_JUMP,
            TIMEZONE_CHANGE
        }
    }
}

/**
 * Implementación por defecto de TimeProvider que usa APIs de Android.
 */
class DefaultTimeProvider(
    private val context: Context,
    private var serverDateOverride: LocalDate? = null
) : TimeProvider {

    private var baselineMonotonic: Long = 0L
    private var baselineWall: Long = 0L
    private var baselineZone: ZoneId = ZoneId.systemDefault()
    private var lastEmittedZone: ZoneId = ZoneId.systemDefault()
    private var initialized = false

    init {
        initializeBaseline()
    }

    private fun initializeBaseline() {
        baselineMonotonic = SystemClock.elapsedRealtime()
        baselineWall = System.currentTimeMillis()
        baselineZone = ZoneId.systemDefault()
        lastEmittedZone = baselineZone
        initialized = true
    }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun wallTimeMillis(): Long = System.currentTimeMillis()

    override fun wallInstant(): Instant = Instant.ofEpochMilli(wallTimeMillis())

    override fun currentZoneId(): ZoneId = ZoneId.systemDefault()

    override fun currentDate(): LocalDate {
        return serverDateOverride ?: LocalDate.now(currentZoneId())
    }

    override fun currentDateTime(): LocalDateTime {
        val date = serverDateOverride
        if (date != null) {
            return date.atStartOfDay()
        }
        return LocalDateTime.now(currentZoneId())
    }

    override fun isServerTimeActive(): Boolean = serverDateOverride != null

    override fun zoneChanges(): Flow<ZoneId> = callbackFlow {
        trySend(lastEmittedZone)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    val newZone = ZoneId.systemDefault()
                    lastEmittedZone = newZone
                    trySend(newZone)
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
        @Suppress("UnspecifiedReceiver")
        context.registerReceiver(receiver, filter)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    override fun detectClockSkew(): TimeProvider.ClockSkewSignal? {
        if (!initialized) {
            initializeBaseline()
            return null
        }

        val currentMonotonic = SystemClock.elapsedRealtime()
        val currentWall = System.currentTimeMillis()
        val currentZone = ZoneId.systemDefault()

        val expectedWall = baselineWall + (currentMonotonic - baselineMonotonic)
        val difference = currentWall - expectedWall

        val jumpThreshold = 30_000L

        if (kotlin.math.abs(difference) <= jumpThreshold) {
            if (currentZone != baselineZone) {
                val signal = TimeProvider.ClockSkewSignal(
                    type = TimeProvider.ClockSkewSignal.SignalType.TIMEZONE_CHANGE,
                    wallTimeBefore = baselineWall,
                    wallTimeAfter = currentWall,
                    monotonicDelta = currentMonotonic - baselineMonotonic,
                    timestamp = System.currentTimeMillis()
                )
                baselineZone = currentZone
                return signal
            }
            return null
        }

        val expectedInstant = baselineWall + (currentMonotonic - baselineMonotonic)
        val instantDrift = kotlin.math.abs(currentWall - expectedInstant)

        return if (instantDrift <= jumpThreshold && currentZone != baselineZone) {
            TimeProvider.ClockSkewSignal(
                type = TimeProvider.ClockSkewSignal.SignalType.TIMEZONE_CHANGE,
                wallTimeBefore = baselineWall,
                wallTimeAfter = currentWall,
                monotonicDelta = currentMonotonic - baselineMonotonic,
                timestamp = System.currentTimeMillis()
            )
        } else if (difference > 0) {
            TimeProvider.ClockSkewSignal(
                type = TimeProvider.ClockSkewSignal.SignalType.FORWARD_JUMP,
                wallTimeBefore = baselineWall,
                wallTimeAfter = currentWall,
                monotonicDelta = currentMonotonic - baselineMonotonic,
                timestamp = System.currentTimeMillis()
            )
        } else {
            TimeProvider.ClockSkewSignal(
                type = TimeProvider.ClockSkewSignal.SignalType.BACKWARD_JUMP,
                wallTimeBefore = baselineWall,
                wallTimeAfter = currentWall,
                monotonicDelta = currentMonotonic - baselineMonotonic,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun setServerDate(serverDate: LocalDate?) {
        this.serverDateOverride = serverDate
    }

    override fun resetBaseline() {
        initializeBaseline()
    }
}

/**
 * Implementación fake para testing JVM.
 * Implementa detección de saltos de reloj para permitir testing sin Android.
 */
class FakeTimeProvider(
    private var fakeElapsed: Long = 0L,
    private var fakeWallMillis: Long = 0L,
    private var fakeZone: ZoneId = ZoneId.of("UTC"),
    private var fakeServerDate: LocalDate? = null
) : TimeProvider {

    private var baselineMonotonic: Long = 0L
    private var baselineWall: Long = 0L
    private var baselineZone: ZoneId = fakeZone
    private var lastEmittedZone: ZoneId = fakeZone
    private var initialized = false

    init {
        initializeBaseline()
    }

    private fun initializeBaseline() {
        baselineMonotonic = fakeElapsed
        baselineWall = fakeWallMillis
        baselineZone = fakeZone
        lastEmittedZone = fakeZone
        initialized = true
    }

    override fun elapsedRealtime(): Long = fakeElapsed

    override fun wallTimeMillis(): Long = fakeWallMillis

    override fun wallInstant(): Instant = Instant.ofEpochMilli(fakeWallMillis)

    override fun currentZoneId(): ZoneId = fakeZone

    override fun currentDate(): LocalDate {
        return fakeServerDate ?: LocalDate.ofInstant(
            Instant.ofEpochMilli(fakeWallMillis),
            fakeZone
        )
    }

    override fun currentDateTime(): LocalDateTime {
        val date = fakeServerDate
        return if (date != null) {
            date.atStartOfDay()
        } else {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(fakeWallMillis), fakeZone)
        }
    }

    override fun isServerTimeActive(): Boolean = fakeServerDate != null

    override fun zoneChanges(): Flow<ZoneId> = callbackFlow {
        trySend(lastEmittedZone)
        awaitClose { }
    }

    override fun detectClockSkew(): TimeProvider.ClockSkewSignal? {
        if (!initialized) {
            initializeBaseline()
            return null
        }

        val expectedWall = baselineWall + (fakeElapsed - baselineMonotonic)
        val difference = fakeWallMillis - expectedWall

        val jumpThreshold = 30_000L

        if (kotlin.math.abs(difference) <= jumpThreshold) {
            if (fakeZone != baselineZone) {
                val signal = TimeProvider.ClockSkewSignal(
                    type = TimeProvider.ClockSkewSignal.SignalType.TIMEZONE_CHANGE,
                    wallTimeBefore = baselineWall,
                    wallTimeAfter = fakeWallMillis,
                    monotonicDelta = fakeElapsed - baselineMonotonic,
                    timestamp = 0L
                )
                baselineZone = fakeZone
                return signal
            }
            return null
        }

        val expectedInstant = baselineWall + (fakeElapsed - baselineMonotonic)
        val instantDrift = kotlin.math.abs(fakeWallMillis - expectedInstant)

        return if (instantDrift <= jumpThreshold && fakeZone != baselineZone) {
            TimeProvider.ClockSkewSignal(
                type = TimeProvider.ClockSkewSignal.SignalType.TIMEZONE_CHANGE,
                wallTimeBefore = baselineWall,
                wallTimeAfter = fakeWallMillis,
                monotonicDelta = fakeElapsed - baselineMonotonic,
                timestamp = 0L
            )
        } else if (difference > 0) {
            TimeProvider.ClockSkewSignal(
                type = TimeProvider.ClockSkewSignal.SignalType.FORWARD_JUMP,
                wallTimeBefore = baselineWall,
                wallTimeAfter = fakeWallMillis,
                monotonicDelta = fakeElapsed - baselineMonotonic,
                timestamp = 0L
            )
        } else {
            TimeProvider.ClockSkewSignal(
                type = TimeProvider.ClockSkewSignal.SignalType.BACKWARD_JUMP,
                wallTimeBefore = baselineWall,
                wallTimeAfter = fakeWallMillis,
                monotonicDelta = fakeElapsed - baselineMonotonic,
                timestamp = 0L
            )
        }
    }

    override fun resetBaseline() {
        initializeBaseline()
    }

    fun advanceTime(millis: Long) {
        fakeElapsed += millis
        fakeWallMillis += millis
    }

    fun setTime(millis: Long) {
        fakeWallMillis = millis
    }

    fun setZone(zone: ZoneId) {
        fakeZone = zone
    }

    fun setServerDate(date: LocalDate?) {
        fakeServerDate = date
    }

    fun simulateForwardJump(jumpMillis: Long) {
        baselineWall = fakeWallMillis
        baselineMonotonic = fakeElapsed
        fakeWallMillis += jumpMillis
    }

    fun simulateBackwardJump(jumpMillis: Long) {
        baselineWall = fakeWallMillis
        baselineMonotonic = fakeElapsed
        fakeWallMillis -= jumpMillis
    }

    fun simulateTimezoneChange(newZone: ZoneId) {
        baselineZone = fakeZone
        fakeZone = newZone
    }
}
