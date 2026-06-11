package ai.deepmost.triage.vehicle

import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.InspectionStatus
import ai.deepmost.triage.data.VehicleRepository
import ai.deepmost.triage.data.entity.FindingEntity

/** One defect's lifecycle across a vehicle's shift history. */
data class LedgerEntry(
    val rootId: String,
    val type: FindingType,
    val zone: String?,
    val capturePoint: String,
    val firstSeenInspectionId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val shiftsPresent: Int,
    val resolved: Boolean
)

/** A point on a vehicle's cleanliness trend. */
data class CleanlinessPoint(val inspectionId: String, val at: Long, val score: Float)

/** A row in the fleet cleanliness league table. */
data class LeagueRow(
    val vehicleId: String,
    val registration: String,
    val avgScore: Float,
    val lastInspectionAt: Long?,
    val openNewDamages: Int
)

/**
 * Derives the damage ledger, cleanliness trend and fleet league from stored findings. These are
 * read models for the Vehicle History and Fleet screens — every defect's lineage (first seen ->
 * present across N shifts -> resolved) is reconstructed from the per-finding match links.
 */
class VehicleAnalytics(
    private val vehicleRepo: VehicleRepository,
    private val inspectionRepo: InspectionRepository,
    private val findingRepo: FindingRepository
) {

    suspend fun ledger(vehicleId: String): List<LedgerEntry> {
        val findings = findingRepo.byVehicle(vehicleId)
        val byId = findings.associateBy { it.id }
        val successorsOf = HashMap<String, MutableList<FindingEntity>>()
        for (f in findings) f.matchedFindingId?.let { successorsOf.getOrPut(it) { mutableListOf() }.add(f) }

        val roots = findings.filter { isDefect(it) && (it.diffStatus == DiffStatus.NEW || it.diffStatus == DiffStatus.FIRST_RECORD) }
        val entries = ArrayList<LedgerEntry>()
        for (root in roots) {
            var shifts = 1
            var lastAt = root.createdAt
            var resolved = false
            var cursor: FindingEntity? = root
            val visited = HashSet<String>()
            while (cursor != null && visited.add(cursor.id)) {
                val succ = successorsOf[cursor.id].orEmpty()
                val resolvedSucc = succ.firstOrNull { it.diffStatus == DiffStatus.RESOLVED }
                if (resolvedSucc != null) { resolved = true; lastAt = maxOf(lastAt, resolvedSucc.createdAt); break }
                val next = succ.firstOrNull { it.diffStatus == DiffStatus.PRE_EXISTING }
                if (next != null) { shifts++; lastAt = maxOf(lastAt, next.createdAt) }
                cursor = next
            }
            entries.add(
                LedgerEntry(
                    rootId = root.id, type = root.type, zone = root.zone, capturePoint = root.capturePoint,
                    firstSeenInspectionId = root.inspectionId, firstSeenAt = root.createdAt,
                    lastSeenAt = lastAt, shiftsPresent = shifts, resolved = resolved
                )
            )
        }
        // Keep determinism: most recent first.
        return entries.sortedByDescending { it.firstSeenAt }
    }

    suspend fun cleanlinessTrend(vehicleId: String): List<CleanlinessPoint> {
        val inspections = inspectionRepo.byVehicle(vehicleId)
            .filter { it.status == InspectionStatus.FINALIZED }
            .sortedBy { it.finalizedAtDevice ?: it.startedAtDevice }
        val points = ArrayList<CleanlinessPoint>()
        for (insp in inspections) {
            val overall = findingRepo.byInspection(insp.id)
                .filter { it.head == FindingHead.CLEANLINESS && it.type == FindingType.CLEANLINESS_SCORE && it.zone == "overall" }
            if (overall.isEmpty()) continue
            val avgClean = overall.map { (1f - it.severity) * 100f }.average().toFloat()
            points.add(CleanlinessPoint(insp.id, insp.finalizedAtDevice ?: insp.startedAtDevice, avgClean))
        }
        return points
    }

    /** Snapshot league across the provided vehicle ids (UI passes the current vehicle list). */
    suspend fun leagueFor(vehicleIds: List<Pair<String, String>>): List<LeagueRow> {
        val rows = ArrayList<LeagueRow>()
        for ((vehicleId, registration) in vehicleIds) {
            val trend = cleanlinessTrend(vehicleId)
            val recent = trend.takeLast(5)
            val avg = if (recent.isEmpty()) Float.NaN else recent.map { it.score }.average().toFloat()
            val openNew = ledger(vehicleId).count { !it.resolved }
            val lastAt = inspectionRepo.latestFinalized(vehicleId)?.finalizedAtDevice
            rows.add(LeagueRow(vehicleId, registration, avg, lastAt, openNew))
        }
        return rows.sortedWith(compareByDescending<LeagueRow> { if (it.avgScore.isNaN()) -1f else it.avgScore })
    }

    private fun isDefect(f: FindingEntity): Boolean = when (f.type) {
        FindingType.TYRE_OK, FindingType.LAMP_INTACT, FindingType.LAMP_WORKING,
        FindingType.CLEANLINESS_SCORE, FindingType.MANUAL_NOTE -> false
        else -> true
    }
}
