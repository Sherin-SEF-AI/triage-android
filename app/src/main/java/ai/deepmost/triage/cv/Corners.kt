package ai.deepmost.triage.cv

import kotlin.math.abs

/** A detected corner keypoint in pixel coordinates with a saliency score. */
data class Corner(val x: Int, val y: Int, val score: Float)

/**
 * FAST-style corner detector (segment test on a Bresenham-16 ring) with non-maximum
 * suppression. Constrained, ghost-guided viewpoints make these stable enough for the
 * registration homography between consecutive walkarounds.
 */
object Corners {

    // Bresenham circle of radius 3, 16 offsets.
    private val ringDx = intArrayOf(0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1)
    private val ringDy = intArrayOf(-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3)

    fun detect(
        img: GrayImage,
        threshold: Float = 0.06f,
        contiguous: Int = 9,
        maxCorners: Int = 400,
        nmsRadius: Int = 4
    ): List<Corner> {
        val w = img.width; val h = img.height
        val candidates = ArrayList<Corner>()
        val margin = 3
        for (y in margin until h - margin) {
            for (x in margin until w - margin) {
                val p = img.lum[y * w + x]
                val hi = p + threshold
                val lo = p - threshold
                // Quick rejection with the 4 compass points.
                var brighter = 0; var darker = 0
                for (k in intArrayOf(0, 4, 8, 12)) {
                    val v = img.lum[(y + ringDy[k]) * w + (x + ringDx[k])]
                    if (v > hi) brighter++ else if (v < lo) darker++
                }
                if (brighter < 3 && darker < 3) continue

                if (hasContiguousSegment(img, x, y, hi, lo, contiguous)) {
                    candidates.add(Corner(x, y, cornerScore(img, x, y, p)))
                }
            }
        }
        return nonMaxSuppress(candidates, nmsRadius, maxCorners)
    }

    private fun hasContiguousSegment(
        img: GrayImage, x: Int, y: Int, hi: Float, lo: Float, need: Int
    ): Boolean {
        val w = img.width
        val ring = FloatArray(16)
        for (i in 0 until 16) ring[i] = img.lum[(y + ringDy[i]) * w + (x + ringDx[i])]
        // Check both brighter and darker arcs over the doubled ring.
        var runB = 0; var runD = 0
        for (i in 0 until 16 + need) {
            val v = ring[i % 16]
            if (v > hi) { runB++; if (runB >= need) return true } else runB = 0
            if (v < lo) { runD++; if (runD >= need) return true } else runD = 0
        }
        return false
    }

    private fun cornerScore(img: GrayImage, x: Int, y: Int, p: Float): Float {
        val w = img.width
        var sum = 0f
        for (i in 0 until 16) {
            sum += abs(img.lum[(y + ringDy[i]) * w + (x + ringDx[i])] - p)
        }
        return sum
    }

    private fun nonMaxSuppress(corners: List<Corner>, radius: Int, max: Int): List<Corner> {
        val sorted = corners.sortedByDescending { it.score }
        val kept = ArrayList<Corner>()
        val r2 = radius * radius
        for (c in sorted) {
            if (kept.size >= max) break
            var ok = true
            for (k in kept) {
                val dx = k.x - c.x; val dy = k.y - c.y
                if (dx * dx + dy * dy < r2) { ok = false; break }
            }
            if (ok) kept.add(c)
        }
        return kept
    }
}
