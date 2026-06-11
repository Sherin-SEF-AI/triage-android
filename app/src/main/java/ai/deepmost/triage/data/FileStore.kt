package ai.deepmost.triage.data

import android.content.Context
import java.io.File

/**
 * Owns the on-disk layout for inspection photos, baselines, signatures and exports. Originals
 * are written full-resolution and never modified; analysis always works on decoded copies.
 */
class FileStore(context: Context) {
    private val root: File = context.filesDir

    val inspectionsDir = File(root, "inspections").apply { mkdirs() }
    val baselinesDir = File(root, "baselines").apply { mkdirs() }
    val signaturesDir = File(root, "signatures").apply { mkdirs() }
    val exportsDir = File(root, "exports").apply { mkdirs() }
    val modelsDir = File(root, "models").apply { mkdirs() }

    fun inspectionDir(inspectionId: String): File =
        File(inspectionsDir, inspectionId).apply { mkdirs() }

    fun photoFile(inspectionId: String, photoId: String): File =
        File(inspectionDir(inspectionId), "$photoId.jpg")

    fun signatureFile(inspectionId: String): File =
        File(signaturesDir, "$inspectionId.png")

    fun baselineFile(vehicleId: String, capturePoint: String): File =
        File(baselinesDir, "${vehicleId}_$capturePoint.jpg").apply { parentFile?.mkdirs() }

    fun exportFile(name: String): File = File(exportsDir, name)

    /** Total bytes used by stored photo payloads (excludes manifests/hashes which live in the DB). */
    fun photoBytes(): Long {
        var total = 0L
        inspectionsDir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        baselinesDir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
