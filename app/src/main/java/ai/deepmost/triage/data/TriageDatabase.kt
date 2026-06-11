package ai.deepmost.triage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ai.deepmost.triage.data.dao.BaselineDao
import ai.deepmost.triage.data.dao.DriverDao
import ai.deepmost.triage.data.dao.FindingDao
import ai.deepmost.triage.data.dao.InspectionDao
import ai.deepmost.triage.data.dao.PhotoDao
import ai.deepmost.triage.data.dao.VehicleDao
import ai.deepmost.triage.data.entity.BaselineEntity
import ai.deepmost.triage.data.entity.DriverEntity
import ai.deepmost.triage.data.entity.FindingEntity
import ai.deepmost.triage.data.entity.InspectionEntity
import ai.deepmost.triage.data.entity.PhotoEntity
import ai.deepmost.triage.data.entity.VehicleEntity

@Database(
    entities = [
        VehicleEntity::class,
        DriverEntity::class,
        InspectionEntity::class,
        PhotoEntity::class,
        FindingEntity::class,
        BaselineEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TriageDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun driverDao(): DriverDao
    abstract fun inspectionDao(): InspectionDao
    abstract fun photoDao(): PhotoDao
    abstract fun findingDao(): FindingDao
    abstract fun baselineDao(): BaselineDao

    companion object {
        @Volatile private var instance: TriageDatabase? = null

        fun get(context: Context): TriageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TriageDatabase::class.java,
                    "triage.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
