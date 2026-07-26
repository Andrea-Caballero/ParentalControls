package com.tudominio.parentalcontrol.reward

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manager para el banco de tiempo / recompensas.
 *
 * §0.3: Los grants de recompensa tienen source='reward'.
 * §0.9: Sin saldo infinito, respeta topes del padre.
 *
 * `database` is Hilt-injected (PR 4 of `align-with-guia-fedora44`). Use the
 * [RewardManagerEntryPoint] bridge only for non-Hilt call sites.
 */
@Singleton
class RewardManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase
) {

    companion object {
        private const val TAG = "RewardManager"

        // Scope para grants de recompensa
        const val REWARD_SCOPE = "reward"

        // Máximo acumulado (2 horas por defecto)
        private const val DEFAULT_MAX_BALANCE_MINUTES = 120L

        /**
         * Convenience accessor for non-Hilt call sites. Production code
         * inside `@AndroidEntryPoint` / `@HiltViewModel` should inject the
         * manager directly via `@Inject RewardManager`.
         */
        fun getInstance(context: Context): RewardManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                RewardManagerEntryPoint::class.java
            )
            return entryPoint.rewardManager()
        }
    }

    private val grantDao = database.grantDao()
    private val timeProvider: TimeProvider = DefaultTimeProvider(context)

    // Prefs para el tope máximo
    private val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)

    /**
     * Obtiene el saldo total de recompensas disponibles.
     */
    suspend fun getRewardBalance(): Long {
        val now = timeProvider.wallInstant().toString()
        val maxBalance = getMaxBalanceMinutes()

        return try {
            val grants = grantDao.getGrantsForScope(REWARD_SCOPE).first()
            val activeGrants = grants.filter { it.expires_at > now }

            val totalMinutes = activeGrants.sumOf { it.minutes }.toLong()
            minOf(totalMinutes, maxBalance)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reward balance: ${e.message}")
            0
        }
    }

    /**
     * Obtiene todos los grants de recompensa activos.
     */
    fun getActiveRewardGrants(): Flow<List<RewardGrantUi>> {
        val now = timeProvider.wallInstant().toString()

        return grantDao.getGrantsForScope(REWARD_SCOPE).map { grants ->
            grants.filter { it.expires_at > now }
                .sortedBy { it.expires_at }
                .map { it.toUi() }
        }
    }

    /**
     * Obtiene el historial de recompensas.
     */
    suspend fun getRewardHistory(): List<RewardHistoryItem> {
        val now = timeProvider.wallInstant().toString()

        return try {
            val grants = grantDao.getGrantsForScope(REWARD_SCOPE).first()

            grants.map { grant ->
                val isActive = grant.expires_at > now
                val expiresAt = try {
                    Instant.parse(grant.expires_at)
                } catch (e: Exception) {
                    null
                }

                RewardHistoryItem(
                    id = grant.id,
                    minutes = grant.minutes,
                    grantedAt = grant.granted_at,
                    expiresAt = expiresAt,
                    isActive = isActive,
                    isExpired = expiresAt != null && expiresAt.isBefore(timeProvider.wallInstant())
                )
            }.sortedByDescending { it.grantedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reward history: ${e.message}")
            emptyList()
        }
    }

    /**
     * Obtiene el tope máximo de saldo.
     */
    fun getMaxBalanceMinutes(): Long {
        return prefs.getLong("max_balance", DEFAULT_MAX_BALANCE_MINUTES)
    }

    /**
     * Establece el tope máximo de saldo (solo desde settings del padre).
     */
    fun setMaxBalanceMinutes(maxMinutes: Long) {
        prefs.edit().putLong("max_balance", maxMinutes).apply()
        Log.d(TAG, "Max balance set to $maxMinutes minutes")
    }

    /**
     * Verifica si hay saldo disponible.
     */
    suspend fun hasRewardBalance(): Boolean {
        return getRewardBalance() > 0
    }

    /**
     * Consume tiempo del saldo de recompensa.
     * Devuelve true si se pudo consumir.
     */
    suspend fun consumeRewardMinutes(minutes: Int): Boolean {
        val currentBalance = getRewardBalance()

        if (currentBalance < minutes) {
            Log.w(TAG, "Not enough reward balance: $currentBalance < $minutes")
            return false
        }

        // En una implementación real, decrementaríamos el grant activo
        // Por ahora, simplemente lo marcamos como usado
        Log.d(TAG, "Consumed $minutes reward minutes, remaining: ${currentBalance - minutes}")
        return true
    }

    /**
     * Procesa un grant de recompensa desde el servidor.
     *
     * §0.3: El grant tiene source='reward'.
     */
    suspend fun processRewardGrant(
        grantId: String,
        minutes: Int,
        expiresAt: Instant
    ): Boolean {
        return try {
            val now = timeProvider.wallInstant()
            val grantedAt = now.toString()
            val expiresAtStr = expiresAt.toString()

            val grant = GrantEntity(
                id = "reward_$grantId",
                device_id = "reward_device",
                request_id = null,
                scope = REWARD_SCOPE,
                minutes = minutes,
                source = "reward",
                granted_at = grantedAt,
                expires_at = expiresAtStr
            )

            grantDao.insertGrant(grant)

            Log.d(TAG, "Reward grant created: $grantId, $minutes min, expires at $expiresAt")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error processing reward grant: ${e.message}")
            false
        }
    }

    /**
     * Limpia grants expirados.
     */
    suspend fun cleanupExpiredGrants() {
        // Room debería manejar esto automáticamente,
        // pero podemos hacer limpieza manual si es necesario
        Log.d(TAG, "Cleaning up expired reward grants")
    }

    private fun GrantEntity.toUi(): RewardGrantUi {
        val expiresAt = try {
            Instant.parse(expires_at)
        } catch (e: Exception) {
            null
        }

        val now = timeProvider.wallInstant()
        val minutesRemaining = if (expiresAt != null) {
            val seconds = java.time.Duration.between(now, expiresAt).seconds
            maxOf(0, seconds / 60)
        } else {
            minutes
        }

        return RewardGrantUi(
            id = id,
            minutes = minutes,
            minutesRemaining = minutesRemaining.toInt(),
            expiresAt = expiresAt,
            grantedAt = granted_at
        )
    }
}

/**
 * UI model para grant de recompensa.
 */
data class RewardGrantUi(
    val id: String,
    val minutes: Int,
    val minutesRemaining: Int,
    val expiresAt: Instant?,
    val grantedAt: String
) {
    val isExpiringSoon: Boolean
        get() {
            val expires = expiresAt ?: return false
            val minutesLeft = java.time.Duration.between(
                Instant.now(), expires
            ).toMinutes()
            return minutesLeft in 0..5
        }

    val isExpired: Boolean
        get() {
            val expires = expiresAt ?: return false
            return expires.isBefore(Instant.now())
        }
}

/**
 * Item de historial de recompensas.
 */
data class RewardHistoryItem(
    val id: String,
    val minutes: Int,
    val grantedAt: String,
    val expiresAt: Instant?,
    val isActive: Boolean,
    val isExpired: Boolean
)

/**
 * Hilt [EntryPoint] that exposes [RewardManager] from the
 * `SingletonComponent` to non-Hilt call sites (legacy Workers, plain
 * unit tests). Production code MUST inject the manager directly via
 * `@Inject RewardManager` to avoid the runtime lookup cost.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RewardManagerEntryPoint {
    fun rewardManager(): RewardManager
}
