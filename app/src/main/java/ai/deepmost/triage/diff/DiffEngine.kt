package ai.deepmost.triage.diff

import ai.deepmost.triage.cv.Homography
import ai.deepmost.triage.cv.NormPoint
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.heads.AnalysisFinding
import ai.deepmost.triage.heads.RegisteredReference
import timber.log.Timber

/** Result of diffing one station's current findings against the registered previous walkaround. */
data class DiffOutcome(
    val current: List<AnalysisFinding>,   // current findings, diffStatus filled in
    val resolved: List<AnalysisFinding>   // synthesized findings for previous defects now gone
)

/**
 * Finding-level diff. Defect-type findings are matched against the registered previous
 * walkaround's defects (warped into the current frame) by zone + type-compatibility + IoU +
 * centroid proximity:
 *   - matched      -> PRE_EXISTING
 *   - unmatched current -> NEW  (the money case: damage that appeared this shift)
 *   - unmatched previous -> RESOLVED (repaired / cleaned)
 *
 * If the registration was not confident, NO NEW/RESOLVED verdict is emitted — every defect is
 * marked REVIEW_REQUIRED for side-by-side human comparison. A failed registration never
 * fabricates a diff verdict. When there is no previous station photo at all, findings are
 * FIRST_RECORD.
 */
class DiffEngine(
    private val iouThreshold: Float = 0.18f,
    private val centroidThreshold: Float = 0.12f
) {

    fun diff(current: List<AnalysisFinding>, registered: RegisteredReference?): DiffOutcome {
        // No previous station reference -> first record for this station.
        if (registered == null) {
            return DiffOutcome(current.map { it.copy(diffStatus = DiffStatus.FIRST_RECORD) }, emptyList())
        }

        // Registration failed/low confidence -> review-required for defects, no fabricated verdicts.
        if (!registered.confident || registered.homography == null) {
            Timber.w("Diff: low registration confidence (%.2f) -> REVIEW_REQUIRED", registered.inlierRatio)
            return DiffOutcome(
                current.map {
                    if (isDefect(it)) it.copy(diffStatus = DiffStatus.REVIEW_REQUIRED)
                    else it.copy(diffStatus = DiffStatus.PRE_EXISTING)
                },
                emptyList()
            )
        }

        val h = registered.homography
        val curDefects = current.filter { isDefect(it) }
        val nonDefects = current.filter { !isDefect(it) }
        val prevDefects = registered.prevFindings.filter { isDefect(it) }

        // Warp previous defect regions into the current frame.
        val warpedPrev = prevDefects.map { it to warpRect(h, it.bbox, registered) }
        val matchedPrev = HashSet<String>()

        val updatedCurrent = ArrayList<AnalysisFinding>(current.size)
        for (cur in curDefects) {
            var bestPrevId: String? = null
            var bestScore = 0f
            for ((prev, warped) in warpedPrev) {
                if (prev.id in matchedPrev) continue
                if (!compatible(cur, prev)) continue
                val iou = NormRect.iou(cur.bbox, warped)
                val centroidClose = NormPoint(cur.bbox.centerX, cur.bbox.centerY)
                    .distanceTo(NormPoint(warped.centerX, warped.centerY)) < centroidThreshold
                val sameZone = cur.zone != null && cur.zone == prev.zone
                val score = iou + (if (centroidClose) 0.2f else 0f) + (if (sameZone) 0.15f else 0f)
                val accept = iou >= iouThreshold || (centroidClose && sameZone)
                if (accept && score > bestScore) { bestScore = score; bestPrevId = prev.id }
            }
            if (bestPrevId != null) {
                matchedPrev.add(bestPrevId)
                updatedCurrent.add(cur.copy(diffStatus = DiffStatus.PRE_EXISTING, matchedFindingId = bestPrevId))
            } else {
                updatedCurrent.add(cur.copy(diffStatus = DiffStatus.NEW))
            }
        }
        // Non-defect readouts (scores, OK/INTACT/WORKING) are PRE_EXISTING in a repeat record.
        for (nd in nonDefects) updatedCurrent.add(nd.copy(diffStatus = DiffStatus.PRE_EXISTING))

        // Previous defects with no current match are RESOLVED.
        val resolved = warpedPrev
            .filter { it.first.id !in matchedPrev }
            .map { (prev, warped) ->
                prev.copy(
                    diffStatus = DiffStatus.RESOLVED,
                    matchedFindingId = prev.id,
                    bbox = warped,
                    photoId = current.firstOrNull()?.photoId
                )
            }

        Timber.i(
            "Diff %s: new=%d preexisting=%d resolved=%d",
            current.firstOrNull()?.capturePoint,
            updatedCurrent.count { it.diffStatus == DiffStatus.NEW },
            updatedCurrent.count { it.diffStatus == DiffStatus.PRE_EXISTING },
            resolved.size
        )
        return DiffOutcome(updatedCurrent, resolved)
    }

    private fun warpRect(h: Homography, rect: NormRect, ref: RegisteredReference): NormRect {
        // Previous bbox (in previous-image normalized coords) -> current frame.
        val pw = ref.prevRgb.width; val ph = ref.prevRgb.height
        // Use current frame dims from any current finding is unavailable here; warpNorm needs dst
        // dims, but normalized output is dst-dim-independent if we treat both as unit squares.
        val tl = h.warpNorm(NormPoint(rect.left, rect.top), pw, ph, pw, ph)
        val br = h.warpNorm(NormPoint(rect.right, rect.bottom), pw, ph, pw, ph)
        val l = minOf(tl.x, br.x); val r = maxOf(tl.x, br.x)
        val t = minOf(tl.y, br.y); val b = maxOf(tl.y, br.y)
        return NormRect(l, t, r, b).clampUnit()
    }

    private fun compatible(a: AnalysisFinding, b: AnalysisFinding): Boolean {
        if (a.head != b.head) return false
        // Damage anomalies/classes are mutually compatible; lamp/tyre match by exact-ish type group.
        return typeGroup(a.type) == typeGroup(b.type)
    }

    private fun typeGroup(t: FindingType): Int = when (t) {
        FindingType.ANOMALY, FindingType.SCRATCH, FindingType.DENT, FindingType.CRACK,
        FindingType.BROKEN_PART, FindingType.MISSING_PART, FindingType.PAINT_PEEL -> 1
        FindingType.LAMP_CRACKED, FindingType.LAMP_FOGGED, FindingType.LAMP_MISSING,
        FindingType.LAMP_NOT_WORKING -> 2
        FindingType.TYRE_WORN, FindingType.TYRE_DAMAGED, FindingType.TYRE_FLAT_LOW -> 3
        FindingType.MUD_SPLASH, FindingType.STAIN, FindingType.LITTER, FindingType.CLUTTER,
        FindingType.DUST -> 4
        else -> 0
    }

    private fun isDefect(f: AnalysisFinding): Boolean = when (f.type) {
        FindingType.TYRE_OK, FindingType.LAMP_INTACT, FindingType.LAMP_WORKING,
        FindingType.CLEANLINESS_SCORE, FindingType.MANUAL_NOTE -> false
        else -> f.head != FindingHead.MANUAL || f.type != FindingType.MANUAL_NOTE
    }
}
