package ai.deepmost.triage

import ai.deepmost.triage.di.AppContainer
import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Application entry point. Owns the [AppContainer] (manual DI), initializes Timber logging and
 * provides the WorkManager configuration (sync is on-demand and default OFF).
 */
class TriageApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        container = AppContainer(this)
        ai.deepmost.triage.notifications.Reminders.ensureChannel(this)
        // Seed a default supervisor on first run, then auto-provision downloadable models
        // (provisioning only — analysis still runs fully on-device/offline).
        CoroutineScope(Dispatchers.IO).launch {
            container.authRepository.ensureSeed()
            val settings = container.settingsRepository.settings.first()
            container.modelBootstrap.provision(enabled = settings.autoDownloadModels, wifiOnly = false)
            ai.deepmost.triage.notifications.Reminders.schedule(
                this@TriageApplication, settings.remindersEnabled, settings.shiftStartHour, settings.shiftEndHour
            )
            if (settings.syncEnabled) {
                ai.deepmost.triage.sync.SyncScheduler.schedulePeriodic(this@TriageApplication, settings.syncWifiOnly)
            }
        }
        Timber.i("TRIAGE started — 100%% on-device analysis; sync default OFF")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
