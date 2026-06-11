package ai.deepmost.triage.registry

import ai.deepmost.triage.cv.NormRect
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/** A model detection box in normalized coordinates. */
data class ModelDetection(val rect: NormRect, val labelIndex: Int, val label: String, val score: Float)

/** A class-map from a segmentation model. */
class SegMap(val width: Int, val height: Int, val classOf: IntArray, val numClasses: Int) {
    fun coverage(labelIndex: Int): Float {
        var c = 0
        for (v in classOf) if (v == labelIndex) c++
        return c.toFloat() / classOf.size.coerceAtLeast(1)
    }
}

/**
 * Wraps a LiteRT interpreter with typed run helpers for the three conventional output
 * signatures used by the heads. The expected I/O contract per task is documented in the README;
 * a model that conforms drops in without code changes, and any decode error is caught so the
 * head reverts to classical CV.
 *
 *  - CLASSIFICATION: input [1,H,W,3] float [0,1]; output [1,numClasses] (logits or probs).
 *  - DETECTION:      TFLite-SSD post-processed 4-output: boxes[1,N,4] (ymin,xmin,ymax,xmax),
 *                    classes[1,N], scores[1,N], count[1].
 *  - SEGMENTATION:   input [1,H,W,3]; output [1,H,W,numClasses] float (per-class scores).
 */
class ModelHandle(
    private val interpreter: Interpreter,
    val spec: ModelSpec,
    private val delegate: AutoCloseable?
) {
    private val inW = spec.inputWidth
    private val inH = spec.inputHeight

    /** True when the model's input tensor is quantized uint8 (e.g. COCO SSD) rather than float32. */
    private val quantizedInput: Boolean = runCatching {
        interpreter.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.UINT8
    }.getOrDefault(false)

    private fun inputBuffer(bitmap: Bitmap): ByteBuffer {
        val resized = if (bitmap.width != inW || bitmap.height != inH)
            Bitmap.createScaledBitmap(bitmap, inW, inH, true) else bitmap
        val px = IntArray(inW * inH)
        resized.getPixels(px, 0, inW, 0, 0, inW, inH)
        return if (quantizedInput) {
            // uint8 RGB in [0,255], no normalization.
            val buf = ByteBuffer.allocateDirect(inW * inH * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                buf.put(((p ushr 16) and 0xFF).toByte())
                buf.put(((p ushr 8) and 0xFF).toByte())
                buf.put((p and 0xFF).toByte())
            }
            buf.rewind(); buf
        } else {
            // float32 RGB normalized to [0,1].
            val buf = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                buf.putFloat(((p ushr 16) and 0xFF) / 255f)
                buf.putFloat(((p ushr 8) and 0xFF) / 255f)
                buf.putFloat((p and 0xFF) / 255f)
            }
            buf.rewind(); buf
        }
    }

    fun classify(bitmap: Bitmap): FloatArray? = try {
        val n = spec.labels.size.coerceAtLeast(1)
        val out = Array(1) { FloatArray(n) }
        interpreter.run(inputBuffer(bitmap), out)
        softmax(out[0])
    } catch (t: Throwable) {
        Timber.e(t, "classify failed for %s", spec.engineTag()); null
    }

    fun detect(bitmap: Bitmap, scoreThreshold: Float = 0.4f, maxDet: Int = 25): List<ModelDetection>? = try {
        val boxes = Array(1) { Array(maxDet) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxDet) }
        val scores = Array(1) { FloatArray(maxDet) }
        val count = FloatArray(1)
        val outputs: Map<Int, Any> = mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer(bitmap)), outputs)
        val result = ArrayList<ModelDetection>()
        val n = count[0].toInt().coerceIn(0, maxDet)
        for (i in 0 until n) {
            val s = scores[0][i]
            if (s < scoreThreshold) continue
            val b = boxes[0][i] // ymin, xmin, ymax, xmax
            val idx = classes[0][i].toInt().coerceIn(0, spec.labels.size - 1)
            result.add(
                ModelDetection(
                    NormRect(b[1], b[0], b[3], b[2]).clampUnit(),
                    idx, spec.labels.getOrElse(idx) { "class$idx" }, s
                )
            )
        }
        result
    } catch (t: Throwable) {
        Timber.e(t, "detect failed for %s", spec.engineTag()); null
    }

    fun segment(bitmap: Bitmap): SegMap? = try {
        val numClasses = spec.labels.size.coerceAtLeast(1)
        val out = Array(1) { Array(inH) { Array(inW) { FloatArray(numClasses) } } }
        interpreter.run(inputBuffer(bitmap), out)
        val classOf = IntArray(inW * inH)
        for (y in 0 until inH) {
            for (x in 0 until inW) {
                val scores = out[0][y][x]
                var best = 0; var bestV = scores[0]
                for (c in 1 until numClasses) if (scores[c] > bestV) { bestV = scores[c]; best = c }
                classOf[y * inW + x] = best
            }
        }
        SegMap(inW, inH, classOf, numClasses)
    } catch (t: Throwable) {
        Timber.e(t, "segment failed for %s", spec.engineTag()); null
    }

    private fun softmax(v: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (x in v) if (x > max) max = x
        var sum = 0f
        val out = FloatArray(v.size)
        for (i in v.indices) { out[i] = exp(v[i] - max); sum += out[i] }
        if (sum > 0f) for (i in out.indices) out[i] /= sum
        return out
    }

    fun close() {
        runCatching { interpreter.close() }
        runCatching { delegate?.close() }
    }
}
