package ai.deepmost.triage.cv

/** A keypoint plus its normalized patch descriptor (mean-subtracted, unit-norm). */
class FeaturePoint(val x: Int, val y: Int, val descriptor: FloatArray)

/** A pair of matched keypoints between two images, with NCC similarity. */
data class Match(val ax: Int, val ay: Int, val bx: Int, val by: Int, val score: Float)

/**
 * Normalized-cross-correlation patch descriptors and a mutual-best-match matcher. With
 * ghost-constrained viewpoints the appearance change between walkarounds is small, so NCC
 * patches are sufficient correspondences for a RANSAC homography.
 */
object Descriptors {

    const val PATCH = 9 // odd window size

    fun describe(img: GrayImage, corners: List<Corner>): List<FeaturePoint> {
        val r = PATCH / 2
        val w = img.width; val h = img.height
        val out = ArrayList<FeaturePoint>(corners.size)
        for (c in corners) {
            if (c.x - r < 0 || c.y - r < 0 || c.x + r >= w || c.y + r >= h) continue
            val desc = FloatArray(PATCH * PATCH)
            var idx = 0
            var sum = 0f
            for (dy in -r..r) {
                val row = (c.y + dy) * w
                for (dx in -r..r) {
                    val v = img.lum[row + c.x + dx]
                    desc[idx++] = v
                    sum += v
                }
            }
            val mean = sum / desc.size
            var norm = 0f
            for (i in desc.indices) { desc[i] -= mean; norm += desc[i] * desc[i] }
            norm = kotlin.math.sqrt(norm)
            if (norm < 1e-5f) continue
            for (i in desc.indices) desc[i] /= norm
            out.add(FeaturePoint(c.x, c.y, desc))
        }
        return out
    }

    /** NCC between two unit-norm, mean-subtracted descriptors == their dot product. */
    private fun ncc(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /**
     * Mutual-best NCC matching with a minimum-score gate and a spatial search window
     * (matches between consecutive walkarounds are spatially local).
     */
    fun match(
        a: List<FeaturePoint>,
        b: List<FeaturePoint>,
        imgW: Int,
        imgH: Int,
        minScore: Float = 0.80f,
        searchFrac: Float = 0.35f
    ): List<Match> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val win = (searchFrac * maxOf(imgW, imgH))
        val win2 = win * win
        val bestForA = IntArray(a.size) { -1 }
        val bestScoreA = FloatArray(a.size) { minScore }
        val bestForB = IntArray(b.size) { -1 }
        val bestScoreB = FloatArray(b.size) { minScore }

        for (i in a.indices) {
            val ai = a[i]
            for (j in b.indices) {
                val bj = b[j]
                val dx = (ai.x - bj.x).toFloat(); val dy = (ai.y - bj.y).toFloat()
                if (dx * dx + dy * dy > win2) continue
                val s = ncc(ai.descriptor, bj.descriptor)
                if (s > bestScoreA[i]) { bestScoreA[i] = s; bestForA[i] = j }
                if (s > bestScoreB[j]) { bestScoreB[j] = s; bestForB[j] = i }
            }
        }

        val matches = ArrayList<Match>()
        for (i in a.indices) {
            val j = bestForA[i]
            if (j >= 0 && bestForB[j] == i) {
                matches.add(Match(a[i].x, a[i].y, b[j].x, b[j].y, bestScoreA[i]))
            }
        }
        return matches
    }
}
