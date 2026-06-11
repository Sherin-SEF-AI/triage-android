package ai.deepmost.triage.inspection

import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.InspectionStatus
import ai.deepmost.triage.data.InspectionType
import ai.deepmost.triage.data.VehicleRepository
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import ai.deepmost.triage.integrity.HashChain
import ai.deepmost.triage.integrity.Hashing
import ai.deepmost.triage.integrity.Manifest
import ai.deepmost.triage.integrity.SignatureStore
import ai.deepmost.triage.quality.QualityResult
import android.os.SystemClock
import timber.log.Timber
import java.io.File
import java.util.UUID

/** Optional geo-stamp captured with an inspection / photo. */
data class LocationStamp(val lat: Double?, val lon: Double?, val gpsTimeMillis: Long?)

/** Per-photo capture record passed in from the capture pipeline (file already written + hashed). */
data class CapturedPhoto(
    val capturePoint: String,
    val file: File,
    val sha256: String,
    val width: Int,
    val height: Int,
    val quality: QualityResult,
    val location: LocationStamp?
)

/** Attribution window for a NEW finding: which two shifts it appeared between. */
data class Attribution(
    val previousInspectionId: String?,
    val previousDriverId: String?,
    val previousFinalizedAt: Long?,
    val currentInspectionId: String,
    val currentDriverId: String,
    val currentStartedAt: Long
)

/**
 * Owns inspection lifecycle: create (with device + monotonic + optional GPS time), append
 * accepted photos (persisted immediately so the walkaround is process-death safe), and finalize
 * — which writes the signature, links the per-vehicle hash chain, computes the manifest hash and
 * flips the record immutable. Corrections are appended as NEW linked inspections, never edits.
 */
class InspectionService(
    private val inspectionRepo: InspectionRepository,
    private val findingRepo: FindingRepository,
    private val vehicleRepo: VehicleRepository,
    private val hashChain: HashChain,
    private val signatureStore: SignatureStore
) {

    suspend fun create(
        vehicleId: String,
        driverId: String,
        type: InspectionType,
        profileId: String,
        location: LocationStamp?
    ): InspectionEntity {
        val entity = InspectionEntity(
            id = UUID.randomUUID().toString(),
            vehicleId = vehicleId,
            driverId = driverId,
            type = type,
            status = InspectionStatus.IN_PROGRESS,
            profileId = profileId,
            startedAtDevice = System.currentTimeMillis(),
            startedAtElapsed = SystemClock.elapsedRealtime(),
            gpsTimeMillis = location?.gpsTimeMillis,
            lat = location?.lat,
            lon = location?.lon
        )
        inspectionRepo.upsert(entity)
        Timber.i("Created inspection %s (%s) for vehicle %s", entity.id, type, vehicleId)
        return entity
    }

    /** Persist an accepted photo immediately (resumability). Returns the stored entity. */
    suspend fun addPhoto(inspectionId: String, captured: CapturedPhoto): PhotoEntity {
        val photo = PhotoEntity(
            id = UUID.randomUUID().toString(),
            inspectionId = inspectionId,
            capturePoint = captured.capturePoint,
            filePath = captured.file.absolutePath,
            sha256 = captured.sha256,
            capturedAtDevice = System.currentTimeMillis(),
            capturedAtElapsed = SystemClock.elapsedRealtime(),
            gpsTimeMillis = captured.location?.gpsTimeMillis,
            lat = captured.location?.lat,
            lon = captured.location?.lon,
            width = captured.width,
            height = captured.height,
            sharpness = captured.quality.sharpness,
            exposureClip = captured.quality.exposureClip,
            framingScore = captured.quality.framingScore,
            brightness = captured.quality.brightness,
            qualityPassed = captured.quality.passed
        )
        inspectionRepo.upsertPhoto(photo)
        return photo
    }

    suspend fun markAnalyzed(inspectionId: String) {
        val insp = inspectionRepo.byId(inspectionId) ?: return
        if (insp.status == InspectionStatus.IN_PROGRESS) {
            inspectionRepo.update(insp.copy(status = InspectionStatus.ANALYZED))
        }
    }

    /**
     * Finalize: write signature, link the chain, compute the manifest hash, persist immutable.
     * @return the finalized inspection with its manifestHash, or null if already finalized.
     */
    suspend fun finalize(
        inspectionId: String,
        signaturePng: ByteArray,
        markDisputed: Boolean = false
    ): InspectionEntity? {
        val current = inspectionRepo.byId(inspectionId) ?: return null
        if (current.status == InspectionStatus.FINALIZED) {
            Timber.w("Inspection %s already finalized; ignoring", inspectionId)
            return current
        }
        val signaturePath = signatureStore.saveSignature(inspectionId, signaturePng)
        val signatureSha = Hashing.sha256(signaturePng)
        val (prevId, prevHash) = hashChain.previousLink(current.vehicleId, inspectionId)

        val photos = inspectionRepo.photos(inspectionId)
        val findings = findingRepo.byInspection(inspectionId)

        // INCIDENT records, an explicit dispute flag, or any driver-disputed finding route the
        // record to the supervisor dispute queue; otherwise it finalizes clean.
        val disputed = markDisputed ||
            current.type == InspectionType.INCIDENT ||
            findings.any { it.driverAnnotation == ai.deepmost.triage.data.DriverAnnotation.DISPUTED }

        val staged = current.copy(
            status = if (disputed) InspectionStatus.DISPUTED else InspectionStatus.FINALIZED,
            finalizedAtDevice = System.currentTimeMillis(),
            signaturePath = signaturePath,
            prevInspectionId = prevId,
            prevHash = prevHash
        )
        val manifest = Manifest.build(staged, photos, findings, signatureSha)
        val manifestString = Manifest.canonicalString(manifest)
        val manifestHash = Hashing.sha256(manifestString)

        // Device-bound HMAC over the manifest (sidecar; supplementary to the SHA-256 chain).
        signatureStore.deviceSign(manifestString)?.let { hmac ->
            runCatching { File(signaturePath + ".hmac").writeText(hmac) }
        }

        val finalized = staged.copy(manifestHash = manifestHash)
        inspectionRepo.update(finalized)
        vehicleRepo.updateChainHead(current.vehicleId, manifestHash)
        Timber.i("Finalized inspection %s manifestHash=%s prev=%s", inspectionId, manifestHash, prevHash)
        return finalized
    }

    /** Compute the responsible-shift window for the current inspection's NEW findings. */
    suspend fun attribution(inspectionId: String): Attribution? {
        val current = inspectionRepo.byId(inspectionId) ?: return null
        val prev = inspectionRepo.previousFinalized(current.vehicleId, inspectionId)
        return Attribution(
            previousInspectionId = prev?.id,
            previousDriverId = prev?.driverId,
            previousFinalizedAt = prev?.finalizedAtDevice,
            currentInspectionId = current.id,
            currentDriverId = current.driverId,
            currentStartedAt = current.startedAtDevice
        )
    }
}
