package ai.deepmost.triage.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Thin CameraX wrapper for guided photo capture (no ARCore — plain ImageCapture). Binds a
 * preview to the lifecycle and captures full-resolution stills to a file; the caller hashes and
 * quality-gates the result before accepting it.
 */
class CaptureController {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * Bind preview + still capture, plus an optional live RGBA analyzer (Smart-capture). The
     * analyzer runs on its own executor and is throttled internally.
     */
    suspend fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer? = null
    ) {
        val provider = awaitProvider(context)
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCapture = capture

        val analysis = analyzer?.let { a ->
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .build()
                .also { it.setAnalyzer(analysisExecutor, a) }
        }

        try {
            provider.unbindAll()
            val useCases = listOfNotNull(preview, capture, analysis).toTypedArray()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
        } catch (t: Throwable) {
            Timber.e(t, "Camera bind failed")
        }
    }

    /** Capture a full-resolution still to [outputFile]. Returns true on success. */
    suspend fun capture(context: Context, outputFile: File): Boolean {
        val capture = imageCapture ?: return false
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Timber.e(exception, "Capture failed")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            )
        }
    }

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        runCatching { analysisExecutor.shutdown() }
        imageCapture = null
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }
}
