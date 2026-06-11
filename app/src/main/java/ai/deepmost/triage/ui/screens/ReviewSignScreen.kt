package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.DriverAnnotation
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.inspection.Attribution
import ai.deepmost.triage.ui.common.CardSurface
import ai.deepmost.triage.ui.common.HairlineDivider
import ai.deepmost.triage.ui.common.KeyValue
import ai.deepmost.triage.ui.common.SectionHeader
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import ai.deepmost.triage.ui.theme.WarnAmber
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReviewSignScreen(
    container: AppContainer,
    inspectionId: String,
    onFinalized: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val findings by container.findingRepository.observeByInspection(inspectionId).collectAsState(initial = emptyList())
    var inspectionVehicleId by remember { mutableStateOf<String?>(null) }
    var attribution by remember { mutableStateOf<Attribution?>(null) }
    var photoPaths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var prevPhotoPaths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showManual by remember { mutableStateOf(false) }
    var finalizing by remember { mutableStateOf(false) }

    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var padSize by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(inspectionId) {
        val insp = container.inspectionRepository.byId(inspectionId)
        inspectionVehicleId = insp?.vehicleId
        attribution = container.inspectionService.attribution(inspectionId)
        photoPaths = container.inspectionRepository.photos(inspectionId).associate { it.capturePoint to it.filePath }
        // Previous accepted walkaround photos, for the side-by-side NEW / REVIEW comparison.
        if (insp != null) {
            container.inspectionRepository.previousFinalized(insp.vehicleId, inspectionId)?.let { prev ->
                prevPhotoPaths = container.inspectionRepository.photos(prev.id)
                    .filter { !it.evicted }.associate { it.capturePoint to it.filePath }
            }
        }
    }

    val newOnes = findings.filter { it.diffStatus == DiffStatus.NEW }
    val review = findings.filter { it.diffStatus == DiffStatus.REVIEW_REQUIRED }
    val preExisting = findings.filter { it.diffStatus == DiffStatus.PRE_EXISTING && it.type != FindingType.CLEANLINESS_SCORE && isDefectType(it.type) }
    val firstRecord = findings.filter { it.diffStatus == DiffStatus.FIRST_RECORD && isDefectType(it.type) }
    val resolved = findings.filter { it.diffStatus == DiffStatus.RESOLVED }
    val cleanliness = findings.filter { it.head == FindingHead.CLEANLINESS && it.type == FindingType.CLEANLINESS_SCORE }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.review_and_sign), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
            HairlineDivider(Modifier.padding(vertical = 8.dp))
        }

        if (newOnes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.new_damage) + " (${newOnes.size})") }
            attribution?.let { attr ->
                item { AttributionBanner(attr) }
            }
            items(newOnes) { f -> FindingRow(container, f, photoPaths[f.capturePoint], TriageAccent, prevPhotoPath = prevPhotoPaths[f.capturePoint], comparison = true) }
        }
        if (review.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.review_required) + " (${review.size})") }
            items(review) { f -> FindingRow(container, f, photoPaths[f.capturePoint], WarnAmber, reviewNote = true, prevPhotoPath = prevPhotoPaths[f.capturePoint], comparison = true) }
        }
        if (firstRecord.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.first_record) + " (${firstRecord.size})") }
            items(firstRecord) { f -> FindingRow(container, f, photoPaths[f.capturePoint], TextSecondary) }
        }
        if (preExisting.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.pre_existing) + " (${preExisting.size})") }
            items(preExisting) { f -> FindingRow(container, f, photoPaths[f.capturePoint], TextSecondary) }
        }
        if (resolved.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.resolved) + " (${resolved.size})") }
            items(resolved) { f -> FindingRow(container, f, photoPaths[f.capturePoint], OkGreen) }
        }

        item {
            SectionHeader(stringResource(R.string.cleanliness))
            CardSurface {
                Column {
                    cleanliness.sortedBy { it.capturePoint }.forEach {
                        KeyValue(
                            it.capturePoint + if (it.lowConfidence) " *" else "",
                            "${((1f - it.severity) * 100f).toInt()}/100"
                        )
                    }
                    if (cleanliness.any { it.lowConfidence }) {
                        Text(stringResource(R.string.low_confidence_baseline), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_manual_finding))
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.driver_signature))
            SignaturePad(strokes = strokes, onSize = { padSize = it })
            TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.clear_signature)) }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = strokes.isNotEmpty() && !finalizing,
                onClick = {
                    finalizing = true
                    scope.launch {
                        val png = withContext(Dispatchers.Default) { renderSignaturePng(strokes, padSize) }
                        val finalized = container.inspectionService.finalize(inspectionId, png)
                        // Local PDF + optional sync (default OFF).
                        withContext(Dispatchers.Default) {
                            runCatching { container.pdfGenerator.generate(inspectionId, container.currentSettings.redactPlates) }
                            container.retentionManager.enforce()
                        }
                        val settings = container.currentSettings
                        if (settings.syncEnabled) {
                            ai.deepmost.triage.sync.SyncScheduler.requestSync(context, settings.syncWifiOnly)
                        }
                        finalized?.vehicleId?.let { onFinalized(it) }
                            ?: inspectionVehicleId?.let { onFinalized(it) }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (finalizing) stringResource(R.string.finalizing) else stringResource(R.string.finalize_and_sign)) }
        }
    }

    if (showManual) {
        ManualFindingDialog(
            onDismiss = { showManual = false },
            onAdd = { type, severity, note, capturePoint, bbox ->
                scope.launch {
                    val photo = container.inspectionRepository.photoAt(inspectionId, capturePoint)
                    container.findingRepository.upsert(
                        FindingEntity(
                            id = UUID.randomUUID().toString(), inspectionId = inspectionId,
                            photoId = photo?.id, capturePoint = capturePoint, head = FindingHead.MANUAL,
                            type = type, zone = "manual", severity = severity, confidence = 1f,
                            bboxLeft = bbox.left, bboxTop = bbox.top, bboxRight = bbox.right, bboxBottom = bbox.bottom,
                            engine = "manual", diffStatus = DiffStatus.NEW,
                            driverAnnotation = DriverAnnotation.CONFIRMED, driverNote = note,
                            labelSource = ai.deepmost.triage.data.LabelSource.MANUAL,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    showManual = false
                }
            },
            photoPaths = photoPaths
        )
    }
}

@Composable
private fun AttributionBanner(attr: Attribution) {
    CardSurface(Modifier.padding(vertical = 4.dp)) {
        Column {
            Text(stringResource(R.string.attribution_title), color = TriageAccent, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(
                    R.string.attribution_body,
                    attr.previousDriverId?.take(8) ?: "—",
                    attr.currentDriverId.take(8)
                ),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FindingRow(
    container: AppContainer,
    finding: FindingEntity,
    photoPath: String?,
    accent: Color,
    reviewNote: Boolean = false,
    prevPhotoPath: String? = null,
    comparison: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var note by remember(finding.id) { mutableStateOf(finding.driverNote ?: "") }
    val bmp = remember(photoPath) { photoPath?.let { runCatching { Images.decodeForAnalysis(File(it), 480) }.getOrNull() } }
    val prevBmp = remember(prevPhotoPath) { if (comparison) prevPhotoPath?.let { runCatching { Images.decodeForAnalysis(File(it), 480) }.getOrNull() } else null }

    CardSurface(Modifier.padding(vertical = 4.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${finding.capturePoint} · ${finding.zone ?: "-"}", style = MaterialTheme.typography.labelMedium, color = accent)
                Text(finding.type.name, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            var showHeat by remember(finding.id) { mutableStateOf(false) }
            var zoom by remember(finding.id) { mutableStateOf(false) }
            if (comparison && prevBmp != null && bmp != null) {
                // Before/after wipe: previous shift vs current — drag the divider to see what changed.
                Text("${stringResource(R.string.previous_shift)} ⇄ ${stringResource(R.string.current_shot)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                ai.deepmost.triage.ui.common.BeforeAfterSlider(prevBmp.asImageBitmap(), bmp.asImageBitmap(), accent, Modifier.padding(vertical = 6.dp))
            } else bmp?.let { b ->
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { zoom = true }) {
                    if (showHeat && finding.head == FindingHead.DAMAGE) {
                        ai.deepmost.triage.ui.common.HeatmapImage(
                            b.asImageBitmap(),
                            listOf(ai.deepmost.triage.ui.common.HeatRegion(
                                ai.deepmost.triage.cv.NormRect(finding.bboxLeft, finding.bboxTop, finding.bboxRight, finding.bboxBottom),
                                finding.severity
                            )),
                            accent
                        )
                    } else {
                        Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height))
                        Canvas(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
                            drawRect(accent, Offset(finding.bboxLeft * size.width, finding.bboxTop * size.height), Size((finding.bboxRight - finding.bboxLeft) * size.width, (finding.bboxBottom - finding.bboxTop) * size.height), style = Stroke(width = 3f))
                        }
                    }
                }
                if (finding.head == FindingHead.DAMAGE) {
                    TextButton(onClick = { showHeat = !showHeat }) {
                        Text(if (showHeat) "Hide heatmap" else "Heatmap", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (zoom) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { zoom = false }) {
                        Box(Modifier.fillMaxWidth().background(Color.Black)) {
                            ai.deepmost.triage.ui.common.ZoomableImage(b.asImageBitmap(), Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height))
                            TextButton(onClick = { zoom = false }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)) { Text(stringResource(R.string.close), color = Color.White) }
                        }
                    }
                }
            }
            KeyValue("severity", "%.2f".format(finding.severity))
            KeyValue("confidence", "%.2f".format(finding.confidence))
            KeyValue("engine", finding.engine)
            if (finding.lowConfidence) Text(stringResource(R.string.low_confidence), color = WarnAmber, style = MaterialTheme.typography.labelSmall)
            finding.trend?.let { KeyValue("trend Δ", "%+.2f".format(it)) }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val confirmed = finding.driverAnnotation == DriverAnnotation.CONFIRMED
                val disputed = finding.driverAnnotation == DriverAnnotation.DISPUTED
                OutlinedButton(onClick = {
                    scope.launch { container.findingRepository.update(finding.copy(driverAnnotation = DriverAnnotation.CONFIRMED, driverNote = note)) }
                }) { Text(if (confirmed) "✓ " + stringResource(R.string.confirmed) else stringResource(R.string.confirm)) }
                OutlinedButton(onClick = {
                    scope.launch { container.findingRepository.update(finding.copy(driverAnnotation = DriverAnnotation.DISPUTED, driverNote = note)) }
                }) { Text(if (disputed) "✗ " + stringResource(R.string.disputed) else stringResource(R.string.dispute)) }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text(stringResource(R.string.note)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true
            )
        }
    }
}

@Composable
private fun SignaturePad(strokes: SnapshotStateList<MutableList<Offset>>, onSize: (Size) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(Color(0xFF1C1C1F), MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> strokes.add(mutableListOf(offset)) },
                    onDrag = { change, _ -> strokes.lastOrNull()?.add(change.position) }
                )
            }
    ) {
        onSize(size)
        // Quadratic-bezier smoothing through stroke midpoints.
        for (stroke in strokes) {
            if (stroke.size < 2) continue
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(stroke[0].x, stroke[0].y)
                for (i in 1 until stroke.size - 1) {
                    val mx = (stroke[i].x + stroke[i + 1].x) / 2f
                    val my = (stroke[i].y + stroke[i + 1].y) / 2f
                    quadraticTo(stroke[i].x, stroke[i].y, mx, my)
                }
                lineTo(stroke.last().x, stroke.last().y)
            }
            drawPath(path, Color.White, style = Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

private fun renderSignaturePng(strokes: List<List<Offset>>, size: Size): ByteArray {
    val w = if (size.width > 0) size.width.toInt() else 800
    val h = if (size.height > 0) size.height.toInt() else 320
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(AndroidColor.WHITE)
    val paint = AndroidPaint().apply {
        color = AndroidColor.BLACK; strokeWidth = 6f; isAntiAlias = true
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND
    }
    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val path = android.graphics.Path().apply {
            moveTo(stroke[0].x, stroke[0].y)
            for (i in 1 until stroke.size - 1) {
                val mx = (stroke[i].x + stroke[i + 1].x) / 2f
                val my = (stroke[i].y + stroke[i + 1].y) / 2f
                quadTo(stroke[i].x, stroke[i].y, mx, my)
            }
            lineTo(stroke.last().x, stroke.last().y)
        }
        canvas.drawPath(path, paint)
    }
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

@Composable
private fun ManualFindingDialog(
    onDismiss: () -> Unit,
    onAdd: (FindingType, Float, String, String, ai.deepmost.triage.cv.NormRect) -> Unit,
    photoPaths: Map<String, String>
) {
    val stations = photoPaths.keys.toList()
    var note by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("0.6") }
    var type by remember { mutableStateOf(FindingType.SCRATCH) }
    var station by remember { mutableStateOf(stations.firstOrNull() ?: "FRONT") }
    // Normalized drawn region (defaults to a centred box until the driver drags one).
    var region by remember { mutableStateOf(ai.deepmost.triage.cv.NormRect(0.35f, 0.35f, 0.65f, 0.65f)) }
    val types = listOf(FindingType.SCRATCH, FindingType.DENT, FindingType.CRACK, FindingType.BROKEN_PART, FindingType.MISSING_PART, FindingType.PAINT_PEEL, FindingType.MANUAL_NOTE)
    val bmp = remember(station) { photoPaths[station]?.let { runCatching { Images.decodeForAnalysis(File(it), 600) }.getOrNull() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_manual_finding)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(stringResource(R.string.type), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    types.take(4).forEach { t ->
                        OutlinedButton(onClick = { type = t }) {
                            Text(t.name.take(5), style = MaterialTheme.typography.labelSmall, color = if (type == t) TriageAccent else TextSecondary)
                        }
                    }
                }
                Text(stringResource(R.string.station), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stations.take(6).forEach { s ->
                        OutlinedButton(onClick = { station = s }) {
                            Text(s.take(6), style = MaterialTheme.typography.labelSmall, color = if (station == s) TriageAccent else TextSecondary)
                        }
                    }
                }
                // Tap-to-draw region on the captured station photo.
                bmp?.let { b ->
                    Text(stringResource(R.string.draw_region), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height))
                        Canvas(
                            Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                                .pointerInput(station) {
                                    detectDragGestures(
                                        onDragStart = { o -> region = ai.deepmost.triage.cv.NormRect(o.x / size.width, o.y / size.height, o.x / size.width, o.y / size.height) },
                                        onDrag = { change, _ ->
                                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                            val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                            region = ai.deepmost.triage.cv.NormRect(minOf(region.left, nx), minOf(region.top, ny), maxOf(region.left, nx), maxOf(region.top, ny))
                                        }
                                    )
                                }
                        ) {
                            drawRect(
                                color = TriageAccent,
                                topLeft = Offset(region.left * size.width, region.top * size.height),
                                size = Size(region.width * size.width, region.height * size.height),
                                style = Stroke(width = 3f)
                            )
                        }
                    }
                }
                OutlinedTextField(value = severity, onValueChange = { severity = it }, label = { Text("severity 0-1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rect = if (region.width < 0.02f || region.height < 0.02f)
                    ai.deepmost.triage.cv.NormRect(0.3f, 0.3f, 0.7f, 0.7f) else region.clampUnit()
                onAdd(type, severity.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f, note, station, rect)
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun isDefectType(t: FindingType): Boolean = when (t) {
    FindingType.TYRE_OK, FindingType.LAMP_INTACT, FindingType.LAMP_WORKING,
    FindingType.CLEANLINESS_SCORE -> false
    else -> true
}
