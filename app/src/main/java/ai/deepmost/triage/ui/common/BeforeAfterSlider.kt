package ai.deepmost.triage.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Before/after comparison: the PREVIOUS (registered) shot fills the frame, the CURRENT shot is
 * revealed left-of a draggable vertical divider. Lets the driver/supervisor wipe between shifts to
 * see exactly what changed.
 */
@Composable
fun BeforeAfterSlider(
    previous: ImageBitmap,
    current: ImageBitmap,
    accent: Color,
    modifier: Modifier = Modifier
) {
    var pos by remember { mutableFloatStateOf(0.5f) }
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(current.width.toFloat() / current.height)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    pos = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    ) {
        drawImageFitted(previous)
        clipRect(right = size.width * pos) { drawImageFitted(current) }
        val x = size.width * pos
        drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
        drawCircle(accent, radius = 14f, center = Offset(x, size.height / 2f))
    }
}

private fun DrawScope.drawImageFitted(img: ImageBitmap) {
    val scale = minOf(size.width / img.width, size.height / img.height)
    val w = img.width * scale
    val h = img.height * scale
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(w.toInt(), h.toInt())
    )
}
