package ai.deepmost.triage.capture

import ai.deepmost.triage.config.StationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semi-transparent ghost overlay the driver aligns the vehicle to. Draws the station's alignment
 * outline plus (for body/tyre stations) the expected framing rectangle and any lamp polygons, all
 * from normalized config coordinates scaled to the preview.
 */
@Composable
fun GhostOverlay(station: StationSpec, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val ghost = station.ghostPolyline()
        if (ghost.size >= 2) {
            val path = Path().apply {
                moveTo(ghost[0].x * w, ghost[0].y * h)
                for (i in 1 until ghost.size) lineTo(ghost[i].x * w, ghost[i].y * h)
            }
            drawPath(path, color = Color.White.copy(alpha = 0.55f), style = Stroke(width = 4f))
        }
        // Framing rect (dashed).
        station.framing?.let { fr ->
            val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)
            drawRect(
                color = accent.copy(alpha = 0.5f),
                topLeft = Offset(fr.left * w, fr.top * h),
                size = androidx.compose.ui.geometry.Size((fr.right - fr.left) * w, (fr.bottom - fr.top) * h),
                style = Stroke(width = 3f, pathEffect = dash)
            )
        }
        // Lamp polygons.
        for (lamp in station.lampZones()) {
            val pts = lamp.polygon
            if (pts.size < 2) continue
            val path = Path().apply {
                moveTo(pts[0].x * w, pts[0].y * h)
                for (i in 1 until pts.size) lineTo(pts[i].x * w, pts[i].y * h)
                close()
            }
            drawPath(path, color = Color.Cyan.copy(alpha = 0.35f), style = Stroke(width = 2f))
        }
    }
}

/** Horizontal progress strip of all stations: captured, analyzing, current, pending. */
@Composable
fun StationStrip(
    stations: List<StationSpec>,
    currentIndex: Int,
    capturedPoints: Set<String>,
    analyzingPoints: Set<String>,
    accent: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(modifier = modifier) {
        items(stations.size) { index ->
            val station = stations[index]
            val captured = station.id in capturedPoints
            val analyzing = station.id in analyzingPoints
            val isCurrent = index == currentIndex
            val color = when {
                isCurrent -> accent
                analyzing -> Color(0xFFB0B0B0)
                captured -> Color(0xFF3A6B4A)
                else -> Color(0xFF2A2A2C)
            }
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    color = Color.White,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
