package ai.deepmost.triage.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ai.deepmost.triage.data.InspectionStatus
import ai.deepmost.triage.data.entity.BaselineEntity
import ai.deepmost.triage.data.entity.DriverEntity
import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import ai.deepmost.triage.data.entity.VehicleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(v: VehicleEntity)

    @Update suspend fun update(v: VehicleEntity)

    @Query("SELECT * FROM vehicles ORDER BY registration")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun byId(id: String): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE registration = :reg LIMIT 1")
    suspend fun byRegistration(reg: String): VehicleEntity?

    @Query("UPDATE vehicles SET chainHead = :hash WHERE id = :id")
    suspend fun updateChainHead(id: String, hash: String)
}

@Dao
interface DriverDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: DriverEntity)

    @Query("SELECT * FROM drivers ORDER BY name")
    fun observeAll(): Flow<List<DriverEntity>>

    @Query("SELECT * FROM drivers WHERE id = :id")
    suspend fun byId(id: String): DriverEntity?

    @Query("SELECT * FROM drivers WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): DriverEntity?

    @Query("SELECT * FROM drivers WHERE role = 'SUPERVISOR'")
    suspend fun supervisors(): List<DriverEntity>

    @Query("SELECT COUNT(*) FROM drivers")
    suspend fun count(): Int
}

@Dao
interface InspectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(i: InspectionEntity)

    @Update suspend fun update(i: InspectionEntity)

    @Query("SELECT * FROM inspections WHERE id = :id")
    suspend fun byId(id: String): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE id = :id")
    fun observeById(id: String): Flow<InspectionEntity?>

    @Query("SELECT * FROM inspections WHERE vehicleId = :vehicleId ORDER BY startedAtDevice DESC")
    fun observeByVehicle(vehicleId: String): Flow<List<InspectionEntity>>

    @Query("SELECT * FROM inspections WHERE vehicleId = :vehicleId ORDER BY startedAtDevice DESC")
    suspend fun byVehicle(vehicleId: String): List<InspectionEntity>

    @Query(
        "SELECT * FROM inspections WHERE vehicleId = :vehicleId AND status = 'FINALIZED' " +
            "AND type != 'BASELINE' ORDER BY finalizedAtDevice DESC LIMIT 1"
    )
    suspend fun latestFinalized(vehicleId: String): InspectionEntity?

    @Query(
        "SELECT * FROM inspections WHERE vehicleId = :vehicleId AND status = 'FINALIZED' " +
            "AND type != 'BASELINE' AND id != :excludeId ORDER BY finalizedAtDevice DESC LIMIT 1"
    )
    suspend fun previousFinalized(vehicleId: String, excludeId: String): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE driverId = :driverId AND status = 'IN_PROGRESS' ORDER BY startedAtDevice DESC")
    fun observeInProgress(driverId: String): Flow<List<InspectionEntity>>

    @Query("SELECT * FROM inspections WHERE status = :status")
    suspend fun byStatus(status: InspectionStatus): List<InspectionEntity>

    @Query("SELECT * FROM inspections WHERE status = 'DISPUTED' ORDER BY startedAtDevice DESC")
    fun observeDisputed(): Flow<List<InspectionEntity>>

    @Query("SELECT * FROM inspections WHERE status = 'FINALIZED' AND synced = 0")
    suspend fun finalizedUnsynced(): List<InspectionEntity>

    @Query("SELECT * FROM inspections WHERE status = 'FINALIZED' AND synced = 1 ORDER BY finalizedAtDevice ASC")
    suspend fun finalizedSyncedOldestFirst(): List<InspectionEntity>

    @Query("SELECT * FROM inspections ORDER BY startedAtDevice DESC")
    fun observeAll(): Flow<List<InspectionEntity>>
}

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PhotoEntity)

    @Update suspend fun update(p: PhotoEntity)

    @Query("SELECT * FROM photos WHERE inspectionId = :inspectionId")
    suspend fun byInspection(inspectionId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE inspectionId = :inspectionId")
    fun observeByInspection(inspectionId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE inspectionId = :inspectionId AND capturePoint = :cp LIMIT 1")
    suspend fun byInspectionAndPoint(inspectionId: String, cp: String): PhotoEntity?

    @Query("UPDATE photos SET evicted = 1 WHERE id = :id")
    suspend fun markEvicted(id: String)

    @Query("SELECT COALESCE(SUM(width * height), 0) FROM photos WHERE evicted = 0")
    suspend fun activePixelLoad(): Long
}

@Dao
interface FindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(f: FindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(f: List<FindingEntity>)

    @Update suspend fun update(f: FindingEntity)

    @Query("DELETE FROM findings WHERE inspectionId = :inspectionId AND capturePoint = :cp AND head = :head")
    suspend fun clearForStationHead(inspectionId: String, cp: String, head: String)

    @Query("SELECT * FROM findings WHERE inspectionId = :inspectionId")
    suspend fun byInspection(inspectionId: String): List<FindingEntity>

    @Query("SELECT * FROM findings WHERE inspectionId = :inspectionId ORDER BY head, capturePoint")
    fun observeByInspection(inspectionId: String): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE id = :id")
    suspend fun byId(id: String): FindingEntity?

    @Query(
        "SELECT f.* FROM findings f INNER JOIN inspections i ON f.inspectionId = i.id " +
            "WHERE i.vehicleId = :vehicleId ORDER BY f.createdAt DESC"
    )
    suspend fun byVehicle(vehicleId: String): List<FindingEntity>
}

@Dao
interface BaselineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BaselineEntity)

    @Query("SELECT * FROM baselines WHERE vehicleId = :vehicleId AND capturePoint = :cp LIMIT 1")
    suspend fun byVehicleAndPoint(vehicleId: String, cp: String): BaselineEntity?

    @Query("SELECT * FROM baselines WHERE vehicleId = :vehicleId")
    suspend fun byVehicle(vehicleId: String): List<BaselineEntity>

    @Query("SELECT * FROM baselines WHERE vehicleId = :vehicleId")
    fun observeByVehicle(vehicleId: String): Flow<List<BaselineEntity>>
}
