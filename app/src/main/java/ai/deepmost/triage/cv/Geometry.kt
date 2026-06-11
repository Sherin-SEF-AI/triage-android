package ai.deepmost.triage.cv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Normalized 2D geometry primitives. All coordinates are in the unit square [0,1]x[0,1]
 * relative to a photo, so geometry is resolution-independent and survives downscaling for
 * analysis while still mapping back onto the stored full-res original.
 */
data class NormPoint(val x: Float, val y: Float) {
    fun distanceTo(o: NormPoint): Float {
        val dx = x - o.x
        val dy = y - o.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** Axis-aligned rectangle in normalized coordinates. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right - left).coerceAtLeast(0f)
    val height get() = (bottom - top).coerceAtLeast(0f)
    val area get() = width * height
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    fun clampUnit(): NormRect = NormRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
    )

    fun intersect(o: NormRect): NormRect {
        val l = max(left, o.left)
        val t = max(top, o.top)
        val r = min(right, o.right)
        val b = min(bottom, o.bottom)
        return if (r <= l || b <= t) NormRect(0f, 0f, 0f, 0f) else NormRect(l, t, r, b)
    }

    companion object {
        fun iou(a: NormRect, b: NormRect): Float {
            val inter = a.intersect(b).area
            val union = a.area + b.area - inter
            return if (union <= 0f) 0f else inter / union
        }
    }
}

/** Closed/open polygon in normalized coordinates (used for ghost outlines, zones, lamps). */
data class NormPolygon(val points: List<NormPoint>) {
    fun bounds(): NormRect {
        if (points.isEmpty()) return NormRect(0f, 0f, 0f, 0f)
        var l = 1f; var t = 1f; var r = 0f; var b = 0f
        for (p in points) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        return NormRect(l, t, r, b)
    }

    fun centroid(): NormPoint {
        if (points.isEmpty()) return NormPoint(0.5f, 0.5f)
        var sx = 0f; var sy = 0f
        for (p in points) { sx += p.x; sy += p.y }
        return NormPoint(sx / points.size, sy / points.size)
    }

    /** Ray-casting point-in-polygon test. */
    fun contains(p: NormPoint): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val pi = points[i]
            val pj = points[j]
            val intersect = (pi.y > p.y) != (pj.y > p.y) &&
                p.x < (pj.x - pi.x) * (p.y - pi.y) / ((pj.y - pi.y) + 1e-9f) + pi.x
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }
}

object Geometry {
    /** Cosine similarity of two equal-length feature vectors. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom <= 0f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }
}
