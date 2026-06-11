package ai.deepmost.triage.di

import ai.deepmost.triage.auth.AuthRepository
import ai.deepmost.triage.config.StationConfigLoader
import ai.deepmost.triage.data.BaselineRepository
import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.TriageDatabase
import ai.deepmost.triage.data.VehicleRepository
import ai.deepmost.triage.diff.DiffEngine
import ai.deepmost.triage.export.DatasetExporter
import ai.deepmost.triage.export.PdfGenerator
import ai.deepmost.triage.heads.AnalysisHead
import ai.deepmost.triage.heads.AnalysisOrchestrator
import ai.deepmost.triage.heads.clean.CleanlinessHead
import ai.deepmost.triage.heads.damage.DamageHead
import ai.deepmost.triage.heads.lamp.LampHead
import ai.deepmost.triage.heads.tyre.TyreHead
import ai.deepmost.triage.integrity.HashChain
import ai.deepmost.triage.integrity.SignatureStore
import ai.deepmost.triage.inspection.InspectionService
import ai.deepmost.triage.inspection.LocationProvider
import ai.deepmost.triage.inspection.ShiftTypeSuggester
import ai.deepmost.triage.quality.QualityGate
import ai.deepmost.triage.registry.LiteRtInterpreterFactory
import ai.deepmost.triage.registry.ModelRegistry
import ai.deepmost.triage.settings.SettingsRepository
import ai.deepmost.triage.settings.TriageSettings
import ai.deepmost.triage.storage.RetentionManager
import ai.deepmost.triage.vehicle.BaselineService
import ai.deepmost.triage.vehicle.VehicleAnalytics
import ai.deepmost.triage.vehicle.VehicleService
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Manual dependency container (service locator). A single instance lives on the Application and
 * wires every collaborator. We avoid Hilt/KSP weight; Room is the only code-gen dependency.
 */
class AppContainer(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db = TriageDatabase.get(appContext)
    val fileStore = FileStore(appContext)

    val vehicleRepository = VehicleRepository(db.vehicleDao())
    val inspectionRepository = InspectionRepository(db.inspectionDao(), db.photoDao())
    val findingRepository = FindingRepository(db.findingDao())
    val baselineRepository = BaselineRepository(db.baselineDao())

    val settingsRepository = SettingsRepository(appContext)

    /** Cached latest settings so synchronous consumers (quality gate, diff) can read thresholds. */
    @Volatile var currentSettings: TriageSettings = TriageSettings()
        private set

    val configLoader = StationConfigLoader(appContext)

    private val interpreterFactory = LiteRtInterpreterFactory()
    val modelRegistry = ModelRegistry(appContext, fileStore, interpreterFactory)
    val modelBootstrap = ai.deepmost.triage.registry.ModelBootstrap(appContext, modelRegistry, scope)

    val qualityGate = QualityGate { currentSettings.qualityThresholds() }

    private val heads: List<AnalysisHead> = listOf(
        DamageHead(), TyreHead(), LampHead(), CleanlinessHead()
    )
    private val diffEngine = DiffEngine()

    val analysisOrchestrator = AnalysisOrchestrator(
        heads = heads,
        diffEngine = diffEngine,
        findingRepo = findingRepository,
        inspectionRepo = inspectionRepository,
        baselineRepo = baselineRepository,
        fileStore = fileStore,
        configLoader = configLoader,
        registry = modelRegistry,
        registrationConfidence = { currentSettings.registrationConfidence }
    )

    val signatureStore = SignatureStore(fileStore)
    val hashChain = HashChain(inspectionRepository, findingRepository, fileStore, signatureStore)

    val inspectionService = InspectionService(
        inspectionRepository, findingRepository, vehicleRepository, hashChain, signatureStore
    )
    val shiftTypeSuggester = ShiftTypeSuggester()
    val locationProvider = LocationProvider(appContext)

    val vehicleService = VehicleService(vehicleRepository)
    val baselineService = BaselineService(baselineRepository, configLoader, fileStore)
    val vehicleAnalytics = VehicleAnalytics(vehicleRepository, inspectionRepository, findingRepository)
    val fleetMetrics = ai.deepmost.triage.analytics.FleetMetrics(inspectionRepository, findingRepository, db.driverDao())

    val datasetExporter = DatasetExporter(inspectionRepository, findingRepository, vehicleRepository, fileStore)
    val pdfGenerator = PdfGenerator(inspectionRepository, findingRepository, vehicleRepository, fileStore)
    val retentionManager = RetentionManager(inspectionRepository, fileStore, settingsRepository)

    val authRepository = AuthRepository(db.driverDao())

    init {
        settingsRepository.settings
            .onEach { currentSettings = it }
            .launchIn(scope)
    }
}
