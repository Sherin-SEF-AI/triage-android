package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.capture.CaptureController
import ai.deepmost.triage.capture.CaptureViewModel
import ai.deepmost.triage.capture.GhostOverlay
import ai.deepmost.triage.capture.LiveGuidance
import ai.deepmost.triage.capture.StationStrip
import ai.deepmost.triage.capture.VoiceGuide
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.quality.QualityIssue
import ai.deepmost.triage.ui.common.TriageViewModelFactory
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TriageAccent
import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

@Composable
fun CaptureScreen(
    container: AppContainer,
    inspectionId: String,
    onFinishCapture: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val vm: CaptureViewModel = viewModel(factory = TriageViewModelFactory(container))
    val state by vm.state.collectAsState()
    val live by vm.liveState.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState(initial = container.currentSettings)
    val controller = remember { CaptureController() }
    val tone = remember { ToneGenerator(AudioManager.STREAM_SYSTEM, 70) }

    // Voice guide, recreated when the UI language changes; disposed with the screen.
    val voice = remember(settings.language) { VoiceGuide(context, settings.language) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }

    var hasCamera by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasCamera = grants[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) { vm.start(inspectionId) }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    LaunchedEffect(hasCamera) {
        if (hasCamera) controller.bind(context, lifecycleOwner, previewView, vm.analyzer())
    }
    DisposableEffect(Unit) { onDispose { controller.unbind(); tone.release() } }

    // Speak the station to frame whenever it changes.
    LaunchedEffect(state.currentStation?.id) {
        val st = state.currentStation
        if (settings.voiceGuidance && st != null && !state.complete) {
            val hint = if (st.lampZones().isNotEmpty()) " " + context.getString(R.string.lamp_capture_hint) else ""
            voice.speak("${st.label}.$hint")
        }
    }

    // Accept feedback: flash auto-clear + haptic + tone + voice.
    LaunchedEffect(state.flashAccept) {
        if (state.flashAccept) {
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
            if (settings.voiceGuidance) voice.speak(context.getString(R.string.captured))
            delay(180); vm.clearFlash()
        }
    }
    // Reject feedback: haptic + spoken reason.
    LaunchedEffect(state.lastVerdict) {
        val v = state.lastVerdict
        if (v != null && !v.passed) {
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (settings.voiceGuidance) voice.speak(v.issues.joinToString(". ") { context.getString(issueRes(it)) })
        }
    }
    // Auto-capture when the live frame holds "ready".
    LaunchedEffect(live.ready, settings.autoCapture, state.capturing, state.complete) {
        if (settings.autoCapture && live.ready && hasCamera && !state.capturing && !state.complete) {
            delay(800)
            if (vm.liveState.value.ready && !vm.state.value.capturing && !vm.state.value.complete) {
                vm.capture(context, controller)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        state.currentStation?.let { station ->
            GhostOverlay(station = station, accent = TriageAccent, modifier = Modifier.fillMaxSize())
        }

        // Live analysis overlay: vehicle bbox + framing readiness, drawn on the preview.
        Canvas(Modifier.fillMaxSize()) {
            live.vehicleBox?.let { b ->
                drawRect(
                    color = if (live.ready) OkGreen else TriageAccent,
                    topLeft = Offset(b.left * size.width, b.top * size.height),
                    size = Size(b.width * size.width, b.height * size.height),
                    style = Stroke(width = 4f)
                )
            }
        }

        if (state.flashAccept) {
            Box(Modifier.fillMaxSize().background(TriageAccent.copy(alpha = 0.25f)))
        }

        // Top bar.
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back), color = Color.White) }
                Text(
                    "${state.capturedPoints.size}/${state.stations.size}",
                    style = MaterialTheme.typography.labelLarge, color = Color.White
                )
            }
            state.currentStation?.let {
                Text(it.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (it.lampZones().isNotEmpty()) {
                    Text(stringResource(R.string.lamp_capture_hint), style = MaterialTheme.typography.labelSmall, color = TriageAccent)
                }
            }
            // Live framing meter + guidance.
            if (!state.complete) LiveFramingBar(framing = live.framing, ready = live.ready, guidance = live.guidance)
        }

        // Bottom controls.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            state.lastVerdict?.let { verdict ->
                if (!verdict.passed) {
                    Box(Modifier.fillMaxWidth().background(TriageAccent.copy(alpha = 0.85f), MaterialTheme.shapes.small).padding(8.dp)) {
                        Text(verdict.issues.joinToString("  ") { context.getString(issueRes(it)) }, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            StationStrip(
                stations = state.stations,
                currentIndex = state.currentIndex,
                capturedPoints = state.capturedPoints,
                analyzingPoints = state.analyzingPoints,
                accent = TriageAccent,
                onSelect = { vm.selectStation(it) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            if (state.complete) {
                Button(onClick = onFinishCapture, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(stringResource(R.string.review_and_sign))
                }
            } else {
                Button(
                    enabled = hasCamera && !state.capturing,
                    onClick = { vm.capture(context, controller) },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text(if (state.capturing) stringResource(R.string.capturing) else stringResource(R.string.capture))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        enabled = state.currentStation?.id in state.capturedPoints,
                        onClick = { vm.flagIssue() }
                    ) { Text(stringResource(R.string.flag_issue), color = Color.White) }
                    TextButton(onClick = onFinishCapture) {
                        Text(stringResource(R.string.finish_early), color = OkGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveFramingBar(framing: Float, ready: Boolean, guidance: LiveGuidance) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.framing), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(
                if (ready) stringResource(R.string.ready) else guidanceText(guidance),
                color = if (ready) OkGreen else TriageAccent, style = MaterialTheme.typography.labelSmall
            )
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(Color(0xFF2A2A2E), RoundedCornerShape(3.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(framing.coerceIn(0f, 1f)).height(6.dp)
                    .background(if (ready) OkGreen else TriageAccent, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun guidanceText(g: LiveGuidance): String = stringResource(
    when (g) {
        LiveGuidance.DARK -> R.string.g_dark
        LiveGuidance.BLURRY -> R.string.g_blurry
        LiveGuidance.MOVE_LEFT -> R.string.g_move_left
        LiveGuidance.MOVE_RIGHT -> R.string.g_move_right
        LiveGuidance.MOVE_CLOSER -> R.string.g_move_closer
        LiveGuidance.STEADY -> R.string.g_steady
        LiveGuidance.GOOD -> R.string.g_good
        LiveGuidance.NONE -> R.string.g_steady
    }
)

private fun issueRes(issue: QualityIssue): Int = when (issue) {
    QualityIssue.TOO_BLURRY -> R.string.q_too_blurry
    QualityIssue.OVEREXPOSED -> R.string.q_overexposed
    QualityIssue.UNDEREXPOSED -> R.string.q_underexposed
    QualityIssue.NO_VEHICLE_IN_FRAME -> R.string.q_no_vehicle
    QualityIssue.MISFRAMED -> R.string.q_misframed
    QualityIssue.TOO_DARK -> R.string.q_too_dark
    QualityIssue.TOO_BRIGHT -> R.string.q_too_bright
}
