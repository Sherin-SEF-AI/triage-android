package ai.deepmost.triage.config

import android.content.Context
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Loads vehicle-profile station configs from assets/stations/. Config-driven geometry means
 * adding a new vehicle model is a JSON drop-in plus a Settings selection — no code change.
 */
class StationConfigLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, VehicleProfileConfig>()

    /** List available profile files (without extension) in assets/stations/. */
    fun availableProfiles(): List<String> = try {
        context.assets.list("stations")?.filter { it.endsWith(".json") }
            ?.map { it.removeSuffix(".json") } ?: emptyList()
    } catch (t: Throwable) {
        Timber.e(t, "Failed to list station configs")
        emptyList()
    }

    fun load(profileFile: String): VehicleProfileConfig? {
        cache[profileFile]?.let { return it }
        return try {
            val text = context.assets.open("stations/$profileFile.json").bufferedReader().use { it.readText() }
            val cfg = json.decodeFromString(VehicleProfileConfig.serializer(), text)
            cache[profileFile] = cfg
            Timber.i("Loaded station config '%s' (%d stations)", cfg.profileId, cfg.stations.size)
            cfg
        } catch (t: Throwable) {
            Timber.e(t, "Failed to load station config %s", profileFile)
            null
        }
    }

    companion object {
        const val DEFAULT_PROFILE = "xprest"
    }
}
