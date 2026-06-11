package ai.deepmost.triage.ui.common

import ai.deepmost.triage.R
import ai.deepmost.triage.vehicle.PlateScanAnalyzer
import ai.deepmost.triage.vehicle.QrAnalyzer
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/** Scan mode for [CameraScanDialog]. */
enum class ScanMode { QR, PLATE }

/**
 * On-device camera scanner dialog. In QR mode it runs the ML Kit barcode [QrAnalyzer]; in PLATE
 * mode the ML Kit text [PlateScanAnalyzer]. Both are fully local. Emits the decoded value once.
 */
@Composable
fun CameraScanDialog(mode: ScanMode, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(hasCamera) {
        if (!hasCamera) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cp = future.get()
            provider = cp
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analyzer: ImageAnalysis.Analyzer = when (mode) {
                ScanMode.QR -> QrAnalyzer { onResult(it) }
                ScanMode.PLATE -> PlateScanAnalyzer { onResult(it) }
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            runCatching {
                cp.unbindAll()
                cp.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF0A0A0B), MaterialTheme.shapes.medium).padding(12.dp)
        ) {
            val title = if (mode == ScanMode.QR) R.string.scan_qr else R.string.scan_plate
            val hint = if (mode == ScanMode.QR) R.string.qr_point_camera else R.string.plate_point_camera
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(stringResource(hint), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A9AA0))
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(360.dp).padding(vertical = 8.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = Color.White) }
            }
        }
    }
}

/** Backwards-compatible QR-only entry point. */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) =
    CameraScanDialog(ScanMode.QR, onResult, onDismiss)
