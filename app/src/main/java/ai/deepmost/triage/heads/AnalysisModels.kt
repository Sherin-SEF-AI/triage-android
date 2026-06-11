package ai.deepmost.triage.heads

import ai.deepmost.triage.config.StationSpec
import ai.deepmost.triage.cv.GrayImage
import ai.deepmost.triage.cv.Homography
import ai.deepmost.triage.cv.NormPoint
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.cv.RgbImage
import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.DriverAnnotation
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.LabelSource
import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.registry.ModelRegistry

/**
 * In-memory finding produced by a head, before diff classification and persistence. Carries
 * everything needed for explainability: zone, type, severity, confidence, region, engine.
 */
data class AnalysisFinding(
    val id: String,
    val inspectionId: String,
    val photoId: String?,
    val capturePoint: String,
    val head: FindingHead,
    val type: FindingType,
    val zone: String?,
    val severity: Float,
    val confidence: Float,
    val bbox: NormRect,
    val polygon: List<NormPoint>? = null,
    val engine: String,
    val diffStatus: DiffStatus = DiffStatus.UNMATCHED,
    val matchedFindingId: String? = null,
    val lowConfidence: Boolean = false,
    val trend: Float? = null,
    val labelSource: LabelSource = LabelSource.MODEL_ONLY,
    val createdAt: Long
) {
    fun toEntity(): FindingEntity = FindingEntity(
        id = id, inspectionId = inspectionId, photoId = photoId, capturePoint = capturePoint,
        head = head, type = type, zone = zone, severity = severity, confidence = confidence,
        bboxLeft = bbox.left, bboxTop = bbox.top, bboxRight = bbox.right, bboxBottom = bbox.bottom,
        polygonJson = polygon?.joinToString(";") { "${it.x},${it.y}" },
        engine = engine, diffStatus = diffStatus, matchedFindingId = matchedFindingId,
        driverAnnotation = DriverAnnotation.NONE, lowConfidence = lowConfidence, trend = trend,
        labelSource = labelSource, createdAt = createdAt
    )

    companion object {
        fun polygonFromJson(json: String?): List<NormPoint>? = json
            ?.split(";")?.mapNotNull {
                val parts = it.split(",")
                if (parts.size == 2) NormPoint(parts[0].toFloat(), parts[1].toFloat()) else null
            }

        fun fromEntity(e: FindingEntity): AnalysisFinding = AnalysisFinding(
            id = e.id, inspectionId = e.inspectionId, photoId = e.photoId, capturePoint = e.capturePoint,
            head = e.head, type = e.type, zone = e.zone, severity = e.severity, confidence = e.confidence,
            bbox = NormRect(e.bboxLeft, e.bboxTop, e.bboxRight, e.bboxBottom),
            polygon = polygonFromJson(e.polygonJson), engine = e.engine, diffStatus = e.diffStatus,
            matchedFindingId = e.matchedFindingId, lowConfidence = e.lowConfidence, trend = e.trend,
            labelSource = e.labelSource, createdAt = e.createdAt
        )
    }
}

/** Decoded reference for a station from the per-vehicle clean baseline. */
class BaselineReference(
    val rgb: RgbImage,
    val gray: GrayImage,
    val metricsJson: String
)

/**
 * The previous accepted walkaround's photo for this station, registered into the current frame
 * via [homography], plus its prior findings (used by the change-detector and the matcher).
 */
class RegisteredReference(
    val homography: Homography?,
    val inlierRatio: Float,
    val prevRgb: RgbImage,
    val prevGray: GrayImage,
    val prevFindings: List<AnalysisFinding>,
    val prevInspectionId: String,
    val minConfidentRatio: Float = DEFAULT_MIN_RATIO
) {
    val confident: Boolean get() = homography != null && inlierRatio >= minConfidentRatio
    companion object { const val DEFAULT_MIN_RATIO = 0.30f }
}

/** Everything a head needs to analyse one photo, all local and pre-decoded. */
class AnalysisContext(
    val inspectionId: String,
    val photoId: String?,
    val station: StationSpec,
    val rgb: RgbImage,
    val gray: GrayImage,
    val registry: ModelRegistry,
    val baseline: BaselineReference?,
    val registered: RegisteredReference?,
    val nowMillis: Long,
    val ambientDark: Boolean = false,
    val idGen: () -> String
)
