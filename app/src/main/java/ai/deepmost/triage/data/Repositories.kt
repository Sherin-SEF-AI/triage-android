package ai.deepmost.triage.data

import ai.deepmost.triage.data.dao.BaselineDao
import ai.deepmost.triage.data.dao.FindingDao
import ai.deepmost.triage.data.dao.InspectionDao
import ai.deepmost.triage.data.dao.PhotoDao
import ai.deepmost.triage.data.dao.VehicleDao
import ai.deepmost.triage.data.entity.BaselineEntity
import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import ai.deepmost.triage.data.entity.VehicleEntity
import kotlinx.coroutines.flow.Flow

class VehicleRepository(private val dao: VehicleDao) {
    fun observeAll(): Flow<List<VehicleEntity>> = dao.observeAll()
    suspend fun byId(id: String) = dao.byId(id)
    suspend fun byRegistration(reg: String) = dao.byRegistration(reg)
    suspend fun upsert(v: VehicleEntity) = dao.upsert(v)
    suspend fun update(v: VehicleEntity) = dao.update(v)
    suspend fun updateChainHead(id: String, hash: String) = dao.updateChainHead(id, hash)
}

class InspectionRepository(
    private val inspectionDao: InspectionDao,
    private val photoDao: PhotoDao
) {
    suspend fun upsert(i: InspectionEntity) = inspectionDao.upsert(i)
    suspend fun update(i: InspectionEntity) = inspectionDao.update(i)
    suspend fun byId(id: String) = inspectionDao.byId(id)
    fun observeById(id: String) = inspectionDao.observeById(id)
    fun observeByVehicle(vehicleId: String) = inspectionDao.observeByVehicle(vehicleId)
    suspend fun byVehicle(vehicleId: String) = inspectionDao.byVehicle(vehicleId)
    suspend fun latestFinalized(vehicleId: String) = inspectionDao.latestFinalized(vehicleId)
    suspend fun previousFinalized(vehicleId: String, excludeId: String) =
        inspectionDao.previousFinalized(vehicleId, excludeId)
    fun observeInProgress(driverId: String) = inspectionDao.observeInProgress(driverId)
    fun observeDisputed() = inspectionDao.observeDisputed()
    fun observeAll() = inspectionDao.observeAll()
    suspend fun finalizedUnsynced() = inspectionDao.finalizedUnsynced()
    suspend fun finalizedSyncedOldestFirst() = inspectionDao.finalizedSyncedOldestFirst()

    suspend fun upsertPhoto(p: PhotoEntity) = photoDao.upsert(p)
    suspend fun updatePhoto(p: PhotoEntity) = photoDao.update(p)
    suspend fun photos(inspectionId: String) = photoDao.byInspection(inspectionId)
    fun observePhotos(inspectionId: String) = photoDao.observeByInspection(inspectionId)
    suspend fun photoAt(inspectionId: String, capturePoint: String) =
        photoDao.byInspectionAndPoint(inspectionId, capturePoint)
    suspend fun markPhotoEvicted(id: String) = photoDao.markEvicted(id)
    suspend fun activePixelLoad() = photoDao.activePixelLoad()
}

class FindingRepository(private val dao: FindingDao) {
    suspend fun upsert(f: FindingEntity) = dao.upsert(f)
    suspend fun upsertAll(f: List<FindingEntity>) = dao.upsertAll(f)
    suspend fun update(f: FindingEntity) = dao.update(f)
    suspend fun byId(id: String) = dao.byId(id)
    suspend fun byInspection(inspectionId: String) = dao.byInspection(inspectionId)
    fun observeByInspection(inspectionId: String) = dao.observeByInspection(inspectionId)
    suspend fun byVehicle(vehicleId: String) = dao.byVehicle(vehicleId)
    suspend fun clearForStationHead(inspectionId: String, cp: String, head: String) =
        dao.clearForStationHead(inspectionId, cp, head)
}

class BaselineRepository(private val dao: BaselineDao) {
    suspend fun upsert(b: BaselineEntity) = dao.upsert(b)
    suspend fun byVehicleAndPoint(vehicleId: String, cp: String) = dao.byVehicleAndPoint(vehicleId, cp)
    suspend fun byVehicle(vehicleId: String) = dao.byVehicle(vehicleId)
    fun observeByVehicle(vehicleId: String) = dao.observeByVehicle(vehicleId)
}
