package ai.deepmost.triage.capture

import ai.deepmost.triage.config.StationKind
import ai.deepmost.triage.config.StationSpec
import ai.deepmost.triage.cv.Edges
import ai.deepmost.triage.cv.Histogram
import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.registry.ModelRegistry
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import timber.log.Timber

/** Directional capture guidance derived from the live preview. */
enum class LiveGuidance { NONE, DARK, BLURRY, MOVE_LEFT, MOVE_RIGHT, MOVE_CLOSER, STEADY, GOOD }

/** Snapshot of the live (pre-shot) frame analysis, surfaced on the capture screen. */
data class LiveFrameState(
    val framing: Float = 0f,        // 0..1 readiness (IoU vs ghost, or edge density)
    val sharpnessOk: Boolean = false,
    val exposureOk: Boolean = false,
    val vehicleBox: NormRect? = null,
    val guidance: LiveGuidance = LiveGuidance.NONE,
    val ready: Boolean = false       // good enough to auto-capture
)

/**
 * CameraX live-preview analyzer. Throttled to a few fps, it converts the latest RGBA frame to a
 * small bitmap and computes sharpness, exposure, a live vehicle box (when the detector model is
 * installed) and framing readiness vs the current station's ghost — purely a pre-shot hint. The
 * authoritative quality gate still runs on the full captured still.
 */
class LiveAnalyzer(
    private val stationProvider: () -> StationSpec?,
    private val registry: ModelRegistry,
    private val sharpnessMin: () -> Float,
    private val onResult: (LiveFrameState) -> Unit,
    private val minIntervalMs: Long = 220L
) : ImageAnalysis.Analyzer {

    @Volatile private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastRun < minIntervalMs) { image.close(); return }
        lastRun = now
        try {
            val station = stationProvider.invoke() ?: run { image.close(); return }
            val bmp = image.toRgbaBitmap() ?: run { image.close(); return }
            val small = Images.downscale(bmp, 320)
            val rgb = Images.bitmapToRgb(small)
            val gray = rgb.toGray()

            val sharp = Edges.laplacianVariance(gray)
            val sharpnessOk = sharp >= sharpnessMin.invoke()
            val exp = Histogram.exposureStats(gray)
            val exposureOk = exp.mean in 0.12f..0.96f && exp.clippingFraction < 0.5f

            var box: NormRect? = null
            var framing: Float
            if (station.stationKind == StationKind.INTERIOR) {
                framing = exp.mean.coerceIn(0f, 1f)
            } else {
                box = detectVehicle(small, station)
                framing = if (box != null && station.framing != null) {
                    NormRect.iou(box, station.framing.toNorm())
                } else {
                    Edges.edgeDensity(gray.crop(station.framing?.toNorm() ?: NormRect(0.1f, 0.1f, 0.9f, 0.9f)))
                }
            }

            onResult(buildState(station, framing, sharpnessOk, exposureOk, box, exp.mean))
        } catch (t: Throwable) {
            Timber.w(t, "Live analyze failed")
        } finally {
            image.close()
        }
    }

    private fun buildState(
        station: StationSpec, framing: Float, sharpOk: Boolean, expOk: Boolean,
        box: NormRect?, meanLum: Float
    ): LiveFrameState {
        val framingGood = framing >= if (box != null) 0.45f else 0.06f
        val guidance = when {
            meanLum < 0.12f -> LiveGuidance.DARK
            !sharpOk -> LiveGuidance.BLURRY
            box != null && station.framing != null -> {
                val ghost = station.framing.toNorm()
                when {
                    framingGood -> LiveGuidance.GOOD
                    box.centerX < ghost.centerX - 0.12f -> LiveGuidance.MOVE_RIGHT
                    box.centerX > ghost.centerX + 0.12f -> LiveGuidance.MOVE_LEFT
                    box.area < ghost.area * 0.6f -> LiveGuidance.MOVE_CLOSER
                    else -> LiveGuidance.STEADY
                }
            }
            framingGood -> LiveGuidance.GOOD
            else -> LiveGuidance.STEADY
        }
        val ready = framingGood && sharpOk && expOk
        return LiveFrameState(framing.coerceIn(0f, 1f), sharpOk, expOk, box, guidance, ready)
    }

    private fun detectVehicle(bmp: Bitmap, station: StationSpec): NormRect? {
        val handle = registry.handleFor("VEHICLE_DETECTOR") ?: return null
        return try {
            val dets = handle.detect(bmp, scoreThreshold = 0.35f) ?: return null
            dets.filter { it.label in VEHICLE_LABELS }.maxByOrNull { it.score }?.rect
        } catch (t: Throwable) { null }
    }

    companion object {
        private val VEHICLE_LABELS = setOf("car", "truck", "bus", "motorcycle")
    }
}

/** Convert an RGBA_8888 ImageProxy to a Bitmap (accounting for row stride). */
fun ImageProxy.toRgbaBitmap(): Bitmap? {
    return try {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
    } catch (t: Throwable) {
        Timber.w(t, "RGBA->bitmap failed"); null
    }
}
