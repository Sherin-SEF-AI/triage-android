package ai.deepmost.triage.cv

import kotlin.math.sqrt

/** Luminance histogram statistics used by the exposure portion of the quality gate. */
data class ExposureStats(
    val mean: Float,           // [0,1]
    val stdDev: Float,
    val overExposedFraction: Float,   // fraction of pixels at/near white
    val underExposedFraction: Float,  // fraction of pixels at/near black
    val clippingFraction: Float       // over + under
)

object Histogram {

    /** 256-bin luminance histogram of a [GrayImage]. */
    fun luminanceHistogram(img: GrayImage): IntArray {
        val hist = IntArray(256)
        for (v in img.lum) {
            val bin = (v * 255f).toInt().coerceIn(0, 255)
            hist[bin]++
        }
        return hist
    }

    fun exposureStats(
        img: GrayImage,
        highThreshold: Int = 250,
        lowThreshold: Int = 5
    ): ExposureStats {
        val hist = luminanceHistogram(img)
        val total = img.lum.size.coerceAtLeast(1)
        var over = 0
        var under = 0
        for (b in highThreshold..255) over += hist[b]
        for (b in 0..lowThreshold) under += hist[b]
        var mean = 0.0
        for (v in img.lum) mean += v
        mean /= total
        var varSum = 0.0
        for (v in img.lum) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / total).toFloat()
        val overF = over.toFloat() / total
        val underF = under.toFloat() / total
        return ExposureStats(mean.toFloat(), std, overF, underF, overF + underF)
    }

    /** Mean luminance only — cheap brightness probe for interior stations. */
    fun meanLuminance(img: GrayImage): Float {
        if (img.lum.isEmpty()) return 0f
        var sum = 0.0
        for (v in img.lum) sum += v
        return (sum / img.lum.size).toFloat()
    }

    /** Cumulative histogram, normalized to [0,1]. Used for histogram-based comparisons. */
    fun normalizedCumulative(hist: IntArray): FloatArray {
        val total = hist.sum().coerceAtLeast(1)
        val cum = FloatArray(hist.size)
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            cum[i] = acc.toFloat() / total
        }
        return cum
    }

    /**
     * Earth-mover-ish distance between two luminance histograms via their normalized CDFs.
     * Robust to small brightness shifts; used as one appearance-similarity signal.
     */
    fun cdfDistance(a: GrayImage, b: GrayImage): Float {
        val ca = normalizedCumulative(luminanceHistogram(a))
        val cb = normalizedCumulative(luminanceHistogram(b))
        var sum = 0f
        for (i in ca.indices) sum += kotlin.math.abs(ca[i] - cb[i])
        return sum / ca.size
    }
}
