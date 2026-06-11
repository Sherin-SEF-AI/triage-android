package ai.deepmost.triage.heads.lamp

import ai.deepmost.triage.config.CfgZone
import ai.deepmost.triage.config.HeadId
import ai.deepmost.triage.cv.Edges
import ai.deepmost.triage.cv.Histogram
import ai.deepmost.triage.cv.Hsv
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
import ai.deepmost.triage.registry.ModelTask
import timber.log.Timber

/**
 * Lamp head (FRONT and REAR stations).
 *
 * MODEL path: a lamp detector (intact / cracked / fogged / missing).
 *
 * CLASSICAL fallback: for each lamp polygon from the station config, compute crack-edge density,
 * a fogging proxy (low contrast + milky low-saturation high-value), and a gross difference vs the
 * registered previous walkaround. An optional powered-lamp check (only when ambient is dark)
 * detects a lit blob in the lamp polygon and records WORKING / NOT-WORKING; in bright daylight the
 * powered check is skipped with the reason logged.
 */
class LampHead : AnalysisHead {
    override val id = HeadId.LAMP

    override fun appliesTo(ctx: AnalysisContext): Boolean =
        ctx.station.activeHeads.contains(HeadId.LAMP) && ctx.station.lampZones().isNotEmpty()

    override suspend fun analyze(ctx: AnalysisContext): List<AnalysisFinding> {
        modelFindings(ctx)?.let { if (it.isNotEmpty()) return it + poweredChecks(ctx) }
        val out = ArrayList<AnalysisFinding>()
        for (lamp in ctx.station.lampZones()) out += classicalLamp(ctx, lamp)
        out += poweredChecks(ctx)
        return out
    }

    private fun modelFindings(ctx: AnalysisContext): List<AnalysisFinding>? {
        val handle = ctx.registry.handleFor("LAMP") ?: return null
        if (handle.spec.modelTask != ModelTask.DETECTION) return null
        return try {
            val bmp = ai.deepmost.triage.cv.Images.rgbToBitmap(ctx.rgb)
            val dets = handle.detect(bmp) ?: return null
            dets.map { d ->
                AnalysisFinding(
                    id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
                    capturePoint = ctx.station.id, head = FindingHead.LAMP, type = mapLabel(d.label),
                    zone = nearestLampZone(ctx, d.rect), severity = severityFor(d.label, d.score),
                    confidence = d.score, bbox = d.rect, engine = handle.spec.engineTag(),
                    labelSource = LabelSource.MODEL_ONLY, createdAt = ctx.nowMillis
                )
            }
        } catch (t: Throwable) { null }
    }

    private fun classicalLamp(ctx: AnalysisContext, lamp: CfgZone): AnalysisFinding {
        val poly = lamp.toPolygon()
        val bounds = poly.bounds().clampUnit()
        val cropGray = ctx.gray.crop(bounds)
        val cropRgb = ctx.rgb.crop(bounds)

        val crackEdges = Edges.edgeDensity(cropGray, threshold = 0.22f)
        val contrast = Edges.rmsContrast(cropGray)
        val hsv = Hsv.stats(cropRgb)
        // Fogging: milky surface — high value, low saturation, low contrast.
        val fogScore = ((1f - contrast * 4f).coerceIn(0f, 1f) * 0.5f +
            (1f - hsv.meanS).coerceIn(0f, 1f) * 0.3f +
            hsv.meanV.coerceIn(0f, 1f) * 0.2f)

        // Gross difference vs registered previous lamp (crack / damage appeared).
        var grossDiff = 0f
        var lowConf = true
        val reg = ctx.registered
        if (reg?.confident == true && reg.homography != null) {
            val inv = reg.homography.inverse()
            if (inv != null) {
                val warped = lamp.polygon.map {
                    inv.warpNorm(NormPoint(it.x, it.y), ctx.rgb.width, ctx.rgb.height,
                        reg.prevRgb.width, reg.prevRgb.height)
                }
                val prevCrop = reg.prevGray.crop(NormPolygon(warped).bounds().clampUnit())
                grossDiff = Histogram.cdfDistance(cropGray, prevCrop)
                lowConf = false
            }
        }

        val (type, severity, confidence) = when {
            crackEdges > CRACK_EDGE_HIGH || (grossDiff > GROSS_DIFF_HIGH && crackEdges > CRACK_EDGE_MED) ->
                Triple(FindingType.LAMP_CRACKED, (crackEdges * 2f).coerceIn(0.3f, 1f), if (lowConf) 0.4f else 0.6f)
            // Fogging is a milky, BRIGHT surface — gate on value so dark/uniform regions don't read as fog.
            fogScore > FOG_HIGH && hsv.meanV > FOG_MIN_VALUE ->
                Triple(FindingType.LAMP_FOGGED, fogScore.coerceIn(0.3f, 1f), if (lowConf) 0.4f else 0.55f)
            hsv.meanV < MISSING_DARK && contrast < 0.04f ->
                Triple(FindingType.LAMP_MISSING, 0.8f, 0.4f)
            else ->
                Triple(FindingType.LAMP_INTACT, 0.05f, if (lowConf) 0.45f else 0.6f)
        }

        return AnalysisFinding(
            id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
            capturePoint = ctx.station.id, head = FindingHead.LAMP, type = type, zone = lamp.name,
            severity = severity, confidence = confidence, bbox = bounds, polygon = poly.points,
            engine = Engine.CLASSICAL, lowConfidence = lowConf,
            trend = if (grossDiff > 0f) grossDiff else null,
            labelSource = LabelSource.MODEL_ONLY, createdAt = ctx.nowMillis
        )
    }

    /** Powered-lamp working check — only meaningful when ambient is dark. */
    private fun poweredChecks(ctx: AnalysisContext): List<AnalysisFinding> {
        if (!ctx.ambientDark) {
            Timber.d("Powered-lamp check skipped at %s: ambient too bright", ctx.station.id)
            return emptyList()
        }
        val out = ArrayList<AnalysisFinding>()
        for (lamp in ctx.station.lampZones()) {
            val crop = ctx.gray.crop(lamp.toPolygon().bounds().clampUnit())
            val meanV = Histogram.meanLuminance(crop)
            // A lit lamp shows a bright intensity blob against the dark scene.
            val lit = meanV > LIT_THRESHOLD
            out.add(
                AnalysisFinding(
                    id = ctx.idGen(), inspectionId = ctx.inspectionId, photoId = ctx.photoId,
                    capturePoint = ctx.station.id, head = FindingHead.LAMP,
                    type = if (lit) FindingType.LAMP_WORKING else FindingType.LAMP_NOT_WORKING,
                    zone = lamp.name, severity = if (lit) 0.05f else 0.85f,
                    confidence = 0.6f, bbox = lamp.toPolygon().bounds().clampUnit(),
                    engine = Engine.CLASSICAL, labelSource = LabelSource.MODEL_ONLY,
                    createdAt = ctx.nowMillis
                )
            )
        }
        return out
    }

    private fun nearestLampZone(ctx: AnalysisContext, rect: NormRect): String? {
        val center = NormPoint(rect.centerX, rect.centerY)
        return ctx.station.lampZones().minByOrNull {
            it.toPolygon().centroid().distanceTo(center)
        }?.name
    }

    private fun mapLabel(label: String): FindingType = when (label.lowercase()) {
        "cracked" -> FindingType.LAMP_CRACKED
        "fogged" -> FindingType.LAMP_FOGGED
        "missing" -> FindingType.LAMP_MISSING
        else -> FindingType.LAMP_INTACT
    }

    private fun severityFor(label: String, score: Float): Float = when (label.lowercase()) {
        "cracked" -> 0.6f * score + 0.2f
        "fogged" -> 0.5f * score + 0.2f
        "missing" -> 0.9f * score
        else -> 0.05f
    }

    companion object {
        private const val CRACK_EDGE_HIGH = 0.32f
        private const val CRACK_EDGE_MED = 0.20f
        private const val GROSS_DIFF_HIGH = 0.18f
        private const val FOG_HIGH = 0.62f
        private const val FOG_MIN_VALUE = 0.40f
        private const val MISSING_DARK = 0.10f
        private const val LIT_THRESHOLD = 0.35f
    }
}
