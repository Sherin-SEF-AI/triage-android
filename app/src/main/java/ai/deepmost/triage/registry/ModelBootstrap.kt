package ai.deepmost.triage.registry

import ai.deepmost.triage.integrity.Hashing
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automatic in-app model provisioning. On app start (and on demand), any registry model that has a
 * `url` and isn't already installed is downloaded into the app's models dir and activated. This is
 * PROVISIONING, not analysis — it runs off the analysis path, so the 100%-on-device airplane-mode
 * analysis guarantee holds: with no network, heads simply keep using the classical fallback (or any
 * model already present) and provisioning retries on the next launch.
 */
class ModelBootstrap(
    private val context: Context,
    private val registry: ModelRegistry,
    private val scope: CoroutineScope
) {
    enum class Status { IDLE, DOWNLOADING, INSTALLED, FAILED, SKIPPED }

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()

    /** Fire-and-forget provisioning used at app start. */
    fun provision(enabled: Boolean, wifiOnly: Boolean) {
        scope.launch { provisionNow(enabled, wifiOnly) }
    }

    /** Manual trigger (Fleet "Download now") — ignores the auto-enabled preference. */
    fun forceProvisionOne(head: String) {
        scope.launch {
            val spec = registry.specFor(head) ?: return@launch
            if (!spec.downloadable) return@launch
            download(spec)
        }
    }

    suspend fun provisionNow(enabled: Boolean, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        if (!enabled) {
            Timber.d("Model auto-download disabled by settings")
            return@withContext
        }
        for (spec in registry.specs()) {
            if (!spec.downloadable) continue
            if (registry.modelFile(spec.head) != null) {
                setStatus(spec.head, Status.INSTALLED); continue
            }
            if (!hasNetwork(wifiOnly)) {
                Timber.i("No %s network; deferring model %s", if (wifiOnly) "wifi" else "", spec.engineTag())
                setStatus(spec.head, Status.SKIPPED); continue
            }
            download(spec)
        }
    }

    private suspend fun download(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        setStatus(spec.head, Status.DOWNLOADING)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Timber.e("Model %s download HTTP %d", spec.engineTag(), code)
                setStatus(spec.head, Status.FAILED); return@withContext false
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (spec.sha256.isNotBlank()) {
                val actual = Hashing.sha256(bytes)
                if (!actual.equals(spec.sha256, ignoreCase = true)) {
                    Timber.e("Model %s sha mismatch (got %s)", spec.engineTag(), actual.take(12))
                    setStatus(spec.head, Status.FAILED); return@withContext false
                }
            }
            val ok = registry.installModel(spec.head, bytes)
            setStatus(spec.head, if (ok) Status.INSTALLED else Status.FAILED)
            Timber.i("Provisioned model %s (%d bytes) -> %s", spec.engineTag(), bytes.size, ok)
            ok
        } catch (t: Throwable) {
            Timber.e(t, "Model %s download failed", spec.engineTag())
            setStatus(spec.head, Status.FAILED); false
        } finally {
            conn?.disconnect()
        }
    }

    private fun setStatus(head: String, s: Status) {
        _status.value = _status.value.toMutableMap().apply { put(head, s) }
    }

    private fun hasNetwork(wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        return true
    }
}
