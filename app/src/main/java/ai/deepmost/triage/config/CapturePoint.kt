package ai.deepmost.triage.config

/**
 * The fixed guided-walkaround stations for a sedan, in capture order. The set is fixed in
 * code (so the pipeline, DB and diff can reason about it), while per-station GEOMETRY is
 * config-driven via [StationConfig] JSON so new vehicle models are added without code changes.
 */
enum class CapturePoint(val order: Int, val kind: StationKind) {
    FRONT(0, StationKind.BODY),
    FRONT_LEFT_34(1, StationKind.BODY),
    LEFT(2, StationKind.BODY),
    REAR_LEFT_34(3, StationKind.BODY),
    REAR(4, StationKind.BODY),
    REAR_RIGHT_34(5, StationKind.BODY),
    RIGHT(6, StationKind.BODY),
    FRONT_RIGHT_34(7, StationKind.BODY),
    TYRE_FL(8, StationKind.TYRE),
    TYRE_FR(9, StationKind.TYRE),
    INTERIOR_FRONT(10, StationKind.INTERIOR),
    INTERIOR_REAR(11, StationKind.INTERIOR);

    companion object {
        val SEQUENCE: List<CapturePoint> = entries.sortedBy { it.order }
        fun fromIdOrNull(id: String): CapturePoint? = entries.firstOrNull { it.name == id }
    }
}

enum class StationKind { BODY, TYRE, INTERIOR }

/** Which analysis heads can run at a station. */
enum class HeadId { DAMAGE, TYRE, LAMP, CLEANLINESS }
