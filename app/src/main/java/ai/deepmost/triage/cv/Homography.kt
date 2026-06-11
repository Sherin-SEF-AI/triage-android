package ai.deepmost.triage.cv

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A 3x3 planar homography stored row-major (h[8] normalized to 1). Maps points from the
 * SOURCE image (previous walkaround) into the DESTINATION image (current walkaround).
 */
class Homography(val h: DoubleArray) {
    init { require(h.size == 9) }

    fun warp(x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        val u = (h[0] * x + h[1] * y + h[2]) / w
        val v = (h[3] * x + h[4] * y + h[5]) / w
        return doubleArrayOf(u, v)
    }

    /** Warp a normalized point given the SOURCE and DESTINATION image dimensions. */
    fun warpNorm(p: NormPoint, srcW: Int, srcH: Int, dstW: Int, dstH: Int): NormPoint {
        val out = warp((p.x * srcW).toDouble(), (p.y * srcH).toDouble())
        return NormPoint((out[0] / dstW).toFloat(), (out[1] / dstH).toFloat())
    }

    /** Inverse homography (destination -> source), or null if singular. */
    fun inverse(): Homography? {
        val a = h
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        if (kotlin.math.abs(det) < 1e-12) return null
        val inv = DoubleArray(9)
        inv[0] = (a[4] * a[8] - a[5] * a[7]) / det
        inv[1] = (a[2] * a[7] - a[1] * a[8]) / det
        inv[2] = (a[1] * a[5] - a[2] * a[4]) / det
        inv[3] = (a[5] * a[6] - a[3] * a[8]) / det
        inv[4] = (a[0] * a[8] - a[2] * a[6]) / det
        inv[5] = (a[2] * a[3] - a[0] * a[5]) / det
        inv[6] = (a[3] * a[7] - a[4] * a[6]) / det
        inv[7] = (a[1] * a[6] - a[0] * a[7]) / det
        inv[8] = (a[0] * a[4] - a[1] * a[3]) / det
        // Normalize so inv[8] == 1 for consistency.
        if (kotlin.math.abs(inv[8]) > 1e-12) {
            val s = inv[8]
            for (i in 0 until 9) inv[i] /= s
        }
        return Homography(inv)
    }
}

/** Result of a RANSAC homography estimation. */
data class RegistrationResult(
    val homography: Homography?,
    val inliers: Int,
    val total: Int
) {
    val inlierRatio: Float get() = if (total == 0) 0f else inliers.toFloat() / total
}

object HomographyEstimator {

    /**
     * Estimate a homography from pixel-space correspondences via RANSAC over minimal
     * 4-point DLT solves, then refit on the consensus set. Seeded RNG keeps it deterministic
     * (guideline 5). Returns the model plus inlier statistics for the confidence gate.
     */
    fun ransac(
        matches: List<Match>,
        iterations: Int = 300,
        inlierThresholdPx: Double = 3.0,
        seed: Long = 1234567L
    ): RegistrationResult {
        if (matches.size < 4) return RegistrationResult(null, 0, matches.size)
        val rng = Random(seed)
        val n = matches.size
        var bestInliers = -1
        var bestModel: DoubleArray? = null
        val thr2 = inlierThresholdPx * inlierThresholdPx

        repeat(iterations) {
            val idx = pickFour(n, rng)
            val src = Array(4) { doubleArrayOf(matches[idx[it]].ax.toDouble(), matches[idx[it]].ay.toDouble()) }
            val dst = Array(4) { doubleArrayOf(matches[idx[it]].bx.toDouble(), matches[idx[it]].by.toDouble()) }
            val model = dlt(src, dst) ?: return@repeat
            var inliers = 0
            for (m in matches) {
                val w = model[6] * m.ax + model[7] * m.ay + model[8]
                if (kotlin.math.abs(w) < 1e-9) continue
                val u = (model[0] * m.ax + model[1] * m.ay + model[2]) / w
                val v = (model[3] * m.ax + model[4] * m.ay + model[5]) / w
                val du = u - m.bx; val dv = v - m.by
                if (du * du + dv * dv < thr2) inliers++
            }
            if (inliers > bestInliers) { bestInliers = inliers; bestModel = model }
        }

        val model = bestModel ?: return RegistrationResult(null, 0, n)
        // Refit on all inliers for a stable final estimate.
        val inSrc = ArrayList<DoubleArray>()
        val inDst = ArrayList<DoubleArray>()
        for (m in matches) {
            val w = model[6] * m.ax + model[7] * m.ay + model[8]
            if (kotlin.math.abs(w) < 1e-9) continue
            val u = (model[0] * m.ax + model[1] * m.ay + model[2]) / w
            val v = (model[3] * m.ax + model[4] * m.ay + model[5]) / w
            val du = u - m.bx; val dv = v - m.by
            if (du * du + dv * dv < thr2) {
                inSrc.add(doubleArrayOf(m.ax.toDouble(), m.ay.toDouble()))
                inDst.add(doubleArrayOf(m.bx.toDouble(), m.by.toDouble()))
            }
        }
        val refined = if (inSrc.size >= 4) dltLeastSquares(inSrc, inDst) ?: model else model
        return RegistrationResult(Homography(refined), bestInliers, n)
    }

    private fun pickFour(n: Int, rng: Random): IntArray {
        val s = HashSet<Int>()
        while (s.size < 4) s.add(rng.nextInt(n))
        return s.toIntArray()
    }

    /** Exact 4-point DLT (8 unknowns, h22 fixed to 1) via Gaussian elimination. */
    private fun dlt(src: Array<DoubleArray>, dst: Array<DoubleArray>): DoubleArray? {
        return dltLeastSquares(src.toList(), dst.toList())
    }

    /**
     * Least-squares homography with h22 == 1. Builds the 2N x 8 system and solves the 8x8
     * normal equations. Returns a 9-vector (last element 1).
     */
    private fun dltLeastSquares(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
        val n = src.size
        if (n < 4) return null
        // Normal equations A^T A x = A^T b, where x = [h0..h7].
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        val row = DoubleArray(8)
        for (i in 0 until n) {
            val x = src[i][0]; val y = src[i][1]
            val u = dst[i][0]; val v = dst[i][1]
            // Equation for u.
            row[0] = x; row[1] = y; row[2] = 1.0; row[3] = 0.0; row[4] = 0.0; row[5] = 0.0
            row[6] = -u * x; row[7] = -u * y
            accumulate(ata, atb, row, u)
            // Equation for v.
            row[0] = 0.0; row[1] = 0.0; row[2] = 0.0; row[3] = x; row[4] = y; row[5] = 1.0
            row[6] = -v * x; row[7] = -v * y
            accumulate(ata, atb, row, v)
        }
        val sol = solve8(ata, atb) ?: return null
        return doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0)
    }

    private fun accumulate(ata: Array<DoubleArray>, atb: DoubleArray, row: DoubleArray, b: Double) {
        for (r in 0 until 8) {
            atb[r] += row[r] * b
            val ar = ata[r]
            val rr = row[r]
            for (c in 0 until 8) ar[c] += rr * row[c]
        }
    }

    /** Solve an 8x8 linear system via Gaussian elimination with partial pivoting. */
    private fun solve8(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 8
        val m = Array(n) { i -> DoubleArray(n + 1).also { System.arraycopy(a[i], 0, it, 0, n); it[n] = b[i] } }
        for (col in 0 until n) {
            var pivot = col
            var maxAbs = kotlin.math.abs(m[col][col])
            for (r in col + 1 until n) {
                val v = kotlin.math.abs(m[r][col])
                if (v > maxAbs) { maxAbs = v; pivot = r }
            }
            if (maxAbs < 1e-12) return null
            if (pivot != col) { val t = m[pivot]; m[pivot] = m[col]; m[col] = t }
            val pv = m[col][col]
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col] / pv
                if (factor == 0.0) continue
                for (c in col..n) m[r][c] -= factor * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (i in 0 until n) x[i] = m[i][n] / m[i][i]
        return x
    }

    /** Geometric reprojection RMS of matches under a homography (diagnostic). */
    fun reprojectionRms(h: Homography, matches: List<Match>): Float {
        if (matches.isEmpty()) return 0f
        var sum = 0.0
        for (m in matches) {
            val p = h.warp(m.ax.toDouble(), m.ay.toDouble())
            val du = p[0] - m.bx; val dv = p[1] - m.by
            sum += du * du + dv * dv
        }
        return sqrt(sum / matches.size).toFloat()
    }
}
