package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.data.InspectionType
import ai.deepmost.triage.data.entity.VehicleEntity
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.ui.common.CardSurface
import ai.deepmost.triage.ui.common.HairlineDivider
import ai.deepmost.triage.ui.common.KeyValue
import ai.deepmost.triage.ui.common.SectionHeader
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun InspectHomeScreen(
    container: AppContainer,
    onStartCapture: (String) -> Unit,
    onReview: (String) -> Unit,
    onHistory: (String) -> Unit,
    onFleet: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val session by container.authRepository.session.collectAsState(initial = null)
    val vehicles by container.vehicleService.observeAll().collectAsState(initial = emptyList())
    val driverId = session?.driverId
    val inProgress by (if (driverId != null) container.inspectionRepository.observeInProgress(driverId)
    else container.inspectionRepository.observeInProgress("")).collectAsState(initial = emptyList())

    var selected by remember { mutableStateOf<VehicleEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var showPlate by remember { mutableStateOf(false) }
    var lowStorage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { lowStorage = !container.retentionManager.canStartWalkaround() }
    // Auto-select so "Start inspection" is immediately actionable (tapping a card still re-selects).
    LaunchedEffect(vehicles) {
        if (selected == null && vehicles.isNotEmpty()) selected = vehicles.first()
        else if (selected != null && vehicles.none { it.id == selected!!.id }) selected = vehicles.firstOrNull()
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("TRIAGE", style = MaterialTheme.typography.titleLarge, color = TriageAccent)
                Text(session?.let { "${it.name} · ${it.role}" } ?: "", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Row {
                TextButton(onClick = onFleet) { Text(stringResource(R.string.fleet)) }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
                TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            }
        }
        HairlineDivider(Modifier.padding(vertical = 8.dp))

        if (lowStorage) {
            Text(
                stringResource(R.string.low_storage_warning),
                color = ai.deepmost.triage.ui.theme.WarnAmber,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (inProgress.isNotEmpty()) {
            SectionHeader(stringResource(R.string.resume_in_progress))
            inProgress.forEach { insp ->
                CardSurface(Modifier.padding(vertical = 4.dp).clickable { onStartCapture(insp.id) }) {
                    Column {
                        KeyValue(stringResource(R.string.type), insp.type.name)
                        KeyValue(stringResource(R.string.status), insp.status.name)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.select_vehicle))
            Row {
                TextButton(onClick = { showScan = true }) { Text(stringResource(R.string.scan_qr)) }
                TextButton(onClick = { showPlate = true }) { Text(stringResource(R.string.scan_plate)) }
                TextButton(onClick = { showAdd = true }) { Text(stringResource(R.string.add_vehicle)) }
            }
        }

        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.search)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        val shownVehicles = vehicles.filter {
            query.isBlank() || it.registration.contains(query, true) || it.fleetLabel.contains(query, true)
        }
        if (vehicles.isEmpty()) {
            Text(stringResource(R.string.no_vehicles), color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
            items(shownVehicles) { v ->
                val isSel = selected?.id == v.id
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .border(1.dp, if (isSel) TriageAccent else Color(0xFF2A2A2E), MaterialTheme.shapes.small)
                        .background(if (isSel) Color(0xFF201210) else Color(0xFF141416), MaterialTheme.shapes.small)
                        .clickable { selected = v }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(v.registration, style = MaterialTheme.typography.labelLarge)
                        Text(v.model, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onHistory(v.id) }) { Text(stringResource(R.string.history)) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            enabled = selected != null,
            onClick = {
                val v = selected ?: return@Button
                val did = driverId ?: return@Button
                scope.launch {
                    val last = container.inspectionRepository.latestFinalized(v.id)
                    val type = container.shiftTypeSuggester.suggest(System.currentTimeMillis(), last?.type)
                    val location = container.locationProvider.currentStamp()
                    val insp = container.inspectionService.create(v.id, did, type, v.profileId, location)
                    onStartCapture(insp.id)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(stringResource(R.string.start_inspection)) }

        OutlinedButton(
            enabled = selected != null,
            onClick = {
                val v = selected ?: return@OutlinedButton
                val did = driverId ?: return@OutlinedButton
                scope.launch {
                    val location = container.locationProvider.currentStamp()
                    val insp = container.inspectionService.create(v.id, did, InspectionType.INCIDENT, v.profileId, location)
                    onStartCapture(insp.id)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text(stringResource(R.string.incident_capture)) }
    }

    if (showAdd) {
        var reg by remember { mutableStateOf("") }
        var fleetLabel by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_vehicle)) },
            text = {
                Column {
                    OutlinedTextField(value = reg, onValueChange = { reg = it }, label = { Text(stringResource(R.string.registration)) }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = fleetLabel, onValueChange = { fleetLabel = it }, label = { Text(stringResource(R.string.fleet_label)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (reg.isNotBlank()) scope.launch {
                        container.vehicleService.create(reg, fleetLabel = fleetLabel)
                        showAdd = false
                    }
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showScan) {
        ai.deepmost.triage.ui.common.CameraScanDialog(
            mode = ai.deepmost.triage.ui.common.ScanMode.QR,
            onResult = { reg ->
                scope.launch {
                    selected = container.vehicleService.findOrCreateByRegistration(reg)
                    showScan = false
                }
            },
            onDismiss = { showScan = false }
        )
    }

    if (showPlate) {
        ai.deepmost.triage.ui.common.CameraScanDialog(
            mode = ai.deepmost.triage.ui.common.ScanMode.PLATE,
            onResult = { plate ->
                scope.launch {
                    selected = container.vehicleService.findOrCreateByRegistration(plate)
                    showPlate = false
                }
            },
            onDismiss = { showPlate = false }
        )
    }
}
