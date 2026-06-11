package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.data.InspectionType
import ai.deepmost.triage.data.Role
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.registry.EngineStatus
import ai.deepmost.triage.ui.common.CardSurface
import ai.deepmost.triage.ui.common.HairlineDivider
import ai.deepmost.triage.ui.common.KeyValue
import ai.deepmost.triage.ui.common.SectionHeader
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import ai.deepmost.triage.ui.theme.WarnAmber
import ai.deepmost.triage.vehicle.LeagueRow
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun FleetScreen(
    container: AppContainer,
    onHistory: (String) -> Unit,
    onReview: (String) -> Unit,
    onCapture: (String) -> Unit,
    onAnalytics: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by container.authRepository.session.collectAsState(initial = null)
    var unlocked by remember { mutableStateOf(session?.role == Role.SUPERVISOR) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    if (!unlocked) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.supervisor_access), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.enter_supervisor_pin), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); pinError = false },
                label = { Text(stringResource(R.string.pin)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            if (pinError) Text(stringResource(R.string.invalid_pin), color = TriageAccent, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                scope.launch { if (container.authRepository.supervisorUnlock(pin)) unlocked = true else pinError = true }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.unlock)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        return
    }

    val vehicles by container.vehicleService.observeAll().collectAsState(initial = emptyList())
    val disputed by container.inspectionRepository.observeDisputed().collectAsState(initial = emptyList())
    var league by remember { mutableStateOf<List<LeagueRow>>(emptyList()) }
    var engineStatuses by remember { mutableStateOf(container.modelRegistry.allStatuses()) }
    val dlStatus by container.modelBootstrap.status.collectAsState()
    var exportMsg by remember { mutableStateOf<String?>(null) }
    // Refresh per-head engine status whenever a download completes.
    LaunchedEffect(dlStatus) { engineStatuses = container.modelRegistry.allStatuses() }

    // SAF picker for installing a .tflite into a head's registry slot.
    var pendingHead by remember { mutableStateOf<String?>(null) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val head = pendingHead
        if (uri != null && head != null) {
            scope.launch {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes != null) container.modelRegistry.installModel(head, bytes)
                engineStatuses = container.modelRegistry.allStatuses()
            }
        }
        pendingHead = null
    }

    LaunchedEffect(vehicles) {
        league = container.vehicleAnalytics.leagueFor(vehicles.map { it.id to it.registration })
    }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.fleet), style = MaterialTheme.typography.titleLarge, color = TriageAccent)
                Row {
                    TextButton(onClick = onAnalytics) { Text(stringResource(R.string.analytics)) }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            }
            HairlineDivider(Modifier.padding(vertical = 8.dp))
        }

        item { SectionHeader(stringResource(R.string.cleanliness_league)) }
        items(league) { row ->
            CardSurface(Modifier.padding(vertical = 3.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.registration, style = MaterialTheme.typography.labelMedium)
                        Text(if (row.avgScore.isNaN()) "—" else "${row.avgScore.toInt()}/100",
                            color = if (row.avgScore.isNaN()) TextSecondary else OkGreen,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    KeyValue(stringResource(R.string.open_new_damage), row.openNewDamages.toString())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onHistory(row.vehicleId) }) { Text(stringResource(R.string.history)) }
                        TextButton(onClick = {
                            scope.launch {
                                val supervisorId = session?.driverId ?: return@launch
                                val v = container.vehicleService.byId(row.vehicleId) ?: return@launch
                                val insp = container.inspectionService.create(v.id, supervisorId, InspectionType.BASELINE, v.profileId, null)
                                onCapture(insp.id)
                            }
                        }) { Text(stringResource(R.string.enroll_baseline)) }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.dispute_queue) + " (${disputed.size})") }
        items(disputed) { insp ->
            CardSurface(Modifier.padding(vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${insp.type} · ${insp.id.take(8)}", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { onReview(insp.id) }) { Text(stringResource(R.string.review)) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.model_registry)) }
        items(engineStatuses) { status: EngineStatus ->
            val spec = container.modelRegistry.specFor(status.head)
            val downloadable = spec?.downloadable == true
            val dl = dlStatus[status.head]
            CardSurface(Modifier.padding(vertical = 3.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(status.head, style = MaterialTheme.typography.labelMedium)
                        val (label, color) = when {
                            status.usingModel -> status.tag to OkGreen
                            dl == ai.deepmost.triage.registry.ModelBootstrap.Status.DOWNLOADING -> stringResource(R.string.model_downloading) to WarnAmber
                            dl == ai.deepmost.triage.registry.ModelBootstrap.Status.FAILED -> stringResource(R.string.model_download_failed) to TriageAccent
                            !downloadable -> stringResource(R.string.model_no_public) to WarnAmber
                            else -> stringResource(R.string.classical_cv) to WarnAmber
                        }
                        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (downloadable && !status.usingModel) {
                            TextButton(onClick = { container.modelBootstrap.forceProvisionOne(status.head) }) {
                                Text(stringResource(R.string.download_now))
                            }
                        }
                        TextButton(onClick = { pendingHead = status.head; modelPicker.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.install_model))
                        }
                        if (status.usingModel) {
                            TextButton(onClick = {
                                scope.launch { container.modelRegistry.uninstall(status.head); engineStatuses = container.modelRegistry.allStatuses() }
                            }) { Text(stringResource(R.string.uninstall_model)) }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.data_export))
            CardSurface {
                Column {
                    Text(stringResource(R.string.export_hint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    exportMsg?.let { Text(it, color = OkGreen, style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = {
                            scope.launch {
                                // Snapshot all finalized ids via direct queries.
                                val ids = container.inspectionRepository.finalizedSyncedOldestFirst()
                                    .plus(container.inspectionRepository.finalizedUnsynced())
                                    .map { it.id }.distinct()
                                val zip = container.datasetExporter.exportBulk(ids, container.currentSettings.redactPlates)
                                if (zip != null) {
                                    exportMsg = zip.name
                                    shareFile(context, zip)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text(stringResource(R.string.export_bulk)) }
                }
            }
        }
    }
}

private fun shareFile(context: android.content.Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export TRIAGE records"))
}
