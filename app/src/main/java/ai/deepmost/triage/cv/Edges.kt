package ai.deepmost.triage.cv

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Edge / gradient operators on [GrayImage]. Used by the quality gate (sharpness via
 * Laplacian variance), the damage change-detector (edge density), the lamp head
 * (crack-edge density) and registration (corner pre-filtering).
 */
object Edges {

    /** Per-pixel Laplacian response (4-neighbour). Border pixels are 0. */
    fun laplacian(img: GrayImage): FloatArray {
        val w = img.width; val h = img.height
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = img.lum[y * w + x]
                val v = img.lum[(y - 1) * w + x] + img.lum[(y + 1) * w + x] +
                    img.lum[y * w + (x - 1)] + img.lum[y * w + (x + 1)] - 4f * c
                out[y * w + x] = v
            }
        }
        return out
    }

    /**
     * Variance of the Laplacian — the canonical focus/sharpness measure. Higher == sharper.
     * Returned in (luminance^2) units; the quality gate compares against a configured threshold.
     */
    fun laplacianVariance(img: GrayImage): Float {
        val lap = laplacian(img)
        var mean = 0.0
        for (v in lap) mean += v
        mean /= lap.size
        var varSum = 0.0
        for (v in lap) {
            val d = v - mean
            varSum += d * d
        }
        return (varSum / lap.size).toFloat()
    }

    /** Sobel gradient magnitude image (normalized roughly to [0,1]). */
    fun sobelMagnitude(img: GrayImage): FloatArray {
        val w = img.width; val h = img.height
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = img.lum[(y - 1) * w + (x - 1)]
                val tc = img.lum[(y - 1) * w + x]
                val tr = img.lum[(y - 1) * w + (x + 1)]
                val ml = img.lum[y * w + (x - 1)]
                val mr = img.lum[y * w + (x + 1)]
                val bl = img.lum[(y + 1) * w + (x - 1)]
                val bc = img.lum[(y + 1) * w + x]
                val br = img.lum[(y + 1) * w + (x + 1)]
                val gx = (tr + 2f * mr + br) - (tl + 2f * ml + bl)
                val gy = (bl + 2f * bc + br) - (tl + 2f * tc + tr)
                out[y * w + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /**
     * Fraction of pixels whose Sobel magnitude exceeds [threshold]. A simple, stable proxy
     * for "how much structure is here", used both for framing (is a vehicle in the ghost
     * region?) and for the lamp crack-edge metric.
     */
    fun edgeDensity(img: GrayImage, threshold: Float = 0.18f): Float {
        val mag = sobelMagnitude(img)
        var count = 0
        for (m in mag) if (m > threshold) count++
        return count.toFloat() / mag.size
    }

    /**
     * High-frequency energy: mean absolute Laplacian response. Dust/mud speckle and tread
     * texture both raise this; a bald tyre or a clean smooth panel lowers it.
     */
    fun highFrequencyEnergy(img: GrayImage): Float {
        val lap = laplacian(img)
        var sum = 0.0
        for (v in lap) sum += abs(v)
        return (sum / lap.size).toFloat()
    }

    /** Local RMS contrast over the whole image (std dev of luminance). */
    fun rmsContrast(img: GrayImage): Float {
        var mean = 0.0
        for (v in img.lum) mean += v
        mean /= img.lum.size
        var varSum = 0.0
        for (v in img.lum) {
            val d = v - mean
            varSum += d * d
        }
        return sqrt(varSum / img.lum.size).toFloat()
    }
}
