package ai.deepmost.triage.capture

import ai.deepmost.triage.config.StationConfigLoader
import ai.deepmost.triage.config.StationSpec
import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.inspection.CapturedPhoto
import ai.deepmost.triage.integrity.Hashing
import ai.deepmost.triage.quality.QualityResult
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID

/** UI state for the guided capture screen. */
data class CaptureUiState(
    val loading: Boolean = true,
    val profileName: String = "",
    val stations: List<StationSpec> = emptyList(),
    val currentIndex: Int = 0,
    val capturedPoints: Set<String> = emptySet(),
    val analyzingPoints: Set<String> = emptySet(),
    val capturing: Boolean = false,
    val lastVerdict: QualityResult? = null,
    val flashAccept: Boolean = false,
    val complete: Boolean = false
) {
    val currentStation: StationSpec? get() = stations.getOrNull(currentIndex)
    val progress: Float get() = if (stations.isEmpty()) 0f else capturedPoints.size.toFloat() / stations.size
}

/**
 * Drives the guided 12-station walkaround: capture -> quality gate -> hash -> persist -> analyze.
 * State is reconstructed from the DB so the walkaround is process-death safe and resumable, and
 * analysis runs in the background per photo so the driver never waits at the end.
 */
class CaptureViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val _liveState = MutableStateFlow(LiveFrameState())
    val liveState: StateFlow<LiveFrameState> = _liveState.asStateFlow()

    /** Build the live preview analyzer for Smart-capture (framing meter, vehicle box, readiness). */
    fun analyzer(): androidx.camera.core.ImageAnalysis.Analyzer = LiveAnalyzer(
        stationProvider = { _state.value.currentStation },
        registry = container.modelRegistry,
        sharpnessMin = { container.currentSettings.sharpnessMin },
        onResult = { _liveState.value = it }
    )

    private lateinit var inspectionId: String
    private var isBaselineEnrollment = false
    private var enrollVehicleId: String? = null
    private var enrollProfileId: String = "xprest"

    fun start(inspectionId: String) {
        this.inspectionId = inspectionId
        viewModelScope.launch {
            val inspection = container.inspectionRepository.byId(inspectionId) ?: return@launch
            isBaselineEnrollment = inspection.type == ai.deepmost.triage.data.InspectionType.BASELINE
            enrollVehicleId = inspection.vehicleId
            enrollProfileId = inspection.profileId
            val profile = container.configLoader.load(inspection.profileId)
                ?: container.configLoader.load(StationConfigLoader.DEFAULT_PROFILE) ?: return@launch
            val existing = container.inspectionRepository.photos(inspectionId)
                .filter { it.qualityPassed }.map { it.capturePoint }.toSet()
            val firstMissing = profile.stations.indexOfFirst { it.id !in existing }.let { if (it < 0) profile.stations.lastIndex else it }
            _state.update {
                it.copy(
                    loading = false,
                    profileName = profile.displayName,
                    stations = profile.stations,
                    capturedPoints = existing,
                    currentIndex = firstMissing,
                    complete = existing.size >= profile.stations.size
                )
            }
        }
    }

    fun selectStation(index: Int) {
        _state.update { it.copy(currentIndex = index.coerceIn(0, it.stations.lastIndex), lastVerdict = null) }
    }

    /**
     * Capture-time tap-to-flag: record a driver-confirmed manual finding for the current station
     * (e.g. a rear tyre issue spotted within the rear ¾ framing). It is first-class data exhaust
     * and is refined later on the Review screen.
     */
    fun flagIssue() {
        val station = _state.value.currentStation ?: return
        if (station.id !in _state.value.capturedPoints) return
        viewModelScope.launch {
            val photo = container.inspectionRepository.photoAt(inspectionId, station.id)
            container.findingRepository.upsert(
                ai.deepmost.triage.data.entity.FindingEntity(
                    id = UUID.randomUUID().toString(), inspectionId = inspectionId,
                    photoId = photo?.id, capturePoint = station.id,
                    head = ai.deepmost.triage.data.FindingHead.MANUAL,
                    type = ai.deepmost.triage.data.FindingType.MANUAL_NOTE, zone = "flagged",
                    severity = 0.5f, confidence = 1f,
                    bboxLeft = 0.3f, bboxTop = 0.3f, bboxRight = 0.7f, bboxBottom = 0.7f,
                    engine = "manual", diffStatus = ai.deepmost.triage.data.DiffStatus.NEW,
                    driverAnnotation = ai.deepmost.triage.data.DriverAnnotation.CONFIRMED,
                    labelSource = ai.deepmost.triage.data.LabelSource.MANUAL,
                    createdAt = System.currentTimeMillis()
                )
            )
            Timber.i("Driver flagged issue at %s", station.id)
        }
    }

    fun clearFlash() { _state.update { it.copy(flashAccept = false) } }

    fun capture(context: Context, controller: CaptureController) {
        val st = _state.value
        val station = st.currentStation ?: return
        if (st.capturing) return
        _state.update { it.copy(capturing = true, lastVerdict = null) }
        viewModelScope.launch {
            val dest = File(container.fileStore.inspectionDir(inspectionId), "${UUID.randomUUID()}.jpg")
            val ok = controller.capture(context, dest)
            if (!ok) {
                dest.delete()
                _state.update { it.copy(capturing = false) }
                return@launch
            }
            val verdict = withContext(Dispatchers.Default) { gate(dest, station) }
            if (verdict == null) {
                dest.delete()
                _state.update { it.copy(capturing = false) }
                return@launch
            }
            if (!verdict.passed) {
                dest.delete()
                _state.update { it.copy(capturing = false, lastVerdict = verdict) }
                return@launch
            }
            acceptPhoto(dest, station, verdict)
        }
    }

    private suspend fun gate(file: File, station: StationSpec): QualityResult? {
        val bmp = Images.decodeForAnalysis(file) ?: return null
        val gray = Images.bitmapToRgb(bmp).toGray()
        // If a vehicle detector model is installed, use a real vehicle bbox for framing IoU;
        // otherwise the gate falls back to the classical edge-density-in-ghost heuristic.
        val vehicleBox = detectVehicleBox(bmp, station)
        return container.qualityGate.evaluate(gray, station, vehicleBox)
    }

    private fun detectVehicleBox(
        bmp: android.graphics.Bitmap, station: StationSpec
    ): ai.deepmost.triage.cv.NormRect? {
        if (station.stationKind == ai.deepmost.triage.config.StationKind.INTERIOR) return null
        val handle = container.modelRegistry.handleFor("VEHICLE_DETECTOR") ?: return null
        return try {
            val dets = handle.detect(bmp, scoreThreshold = 0.35f) ?: return null
            val vehicleLabels = setOf("car", "truck", "bus", "motorcycle")
            // Only a vehicle-class detection counts; otherwise fall back to classical framing
            // (returning a random non-vehicle box would mis-gate the shot).
            dets.filter { it.label in vehicleLabels }.maxByOrNull { it.score }?.rect
        } catch (t: Throwable) {
            Timber.w(t, "Vehicle detector failed; using classical framing")
            null
        }
    }

    private suspend fun acceptPhoto(file: File, station: StationSpec, verdict: QualityResult) {
        val bmp = Images.decodeForAnalysis(file)
        val width = bmp?.width ?: 0
        val height = bmp?.height ?: 0
        // Hash at capture, before any further processing (evidence integrity).
        val sha = withContext(Dispatchers.Default) { Hashing.sha256File(file) }
        val location = container.locationProvider.currentStamp()
        val captured = CapturedPhoto(station.id, file, sha, width, height, verdict, location)
        val photo = container.inspectionService.addPhoto(inspectionId, captured)
        Timber.i("Accepted %s sha=%s", station.id, sha.take(12))

        _state.update {
            it.copy(
                capturing = false,
                flashAccept = true,
                lastVerdict = verdict,
                capturedPoints = it.capturedPoints + station.id,
                analyzingPoints = it.analyzingPoints + station.id,
                currentIndex = nextIndex(it),
                complete = (it.capturedPoints + station.id).size >= it.stations.size
            )
        }

        // Baseline enrollment captures don't diff/analyze — they register the clean reference.
        if (isBaselineEnrollment) {
            viewModelScope.launch(Dispatchers.Default) {
                val supervisorId = container.authRepository.currentSession?.driverId ?: "supervisor"
                enrollVehicleId?.let { vid ->
                    runCatching { container.baselineService.enroll(vid, enrollProfileId, station.id, file, supervisorId) }
                        .onFailure { Timber.e(it, "Baseline enroll failed %s", station.id) }
                }
                _state.update { it.copy(analyzingPoints = it.analyzingPoints - station.id) }
            }
            return
        }

        // Background per-photo analysis (registration + heads + diff).
        viewModelScope.launch(Dispatchers.Default) {
            val inspection = container.inspectionRepository.byId(inspectionId)
            if (inspection != null) {
                runCatching { container.analysisOrchestrator.analyzePhoto(inspection, photo) }
                    .onFailure { Timber.e(it, "Analysis failed for %s", station.id) }
            }
            _state.update { it.copy(analyzingPoints = it.analyzingPoints - station.id) }
            if (_state.value.complete) container.inspectionService.markAnalyzed(inspectionId)
        }
    }

    private fun nextIndex(s: CaptureUiState): Int {
        val captured = s.capturedPoints + (s.currentStation?.id ?: "")
        val next = s.stations.indexOfFirst { it.id !in captured }
        return if (next < 0) s.currentIndex else next
    }
}
