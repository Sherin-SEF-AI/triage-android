package ai.deepmost.triage.sync

import ai.deepmost.triage.TriageApplication
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional, default-OFF sync of finalized records to a configurable HTTPS endpoint. This worker
 * is the ONLY component that touches the network and is entirely separable — the analysis path
 * never depends on it. Uploads each finalized-but-unsynced inspection as a multipart ZIP with a
 * bearer token, marks it synced on success, and relies on WorkManager backoff on failure.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TriageApplication).container
        val settings = container.settingsRepository.settings.first()
        if (!settings.syncEnabled || settings.syncEndpoint.isBlank()) {
            Timber.d("Sync disabled; nothing to do")
            return Result.success()
        }

        val pending = container.inspectionRepository.finalizedUnsynced()
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (insp in pending) {
            val zip = container.datasetExporter.exportInspection(insp.id, settings.redactPlates) ?: continue
            val ok = upload(settings.syncEndpoint, settings.syncToken, zip, insp.id)
            if (ok) {
                container.inspectionRepository.update(
                    insp.copy(synced = true, syncedAt = System.currentTimeMillis())
                )
                Timber.i("Synced inspection %s", insp.id)
            } else {
                anyFailed = true
            }
        }
        // Opportunistically enforce retention now that some records are synced.
        container.retentionManager.enforce()
        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun upload(endpoint: String, token: String, file: File, inspectionId: String): Boolean {
        val boundary = "----triage${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"inspectionId\"\r\n\r\n")
                out.writeBytes("$inspectionId\r\n")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"record\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: application/zip\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            Timber.i("Sync upload %s -> HTTP %d", inspectionId, code)
            code in 200..299
        } catch (t: Throwable) {
            Timber.e(t, "Sync upload failed for %s", inspectionId)
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val UNIQUE_WORK = "triage_sync"
    }
}
