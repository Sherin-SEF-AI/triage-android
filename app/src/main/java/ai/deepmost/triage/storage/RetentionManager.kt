package ai.deepmost.triage.storage

import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File

data class StorageStatus(val usedBytes: Long, val capBytes: Long, val lowStorage: Boolean)

/**
 * Enforces the bounded-storage policy. When photo payloads exceed the configured cap, the
 * OLDEST SYNCED inspections are evicted first (their full-res photos deleted), and never the
 * manifests or hashes — those live in the DB and keep the chain verifiable. Records that have
 * not yet synced are never evicted.
 */
class RetentionManager(
    private val inspectionRepo: InspectionRepository,
    private val fileStore: FileStore,
    private val settingsRepo: SettingsRepository
) {

    suspend fun status(): StorageStatus {
        val settings = settingsRepo.settings.first()
        val used = fileStore.photoBytes()
        return StorageStatus(
            usedBytes = used,
            capBytes = settings.retentionBytesCap,
            lowStorage = freeSpace() < settings.lowStorageWarnBytes
        )
    }

    /** Returns true if there is enough headroom to start a new walkaround. */
    suspend fun canStartWalkaround(): Boolean {
        val settings = settingsRepo.settings.first()
        return freeSpace() >= settings.lowStorageWarnBytes
    }

    /** Evict oldest-synced-first until under the cap. Returns bytes reclaimed. */
    suspend fun enforce(): Long {
        val settings = settingsRepo.settings.first()
        var used = fileStore.photoBytes()
        if (used <= settings.retentionBytesCap) return 0L

        var reclaimed = 0L
        val candidates = inspectionRepo.finalizedSyncedOldestFirst()
        for (insp in candidates) {
            if (used <= settings.retentionBytesCap) break
            val photos = inspectionRepo.photos(insp.id)
            for (p in photos) {
                if (p.evicted) continue
                val f = File(p.filePath)
                val len = if (f.exists()) f.length() else 0L
                if (f.exists()) f.delete()
                inspectionRepo.markPhotoEvicted(p.id)
                used -= len
                reclaimed += len
            }
            Timber.i("Evicted photo payloads for synced inspection %s", insp.id)
        }
        return reclaimed
    }

    private fun freeSpace(): Long = fileStore.inspectionsDir.usableSpace
}
