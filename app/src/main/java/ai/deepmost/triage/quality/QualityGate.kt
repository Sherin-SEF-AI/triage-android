package ai.deepmost.triage.quality

import ai.deepmost.triage.config.StationKind
import ai.deepmost.triage.config.StationSpec
import ai.deepmost.triage.cv.Edges
import ai.deepmost.triage.cv.GrayImage
import ai.deepmost.triage.cv.Histogram
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.cv.RgbImage
import timber.log.Timber

/** Tunable quality thresholds (surfaced in Settings). */
data class QualityThresholds(
    val sharpnessMin: Float = 0.0011f,      // Laplacian variance on [0,1] luminance
    val exposureClipMax: Float = 0.38f,     // max fraction of clipped pixels
    val framingMin: Float = 0.060f,         // min edge density inside ghost region (classical)
    val framingIoUMin: Float = 0.45f,       // min IoU when a vehicle detector is available
    val brightnessMin: Float = 0.12f,       // interior scene brightness floor
    val brightnessMax: Float = 0.96f
)

enum class QualityIssue { TOO_BLURRY, OVEREXPOSED, UNDEREXPOSED, NO_VEHICLE_IN_FRAME, MISFRAMED, TOO_DARK, TOO_BRIGHT }

/** Per-photo quality verdict, stored with the photo and surfaced live during capture. */
data class QualityResult(
    val passed: Boolean,
    val issues: List<QualityIssue>,
    val sharpness: Float,
    val exposureClip: Float,
    val framingScore: Float,
    val brightness: Float
)

/**
 * The live, all-classical, all-local quality gate run before a shot is accepted. It blocks
 * blurry, badly-exposed or mis-framed photos so the downstream analysis and diff get usable
 * input. Interior stations skip the vehicle-framing test and gate on brightness + sharpness.
 */
class QualityGate(private val thresholds: () -> QualityThresholds) {

    /**
     * @param gray working-resolution grayscale of the shot
     * @param station station spec (provides the framing rect / kind)
     * @param vehicleBox optional vehicle bbox from a detector model (else classical framing)
     */
    fun evaluate(
        gray: GrayImage,
        station: StationSpec,
        vehicleBox: NormRect? = null
    ): QualityResult {
        val t = thresholds()
        val issues = ArrayList<QualityIssue>()

        val sharpness = Edges.laplacianVariance(gray)
        if (sharpness < t.sharpnessMin) issues.add(QualityIssue.TOO_BLURRY)

        val exp = Histogram.exposureStats(gray)
        if (exp.overExposedFraction > t.exposureClipMax) issues.add(QualityIssue.OVEREXPOSED)
        if (exp.underExposedFraction > t.exposureClipMax) issues.add(QualityIssue.UNDEREXPOSED)

        var framingScore: Float
        when (station.stationKind) {
            StationKind.INTERIOR -> {
                framingScore = exp.mean
                if (exp.mean < t.brightnessMin) issues.add(QualityIssue.TOO_DARK)
                if (exp.mean > t.brightnessMax) issues.add(QualityIssue.TOO_BRIGHT)
            }
            else -> {
                framingScore = if (vehicleBox != null && station.framing != null) {
                    val iou = NormRect.iou(vehicleBox, station.framing.toNorm())
                    if (iou < t.framingIoUMin) issues.add(QualityIssue.MISFRAMED)
                    iou
                } else {
                    val density = framingEdgeDensity(gray, station.framing?.toNorm())
                    if (density < t.framingMin) issues.add(QualityIssue.NO_VEHICLE_IN_FRAME)
                    density
                }
            }
        }

        val result = QualityResult(
            passed = issues.isEmpty(),
            issues = issues,
            sharpness = sharpness,
            exposureClip = maxOf(exp.overExposedFraction, exp.underExposedFraction),
            framingScore = framingScore,
            brightness = exp.mean
        )
        Timber.d(
            "Quality %s sharp=%.4f clip=%.2f frame=%.3f -> %s",
            station.id, sharpness, result.exposureClip, framingScore, if (result.passed) "PASS" else issues.toString()
        )
        return result
    }

    /**
     * Classical framing proxy: edge density inside the expected vehicle region. A correctly
     * framed body/tyre shot fills the ghost region with vehicle structure; an empty or stepped-back
     * frame leaves it sparse. Falls back to whole-image density if no framing rect is configured.
     */
    private fun framingEdgeDensity(gray: GrayImage, framing: NormRect?): Float {
        val region = framing ?: NormRect(0.1f, 0.1f, 0.9f, 0.9f)
        val crop = gray.crop(region)
        return Edges.edgeDensity(crop)
    }

    /** Convenience that also accepts an [RgbImage] (interior brightness uses luminance only). */
    fun evaluate(rgb: RgbImage, station: StationSpec, vehicleBox: NormRect? = null): QualityResult =
        evaluate(rgb.toGray(), station, vehicleBox)
}
