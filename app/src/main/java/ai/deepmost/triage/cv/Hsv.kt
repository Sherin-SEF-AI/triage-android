package ai.deepmost.triage.cv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Aggregate HSV statistics over a region. */
data class HsvStats(
    val meanH: Float, // degrees [0,360)
    val meanS: Float, // [0,1]
    val meanV: Float, // [0,1]
    val stdS: Float,
    val stdV: Float
)

object Hsv {

    /** Convert a packed ARGB pixel to HSV. Returns floats: h[0,360), s[0,1], v[0,1]. */
    fun rgbToHsv(pixel: Int, out: FloatArray) {
        val r = ((pixel ushr 16) and 0xFF) / 255f
        val g = ((pixel ushr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val cmax = max(r, max(g, b))
        val cmin = min(r, min(g, b))
        val delta = cmax - cmin
        val h: Float = when {
            delta < 1e-6f -> 0f
            cmax == r -> 60f * (((g - b) / delta) % 6f)
            cmax == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        out[0] = if (h < 0f) h + 360f else h
        out[1] = if (cmax <= 0f) 0f else delta / cmax
        out[2] = cmax
    }

    fun stats(img: RgbImage): HsvStats {
        val n = img.pixels.size.coerceAtLeast(1)
        val hsv = FloatArray(3)
        // Circular mean for hue.
        var sinSum = 0.0; var cosSum = 0.0
        var sSum = 0.0; var vSum = 0.0
        var sSq = 0.0; var vSq = 0.0
        for (p in img.pixels) {
            rgbToHsv(p, hsv)
            val rad = Math.toRadians(hsv[0].toDouble())
            sinSum += kotlin.math.sin(rad)
            cosSum += kotlin.math.cos(rad)
            sSum += hsv[1]; vSum += hsv[2]
            sSq += hsv[1] * hsv[1]; vSq += hsv[2] * hsv[2]
        }
        val meanH = ((Math.toDegrees(kotlin.math.atan2(sinSum / n, cosSum / n)) + 360.0) % 360.0).toFloat()
        val meanS = (sSum / n).toFloat()
        val meanV = (vSum / n).toFloat()
        val stdS = sqrt(max(0.0, sSq / n - (sSum / n) * (sSum / n))).toFloat()
        val stdV = sqrt(max(0.0, vSq / n - (vSum / n) * (vSum / n))).toFloat()
        return HsvStats(meanH, meanS, meanV, stdS, stdV)
    }

    /**
     * Binary mask of pixels whose hue is within [hueCenter]±[hueTol] (degrees, circular) with
     * saturation/value above floors. Used for mud-splash (brown) and stain blob detection.
     */
    fun hueMask(
        img: RgbImage,
        hueCenter: Float,
        hueTol: Float,
        minS: Float,
        minV: Float,
        maxV: Float = 1f
    ): BooleanArray {
        val mask = BooleanArray(img.pixels.size)
        val hsv = FloatArray(3)
        for (i in img.pixels.indices) {
            rgbToHsv(img.pixels[i], hsv)
            val dh = circularHueDistance(hsv[0], hueCenter)
            if (dh <= hueTol && hsv[1] >= minS && hsv[2] in minV..maxV) mask[i] = true
        }
        return mask
    }

    fun circularHueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
