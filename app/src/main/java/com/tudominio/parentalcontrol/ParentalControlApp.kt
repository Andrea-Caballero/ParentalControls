package com.tudominio.parentalcontrol

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ParentalControlApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "ParentalControlApp"
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        Log.d(
            TAG,
            "BuildConfig diagnostic \u2014 USE_SHARED_MOCK=${BuildConfig.USE_SHARED_MOCK}, " +
                "USE_MOCK_SUPABASE=${BuildConfig.USE_MOCK_SUPABASE}, " +
                "SHARED_MOCK_URL=${BuildConfig.SHARED_MOCK_URL}, " +
                "SUPABASE_URL=${BuildConfig.SUPABASE_URL}"
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
}
