package ai.deepmost.triage.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.DriverAnnotation
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.InspectionStatus
import ai.deepmost.triage.data.InspectionType
import ai.deepmost.triage.data.LabelSource
import ai.deepmost.triage.data.Role

@Entity(tableName = "vehicles", indices = [Index(value = ["registration"], unique = true)])
data class VehicleEntity(
    @PrimaryKey val id: String,
    val registration: String,
    val model: String = "Tata Xpres-T EV",
    val fleetLabel: String = "",
    val profileId: String = "xprest",
    val photoPath: String? = null,
    /** Head of this vehicle's hash chain: the manifestHash of its latest finalized inspection. */
    val chainHead: String? = null,
    val createdAt: Long
)

@Entity(tableName = "drivers", indices = [Index(value = ["name"], unique = true)])
data class DriverEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pinHash: String,
    val role: Role = Role.DRIVER,
    val createdAt: Long
)

@Entity(
    tableName = "inspections",
    indices = [Index("vehicleId"), Index("driverId"), Index("status")]
)
data class InspectionEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val driverId: String,
    val type: InspectionType,
    val status: InspectionStatus,
    val profileId: String,
    // Timestamps: wall clock + monotonic + optional GPS-derived.
    val startedAtDevice: Long,
    val startedAtElapsed: Long,
    val finalizedAtDevice: Long? = null,
    val gpsTimeMillis: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    // Hash chain.
    val prevInspectionId: String? = null,
    val prevHash: String? = null,
    val manifestHash: String? = null,
    val signaturePath: String? = null,
    // Optional corrections form a linked list to the inspection they amend (immutability rule).
    val correctsInspectionId: String? = null,
    val synced: Boolean = false,
    val syncedAt: Long? = null,
    val notes: String = ""
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(
        entity = InspectionEntity::class,
        parentColumns = ["id"],
        childColumns = ["inspectionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("inspectionId"), Index(value = ["inspectionId", "capturePoint"])]
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val inspectionId: String,
    val capturePoint: String,
    val filePath: String,
    val sha256: String,
    val capturedAtDevice: Long,
    val capturedAtElapsed: Long,
    val gpsTimeMillis: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val width: Int,
    val height: Int,
    // Quality scores recorded at the gate.
    val sharpness: Float,
    val exposureClip: Float,
    val framingScore: Float,
    val brightness: Float,
    val qualityPassed: Boolean,
    val sensorMeta: String = "",
    /** True once the full-res payload has been evicted by retention (hash/manifest survive). */
    val evicted: Boolean = false
)

@Entity(
    tableName = "findings",
    foreignKeys = [ForeignKey(
        entity = InspectionEntity::class,
        parentColumns = ["id"],
        childColumns = ["inspectionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("inspectionId"), Index("photoId"), Index("head"), Index("diffStatus")]
)
data class FindingEntity(
    @PrimaryKey val id: String,
    val inspectionId: String,
    val photoId: String?,
    val capturePoint: String,
    val head: FindingHead,
    val type: FindingType,
    val zone: String? = null,
    val severity: Float,
    val confidence: Float,
    // Normalized bounding region.
    val bboxLeft: Float,
    val bboxTop: Float,
    val bboxRight: Float,
    val bboxBottom: Float,
    /** Optional finer polygon as JSON list of [x,y] pairs in normalized coords. */
    val polygonJson: String? = null,
    val engine: String,
    val diffStatus: DiffStatus,
    val matchedFindingId: String? = null,
    val driverAnnotation: DriverAnnotation = DriverAnnotation.NONE,
    val driverNote: String? = null,
    val lowConfidence: Boolean = false,
    /** Relative-to-last-shift trend value for tyre/cleanliness heads (nullable). */
    val trend: Float? = null,
    val labelSource: LabelSource = LabelSource.MODEL_ONLY,
    val createdAt: Long
)

/**
 * Per-vehicle, per-station "clean enrolled" reference captured by a supervisor. All cleanliness
 * scoring is relative to this; damage change-detection can also fall back to it as a reference.
 */
@Entity(
    tableName = "baselines",
    indices = [Index(value = ["vehicleId", "capturePoint"], unique = true)]
)
data class BaselineEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val capturePoint: String,
    val photoPath: String,
    val sha256: String,
    val supervisorId: String,
    /** Cached reference metrics (JSON) so re-decoding the baseline image is unnecessary. */
    val metricsJson: String,
    val createdAt: Long
)
