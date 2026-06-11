package ai.deepmost.triage.heads.tyre

import ai.deepmost.triage.config.HeadId
import ai.deepmost.triage.cv.Edges
import ai.deepmost.triage.cv.Hough
import ai.deepmost.triage.cv.NormRect
import ai.deepmost.triage.data.Engine
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.LabelSource
import ai.deepmost.triage.heads.AnalysisContext
import ai.deepmost.triage.heads.AnalysisFinding
import ai.deepmost.triage.heads.AnalysisHead
import ai.deepmost.triage.registry.ModelTask

/**
 * Tyre head (closeup stations).
 *
 * MODEL path: a classifier (ok / worn / damaged / flat-low) over the tyre crop.
 *
 * CLASSICAL fallback: detect the tyre ellipse with a minimal Hough vote, then derive
 *  (a) a flat/low heuristic from the ellipse aspect deviation (sidewall bulge / bottom flatten),
 *  (b) a tread-wear indicator from tread-region high-frequency texture energy (bald tyres lose
 *      high-frequency content), reported as a wear severity and a trend vs the previous shift's
 *      same-tyre value.
 */
class TyreHead : AnalysisHead {
    override val id = HeadId.TYRE

    override fun appliesTo(ctx: AnalysisContext): Boolean =
        ctx.station.activeHeads.contains(HeadId.TYRE)

    override suspend fun analyze(ctx: AnalysisContext): List<AnalysisFinding> {
        modelFinding(ctx)?.let { return listOf(it) }
        return listOf(classicalFinding(ctx))
    }

    private fun modelFinding(ctx: AnalysisContext): AnalysisFinding? {
        val handle = ctx.registry.handleFor("TYRE") ?: return null
        if (handle.spec.modelTask != ModelTask.CLASSIFICATION) return null
        return try {
            val region = tyreRegion(ctx)
            val bmp = ai.deepmost.triage.cv.Images.rgbToBitmap(ctx.rgb.crop(region))
            val probs = handle.classify(bmp) ?: return null
            var best = 0
            for (i in probs.indices) if (probs[i] > probs[best]) best = i
            val label = handle.spec.labels.getOrElse(best) { "ok" }
            AnalysisFinding(
                id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
                capturePoint = ctx.station.id, head = FindingHead.TYRE, type = mapLabel(label),
                zone = "tyre", severity = severityForLabel(label, probs[best]), confidence = probs[best],
                bbox = region, engine = handle.spec.engineTag(),
                trend = trendVsPrevious(ctx, severityForLabel(label, probs[best])),
                labelSource = LabelSource.MODEL_ONLY, createdAt = ctx.nowMillis
            )
        } catch (t: Throwable) { null }
    }

    private fun classicalFinding(ctx: AnalysisContext): AnalysisFinding {
        val region = tyreRegion(ctx)
        val crop = ctx.gray.crop(region)
        val circle = Hough.detectBestCircle(crop)

        // Flat/low: large aspect deviation of the detected ellipse.
        val flatLow = circle != null && circle.aspectDeviation > FLAT_LOW_ASPECT

        // Tread wear: high-frequency energy in the tread band (lower == more worn/bald).
        val treadBand = if (circle != null) {
            // Inner annulus around the detected wheel — approximate the tread by a centred box.
            val rNorm = circle.radius / minOf(crop.width, crop.height)
            val cx = circle.cx / crop.width
            val cy = circle.cy / crop.height
            NormRect(
                (region.left + (cx - rNorm * 0.5f) * region.width),
                (region.top + (cy - rNorm * 0.5f) * region.height),
                (region.left + (cx + rNorm * 0.5f) * region.width),
                (region.top + (cy + rNorm * 0.5f) * region.height)
            ).clampUnit()
        } else region
        val treadEnergy = Edges.highFrequencyEnergy(ctx.gray.crop(treadBand))
        // Map energy -> wear severity (heuristic, marked moderate confidence).
        val wearSeverity = (1f - (treadEnergy * TREAD_ENERGY_SCALE)).coerceIn(0f, 1f)

        val (type, severity, confidence) = when {
            flatLow -> Triple(FindingType.TYRE_FLAT_LOW, (circle!!.aspectDeviation * 1.6f).coerceIn(0.3f, 1f), 0.55f)
            wearSeverity > WORN_THRESHOLD -> Triple(FindingType.TYRE_WORN, wearSeverity, 0.5f)
            else -> Triple(FindingType.TYRE_OK, wearSeverity, if (circle != null) 0.5f else 0.35f)
        }

        return AnalysisFinding(
            id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
            capturePoint = ctx.station.id, head = FindingHead.TYRE, type = type,
            zone = "tyre", severity = severity, confidence = confidence,
            bbox = region, engine = Engine.CLASSICAL,
            lowConfidence = circle == null,
            trend = trendVsPrevious(ctx, severity),
            labelSource = LabelSource.MODEL_ONLY, createdAt = ctx.nowMillis
        )
    }

    private fun tyreRegion(ctx: AnalysisContext): NormRect =
        ctx.station.tyreZones().firstOrNull()?.toPolygon()?.bounds()?.clampUnit()
            ?: ctx.station.framing?.toNorm()?.clampUnit()
            ?: NormRect(0.2f, 0.22f, 0.8f, 0.78f)

    /** Delta vs the previous shift's same-tyre severity (positive == worse than last shift). */
    private fun trendVsPrevious(ctx: AnalysisContext, currentSeverity: Float): Float? {
        val prev = ctx.registered?.prevFindings
            ?.firstOrNull { it.head == FindingHead.TYRE } ?: return null
        return currentSeverity - prev.severity
    }

    private fun mapLabel(label: String): FindingType = when (label.lowercase()) {
        "worn" -> FindingType.TYRE_WORN
        "damaged" -> FindingType.TYRE_DAMAGED
        "flat-low", "flat_low", "flat" -> FindingType.TYRE_FLAT_LOW
        else -> FindingType.TYRE_OK
    }

    private fun severityForLabel(label: String, prob: Float): Float = when (label.lowercase()) {
        "worn" -> 0.6f * prob + 0.2f
        "damaged" -> 0.8f * prob + 0.2f
        "flat-low", "flat_low", "flat" -> 0.7f * prob + 0.2f
        else -> 0.1f
    }

    companion object {
        private const val FLAT_LOW_ASPECT = 0.18f
        private const val TREAD_ENERGY_SCALE = 60f
        private const val WORN_THRESHOLD = 0.6f
    }
}
