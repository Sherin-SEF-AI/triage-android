package ai.deepmost.triage.cv

/** Specular-highlight metrics. Clean polished paint shows crisp, high-contrast speculars;
 *  dust and dirt diffuse them, lowering crispness even when brightness is similar. */
data class SpecularStats(
    val highlightFraction: Float, // fraction of bright low-saturation pixels
    val crispness: Float          // mean edge magnitude at highlight borders
)

object Specular {

    /**
     * Detect specular highlights as bright (high V) low-saturation pixels, then measure how
     * crisp their borders are via Sobel magnitude at the highlight boundary. Diffuse dirt
     * produces a blurry boundary (low crispness).
     */
    fun analyze(img: RgbImage, vThreshold: Float = 0.82f, sMax: Float = 0.25f): SpecularStats {
        val n = img.pixels.size.coerceAtLeast(1)
        val mask = BooleanArray(img.pixels.size)
        val hsv = FloatArray(3)
        var count = 0
        for (i in img.pixels.indices) {
            Hsv.rgbToHsv(img.pixels[i], hsv)
            if (hsv[2] >= vThreshold && hsv[1] <= sMax) {
                mask[i] = true; count++
            }
        }
        val highlightFraction = count.toFloat() / n
        if (count == 0) return SpecularStats(0f, 0f)

        val gray = img.toGray()
        val mag = Edges.sobelMagnitude(gray)
        val w = img.width; val h = img.height
        var edgeSum = 0.0
        var edgeCount = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (!mask[idx]) continue
                // Boundary pixel: highlight with at least one non-highlight neighbour.
                val border = !mask[idx - 1] || !mask[idx + 1] ||
                    !mask[idx - w] || !mask[idx + w]
                if (border) { edgeSum += mag[idx]; edgeCount++ }
            }
        }
        val crispness = if (edgeCount == 0) 0f else (edgeSum / edgeCount).toFloat()
        return SpecularStats(highlightFraction, crispness)
    }
}
