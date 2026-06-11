package ai.deepmost.triage.cv

/** A labeled blob from connected-component analysis (pixel coordinates). */
data class Blob(
    val area: Int,
    val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    val centroidX: Float, val centroidY: Float
) {
    fun toNormRect(imgW: Int, imgH: Int): NormRect = NormRect(
        minX.toFloat() / imgW, minY.toFloat() / imgH,
        (maxX + 1).toFloat() / imgW, (maxY + 1).toFloat() / imgH
    )
}

/**
 * Two-pass connected-components labeling (union-find, 8-connectivity) on a boolean mask.
 * Used for mud-splash, stain, litter and lit-lamp blob extraction.
 */
object ConnectedComponents {

    fun label(mask: BooleanArray, width: Int, height: Int, minArea: Int = 1): List<Blob> {
        val labels = IntArray(mask.size) { -1 }
        val parent = ArrayList<Int>()

        fun newLabel(): Int { parent.add(parent.size); return parent.size - 1 }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // First pass.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!mask[idx]) continue
                var best = -1
                // Check W, N, NW, NE neighbours (already labeled).
                val neighbours = intArrayOf(
                    if (x > 0) idx - 1 else -1,
                    if (y > 0) idx - width else -1,
                    if (x > 0 && y > 0) idx - width - 1 else -1,
                    if (x < width - 1 && y > 0) idx - width + 1 else -1
                )
                for (n in neighbours) {
                    if (n >= 0 && labels[n] >= 0) {
                        best = if (best < 0) labels[n] else { union(best, labels[n]); best }
                    }
                }
                labels[idx] = if (best < 0) newLabel() else best
            }
        }

        // Second pass: resolve roots and accumulate stats.
        val stats = HashMap<Int, IntArray>() // root -> [area,minX,minY,maxX,maxY,sumX,sumY]
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (labels[idx] < 0) continue
                val root = find(labels[idx])
                val s = stats.getOrPut(root) {
                    intArrayOf(0, Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, 0, 0)
                }
                s[0]++
                if (x < s[1]) s[1] = x
                if (y < s[2]) s[2] = y
                if (x > s[3]) s[3] = x
                if (y > s[4]) s[4] = y
                s[5] += x
                s[6] += y
            }
        }

        val blobs = ArrayList<Blob>(stats.size)
        for ((_, s) in stats) {
            if (s[0] < minArea) continue
            blobs.add(
                Blob(
                    area = s[0], minX = s[1], minY = s[2], maxX = s[3], maxY = s[4],
                    centroidX = s[5].toFloat() / s[0], centroidY = s[6].toFloat() / s[0]
                )
            )
        }
        blobs.sortByDescending { it.area }
        return blobs
    }
}
