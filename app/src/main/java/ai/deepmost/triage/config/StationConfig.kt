package ai.deepmost.triage.config

import ai.deepmost.triage.cv.NormPoint
import ai.deepmost.triage.cv.NormPolygon
import ai.deepmost.triage.cv.NormRect
import kotlinx.serialization.Serializable

/** Normalized 2D point in config JSON. */
@Serializable
data class CfgPoint(val x: Float, val y: Float) {
    fun toNorm() = NormPoint(x, y)
}

/** A named zone polygon (panel, lamp, tyre, interior region). */
@Serializable
data class CfgZone(
    val name: String,
    val kind: String,          // PANEL | LAMP | TYRE | INTERIOR_FLOOR | INTERIOR_SEAT | LOWER_BODY
    val polygon: List<CfgPoint>
) {
    fun toPolygon() = NormPolygon(polygon.map { it.toNorm() })
}

/** Expected vehicle bounding box (normalized) used by the framing quality check. */
@Serializable
data class CfgRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toNorm() = NormRect(left, top, right, bottom)
}

/** Geometry + active heads for one capture station. */
@Serializable
data class StationSpec(
    val id: String,                 // matches CapturePoint.name
    val label: String,
    val kind: String,               // BODY | TYRE | INTERIOR
    val ghost: List<CfgPoint>,      // semi-transparent alignment outline (polyline)
    val framing: CfgRect? = null,   // expected vehicle bbox for IoU framing check
    val zones: List<CfgZone> = emptyList(),
    val heads: List<String> = emptyList()
) {
    val capturePoint: CapturePoint? get() = CapturePoint.fromIdOrNull(id)
    val stationKind: StationKind get() = runCatching { StationKind.valueOf(kind) }.getOrDefault(StationKind.BODY)
    val activeHeads: List<HeadId> get() = heads.mapNotNull { runCatching { HeadId.valueOf(it) }.getOrNull() }

    fun ghostPolyline(): List<NormPoint> = ghost.map { it.toNorm() }
    fun zonesOfKind(kind: String): List<CfgZone> = zones.filter { it.kind == kind }
    fun panelZones() = zonesOfKind("PANEL")
    fun lampZones() = zonesOfKind("LAMP")
    fun lowerBodyZones() = zonesOfKind("LOWER_BODY")
    fun tyreZones() = zonesOfKind("TYRE")
    fun interiorFloorZones() = zonesOfKind("INTERIOR_FLOOR")
    fun interiorSeatZones() = zonesOfKind("INTERIOR_SEAT")
}

/** A full vehicle-profile station config (one JSON file in assets/stations/). */
@Serializable
data class VehicleProfileConfig(
    val profileId: String,
    val displayName: String,
    val schemaVersion: Int = 1,
    val stations: List<StationSpec>
) {
    fun station(cp: CapturePoint): StationSpec? = stations.firstOrNull { it.id == cp.name }
}
