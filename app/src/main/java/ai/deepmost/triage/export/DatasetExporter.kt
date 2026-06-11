package ai.deepmost.triage.export

import ai.deepmost.triage.data.DriverAnnotation
import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.LabelSource
import ai.deepmost.triage.data.VehicleRepository
import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import ai.deepmost.triage.integrity.Manifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a finalized inspection (or a bulk set) as a ZIP of the original photos plus a
 * findings.jsonl — one JSON object per finding — that is a ready-to-train labeled
 * vehicle-damage dataset. Driver confirm/dispute outcomes ARE the human labels and are recorded
 * with an explicit label_source.
 */
class DatasetExporter(
    private val inspectionRepo: InspectionRepository,
    private val findingRepo: FindingRepository,
    private val vehicleRepo: VehicleRepository,
    private val fileStore: FileStore
) {
    private val json = Json { prettyPrint = false }

    suspend fun exportInspection(inspectionId: String, redactPlates: Boolean = false): File? {
        val inspection = inspectionRepo.byId(inspectionId) ?: return null
        val zip = fileStore.exportFile("inspection_${inspectionId}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            writeInspection(out, inspection, prefix = "", redactPlates = redactPlates)
        }
        Timber.i("Exported inspection %s -> %s (redact=%b)", inspectionId, zip.name, redactPlates)
        return zip
    }

    suspend fun exportBulk(inspectionIds: List<String>, redactPlates: Boolean = false): File? {
        if (inspectionIds.isEmpty()) return null
        val zip = fileStore.exportFile("triage_bulk_${inspectionIds.size}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            for (id in inspectionIds) {
                val inspection = inspectionRepo.byId(id) ?: continue
                writeInspection(out, inspection, prefix = "$id/", redactPlates = redactPlates)
            }
        }
        return zip
    }

    private suspend fun writeInspection(
        out: ZipOutputStream, inspection: InspectionEntity, prefix: String, redactPlates: Boolean
    ) {
        val photos = inspectionRepo.photos(inspection.id)
        val findings = findingRepo.byInspection(inspection.id)
        val vehicle = vehicleRepo.byId(inspection.vehicleId)
        val photoById = photos.associateBy { it.id }

        // Photos (skip evicted payloads). With redaction on, number plates are blacked on the
        // EXPORT COPY only; stored originals + their hashes are untouched.
        for (p in photos) {
            val f = File(p.filePath)
            if (p.evicted || !f.exists()) continue
            out.putNextEntry(ZipEntry("${prefix}photos/${p.capturePoint}_${p.id}.jpg"))
            out.write(Redaction.exportBytes(f, redactPlates))
            out.closeEntry()
        }

        // findings.jsonl
        out.putNextEntry(ZipEntry("${prefix}findings.jsonl"))
        for (finding in findings) {
            val line = json.encodeToString(JsonObject.serializer(), findingJson(finding, inspection, photoById[finding.photoId], vehicle?.model ?: ""))
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
        }
        out.closeEntry()

        // Canonical manifest + integrity summary.
        out.putNextEntry(ZipEntry("${prefix}manifest.json"))
        val manifest = Manifest.build(inspection, photos, findings, null)
        out.write(Manifest.canonicalString(manifest).toByteArray(Charsets.UTF_8))
        out.closeEntry()

        // Signature, if present.
        inspection.signaturePath?.let { sp ->
            val sig = File(sp)
            if (sig.exists()) {
                out.putNextEntry(ZipEntry("${prefix}signature.png"))
                sig.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    private fun findingJson(
        f: FindingEntity, inspection: InspectionEntity, photo: PhotoEntity?, vehicleModel: String
    ): JsonObject = buildJsonObject {
        put("finding_id", f.id)
        put("image_file", photo?.let { "photos/${it.capturePoint}_${it.id}.jpg" } ?: "")
        put("capture_point", f.capturePoint)
        put("head", f.head.name)
        put("type", f.type.name)
        put("zone", f.zone ?: "")
        put("bbox", "${f.bboxLeft},${f.bboxTop},${f.bboxRight},${f.bboxBottom}")
        put("polygon", f.polygonJson ?: "")
        put("severity", f.severity)
        put("confidence", f.confidence)
        put("engine", f.engine)
        put("diff_status", f.diffStatus.name)
        put("low_confidence", f.lowConfidence)
        put("trend", f.trend?.toString() ?: "")
        put("driver_annotation", f.driverAnnotation.name)
        put("driver_note", f.driverNote ?: "")
        put("label_source", labelSource(f).name.lowercase())
        put("vehicle_id", inspection.vehicleId)
        put("vehicle_model", vehicleModel)
        put("inspection_id", inspection.id)
        put("inspection_type", inspection.type.name)
        put("captured_at_device", photo?.capturedAtDevice ?: inspection.startedAtDevice)
    }

    /** Driver outcomes become the dataset's human labels. */
    private fun labelSource(f: FindingEntity): LabelSource = when {
        f.head == FindingHead.MANUAL -> LabelSource.MANUAL
        f.driverAnnotation == DriverAnnotation.CONFIRMED -> LabelSource.DRIVER_CONFIRMED
        f.driverAnnotation == DriverAnnotation.DISPUTED -> LabelSource.DRIVER_DISPUTED
        else -> LabelSource.MODEL_ONLY
    }
}
