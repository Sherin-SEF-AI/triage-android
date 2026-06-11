package ai.deepmost.triage.settings

import ai.deepmost.triage.quality.QualityThresholds
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "triage_settings")

/** Immutable snapshot of all app settings. */
data class TriageSettings(
    val profileId: String = "xprest",
    val retentionBytesCap: Long = 4L * 1024 * 1024 * 1024, // 4 GB of photo payloads
    val lowStorageWarnBytes: Long = 512L * 1024 * 1024,
    val syncEnabled: Boolean = false,                       // default OFF
    val syncEndpoint: String = "",
    val syncToken: String = "",
    val syncWifiOnly: Boolean = true,
    val autoDownloadModels: Boolean = true,                 // provision .tflite models on start
    // Smart capture.
    val autoCapture: Boolean = false,
    val voiceGuidance: Boolean = true,
    val haptics: Boolean = true,
    // Privacy / review.
    val redactPlates: Boolean = true,
    // Access.
    val biometricEnabled: Boolean = false,
    val lastDriverName: String = "",
    val onboardingDone: Boolean = false,
    // Shift reminders.
    val remindersEnabled: Boolean = false,
    val shiftStartHour: Int = 6,
    val shiftEndHour: Int = 18,
    val language: String = "system",                        // system | en | ml | hi
    val registrationConfidence: Float = 0.30f,
    val severityThreshold: Float = 0.30f,
    // Quality gate thresholds.
    val sharpnessMin: Float = 0.0011f,
    val exposureClipMax: Float = 0.38f,
    val framingMin: Float = 0.060f,
    val brightnessMin: Float = 0.12f,
    val brightnessMax: Float = 0.96f
) {
    fun qualityThresholds() = QualityThresholds(
        sharpnessMin = sharpnessMin,
        exposureClipMax = exposureClipMax,
        framingMin = framingMin,
        brightnessMin = brightnessMin,
        brightnessMax = brightnessMax
    )
}

/**
 * DataStore-backed settings. Holds station-config selection, retention policy, the optional sync
 * config (default OFF — the analysis path never depends on it), quality/diff thresholds and the
 * UI language. Role PINs are stored separately by AuthRepository.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<TriageSettings> = context.dataStore.data.map { p ->
        TriageSettings(
            profileId = p[KEY_PROFILE] ?: "xprest",
            retentionBytesCap = p[KEY_RETENTION_CAP] ?: (4L * 1024 * 1024 * 1024),
            lowStorageWarnBytes = p[KEY_LOW_STORAGE] ?: (512L * 1024 * 1024),
            syncEnabled = p[KEY_SYNC_ENABLED] ?: false,
            syncEndpoint = p[KEY_SYNC_ENDPOINT] ?: "",
            syncToken = p[KEY_SYNC_TOKEN] ?: "",
            syncWifiOnly = p[KEY_SYNC_WIFI] ?: true,
            autoDownloadModels = p[KEY_AUTO_MODELS] ?: true,
            autoCapture = p[KEY_AUTO_CAPTURE] ?: false,
            voiceGuidance = p[KEY_VOICE] ?: true,
            haptics = p[KEY_HAPTICS] ?: true,
            redactPlates = p[KEY_REDACT] ?: true,
            biometricEnabled = p[KEY_BIOMETRIC] ?: false,
            lastDriverName = p[KEY_LAST_DRIVER] ?: "",
            onboardingDone = p[KEY_ONBOARDING] ?: false,
            remindersEnabled = p[KEY_REMINDERS] ?: false,
            shiftStartHour = p[KEY_SHIFT_START] ?: 6,
            shiftEndHour = p[KEY_SHIFT_END] ?: 18,
            language = p[KEY_LANGUAGE] ?: "system",
            registrationConfidence = p[KEY_REG_CONF] ?: 0.30f,
            severityThreshold = p[KEY_SEVERITY] ?: 0.30f,
            sharpnessMin = p[KEY_SHARPNESS] ?: 0.0011f,
            exposureClipMax = p[KEY_EXPOSURE] ?: 0.38f,
            framingMin = p[KEY_FRAMING] ?: 0.060f,
            brightnessMin = p[KEY_BRIGHT_MIN] ?: 0.12f,
            brightnessMax = p[KEY_BRIGHT_MAX] ?: 0.96f
        )
    }

    suspend fun setProfile(id: String) = context.dataStore.edit { it[KEY_PROFILE] = id }
    suspend fun setRetentionCap(bytes: Long) = context.dataStore.edit { it[KEY_RETENTION_CAP] = bytes }
    suspend fun setLanguage(lang: String) = context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    suspend fun setAutoDownloadModels(enabled: Boolean) = context.dataStore.edit { it[KEY_AUTO_MODELS] = enabled }
    suspend fun setAutoCapture(v: Boolean) = context.dataStore.edit { it[KEY_AUTO_CAPTURE] = v }
    suspend fun setVoiceGuidance(v: Boolean) = context.dataStore.edit { it[KEY_VOICE] = v }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[KEY_HAPTICS] = v }
    suspend fun setRedactPlates(v: Boolean) = context.dataStore.edit { it[KEY_REDACT] = v }
    suspend fun setBiometric(enabled: Boolean, driverName: String) = context.dataStore.edit {
        it[KEY_BIOMETRIC] = enabled; it[KEY_LAST_DRIVER] = driverName
    }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[KEY_ONBOARDING] = v }
    suspend fun setReminders(enabled: Boolean, startHour: Int, endHour: Int) = context.dataStore.edit {
        it[KEY_REMINDERS] = enabled; it[KEY_SHIFT_START] = startHour; it[KEY_SHIFT_END] = endHour
    }

    suspend fun setSync(enabled: Boolean, endpoint: String, token: String, wifiOnly: Boolean) =
        context.dataStore.edit {
            it[KEY_SYNC_ENABLED] = enabled
            it[KEY_SYNC_ENDPOINT] = endpoint
            it[KEY_SYNC_TOKEN] = token
            it[KEY_SYNC_WIFI] = wifiOnly
        }

    suspend fun setThresholds(
        registrationConfidence: Float, severity: Float,
        sharpnessMin: Float, exposureClipMax: Float, framingMin: Float
    ) = context.dataStore.edit {
        it[KEY_REG_CONF] = registrationConfidence
        it[KEY_SEVERITY] = severity
        it[KEY_SHARPNESS] = sharpnessMin
        it[KEY_EXPOSURE] = exposureClipMax
        it[KEY_FRAMING] = framingMin
    }

    private companion object {
        val KEY_PROFILE = stringPreferencesKey("profile")
        val KEY_RETENTION_CAP = longPreferencesKey("retention_cap")
        val KEY_LOW_STORAGE = longPreferencesKey("low_storage")
        val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val KEY_SYNC_ENDPOINT = stringPreferencesKey("sync_endpoint")
        val KEY_SYNC_TOKEN = stringPreferencesKey("sync_token")
        val KEY_SYNC_WIFI = booleanPreferencesKey("sync_wifi")
        val KEY_AUTO_MODELS = booleanPreferencesKey("auto_models")
        val KEY_AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val KEY_VOICE = booleanPreferencesKey("voice_guidance")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_REDACT = booleanPreferencesKey("redact_plates")
        val KEY_BIOMETRIC = booleanPreferencesKey("biometric")
        val KEY_LAST_DRIVER = stringPreferencesKey("last_driver")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_done")
        val KEY_REMINDERS = booleanPreferencesKey("reminders")
        val KEY_SHIFT_START = androidx.datastore.preferences.core.intPreferencesKey("shift_start")
        val KEY_SHIFT_END = androidx.datastore.preferences.core.intPreferencesKey("shift_end")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_REG_CONF = floatPreferencesKey("reg_conf")
        val KEY_SEVERITY = floatPreferencesKey("severity")
        val KEY_SHARPNESS = floatPreferencesKey("sharpness")
        val KEY_EXPOSURE = floatPreferencesKey("exposure")
        val KEY_FRAMING = floatPreferencesKey("framing")
        val KEY_BRIGHT_MIN = floatPreferencesKey("bright_min")
        val KEY_BRIGHT_MAX = floatPreferencesKey("bright_max")
    }
}
