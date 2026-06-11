package ai.deepmost.triage.vehicle

import ai.deepmost.triage.data.VehicleRepository
import ai.deepmost.triage.data.entity.VehicleEntity
import java.util.UUID

/** Vehicle creation / lookup, including the QR "find-or-create by registration" path. */
class VehicleService(private val vehicleRepo: VehicleRepository) {

    suspend fun create(
        registration: String,
        model: String = "Tata Xpres-T EV",
        fleetLabel: String = "",
        profileId: String = "xprest"
    ): VehicleEntity {
        val existing = vehicleRepo.byRegistration(registration.trim().uppercase())
        if (existing != null) return existing
        val entity = VehicleEntity(
            id = UUID.randomUUID().toString(),
            registration = registration.trim().uppercase(),
            model = model,
            fleetLabel = fleetLabel,
            profileId = profileId,
            createdAt = System.currentTimeMillis()
        )
        vehicleRepo.upsert(entity)
        return entity
    }

    suspend fun byId(id: String) = vehicleRepo.byId(id)

    suspend fun findOrCreateByRegistration(registration: String): VehicleEntity =
        vehicleRepo.byRegistration(registration.trim().uppercase()) ?: create(registration)

    fun observeAll() = vehicleRepo.observeAll()
}
