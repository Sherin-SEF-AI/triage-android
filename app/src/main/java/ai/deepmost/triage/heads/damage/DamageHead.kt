package ai.deepmost.triage.heads.damage

import ai.deepmost.triage.config.CfgZone
import ai.deepmost.triage.config.HeadId
import ai.deepmost.triage.cv.Edges
import ai.deepmost.triage.cv.NormPoint
import ai.deepmost.triage.cv.NormPolygon
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.data.Engine
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.LabelSource
import ai.deepmost.triage.heads.AnalysisContext
import ai.deepmost.triage.heads.AnalysisFinding
import ai.deepmost.triage.heads.AnalysisHead
import ai.deepmost.triage.heads.clean.ZoneMetrics
import ai.deepmost.triage.heads.clean.ZoneStats
import ai.deepmost.triage.registry.ModelTask
import kotlin.math.abs

/**
 * Damage head.
 *
 * MODEL path: a segmentation model (scratch/dent/crack/broken-part/missing-part/paint-peel)
 * is decoded into per-class regions and emitted as typed findings.
 *
 * CLASSICAL fallback is an HONEST CHANGE-DETECTOR, not a classifier. For each panel zone it
 * compares appearance statistics (texture, edge density, specular crispness, value) against the
 * SAME zone in a reference:
 *   1. the registered previous walkaround (preferred — warped via the homography), else
 *   2. the clean baseline, else
 *   3. nothing — in which case it runs an absolute within-zone grid-outlier pass and marks every
 *      candidate LOW_CONFIDENCE / NO_PRIOR_REFERENCE.
 * Classical findings are type=ANOMALY and require driver confirmation; the record states the
 * engine was classical so nobody mistakes a change-detection for a damage classification.
 */
class DamageHead : AnalysisHead {
    override val id = HeadId.DAMAGE

    override fun appliesTo(ctx: AnalysisContext): Boolean =
        ctx.station.activeHeads.contains(HeadId.DAMAGE)

    override suspend fun analyze(ctx: AnalysisContext): List<AnalysisFinding> {
        modelFindings(ctx)?.let { if (it.isNotEmpty() || ctx.registry.handleFor("DAMAGE") != null) return it }
        return classicalFindings(ctx)
    }

    // ---- Model path ----

    private fun modelFindings(ctx: AnalysisContext): List<AnalysisFinding>? {
        val handle = ctx.registry.handleFor("DAMAGE") ?: return null
        if (handle.spec.modelTask != ModelTask.SEGMENTATION) return null
        return try {
            val bmp = ai.deepmost.triage.cv.Images.rgbToBitmap(ctx.rgb)
            val seg = handle.segment(bmp) ?: return null
            val findings = ArrayList<AnalysisFinding>()
            // Skip class 0 (assumed background); emit a finding per non-background class present.
            for (cls in 1 until seg.numClasses) {
                val cov = seg.coverage(cls)
                if (cov < 0.004f) continue
                val rect = classBounds(seg, cls)
                findings.add(
                    AnalysisFinding(
                        id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
                        capturePoint = ctx.station.id, head = FindingHead.DAMAGE,
                        type = mapDamageLabel(handle.spec.labels.getOrElse(cls) { "" }),
                        zone = zoneForRect(ctx, rect),
                        severity = (cov * 8f).coerceIn(0.1f, 1f),
                        confidence = 0.75f, bbox = rect,
                        engine = handle.spec.engineTag(), lowConfidence = false,
                        labelSource = LabelSource.MODEL_ONLY, createdAt = ctx.nowMillis
                    )
                )
            }
            findings
        } catch (t: Throwable) { null }
    }

    private fun classBounds(seg: ai.deepmost.triage.registry.SegMap, cls: Int): NormRect {
        var minX = seg.width; var minY = seg.height; var maxX = 0; var maxY = 0
        for (y in 0 until seg.height) for (x in 0 until seg.width) {
            if (seg.classOf[y * seg.width + x] == cls) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < minX) return NormRect(0f, 0f, 0f, 0f)
        return NormRect(
            minX.toFloat() / seg.width, minY.toFloat() / seg.height,
            (maxX + 1f) / seg.width, (maxY + 1f) / seg.height
        )
    }

    private fun mapDamageLabel(label: String): FindingType = when (label.lowercase()) {
        "scratch" -> FindingType.SCRATCH
        "dent" -> FindingType.DENT
        "crack" -> FindingType.CRACK
        "broken-part", "broken_part" -> FindingType.BROKEN_PART
        "missing-part", "missing_part" -> FindingType.MISSING_PART
        "paint-peel", "paint_peel" -> FindingType.PAINT_PEEL
        else -> FindingType.ANOMALY
    }

    // ---- Classical change-detector ----

    private fun classicalFindings(ctx: AnalysisContext): List<AnalysisFinding> {
        val panels = ctx.station.panelZones()
        if (panels.isEmpty()) return emptyList()

        val reference = referenceMode(ctx)
        val out = ArrayList<AnalysisFinding>()
        for (zone in panels) {
            val poly = zone.toPolygon()
            val cur = ZoneMetrics.compute(ctx.rgb, ctx.gray, poly)
            if (cur.pixelCount < 24) continue
            when (reference) {
                ReferenceMode.REGISTERED, ReferenceMode.BASELINE -> {
                    val ref = referenceStats(ctx, zone, reference) ?: continue
                    val divergence = divergence(cur, ref)
                    if (divergence > DIVERGENCE_THRESHOLD) {
                        out.add(anomaly(ctx, zone.name, poly.bounds().clampUnit(), poly,
                            severity = (divergence * 1.4f).coerceIn(0.1f, 1f),
                            confidence = if (reference == ReferenceMode.REGISTERED) 0.62f else 0.55f,
                            low = false, reason = "change vs ${reference.name.lowercase()}"))
                    }
                }
                ReferenceMode.NONE -> {
                    out += gridOutliers(ctx, zone)
                }
            }
        }
        return out
    }

    private enum class ReferenceMode { REGISTERED, BASELINE, NONE }

    private fun referenceMode(ctx: AnalysisContext): ReferenceMode = when {
        ctx.registered?.confident == true -> ReferenceMode.REGISTERED
        ctx.baseline != null -> ReferenceMode.BASELINE
        else -> ReferenceMode.NONE
    }

    /** Reference zone stats: warp the zone into the previous frame, or use the baseline image. */
    private fun referenceStats(ctx: AnalysisContext, zone: CfgZone, mode: ReferenceMode): ZoneStats? {
        return when (mode) {
            ReferenceMode.REGISTERED -> {
                val reg = ctx.registered ?: return null
                val h = reg.homography ?: return null
                val inv = h.inverse() ?: return null
                // Map current zone polygon into previous-image normalized coordinates.
                val warped = zone.polygon.map {
                    inv.warpNorm(NormPoint(it.x, it.y), ctx.rgb.width, ctx.rgb.height,
                        reg.prevRgb.width, reg.prevRgb.height)
                }
                val poly = NormPolygon(warped).clampedOrNull() ?: return null
                ZoneMetrics.compute(reg.prevRgb, reg.prevGray, poly)
            }
            ReferenceMode.BASELINE -> {
                val b = ctx.baseline ?: return null
                ZoneMetrics.compute(b.rgb, b.gray, zone.toPolygon())
            }
            ReferenceMode.NONE -> null
        }
    }

    private fun NormPolygon.clampedOrNull(): NormPolygon? {
        val b = bounds()
        if (b.area <= 0.0005f) return null
        return this
    }

    /** Weighted divergence of current zone stats from reference, in ~[0,1]. */
    private fun divergence(cur: ZoneStats, ref: ZoneStats): Float {
        if (ref.pixelCount == 0) return 0f
        val dTexture = ((cur.texture - ref.texture) * 4000f).let { abs(it) }.coerceIn(0f, 1f)
        val dEdge = (abs(cur.edgeDensity - ref.edgeDensity) * 6f).coerceIn(0f, 1f)
        val dSpec = (abs(cur.specularCrisp - ref.specularCrisp) * 8f).coerceIn(0f, 1f)
        val dValue = (abs(cur.value - ref.value) * 4f).coerceIn(0f, 1f)
        // Edge & texture increases are the strongest scratch/dent cues.
        return (0.40f * dEdge + 0.30f * dTexture + 0.18f * dSpec + 0.12f * dValue).coerceIn(0f, 1f)
    }

    /**
     * No-reference absolute pass: split the zone into a grid, and flag cells whose local texture
     * energy is a strong statistical outlier (a localized scratch/dent streak). Marked
     * low-confidence with NO_PRIOR_REFERENCE because there is nothing to diff against.
     */
    private fun gridOutliers(ctx: AnalysisContext, zone: CfgZone): List<AnalysisFinding> {
        val bounds = zone.toPolygon().bounds().clampUnit()
        val crop = ctx.gray.crop(bounds)
        if (crop.width < 12 || crop.height < 12) return emptyList()
        val cols = 5; val rows = 4
        val cellW = crop.width / cols; val cellH = crop.height / rows
        if (cellW < 3 || cellH < 3) return emptyList()
        val energies = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val cellRect = NormRect(
                c.toFloat() / cols, r.toFloat() / rows, (c + 1f) / cols, (r + 1f) / rows
            )
            val cell = crop.crop(cellRect)
            energies[r * cols + c] = Edges.highFrequencyEnergy(cell)
        }
        var mean = 0f; for (e in energies) mean += e; mean /= energies.size
        var sd = 0f; for (e in energies) sd += (e - mean) * (e - mean); sd = kotlin.math.sqrt(sd / energies.size)
        if (sd < 1e-6f) return emptyList()
        val out = ArrayList<AnalysisFinding>()
        // No-reference mode is speculative; require a strong outlier and cap to the 2 strongest
        // cells per zone so a clean first record isn't flooded with low-confidence candidates.
        val ranked = (0 until rows * cols).sortedByDescending { energies[it] }
        val allowed = ranked.take(2).toSet()
        for (r in 0 until rows) for (c in 0 until cols) {
            val cell = r * cols + c
            val z = (energies[cell] - mean) / sd
            if (z > 2.8f && cell in allowed) {
                val rect = NormRect(
                    bounds.left + bounds.width * c / cols,
                    bounds.top + bounds.height * r / rows,
                    bounds.left + bounds.width * (c + 1) / cols,
                    bounds.top + bounds.height * (r + 1) / rows
                ).clampUnit()
                out.add(anomaly(ctx, zone.name, rect, null,
                    severity = (z / 6f).coerceIn(0.1f, 0.8f), confidence = 0.35f, low = true,
                    reason = "absolute outlier (no prior reference)"))
            }
        }
        return out
    }

    private fun anomaly(
        ctx: AnalysisContext, zone: String, rect: NormRect, poly: NormPolygon?,
        severity: Float, confidence: Float, low: Boolean, reason: String
    ) = AnalysisFinding(
        id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
        capturePoint = ctx.station.id, head = FindingHead.DAMAGE, type = FindingType.ANOMALY,
        zone = zone, severity = severity, confidence = confidence, bbox = rect, polygon = poly?.points,
        engine = Engine.CLASSICAL, lowConfidence = low, labelSource = LabelSource.MODEL_ONLY,
        createdAt = ctx.nowMillis
    )

    private fun zoneForRect(ctx: AnalysisContext, rect: NormRect): String? {
        val center = NormPoint(rect.centerX, rect.centerY)
        return ctx.station.panelZones().firstOrNull { it.toPolygon().contains(center) }?.name
    }

    companion object { private const val DIVERGENCE_THRESHOLD = 0.34f }
}
