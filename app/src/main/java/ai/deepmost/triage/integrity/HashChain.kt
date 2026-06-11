package ai.deepmost.triage.integrity

import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.InspectionStatus
import ai.deepmost.triage.data.entity.InspectionEntity
import timber.log.Timber
import java.io.File

/** Outcome for a single record in a vehicle's chain. */
data class LinkResult(
    val inspectionId: String,
    val manifestValid: Boolean,
    val photosValid: Boolean,
    val linkValid: Boolean,
    val notes: String
) {
    val ok get() = manifestValid && photosValid && linkValid
}

data class ChainVerification(val vehicleId: String, val links: List<LinkResult>) {
    val valid get() = links.all { it.ok }
    val brokenCount get() = links.count { !it.ok }
}

/**
 * Builds and verifies the per-vehicle hash chain. Each finalized record stores its
 * predecessor's manifestHash; verification re-derives every manifest from the stored data,
 * re-hashes the original photo files (where not yet evicted) and walks the chain to confirm
 * nothing was altered after finalization.
 */
class HashChain(
    private val inspections: InspectionRepository,
    private val findings: FindingRepository,
    private val fileStore: FileStore,
    private val signatureStore: SignatureStore
) {

    suspend fun verifyVehicle(vehicleId: String): ChainVerification {
        val finalized = inspections.byVehicle(vehicleId)
            .filter { it.status == InspectionStatus.FINALIZED }
            .sortedBy { it.finalizedAtDevice ?: it.startedAtDevice }

        val results = ArrayList<LinkResult>(finalized.size)
        var expectedPrevHash: String? = null
        var expectedPrevId: String? = null

        for (insp in finalized) {
            val photos = inspections.photos(insp.id)
            val findingList = findings.byInspection(insp.id)
            val sigSha = signatureStore.signatureSha(insp.id)

            val manifest = Manifest.build(insp, photos, findingList, sigSha)
            val recomputed = Manifest.hash(manifest)
            val manifestValid = recomputed == insp.manifestHash

            // Re-hash original photo payloads that have not been evicted.
            var photosValid = true
            val tamperedPhotos = StringBuilder()
            for (p in photos) {
                if (p.evicted) continue
                val f = File(p.filePath)
                if (!f.exists()) { photosValid = false; tamperedPhotos.append(p.capturePoint).append("(missing) "); continue }
                val actual = Hashing.sha256File(f)
                if (actual != p.sha256) { photosValid = false; tamperedPhotos.append(p.capturePoint).append(" ") }
            }

            val linkValid = insp.prevHash == (expectedPrevHash ?: "") &&
                (insp.prevInspectionId ?: "") == (expectedPrevId ?: "")

            val notes = buildString {
                if (!manifestValid) append("manifest altered; ")
                if (!photosValid) append("photo tampered: $tamperedPhotos; ")
                if (!linkValid) append("chain link mismatch; ")
                if (isEmpty()) append("ok")
            }
            results.add(LinkResult(insp.id, manifestValid, photosValid, linkValid, notes.trim()))
            Timber.i("Chain verify %s -> %s", insp.id, notes)

            expectedPrevHash = insp.manifestHash
            expectedPrevId = insp.id
        }
        return ChainVerification(vehicleId, results)
    }

    /** Compute the next record's prev-link fields for a vehicle (call before finalizing). */
    suspend fun previousLink(vehicleId: String, excludeInspectionId: String): Pair<String?, String?> {
        val prev = inspections.previousFinalized(vehicleId, excludeInspectionId)
        return prev?.let { it.id to it.manifestHash }
            ?: (null to null)
    }
}
