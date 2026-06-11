package ai.deepmost.triage.registry

import ai.deepmost.triage.data.FileStore
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/** Task kind a model performs, governing how its outputs are decoded. */
enum class ModelTask { CLASSIFICATION, DETECTION, SEGMENTATION }

/** One model slot from the registry manifest. */
@Serializable
data class ModelSpec(
    val head: String,
    val modelId: String,
    val version: String,
    val file: String,
    val present: Boolean = false,
    val inputWidth: Int = 224,
    val inputHeight: Int = 224,
    val labels: List<String> = emptyList(),
    val task: String = "CLASSIFICATION",
    /** Optional direct download URL; if set, the app auto-provisions this model on start. */
    val url: String = "",
    /** Optional expected SHA-256 of the downloaded file (lowercase hex) for integrity. */
    val sha256: String = ""
) {
    val modelTask: ModelTask get() = runCatching { ModelTask.valueOf(task) }.getOrDefault(ModelTask.CLASSIFICATION)
    fun engineTag() = "$modelId@$version"
    val downloadable: Boolean get() = url.isNotBlank()
}

@Serializable
private data class RegistryManifest(
    val schemaVersion: Int = 1,
    val note: String = "",
    val models: List<ModelSpec> = emptyList()
)

/** Engine resolved for a head: either an installed model or the classical fallback. */
data class EngineStatus(val head: String, val usingModel: Boolean, val tag: String)

/**
 * Reads the bundled registry manifest and reports, per head, whether a usable .tflite is
 * installed in the app models dir. Day one no model files exist, so every head reports the
 * classical fallback. Dropping a conforming model into [FileStore.modelsDir] and matching the
 * manifest's `file` name flips that head to the model engine automatically.
 */
class ModelRegistry(
    private val context: Context,
    private val fileStore: FileStore,
    private val interpreterFactory: LiteRtInterpreterFactory
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val specsByHead: Map<String, ModelSpec> by lazy { loadManifest() }
    private val handles = HashMap<String, ModelHandle?>()

    private fun loadManifest(): Map<String, ModelSpec> = try {
        val text = context.assets.open("models/registry.json").bufferedReader().use { it.readText() }
        json.decodeFromString(RegistryManifest.serializer(), text).models.associateBy { it.head }
    } catch (t: Throwable) {
        Timber.e(t, "Failed to read model registry manifest")
        emptyMap()
    }

    fun specs(): List<ModelSpec> = specsByHead.values.sortedBy { it.head }

    fun specFor(head: String): ModelSpec? = specsByHead[head]

    /** True if a real model file for the head exists on disk. */
    fun modelFile(head: String): File? {
        val spec = specsByHead[head] ?: return null
        val f = File(fileStore.modelsDir, spec.file)
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun engineStatus(head: String): EngineStatus {
        val spec = specsByHead[head]
        val installed = modelFile(head) != null
        return EngineStatus(
            head = head,
            usingModel = installed,
            tag = if (installed && spec != null) spec.engineTag() else ai.deepmost.triage.data.Engine.CLASSICAL
        )
    }

    fun allStatuses(): List<EngineStatus> = specsByHead.keys.sorted().map { engineStatus(it) }

    /**
     * Lazily load (and cache) the interpreter handle for a head, or null if no model file is
     * present / it failed to load. Heads call this and fall back to classical on null.
     */
    fun handleFor(head: String): ModelHandle? {
        if (handles.containsKey(head)) return handles[head]
        val spec = specsByHead[head]
        val file = modelFile(head)
        val handle = if (spec != null && file != null) {
            interpreterFactory.create(file, spec).also {
                if (it != null) Timber.i("Loaded model %s for head %s", spec.engineTag(), head)
            }
        } else null
        handles[head] = handle
        return handle
    }

    /** Install a model file for a head (copies bytes to the head's expected file name). */
    fun installModel(head: String, bytes: ByteArray): Boolean {
        val spec = specsByHead[head] ?: return false
        return try {
            File(fileStore.modelsDir, spec.file).writeBytes(bytes)
            handles.remove(head)?.close() // force reload on next use
            Timber.i("Installed model for head %s (%d bytes)", head, bytes.size)
            true
        } catch (t: Throwable) {
            Timber.e(t, "Install failed for head %s", head); false
        }
    }

    /** Remove an installed model file for a head, reverting it to the classical fallback. */
    fun uninstall(head: String): Boolean {
        val spec = specsByHead[head] ?: return false
        handles.remove(head)?.close()
        val f = File(fileStore.modelsDir, spec.file)
        return if (f.exists()) f.delete() else true
    }

    fun close() {
        handles.values.forEach { it?.close() }
        handles.clear()
    }
}
