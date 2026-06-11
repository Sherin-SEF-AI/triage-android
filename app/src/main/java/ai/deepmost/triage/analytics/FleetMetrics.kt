package ai.deepmost.triage.analytics

import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.InspectionStatus
import ai.deepmost.triage.data.dao.DriverDao
import ai.deepmost.triage.data.entity.VehicleEntity

/** Per-driver accountability scorecard. */
data class DriverScore(
    val driverId: String,
    val name: String,
    val inspections: Int,
    val newDamagesIntroduced: Int,
    val avgCleanliness: Float,   // 0..100, NaN if none
    val disputeRate: Float       // 0..1
)

/** Per-vehicle damage exposure. */
data class VehicleDamage(val vehicleId: String, val registration: String, val openDamages: Int, val totalFindings: Int)

/** Fleet-level KPIs for the analytics dashboard. */
data class FleetKpis(
    val vehicles: Int,
    val finalizedInspections: Int,
    val openNewDamages: Int,
    val avgFleetCleanliness: Float,
    val driverScores: List<DriverScore>,
    val vehicleDamage: List<VehicleDamage>
)

/**
 * Computes fleet KPIs + driver scorecards from stored inspections/findings. Pure read-model over
 * the existing repositories — no new persistence. NEW findings on a shift count as "damage
 * introduced" for that driver (the responsible-shift attribution at fleet scale).
 */
class FleetMetrics(
    private val inspectionRepo: InspectionRepository,
    private val findingRepo: FindingRepository,
    private val driverDao: DriverDao
) {

    suspend fun compute(vehicles: List<VehicleEntity>): FleetKpis {
        var finalizedCount = 0
        var openNew = 0
        val cleanAll = ArrayList<Float>()
        val vehicleDamage = ArrayList<VehicleDamage>()

        // Driver aggregates.
        val dInspections = HashMap<String, Int>()
        val dNewDamage = HashMap<String, Int>()
        val dClean = HashMap<String, MutableList<Float>>()
        val dDisputed = HashMap<String, Int>()

        for (v in vehicles) {
            val inspections = inspectionRepo.byVehicle(v.id)
                .filter { it.status == InspectionStatus.FINALIZED || it.status == InspectionStatus.DISPUTED }
            var vehicleOpen = 0
            var vehicleTotal = 0
            for (insp in inspections) {
                finalizedCount++
                dInspections[insp.driverId] = (dInspections[insp.driverId] ?: 0) + 1
                if (insp.status == InspectionStatus.DISPUTED) dDisputed[insp.driverId] = (dDisputed[insp.driverId] ?: 0) + 1

                val findings = findingRepo.byInspection(insp.id)
                vehicleTotal += findings.size
                val newDefects = findings.count { it.diffStatus == DiffStatus.NEW && isDefect(it.type) }
                vehicleOpen += newDefects
                openNew += newDefects
                dNewDamage[insp.driverId] = (dNewDamage[insp.driverId] ?: 0) + newDefects

                findings.filter { it.head == FindingHead.CLEANLINESS && it.type == FindingType.CLEANLINESS_SCORE && it.zone == "overall" }
                    .map { (1f - it.severity) * 100f }
                    .let { scores ->
                        cleanAll.addAll(scores)
                        dClean.getOrPut(insp.driverId) { mutableListOf() }.addAll(scores)
                    }
            }
            if (inspections.isNotEmpty()) vehicleDamage.add(VehicleDamage(v.id, v.registration, vehicleOpen, vehicleTotal))
        }

        val driverScores = dInspections.keys.map { id ->
            val name = driverDao.byId(id)?.name ?: id.take(8)
            val clean = dClean[id].orEmpty()
            DriverScore(
                driverId = id, name = name,
                inspections = dInspections[id] ?: 0,
                newDamagesIntroduced = dNewDamage[id] ?: 0,
                avgCleanliness = if (clean.isEmpty()) Float.NaN else clean.average().toFloat(),
                disputeRate = (dDisputed[id] ?: 0).toFloat() / (dInspections[id] ?: 1)
            )
        }.sortedByDescending { it.inspections }

        return FleetKpis(
            vehicles = vehicles.size,
            finalizedInspections = finalizedCount,
            openNewDamages = openNew,
            avgFleetCleanliness = if (cleanAll.isEmpty()) Float.NaN else cleanAll.average().toFloat(),
            driverScores = driverScores,
            vehicleDamage = vehicleDamage.sortedByDescending { it.openDamages }
        )
    }

    private fun isDefect(t: FindingType): Boolean = when (t) {
        FindingType.TYRE_OK, FindingType.LAMP_INTACT, FindingType.LAMP_WORKING,
        FindingType.CLEANLINESS_SCORE -> false
        else -> true
    }
}
