package com.tudominio.parentalcontrol.consent

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.keystore.SecureStorage
import kotlinx.coroutines.runBlocking

class ConsentManager private constructor(context: Context) {

    companion object {
        private const val TAG = "ConsentManager"

        private const val KEY_DISCLOSURE_CONSENT = "disclosure_consent"
        private const val KEY_DISCLOSURE_TIMESTAMP = "disclosure_timestamp"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_AGE_BAND = "age_band"

        private const val CONSENT_VERSION = 1

        @Volatile
        private var instance: ConsentManager? = null

        fun getInstance(context: Context): ConsentManager {
            return instance ?: synchronized(this) {
                instance ?: ConsentManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val secureStorage = SecureStorage.getInstance(context)

    fun hasDisclosureConsent(): Boolean {
        return runBlocking {
            val value = secureStorage.getString(KEY_DISCLOSURE_CONSENT)
            value == "1"
        }
    }

    fun getDisclosureConsentTimestamp(): Long {
        return runBlocking {
            val value = secureStorage.getString(KEY_DISCLOSURE_TIMESTAMP)
            value?.toLongOrNull() ?: 0L
        }
    }

    fun giveDisclosureConsent() {
        runBlocking {
            secureStorage.saveString(KEY_DISCLOSURE_CONSENT, "1")
            secureStorage.saveString(KEY_DISCLOSURE_TIMESTAMP, System.currentTimeMillis().toString())
        }
    }

    fun withdrawDisclosureConsent() {
        runBlocking {
            secureStorage.saveString(KEY_DISCLOSURE_CONSENT, "0")
        }
    }

    fun isOnboardingComplete(): Boolean {
        return runBlocking {
            val value = secureStorage.getString(KEY_ONBOARDING_COMPLETE)
            value == "1"
        }
    }

    fun completeOnboarding() {
        runBlocking {
            secureStorage.saveString(KEY_ONBOARDING_COMPLETE, "1")
        }
    }

    fun resetOnboarding() {
        runBlocking {
            secureStorage.saveString(KEY_ONBOARDING_COMPLETE, "0")
        }
    }

    fun getAgeBand(): AgeBand {
        return runBlocking {
            val value = secureStorage.getString(KEY_AGE_BAND) ?: AgeBand.UNSPECIFIED.name
            try {
                AgeBand.valueOf(value)
            } catch (e: Exception) {
                AgeBand.UNSPECIFIED
            }
        }
    }

    fun setAgeBand(ageBand: AgeBand) {
        runBlocking {
            secureStorage.saveString(KEY_AGE_BAND, ageBand.name)
        }
    }

    fun needsDisclosure(): Boolean = !hasDisclosureConsent()

    fun needsOnboarding(): Boolean = hasDisclosureConsent() && !isOnboardingComplete()

    fun isSetupComplete(): Boolean = hasDisclosureConsent() && isOnboardingComplete()

    fun resetAll() {
        runBlocking {
            secureStorage.clearAll()
        }
    }

    fun getConsentInfo(): ConsentInfo {
        return ConsentInfo(
            hasDisclosureConsent = hasDisclosureConsent(),
            disclosureTimestamp = getDisclosureConsentTimestamp(),
            isOnboardingComplete = isOnboardingComplete(),
            ageBand = getAgeBand()
        )
    }
}

enum class AgeBand {
    AGE_7_12,
    AGE_13_17,
    UNSPECIFIED
}

data class ConsentInfo(
    val hasDisclosureConsent: Boolean,
    val disclosureTimestamp: Long,
    val isOnboardingComplete: Boolean,
    val ageBand: AgeBand
)
