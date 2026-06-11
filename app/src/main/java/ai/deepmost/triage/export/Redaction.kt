package ai.deepmost.triage.export

import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.vehicle.PlateOcr
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Produces the image bytes written into an export. With redaction ON, number-plate regions are
 * detected (on-device OCR) and blacked out on the EXPORT COPY only — the stored original and its
 * SHA-256 are never modified, so the hash chain stays intact. If no plate is found (or redaction is
 * off) the untouched original bytes are returned.
 */
object Redaction {

    fun exportBytes(file: File, redact: Boolean, maxDim: Int = 1600): ByteArray {
        if (!redact || !file.exists()) return safeRead(file)
        val bmp = Images.decodeForAnalysis(file, maxDim) ?: return safeRead(file)
        val regions = PlateOcr.plateRegions(bmp)
        if (regions.isEmpty()) return safeRead(file)
        return try {
            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            for (r in regions) {
                // Pad the box a little so the whole plate is covered.
                val pad = (maxOf(r.width(), r.height()) * 0.12f).toInt()
                canvas.drawRect(
                    (r.left - pad).toFloat(), (r.top - pad).toFloat(),
                    (r.right + pad).toFloat(), (r.bottom + pad).toFloat(), paint
                )
            }
            val out = ByteArrayOutputStream()
            mutable.compress(Bitmap.CompressFormat.JPEG, 92, out)
            Timber.i("Redacted %d plate region(s) in export copy of %s", regions.size, file.name)
            out.toByteArray()
        } catch (t: Throwable) {
            Timber.e(t, "Redaction failed; exporting original"); safeRead(file)
        }
    }

    private fun safeRead(file: File): ByteArray = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
}
