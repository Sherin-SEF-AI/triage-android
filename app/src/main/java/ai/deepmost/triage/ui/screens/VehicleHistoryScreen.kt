package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.integrity.ChainVerification
import ai.deepmost.triage.ui.common.CardSurface
import ai.deepmost.triage.ui.common.HairlineDivider
import ai.deepmost.triage.ui.common.KeyValue
import ai.deepmost.triage.ui.common.SectionHeader
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import ai.deepmost.triage.vehicle.CleanlinessPoint
import ai.deepmost.triage.vehicle.LedgerEntry
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun VehicleHistoryScreen(
    container: AppContainer,
    vehicleId: String,
    onReview: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val inspections by container.inspectionRepository.observeByVehicle(vehicleId).collectAsState(initial = emptyList())
    var ledger by remember { mutableStateOf<List<LedgerEntry>>(emptyList()) }
    var trend by remember { mutableStateOf<List<CleanlinessPoint>>(emptyList()) }
    var registration by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf<ChainVerification?>(null) }
    var verifying by remember { mutableStateOf(false) }

    LaunchedEffect(vehicleId, inspections.size) {
        registration = container.vehicleService.byId(vehicleId)?.registration ?: vehicleId
        ledger = container.vehicleAnalytics.ledger(vehicleId)
        trend = container.vehicleAnalytics.cleanlinessTrend(vehicleId)
    }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(registration, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.vehicle_history), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
            HairlineDivider(Modifier.padding(vertical = 8.dp))
        }

        item {
            CardSurface {
                Column {
                    Text(stringResource(R.string.chain_integrity), style = MaterialTheme.typography.labelLarge)
                    verification?.let { v ->
                        val ok = v.valid
                        Text(
                            if (ok) stringResource(R.string.chain_valid) else stringResource(R.string.chain_broken, v.brokenCount),
                            color = if (ok) OkGreen else TriageAccent,
                            style = MaterialTheme.typography.labelLarge
                        )
                        v.links.filter { !it.ok }.forEach {
                            Text("${it.inspectionId.take(8)}: ${it.notes}", color = TriageAccent, style = MaterialTheme.typography.labelSmall)
                        }
                    } ?: Text(stringResource(R.string.chain_not_checked), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !verifying,
                        onClick = { verifying = true; scope.launch { verification = container.hashChain.verifyVehicle(vehicleId); verifying = false } },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text(if (verifying) stringResource(R.string.verifying) else stringResource(R.string.verify_chain)) }
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.cleanliness_trend))
            CardSurface { CleanlinessChart(trend) }
        }

        item { SectionHeader(stringResource(R.string.damage_ledger) + " (${ledger.size})") }
        items(ledger) { entry ->
            CardSurface(Modifier.padding(vertical = 3.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${entry.capturePoint} · ${entry.zone ?: "-"}", style = MaterialTheme.typography.labelMedium, color = if (entry.resolved) OkGreen else TriageAccent)
                        Text(entry.type.name, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    KeyValue(stringResource(R.string.first_seen), df.format(Date(entry.firstSeenAt)))
                    KeyValue(stringResource(R.string.shifts_present), entry.shiftsPresent.toString())
                    KeyValue(stringResource(R.string.lifecycle), if (entry.resolved) stringResource(R.string.resolved) else stringResource(R.string.open))
                }
            }
        }

        item { SectionHeader(stringResource(R.string.inspections) + " (${inspections.size})") }
        if (inspections.isEmpty()) {
            item { Text(stringResource(R.string.no_inspections), color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
        items(inspections) { insp ->
            val finalized = insp.status.name == "FINALIZED" || insp.status.name == "DISPUTED"
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(Color(0xFF141416), MaterialTheme.shapes.small)
                    .padding(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { onReview(insp.id) },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(insp.type.name, style = MaterialTheme.typography.labelMedium)
                        Text(df.format(Date(insp.startedAtDevice)), color = TextSecondary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    Text(insp.status.name, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
                if (finalized) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch { container.pdfGenerator.generate(insp.id, container.currentSettings.redactPlates)?.let { shareInspectionFile(context, it, "application/pdf") } }
                        }) { Text(stringResource(R.string.share_pdf)) }
                        TextButton(onClick = {
                            scope.launch { container.datasetExporter.exportInspection(insp.id, container.currentSettings.redactPlates)?.let { shareInspectionFile(context, it, "application/zip") } }
                        }) { Text(stringResource(R.string.share_zip)) }
                    }
                }
            }
        }
    }
}

private fun shareInspectionFile(context: android.content.Context, file: java.io.File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share TRIAGE record"))
}

@Composable
private fun CleanlinessChart(points: List<CleanlinessPoint>) {
    if (points.isEmpty()) {
        Text(stringResource(R.string.no_data), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val maxScore = 100f
            val n = points.size
            val stepX = if (n > 1) size.width / (n - 1) else size.width
            // Gridline at 100 and 0.
            drawLine(Color(0xFF2A2A2E), Offset(0f, 0f), Offset(size.width, 0f), 1f)
            drawLine(Color(0xFF2A2A2E), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            var prev: Offset? = null
            points.forEachIndexed { i, p ->
                val x = stepX * i
                val y = size.height * (1f - (p.score / maxScore).coerceIn(0f, 1f))
                val cur = Offset(x, y)
                prev?.let { drawLine(TriageAccent, it, cur, 3f) }
                drawCircle(TriageAccent, 4f, cur)
                prev = cur
            }
        }
        Text(
            "${points.first().score.toInt()} → ${points.last().score.toInt()} /100",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall
        )
    }
}
