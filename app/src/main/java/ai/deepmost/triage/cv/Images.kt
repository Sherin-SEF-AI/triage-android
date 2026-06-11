package ai.deepmost.triage.cv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

/**
 * Grayscale image with luminance stored as Float in [0,1], row-major.
 * This is the working representation for most classical-CV operators.
 */
class GrayImage(val width: Int, val height: Int, val lum: FloatArray) {
    init {
        require(lum.size == width * height) { "lum size ${lum.size} != ${width * height}" }
    }

    inline fun at(x: Int, y: Int): Float = lum[y * width + x]

    fun clampX(x: Int) = x.coerceIn(0, width - 1)
    fun clampY(y: Int) = y.coerceIn(0, height - 1)

    /** Crop to a normalized rect, returns a new GrayImage (empty 1x1 if degenerate). */
    fun crop(rect: NormRect): GrayImage {
        val l = (rect.left * width).roundToInt().coerceIn(0, width - 1)
        val t = (rect.top * height).roundToInt().coerceIn(0, height - 1)
        val r = (rect.right * width).roundToInt().coerceIn(l + 1, width)
        val b = (rect.bottom * height).roundToInt().coerceIn(t + 1, height)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return GrayImage(1, 1, floatArrayOf(0f))
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val srcRow = (t + y) * width + l
            System.arraycopy(lum, srcRow, out, y * w, w)
        }
        return GrayImage(w, h, out)
    }
}

/** RGB image as packed ARGB ints, row-major. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixels ${pixels.size} != ${width * height}" }
    }

    inline fun at(x: Int, y: Int): Int = pixels[y * width + x]

    fun toGray(): GrayImage {
        val lum = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 601 luma.
            lum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return GrayImage(width, height, lum)
    }

    fun crop(rect: NormRect): RgbImage {
        val l = (rect.left * width).roundToInt().coerceIn(0, width - 1)
        val t = (rect.top * height).roundToInt().coerceIn(0, height - 1)
        val r = (rect.right * width).roundToInt().coerceIn(l + 1, width)
        val b = (rect.bottom * height).roundToInt().coerceIn(t + 1, height)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return RgbImage(1, 1, intArrayOf(0))
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val srcRow = (t + y) * width + l
            System.arraycopy(pixels, srcRow, out, y * w, w)
        }
        return RgbImage(w, h, out)
    }
}

object Images {
    /** Maximum working dimension for analysis copies; originals are never touched. */
    const val ANALYSIS_MAX_DIM = 1024

    fun bitmapToRgb(bmp: Bitmap): RgbImage {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return RgbImage(w, h, px)
    }

    fun rgbToBitmap(img: RgbImage): Bitmap {
        val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(img.pixels, 0, img.width, 0, 0, img.width, img.height)
        return bmp
    }

    /**
     * Decode an image file at a bounded sample size and apply EXIF rotation, producing a
     * working bitmap suitable for analysis. The on-disk original is never modified.
     */
    fun decodeForAnalysis(file: File, maxDim: Int = ANALYSIS_MAX_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return applyExifOrientation(file, decoded)
    }

    private fun applyExifOrientation(file: File, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        } catch (t: Throwable) {
            bmp
        }
    }

    fun downscale(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true
        )
    }
}
