package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.ui.common.CardSurface
import ai.deepmost.triage.ui.common.HairlineDivider
import ai.deepmost.triage.ui.common.KeyValue
import ai.deepmost.triage.ui.common.SectionHeader
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.WarnAmber
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settingsRepository.settings.collectAsState(initial = container.currentSettings)
    val profiles = remember { container.configLoader.availableProfiles() }
    var storageUsed by remember { mutableStateOf(0L) }
    var lowStorage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val status = container.retentionManager.status()
        storageUsed = status.usedBytes; lowStorage = status.lowStorage
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        HairlineDivider(Modifier.padding(vertical = 8.dp))

        // Station config.
        SectionHeader(stringResource(R.string.station_config))
        CardSurface {
            Column {
                profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { scope.launch { container.settingsRepository.setProfile(p) } }) {
                            Text(if (settings.profileId == p) "✓ ${stringResource(R.string.active)}" else stringResource(R.string.select))
                        }
                    }
                }
            }
        }

        // Storage / retention.
        SectionHeader(stringResource(R.string.retention))
        CardSurface {
            Column {
                KeyValue(stringResource(R.string.photo_storage), "${storageUsed / (1024 * 1024)} MB")
                KeyValue(stringResource(R.string.retention_cap), "${settings.retentionBytesCap / (1024 * 1024)} MB")
                if (lowStorage) Text(stringResource(R.string.low_storage_warning), color = WarnAmber, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2L, 4L, 8L).forEach { gb ->
                        OutlinedButton(onClick = { scope.launch { container.settingsRepository.setRetentionCap(gb * 1024 * 1024 * 1024) } }) {
                            Text("${gb}GB")
                        }
                    }
                }
                OutlinedButton(onClick = { scope.launch { container.retentionManager.enforce() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.enforce_now))
                }
            }
        }

        // Sync (default OFF).
        SectionHeader(stringResource(R.string.sync_optional))
        var endpoint by remember(settings.syncEndpoint) { mutableStateOf(settings.syncEndpoint) }
        var token by remember(settings.syncToken) { mutableStateOf(settings.syncToken) }
        var wifiOnly by remember(settings.syncWifiOnly) { mutableStateOf(settings.syncWifiOnly) }
        CardSurface {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.sync_enabled), style = MaterialTheme.typography.labelMedium)
                    Switch(checked = settings.syncEnabled, onCheckedChange = { enabled ->
                        scope.launch {
                            container.settingsRepository.setSync(enabled, endpoint, token, wifiOnly)
                            if (enabled) ai.deepmost.triage.sync.SyncScheduler.schedulePeriodic(context, wifiOnly)
                            else ai.deepmost.triage.sync.SyncScheduler.cancelPeriodic(context)
                        }
                    })
                }
                Text(stringResource(R.string.sync_scope_note), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text(stringResource(R.string.endpoint_https)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(stringResource(R.string.token)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.wifi_only), style = MaterialTheme.typography.labelMedium)
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                Button(onClick = {
                    scope.launch { container.settingsRepository.setSync(settings.syncEnabled, endpoint, token, wifiOnly) }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_sync_config)) }
            }
        }

        // Models.
        SectionHeader(stringResource(R.string.model_registry))
        CardSurface {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.auto_download_models), style = MaterialTheme.typography.labelMedium)
                    Switch(checked = settings.autoDownloadModels, onCheckedChange = { enabled ->
                        scope.launch {
                            container.settingsRepository.setAutoDownloadModels(enabled)
                            if (enabled) container.modelBootstrap.provision(true, wifiOnly = false)
                        }
                    })
                }
                Text(stringResource(R.string.auto_download_note), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Smart capture.
        SectionHeader(stringResource(R.string.smart_capture))
        CardSurface {
            Column {
                ToggleRow(stringResource(R.string.auto_capture), settings.autoCapture) { v -> scope.launch { container.settingsRepository.setAutoCapture(v) } }
                ToggleRow(stringResource(R.string.voice_guidance), settings.voiceGuidance) { v -> scope.launch { container.settingsRepository.setVoiceGuidance(v) } }
                ToggleRow(stringResource(R.string.haptics), settings.haptics) { v -> scope.launch { container.settingsRepository.setHaptics(v) } }
                ToggleRow(stringResource(R.string.redact_plates), settings.redactPlates) { v -> scope.launch { container.settingsRepository.setRedactPlates(v) } }
            }
        }

        // Shift reminders.
        SectionHeader(stringResource(R.string.shift_reminders))
        CardSurface {
            ToggleRow(stringResource(R.string.shift_reminders), settings.remindersEnabled) { v ->
                scope.launch {
                    container.settingsRepository.setReminders(v, settings.shiftStartHour, settings.shiftEndHour)
                    ai.deepmost.triage.notifications.Reminders.schedule(context, v, settings.shiftStartHour, settings.shiftEndHour)
                }
            }
        }

        // Thresholds.
        SectionHeader(stringResource(R.string.thresholds))
        var regConf by remember(settings.registrationConfidence) { mutableStateOf(settings.registrationConfidence.toString()) }
        var sev by remember(settings.severityThreshold) { mutableStateOf(settings.severityThreshold.toString()) }
        var sharp by remember(settings.sharpnessMin) { mutableStateOf(settings.sharpnessMin.toString()) }
        var expo by remember(settings.exposureClipMax) { mutableStateOf(settings.exposureClipMax.toString()) }
        var frame by remember(settings.framingMin) { mutableStateOf(settings.framingMin.toString()) }
        CardSurface {
            Column {
                ThresholdField(stringResource(R.string.registration_confidence), regConf) { regConf = it }
                ThresholdField(stringResource(R.string.severity_threshold), sev) { sev = it }
                ThresholdField(stringResource(R.string.sharpness_min), sharp) { sharp = it }
                ThresholdField(stringResource(R.string.exposure_clip_max), expo) { expo = it }
                ThresholdField(stringResource(R.string.framing_min), frame) { frame = it }
                Button(onClick = {
                    scope.launch {
                        container.settingsRepository.setThresholds(
                            regConf.toFloatOrNull() ?: 0.30f, sev.toFloatOrNull() ?: 0.30f,
                            sharp.toFloatOrNull() ?: 0.0011f, expo.toFloatOrNull() ?: 0.38f,
                            frame.toFloatOrNull() ?: 0.060f
                        )
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_thresholds)) }
            }
        }

        // Role PINs.
        SectionHeader(stringResource(R.string.role_pins))
        var newPin by remember { mutableStateOf("") }
        val session by container.authRepository.session.collectAsState(initial = null)
        CardSurface {
            Column {
                Text(stringResource(R.string.change_my_pin), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) }, label = { Text(stringResource(R.string.new_pin)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val id = session?.driverId ?: return@Button
                    if (newPin.length >= 4) scope.launch { container.authRepository.changePin(id, newPin); newPin = "" }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_pin)) }
                if (ai.deepmost.triage.auth.BiometricAuth.isAvailable(context)) {
                    ToggleRow(stringResource(R.string.enable_biometric), settings.biometricEnabled) { v ->
                        scope.launch { container.settingsRepository.setBiometric(v, session?.name ?: settings.lastDriverName) }
                    }
                }
            }
        }

        // Language.
        SectionHeader(stringResource(R.string.language))
        CardSurface {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "en" to "English", "ml" to "മലയാളം", "hi" to "हिन्दी").forEach { (code, label) ->
                    OutlinedButton(onClick = { scope.launch { container.settingsRepository.setLanguage(code) } }) {
                        Text(label, color = if (settings.language == code) OkGreen else TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(stringResource(R.string.language_note), color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.local_scope_statement), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ThresholdField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}
