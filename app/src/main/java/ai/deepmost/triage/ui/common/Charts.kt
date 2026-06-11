package ai.deepmost.triage.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.deepmost.triage.ui.theme.TextSecondary

/** A labeled value for the bar chart. */
data class BarDatum(val label: String, val value: Float)

/** Simple matte bar chart (Canvas). Values are normalized to the max; accent-filled bars. */
@Composable
fun BarChart(data: List<BarDatum>, accent: Color, maxValue: Float? = null, modifier: Modifier = Modifier) {
    if (data.isEmpty()) {
        Text("—", color = TextSecondary, style = MaterialTheme.typography.bodySmall); return
    }
    val max = (maxValue ?: data.maxOf { it.value }).coerceAtLeast(1e-3f)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = data.size
            val slot = size.width / n
            val barW = slot * 0.6f
            for (i in 0 until n) {
                val v = (data[i].value / max).coerceIn(0f, 1f)
                val h = v * (size.height - 6f)
                val left = i * slot + (slot - barW) / 2f
                drawRect(
                    color = accent,
                    topLeft = Offset(left, size.height - h),
                    size = Size(barW, h)
                )
            }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
            data.forEach {
                Text(
                    it.label.take(6),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f).padding(top = 2.dp)
                )
            }
        }
    }
}

/** Multi-series line chart (each series a list of y-values in [0,1] domain after scaling). */
@Composable
fun LineChart(series: List<Pair<Color, List<Float>>>, maxY: Float = 100f, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        drawLine(Color(0xFF2A2A2E), Offset(0f, 0f), Offset(size.width, 0f), 1f)
        drawLine(Color(0xFF2A2A2E), Offset(0f, size.height), Offset(size.width, size.height), 1f)
        for ((color, pts) in series) {
            if (pts.size < 2) continue
            val stepX = size.width / (pts.size - 1)
            var prev: Offset? = null
            pts.forEachIndexed { i, y ->
                val cur = Offset(stepX * i, size.height * (1f - (y / maxY).coerceIn(0f, 1f)))
                prev?.let { drawLine(color, it, cur, 3f) }
                drawCircle(color, 3f, cur)
                prev = cur
            }
        }
    }
}
