package ai.deepmost.triage.data

/** What kind of walkaround produced an inspection. */
enum class InspectionType { SHIFT_START, SHIFT_END, INCIDENT, AD_HOC, BASELINE }

/** Lifecycle of an inspection record. Finalized records are immutable. */
enum class InspectionStatus { IN_PROGRESS, ANALYZED, FINALIZED, DISPUTED }

/** Which analysis head produced a finding. */
enum class FindingHead { DAMAGE, TYRE, LAMP, CLEANLINESS, MANUAL }

/**
 * Finding category. The classical damage head is honest: it emits ANOMALY (a change-detected
 * candidate region), not a specific damage class, unless a real model is installed.
 */
enum class FindingType {
    // Damage (model classes)
    SCRATCH, DENT, CRACK, BROKEN_PART, MISSING_PART, PAINT_PEEL,
    // Damage (classical change-detector)
    ANOMALY,
    // Tyre
    TYRE_OK, TYRE_WORN, TYRE_DAMAGED, TYRE_FLAT_LOW,
    // Lamp
    LAMP_INTACT, LAMP_CRACKED, LAMP_FOGGED, LAMP_MISSING, LAMP_WORKING, LAMP_NOT_WORKING,
    // Cleanliness
    CLEANLINESS_SCORE, DUST, MUD_SPLASH, LITTER, STAIN, CLUTTER,
    // Generic manual
    MANUAL_NOTE
}

/** Diff classification relative to the previous accepted walkaround. */
enum class DiffStatus { NEW, PRE_EXISTING, RESOLVED, UNMATCHED, REVIEW_REQUIRED, FIRST_RECORD }

/** Driver's human label on a finding — these are the export's ground-truth labels. */
enum class DriverAnnotation { NONE, CONFIRMED, DISPUTED }

/** Label provenance for the dataset export. */
enum class LabelSource { MODEL_ONLY, DRIVER_CONFIRMED, DRIVER_DISPUTED, MANUAL }

/** Roles for accountability + supervisor gating. */
enum class Role { DRIVER, SUPERVISOR }

/** Engine that produced a finding, recorded for explainability. */
object Engine {
    const val CLASSICAL = "classical-cv"
    fun model(modelId: String, version: String) = "$modelId@$version"
}
