package ai.deepmost.triage.heads

import ai.deepmost.triage.config.StationConfigLoader
import ai.deepmost.triage.config.StationSpec
import ai.deepmost.triage.cv.GrayImage
import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.cv.RgbImage
import ai.deepmost.triage.data.BaselineRepository
import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import ai.deepmost.triage.diff.DiffEngine
import ai.deepmost.triage.diff.Registration
import ai.deepmost.triage.registry.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Coordinates per-photo analysis off the main thread: decode a working copy, build the baseline
 * and registered-previous references, run each applicable head sequentially (each isolated by
 * try/catch so one failed head never kills the inspection), diff the findings against the
 * previous walkaround, and persist. Photos are analysed with bounded parallelism (2), so the
 * driver never waits at the end — analysis keeps pace with capture.
 */
class AnalysisOrchestrator(
    private val heads: List<AnalysisHead>,
    private val diffEngine: DiffEngine,
    private val findingRepo: FindingRepository,
    private val inspectionRepo: InspectionRepository,
    private val baselineRepo: BaselineRepository,
    private val fileStore: FileStore,
    private val configLoader: StationConfigLoader,
    private val registry: ModelRegistry,
    private val registrationConfidence: () -> Float = { RegisteredReference.DEFAULT_MIN_RATIO },
    private val ambientDarkBrightness: Float = 0.18f
) {
    private val semaphore = Semaphore(2)

    suspend fun analyzeInspection(inspectionId: String) {
        val inspection = inspectionRepo.byId(inspectionId) ?: return
        val photos = inspectionRepo.photos(inspectionId).filter { it.qualityPassed }
        coroutineScope {
            for (photo in photos) launch(Dispatchers.Default) {
                semaphore.withPermit { runCatching { analyzePhoto(inspection, photo) }
                    .onFailure { Timber.e(it, "Photo analysis failed %s", photo.id) } }
            }
        }
    }

    /** Full per-photo pipeline: references -> heads -> diff -> persist. Safe to re-run (idempotent). */
    suspend fun analyzePhoto(inspection: InspectionEntity, photo: PhotoEntity) = withContext(Dispatchers.Default) {
        val profile = configLoader.load(inspection.profileId)
            ?: configLoader.load(StationConfigLoader.DEFAULT_PROFILE) ?: return@withContext
        val station = profile.stations.firstOrNull { it.id == photo.capturePoint } ?: return@withContext

        val workingBmp = Images.decodeForAnalysis(File(photo.filePath)) ?: run {
            Timber.w("Cannot decode photo %s for analysis", photo.id); return@withContext
        }
        val rgb = Images.bitmapToRgb(workingBmp)
        val gray = rgb.toGray()

        val baseline = loadBaseline(inspection.vehicleId, photo.capturePoint)
        val registered = loadRegistered(inspection, photo.capturePoint, gray)
        val ambientDark = photo.brightness < ambientDarkBrightness

        val ctx = AnalysisContext(
            inspectionId = inspection.id, photoId = photo.id, station = station,
            rgb = rgb, gray = gray, registry = registry, baseline = baseline,
            registered = registered, nowMillis = photo.capturedAtDevice, ambientDark = ambientDark,
            idGen = { UUID.randomUUID().toString() }
        )

        val current = ArrayList<AnalysisFinding>()
        for (head in heads) {
            if (!head.appliesTo(ctx)) continue
            val started = System.nanoTime()
            try {
                val produced = head.analyze(ctx)
                current.addAll(produced)
                val ms = (System.nanoTime() - started) / 1_000_000
                Timber.i("Head %s on %s: %d findings in %dms (engine=%s)",
                    head.id, station.id, produced.size, ms,
                    registry.engineStatus(head.id.name).tag)
            } catch (t: Throwable) {
                Timber.e(t, "Head %s FAILED on %s", head.id, station.id)
            }
        }

        val outcome = diffEngine.diff(current, registered)

        // Replace any prior auto-findings for this station (idempotent re-analysis). Manual,
        // driver-added findings use head=MANUAL and are never cleared here.
        for (head in heads) {
            findingRepo.clearForStationHead(inspection.id, photo.capturePoint, head.id.name)
        }
        val entities = (outcome.current + outcome.resolved).map { it.toEntity() }
        findingRepo.upsertAll(entities)
    }

    private suspend fun loadBaseline(vehicleId: String, capturePoint: String): BaselineReference? {
        val b = baselineRepo.byVehicleAndPoint(vehicleId, capturePoint) ?: return null
        val file = File(b.photoPath)
        if (!file.exists()) return null
        val bmp = Images.decodeForAnalysis(file) ?: return null
        val rgb = Images.bitmapToRgb(bmp)
        return BaselineReference(rgb, rgb.toGray(), b.metricsJson)
    }

    private suspend fun loadRegistered(
        inspection: InspectionEntity, capturePoint: String, currentGray: GrayImage
    ): RegisteredReference? {
        val prev = inspectionRepo.previousFinalized(inspection.vehicleId, inspection.id) ?: return null
        val prevPhoto = inspectionRepo.photoAt(prev.id, capturePoint) ?: return null
        if (prevPhoto.evicted) return null
        val prevFile = File(prevPhoto.filePath)
        if (!prevFile.exists()) return null
        val prevBmp = Images.decodeForAnalysis(prevFile) ?: return null
        val prevRgb = Images.bitmapToRgb(prevBmp)
        val prevGray = prevRgb.toGray()

        val regResult = Registration.register(currentGray, prevGray)
        val prevFindings = findingRepo.byInspection(prev.id)
            .filter { it.capturePoint == capturePoint && it.diffStatus != ai.deepmost.triage.data.DiffStatus.RESOLVED }
            .map { AnalysisFinding.fromEntity(it) }

        return RegisteredReference(
            homography = regResult.homography,
            inlierRatio = regResult.inlierRatio,
            prevRgb = prevRgb, prevGray = prevGray,
            prevFindings = prevFindings, prevInspectionId = prev.id,
            minConfidentRatio = registrationConfidence()
        )
    }
}
