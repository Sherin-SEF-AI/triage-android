package ai.deepmost.triage.heads.clean

import ai.deepmost.triage.config.HeadId
import ai.deepmost.triage.config.StationKind
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
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

/**
 * Cleanliness head — exterior (8 body stations) and interior (2 stations), fully local.
 *
 * Classical scoring is RELATIVE to the per-vehicle clean baseline (so it is robust to paint
 * colour / seat fabric across the fleet). Each zone produces a 0..100 cleanliness score from:
 *  - saturation/value drift vs baseline (dust greys/dulls paint),
 *  - added texture energy vs baseline (dust/mud speckle on smooth panels),
 *  - lost specular crispness vs baseline (dirt diffuses highlights),
 *  - mud-splash brown coverage on lower-body zones,
 *  - interior: clutter edge-density + colour-outlier/stain blobs vs the interior baseline.
 *
 * With no baseline the raw heuristic score is emitted, marked low-confidence.
 * If a cleanliness model is installed it produces the per-station class instead; classical runs
 * the per-zone breakdown either way so the record always has zone detail.
 */
class CleanlinessHead : AnalysisHead {
    override val id = HeadId.CLEANLINESS
    private val json = Json { ignoreUnknownKeys = true }

    override fun appliesTo(ctx: AnalysisContext): Boolean =
        ctx.station.activeHeads.contains(HeadId.CLEANLINESS)

    override suspend fun analyze(ctx: AnalysisContext): List<AnalysisFinding> {
        val interior = ctx.station.stationKind == StationKind.INTERIOR
        val baseline = ctx.baseline?.let {
            runCatching { json.decodeFromString(BaselineMetrics.serializer(), it.metricsJson) }.getOrNull()
        }
        val hasBaseline = baseline != null && baseline.zones.isNotEmpty()

        // Optional model engine: a per-station class score. Classical zone breakdown always runs.
        val modelClass = tryModel(ctx, interior)

        val zones: List<Pair<String, NormPolygon>> = if (interior) {
            (ctx.station.interiorSeatZones() + ctx.station.interiorFloorZones())
                .map { it.name to it.toPolygon() }
        } else {
            val panels = ctx.station.panelZones().map { it.name to it.toPolygon() }
            val lower = ctx.station.lowerBodyZones().map { it.name to it.toPolygon() }
            panels + lower
        }.ifEmpty {
            // Fall back to the framing/whole-vehicle region if no zones are configured.
            val r = ctx.station.framing?.toNorm() ?: NormRect(0.1f, 0.2f, 0.9f, 0.85f)
            listOf("vehicle" to polygonOf(r))
        }

        val findings = ArrayList<AnalysisFinding>()
        var scoreSum = 0f
        var scored = 0
        for ((name, poly) in zones) {
            val cur = ZoneMetrics.compute(ctx.rgb, ctx.gray, poly)
            if (cur.pixelCount < 16) continue
            val lowerBody = name.contains("lower") || name.contains("sill") || name.contains("footwell")
            val ref = baseline?.zones?.get(name)
            val score = if (interior) interiorScore(cur, ref) else exteriorScore(cur, ref, lowerBody)
            scoreSum += score; scored++

            val type = zoneFindingType(cur, score, lowerBody, interior, hasBaseline)
            // Severity expresses dirtiness (1 - cleanliness).
            val severity = ((100f - score) / 100f).coerceIn(0f, 1f)
            findings.add(
                AnalysisFinding(
                    id = ctx.idGen(),
                    inspectionId = ctx.inspectionId,
                    photoId = ctx.photoId,
                    capturePoint = ctx.station.id,
                    head = FindingHead.CLEANLINESS,
                    type = type,
                    zone = name,
                    severity = severity,
                    confidence = if (hasBaseline) 0.8f else 0.5f,
                    bbox = poly.bounds().clampUnit(),
                    polygon = poly.points,
                    engine = if (modelClass != null) modelClass.engine else Engine.CLASSICAL,
                    lowConfidence = !hasBaseline,
                    trend = previousZoneScore(ctx, name)?.let { score - it },
                    labelSource = LabelSource.MODEL_ONLY,
                    createdAt = ctx.nowMillis
                )
            )
        }

        // Overall station cleanliness score finding.
        val overall = if (scored > 0) scoreSum / scored else 50f
        findings.add(
            AnalysisFinding(
                id = ctx.idGen(),
                inspectionId = ctx.inspectionId,
                photoId = ctx.photoId,
                capturePoint = ctx.station.id,
                head = FindingHead.CLEANLINESS,
                type = FindingType.CLEANLINESS_SCORE,
                zone = "overall",
                severity = ((100f - overall) / 100f).coerceIn(0f, 1f),
                confidence = if (hasBaseline) 0.85f else 0.5f,
                bbox = ctx.station.framing?.toNorm()?.clampUnit() ?: NormRect(0f, 0f, 1f, 1f),
                engine = modelClass?.engine ?: Engine.CLASSICAL,
                lowConfidence = !hasBaseline,
                labelSource = LabelSource.MODEL_ONLY,
                createdAt = ctx.nowMillis
            )
        )
        return findings
    }

    private data class ModelClass(val label: String, val score: Float, val engine: String)

    private fun tryModel(ctx: AnalysisContext, interior: Boolean): ModelClass? {
        val headKey = if (interior) "CLEANLINESS_INT" else "CLEANLINESS_EXT"
        val handle = ctx.registry.handleFor(headKey) ?: return null
        return try {
            val bmp = ai.deepmost.triage.cv.Images.rgbToBitmap(ctx.rgb)
            if (handle.spec.modelTask == ModelTask.CLASSIFICATION) {
                val probs = handle.classify(bmp) ?: return null
                var best = 0
                for (i in probs.indices) if (probs[i] > probs[best]) best = i
                ModelClass(handle.spec.labels.getOrElse(best) { "class$best" }, probs[best], handle.spec.engineTag())
            } else null
        } catch (t: Throwable) { null }
    }

    /** Exterior 0..100 cleanliness. With a baseline, drift in each cue lowers the score. */
    private fun exteriorScore(cur: ZoneStats, ref: ZoneStats?, lowerBody: Boolean): Float {
        if (ref == null || ref.pixelCount == 0) {
            // Absolute heuristic: high texture + low specular crispness + brown => dirty.
            var s = 100f
            s -= (cur.texture * 4000f).coerceIn(0f, 45f)
            s -= ((0.06f - cur.specularCrisp).coerceAtLeast(0f) * 400f).coerceIn(0f, 25f)
            if (lowerBody) s -= (cur.brownFraction * 120f).coerceIn(0f, 40f)
            return s.coerceIn(0f, 100f)
        }
        var penalty = 0f
        // Added texture vs baseline (dust speckle).
        penalty += ((cur.texture - ref.texture).coerceAtLeast(0f) * 5000f).coerceIn(0f, 40f)
        // Lost specular crispness vs baseline.
        penalty += ((ref.specularCrisp - cur.specularCrisp).coerceAtLeast(0f) * 350f).coerceIn(0f, 25f)
        // Saturation/value dulling.
        penalty += (abs(cur.saturation - ref.saturation) * 60f).coerceIn(0f, 15f)
        penalty += ((ref.value - cur.value).coerceAtLeast(0f) * 60f).coerceIn(0f, 10f)
        // Mud on lower body.
        if (lowerBody) penalty += ((cur.brownFraction - ref.brownFraction).coerceAtLeast(0f) * 150f).coerceIn(0f, 45f)
        return (100f - penalty).coerceIn(0f, 100f)
    }

    /** Interior 0..100 cleanliness: clutter edges + stain/colour-outlier vs interior baseline. */
    private fun interiorScore(cur: ZoneStats, ref: ZoneStats?): Float {
        if (ref == null || ref.pixelCount == 0) {
            var s = 100f
            s -= (cur.edgeDensity * 120f).coerceIn(0f, 45f)   // clutter adds edges
            s -= (cur.texture * 3000f).coerceIn(0f, 25f)
            return s.coerceIn(0f, 100f)
        }
        var penalty = 0f
        penalty += ((cur.edgeDensity - ref.edgeDensity).coerceAtLeast(0f) * 160f).coerceIn(0f, 45f)
        penalty += ((cur.texture - ref.texture).coerceAtLeast(0f) * 4000f).coerceIn(0f, 25f)
        penalty += (abs(cur.saturation - ref.saturation) * 70f).coerceIn(0f, 20f) // stains shift colour
        return (100f - penalty).coerceIn(0f, 100f)
    }

    private fun zoneFindingType(
        cur: ZoneStats, score: Float, lowerBody: Boolean, interior: Boolean, hasBaseline: Boolean
    ): FindingType = when {
        // Mud is an absolute brown-blob detection — valid even without a baseline.
        lowerBody && cur.brownFraction > 0.08f -> FindingType.MUD_SPLASH
        // Without a clean baseline we can't honestly call a zone "dusty/cluttered/stained" — that
        // would mislabel the dataset exhaust. Report a score only and let enrollment unlock typing.
        !hasBaseline -> FindingType.CLEANLINESS_SCORE
        interior && score < 60f -> FindingType.CLUTTER
        interior && cur.saturation > 0.35f && score < 80f -> FindingType.STAIN
        score < 65f -> FindingType.DUST
        else -> FindingType.CLEANLINESS_SCORE
    }

    private fun polygonOf(r: NormRect) = NormPolygon(listOf(
        ai.deepmost.triage.cv.NormPoint(r.left, r.top),
        ai.deepmost.triage.cv.NormPoint(r.right, r.top),
        ai.deepmost.triage.cv.NormPoint(r.right, r.bottom),
        ai.deepmost.triage.cv.NormPoint(r.left, r.bottom)
    ))

    /** Previous-walkaround score for this zone (for the per-shift trend), if registered. */
    private fun previousZoneScore(ctx: AnalysisContext, zone: String): Float? {
        val prev = ctx.registered?.prevFindings ?: return null
        val match = prev.firstOrNull {
            it.head == FindingHead.CLEANLINESS && it.zone == zone
        } ?: return null
        return (1f - match.severity) * 100f
    }

    companion object {
        /** Compute and serialize baseline metrics for a freshly-cleaned enrollment photo. */
        fun computeBaselineMetrics(
            station: ai.deepmost.triage.config.StationSpec,
            rgb: ai.deepmost.triage.cv.RgbImage,
            gray: ai.deepmost.triage.cv.GrayImage
        ): String {
            val zoneSpecs = station.panelZones() + station.lowerBodyZones() +
                station.interiorSeatZones() + station.interiorFloorZones()
            val map = HashMap<String, ZoneStats>()
            for (z in zoneSpecs) {
                val stats = ZoneMetrics.compute(rgb, gray, z.toPolygon())
                if (stats.pixelCount >= 16) map[z.name] = stats
            }
            return Json.encodeToString(BaselineMetrics.serializer(), BaselineMetrics(map))
        }
    }
}
