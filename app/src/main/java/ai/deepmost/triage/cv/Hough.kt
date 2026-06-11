package ai.deepmost.triage.cv

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A detected circle/ellipse approximation in pixel coordinates. */
data class DetectedCircle(
    val cx: Float, val cy: Float, val radius: Float,
    val score: Int,        // accumulator votes
    val aspectDeviation: Float // measured vertical/horizontal extent deviation from 1.0 (bulge proxy)
)

/**
 * Minimal Hough-circle voting in pure Kotlin, restricted to a sensible radius band so it is
 * fast enough for on-device tyre detection on a closeup. We vote with gradient-edge pixels,
 * scan a coarse radius range, and refine the best centre. Aspect deviation (a flat-tyre /
 * sidewall-bulge proxy) is estimated from the spread of strong edge pixels around the centre.
 */
object Hough {

    fun detectBestCircle(
        img: GrayImage,
        minRadiusFrac: Float = 0.22f,
        maxRadiusFrac: Float = 0.48f,
        edgeThreshold: Float = 0.22f,
        accumStep: Int = 2
    ): DetectedCircle? {
        val w = img.width; val h = img.height
        if (w < 16 || h < 16) return null
        val mag = Edges.sobelMagnitude(img)

        val minDim = minOf(w, h)
        val rMin = (minRadiusFrac * minDim).roundToInt().coerceAtLeast(4)
        val rMax = (maxRadiusFrac * minDim).roundToInt().coerceAtLeast(rMin + 2)

        // Collect edge points (downsampled for speed).
        val edges = ArrayList<Int>()
        for (y in 0 until h step accumStep) {
            for (x in 0 until w step accumStep) {
                if (mag[y * w + x] > edgeThreshold) edges.add(y * w + x)
            }
        }
        if (edges.size < 12) return null

        // Accumulator over centre grid (coarse) and radius.
        val gridStep = accumStep
        val gw = (w + gridStep - 1) / gridStep
        val gh = (h + gridStep - 1) / gridStep
        var bestVotes = 0
        var bestCx = 0; var bestCy = 0; var bestR = rMin

        // For each candidate radius, vote centres along gradient direction approximations:
        // a coarse but effective scheme — each edge point votes for centres on a ring.
        val angles = 24
        val cosT = FloatArray(angles)
        val sinT = FloatArray(angles)
        for (a in 0 until angles) {
            val t = (2.0 * Math.PI * a / angles)
            cosT[a] = cos(t).toFloat(); sinT[a] = sin(t).toFloat()
        }

        var r = rMin
        while (r <= rMax) {
            val acc = IntArray(gw * gh)
            for (e in edges) {
                val ex = e % w; val ey = e / w
                for (a in 0 until angles) {
                    val cx = (ex - r * cosT[a]).roundToInt()
                    val cy = (ey - r * sinT[a]).roundToInt()
                    if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue
                    val gi = (cy / gridStep) * gw + (cx / gridStep)
                    acc[gi]++
                }
            }
            for (gi in acc.indices) {
                if (acc[gi] > bestVotes) {
                    bestVotes = acc[gi]
                    bestCx = (gi % gw) * gridStep + gridStep / 2
                    bestCy = (gi / gw) * gridStep + gridStep / 2
                    bestR = r
                }
            }
            r += 2
        }
        if (bestVotes < 12) return null

        val aspectDeviation = estimateAspectDeviation(edges, w, bestCx, bestCy, bestR)
        return DetectedCircle(bestCx.toFloat(), bestCy.toFloat(), bestR.toFloat(), bestVotes, aspectDeviation)
    }

    /**
     * Estimate how non-circular the edge cloud around the centre is: compare horizontal vs
     * vertical mean radial extent. A flat/low tyre flattens the bottom and bulges sidewalls,
     * pushing this away from 0.
     */
    private fun estimateAspectDeviation(
        edges: List<Int>, w: Int, cx: Int, cy: Int, r: Int
    ): Float {
        var hSum = 0.0; var hN = 0
        var vSum = 0.0; var vN = 0
        val band = r * 0.5f
        for (e in edges) {
            val ex = e % w; val ey = e / w
            val dx = (ex - cx).toFloat(); val dy = (ey - cy).toFloat()
            val dist = sqrt(dx * dx + dy * dy)
            if (kotlin.math.abs(dist - r) > band) continue
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) { hSum += kotlin.math.abs(dx); hN++ }
            else { vSum += kotlin.math.abs(dy); vN++ }
        }
        if (hN == 0 || vN == 0) return 0f
        val hMean = hSum / hN
        val vMean = vSum / vN
        val ratio = if (hMean <= 0) 1.0 else vMean / hMean
        return kotlin.math.abs(ratio - 1.0).toFloat()
    }
}
