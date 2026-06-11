package ai.deepmost.triage.heads.clean

import ai.deepmost.triage.cv.ConnectedComponents
import ai.deepmost.triage.cv.Edges
import ai.deepmost.triage.cv.GrayImage
import ai.deepmost.triage.cv.Hsv
import ai.deepmost.triage.cv.NormPolygon
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.cv.RgbImage
import ai.deepmost.triage.cv.Specular
import kotlinx.serialization.Serializable

/**
 * Appearance statistics for a single zone. These are the features the cleanliness head compares
 * against the per-vehicle clean baseline, and that the damage change-detector compares against
 * the registered previous walkaround.
 */
@Serializable
data class ZoneStats(
    val saturation: Float,       // mean HSV S
    val value: Float,            // mean HSV V (brightness)
    val texture: Float,          // mean |Laplacian| — dust/mud speckle raises this on smooth paint
    val specularCrisp: Float,    // crispness of specular highlights — dirt diffuses them (lowers)
    val specularFraction: Float, // fraction of crisp highlight pixels
    val edgeDensity: Float,      // structural edge fraction
    val brownFraction: Float,    // mud-hue blob coverage (used on lower-body zones)
    val pixelCount: Int
)

/** Cached reference metrics for a station baseline (per-zone + lower-body brown floor). */
@Serializable
data class BaselineMetrics(
    val zones: Map<String, ZoneStats> = emptyMap()
)

object ZoneMetrics {

    /** Compute appearance stats for the pixels inside a normalized polygon. */
    fun compute(rgb: RgbImage, gray: GrayImage, polygon: NormPolygon): ZoneStats {
        val bounds = polygon.bounds().clampUnit()
        if (bounds.area <= 0f) return EMPTY
        val cropRgb = rgb.crop(bounds)
        val cropGray = gray.crop(bounds)
        val w = cropRgb.width; val h = cropRgb.height
        if (w < 2 || h < 2) return EMPTY

        // Build polygon mask in crop coordinates.
        val mask = BooleanArray(w * h)
        var inCount = 0
        for (y in 0 until h) {
            val ny = bounds.top + (y + 0.5f) / h * bounds.height
            for (x in 0 until w) {
                val nx = bounds.left + (x + 0.5f) / w * bounds.width
                if (polygon.contains(ai.deepmost.triage.cv.NormPoint(nx, ny))) {
                    mask[y * w + x] = true; inCount++
                }
            }
        }
        if (inCount < 16) return EMPTY

        // HSV means over masked pixels.
        val hsv = FloatArray(3)
        var sSum = 0.0; var vSum = 0.0
        for (i in cropRgb.pixels.indices) {
            if (!mask[i]) continue
            Hsv.rgbToHsv(cropRgb.pixels[i], hsv)
            sSum += hsv[1]; vSum += hsv[2]
        }
        val meanS = (sSum / inCount).toFloat()
        val meanV = (vSum / inCount).toFloat()

        // Texture (masked mean |Laplacian|) and edge density.
        val lap = Edges.laplacian(cropGray)
        val sobel = Edges.sobelMagnitude(cropGray)
        var texSum = 0.0; var edgeCount = 0
        for (i in lap.indices) {
            if (!mask[i]) continue
            texSum += kotlin.math.abs(lap[i])
            if (sobel[i] > 0.18f) edgeCount++
        }
        val texture = (texSum / inCount).toFloat()
        val edgeDensity = edgeCount.toFloat() / inCount

        val spec = Specular.analyze(cropRgb)
        val brown = brownFraction(cropRgb, mask, inCount)

        return ZoneStats(meanS, meanV, texture, spec.crispness, spec.highlightFraction, edgeDensity, brown, inCount)
    }

    /** Fraction of masked pixels matching a mud/dirt brown hue band with connected support. */
    private fun brownFraction(crop: RgbImage, mask: BooleanArray, inCount: Int): Float {
        val hsv = FloatArray(3)
        val brownMask = BooleanArray(crop.pixels.size)
        var count = 0
        for (i in crop.pixels.indices) {
            if (!mask[i]) continue
            Hsv.rgbToHsv(crop.pixels[i], hsv)
            // Brown/ochre: hue ~20-45 deg, moderate saturation, low-mid value.
            if (hsv[0] in 18f..50f && hsv[1] in 0.20f..0.85f && hsv[2] in 0.12f..0.65f) {
                brownMask[i] = true; count++
            }
        }
        if (count < 8) return 0f
        // Require some connected support so noise doesn't read as mud.
        val blobs = ConnectedComponents.label(brownMask, crop.width, crop.height, minArea = 12)
        var blobArea = 0
        for (b in blobs) blobArea += b.area
        return blobArea.toFloat() / inCount
    }

    fun rectStats(rgb: RgbImage, gray: GrayImage, rect: NormRect): ZoneStats =
        compute(rgb, gray, NormPolygon(listOf(
            ai.deepmost.triage.cv.NormPoint(rect.left, rect.top),
            ai.deepmost.triage.cv.NormPoint(rect.right, rect.top),
            ai.deepmost.triage.cv.NormPoint(rect.right, rect.bottom),
            ai.deepmost.triage.cv.NormPoint(rect.left, rect.bottom)
        )))

    val EMPTY = ZoneStats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)
}
