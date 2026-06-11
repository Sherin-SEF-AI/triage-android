package ai.deepmost.triage.ui.common

import ai.deepmost.triage.cv.NormRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** A damage region with a 0..1 severity, for the heatmap overlay. */
data class HeatRegion(val rect: NormRect, val severity: Float)

/**
 * Damage severity heatmap over a photo: each finding region is filled with a radial accent glow
 * whose intensity tracks severity, plus an outline. Derived from the classical change-detector
 * signals (or model segmentation when a model is installed) — a visual aid, not a trained saliency
 * map.
 */
@Composable
fun HeatmapImage(
    bitmap: ImageBitmap,
    regions: List<HeatRegion>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height))
        Canvas(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
            for (r in regions) {
                val left = r.rect.left * size.width
                val top = r.rect.top * size.height
                val w = r.rect.width * size.width
                val h = r.rect.height * size.height
                val alpha = (0.20f + 0.55f * r.severity).coerceIn(0.2f, 0.8f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = alpha), Color.Transparent),
                        center = Offset(left + w / 2f, top + h / 2f),
                        radius = maxOf(w, h) * 0.75f
                    ),
                    topLeft = Offset(left, top),
                    size = Size(w, h)
                )
            }
        }
    }
}
