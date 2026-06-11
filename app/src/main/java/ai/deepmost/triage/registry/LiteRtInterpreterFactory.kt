package ai.deepmost.triage.registry

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Builds a LiteRT [Interpreter] for a model file with a GPU -> NNAPI -> CPU delegate fallback.
 * Each tier is attempted in order; if delegate creation or interpreter construction throws, we
 * drop to the next tier. If all fail, the caller falls back to the classical CV head.
 *
 * NOTE: the LiteRT artifact (com.google.ai.edge.litert) exposes the org.tensorflow.lite Java
 * API used here. If a future artifact relocates these classes, this file is the single point of
 * adjustment; nothing else in the app references the interpreter directly.
 */
class LiteRtInterpreterFactory {

    fun create(file: File, spec: ModelSpec): ModelHandle? {
        val buffer = try {
            loadMappedFile(file)
        } catch (t: Throwable) {
            Timber.e(t, "Cannot map model file %s", file.name)
            return null
        }

        // GPU delegate.
        tryGpu(buffer, spec)?.let { return it }
        // NNAPI delegate.
        tryNnApi(buffer, spec)?.let { return it }
        // CPU (multi-threaded).
        return tryCpu(buffer, spec)
    }

    private fun tryGpu(buffer: ByteBuffer, spec: ModelSpec): ModelHandle? = try {
        if (!CompatibilityList().isDelegateSupportedOnThisDevice) {
            null
        } else {
            val delegate = GpuDelegate()
            val opts = Interpreter.Options().addDelegate(delegate)
            val interpreter = Interpreter(buffer, opts)
            Timber.i("Model %s on GPU delegate", spec.engineTag())
            ModelHandle(interpreter, spec, delegate)
        }
    } catch (t: Throwable) {
        Timber.w(t, "GPU delegate failed for %s, trying NNAPI", spec.engineTag())
        null
    }

    private fun tryNnApi(buffer: ByteBuffer, spec: ModelSpec): ModelHandle? = try {
        val delegate = NnApiDelegate()
        val opts = Interpreter.Options().addDelegate(delegate)
        val interpreter = Interpreter(buffer, opts)
        Timber.i("Model %s on NNAPI delegate", spec.engineTag())
        ModelHandle(interpreter, spec, delegate)
    } catch (t: Throwable) {
        Timber.w(t, "NNAPI delegate failed for %s, trying CPU", spec.engineTag())
        null
    }

    private fun tryCpu(buffer: ByteBuffer, spec: ModelSpec): ModelHandle? = try {
        val opts = Interpreter.Options().setNumThreads(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )
        val interpreter = Interpreter(buffer, opts)
        Timber.i("Model %s on CPU", spec.engineTag())
        ModelHandle(interpreter, spec, null)
    } catch (t: Throwable) {
        Timber.e(t, "CPU interpreter failed for %s", spec.engineTag())
        null
    }

    private fun loadMappedFile(file: File): ByteBuffer {
        FileChannel.open(file.toPath()).use { channel ->
            val size = channel.size()
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            mapped.order(ByteOrder.nativeOrder())
            return mapped
        }
    }
}
