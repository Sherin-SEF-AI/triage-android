package ai.deepmost.triage.integrity

import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a canonical, stable-ordered manifest of a finalized inspection and computes its
 * SHA-256 (the record's tamper-evident fingerprint). The manifest deliberately includes the
 * previous record's hash so a per-vehicle chain forms. The JSON is built with explicit,
 * sorted ordering so re-hashing the same data always yields the same digest.
 */
object Manifest {

    private val json = Json { prettyPrint = false }

    fun build(
        inspection: InspectionEntity,
        photos: List<PhotoEntity>,
        findings: List<FindingEntity>,
        signatureSha256: String?
    ): JsonObject {
        val sortedPhotos = photos.sortedBy { it.capturePoint }
        val sortedFindings = findings.sortedBy { it.id }
        return buildJsonObject {
            put("schemaVersion", 1)
            put("inspectionId", inspection.id)
            put("vehicleId", inspection.vehicleId)
            put("driverId", inspection.driverId)
            put("type", inspection.type.name)
            put("profileId", inspection.profileId)
            put("startedAtDevice", inspection.startedAtDevice)
            put("startedAtElapsed", inspection.startedAtElapsed)
            put("finalizedAtDevice", inspection.finalizedAtDevice ?: 0L)
            put("gpsTimeMillis", inspection.gpsTimeMillis ?: 0L)
            put("lat", inspection.lat ?: Double.NaN.let { 0.0 })
            put("lon", inspection.lon ?: 0.0)
            put("prevInspectionId", inspection.prevInspectionId ?: "")
            put("prevHash", inspection.prevHash ?: "")
            put("correctsInspectionId", inspection.correctsInspectionId ?: "")
            put("signatureSha256", signatureSha256 ?: "")
            put("photos", buildPhotos(sortedPhotos))
            put("findings", buildFindings(sortedFindings))
        }
    }

    private fun buildPhotos(photos: List<PhotoEntity>): JsonArray = buildJsonArray {
        for (p in photos) add(buildJsonObject {
            put("capturePoint", p.capturePoint)
            put("sha256", p.sha256)
            put("capturedAtDevice", p.capturedAtDevice)
            put("capturedAtElapsed", p.capturedAtElapsed)
            put("width", p.width)
            put("height", p.height)
            put("qualityPassed", p.qualityPassed)
            put("sharpness", p.sharpness.toString())
            put("exposureClip", p.exposureClip.toString())
            put("framingScore", p.framingScore.toString())
        })
    }

    private fun buildFindings(findings: List<FindingEntity>): JsonArray = buildJsonArray {
        for (f in findings) add(buildJsonObject {
            put("id", f.id)
            put("capturePoint", f.capturePoint)
            put("head", f.head.name)
            put("type", f.type.name)
            put("zone", f.zone ?: "")
            put("severity", f.severity.toString())
            put("confidence", f.confidence.toString())
            put("bbox", "${f.bboxLeft},${f.bboxTop},${f.bboxRight},${f.bboxBottom}")
            put("engine", f.engine)
            put("diffStatus", f.diffStatus.name)
            put("matchedFindingId", f.matchedFindingId ?: "")
            put("driverAnnotation", f.driverAnnotation.name)
            put("lowConfidence", f.lowConfidence)
        })
    }

    fun canonicalString(manifest: JsonObject): String = json.encodeToString(JsonObject.serializer(), manifest)

    fun hash(manifest: JsonObject): String = Hashing.sha256(canonicalString(manifest))
}
