package ai.deepmost.triage.vehicle

import ai.deepmost.triage.config.StationConfigLoader
import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.data.BaselineRepository
import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.entity.BaselineEntity
import ai.deepmost.triage.heads.clean.CleanlinessHead
import ai.deepmost.triage.integrity.Hashing
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Supervisor "vehicle enrolled clean" baseline. Captured once when the vehicle is freshly
 * cleaned, per station, so all later cleanliness scoring is relative (robust to paint colour /
 * seat fabric). The reference zone metrics are pre-computed and cached at enrollment time.
 */
class BaselineService(
    private val baselineRepo: BaselineRepository,
    private val configLoader: StationConfigLoader,
    private val fileStore: FileStore
) {

    /** Enroll a single station baseline from a captured photo file. */
    suspend fun enroll(
        vehicleId: String,
        profileId: String,
        capturePoint: String,
        sourceFile: File,
        supervisorId: String
    ): Boolean {
        val profile = configLoader.load(profileId)
            ?: configLoader.load(StationConfigLoader.DEFAULT_PROFILE) ?: return false
        val station = profile.stations.firstOrNull { it.id == capturePoint } ?: return false

        val bmp = Images.decodeForAnalysis(sourceFile) ?: return false
        val rgb = Images.bitmapToRgb(bmp)
        val gray = rgb.toGray()
        val metricsJson = CleanlinessHead.computeBaselineMetrics(station, rgb, gray)

        // Persist a stable copy of the baseline image.
        val dest = fileStore.baselineFile(vehicleId, capturePoint)
        sourceFile.copyTo(dest, overwrite = true)
        val sha = Hashing.sha256File(dest)

        baselineRepo.upsert(
            BaselineEntity(
                id = UUID.randomUUID().toString(),
                vehicleId = vehicleId,
                capturePoint = capturePoint,
                photoPath = dest.absolutePath,
                sha256 = sha,
                supervisorId = supervisorId,
                metricsJson = metricsJson,
                createdAt = System.currentTimeMillis()
            )
        )
        Timber.i("Enrolled baseline for %s @ %s", vehicleId, capturePoint)
        return true
    }

    suspend fun enrolledStations(vehicleId: String): Set<String> =
        baselineRepo.byVehicle(vehicleId).map { it.capturePoint }.toSet()
}
