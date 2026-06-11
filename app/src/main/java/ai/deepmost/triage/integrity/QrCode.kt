package ai.deepmost.triage.integrity

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import timber.log.Timber

/**
 * Generates a QR encoding a record's tamper-evident fingerprint (`TRIAGE:<inspectionId>:<manifestHash>`)
 * so a second device can scan it and verify the hash independently. Fully on-device (ZXing).
 */
object QrCode {

    fun payload(inspectionId: String, manifestHash: String): String =
        "TRIAGE:$inspectionId:$manifestHash"

    fun generate(text: String, size: Int = 512): Bitmap? = try {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        bmp
    } catch (t: Throwable) {
        Timber.e(t, "QR generation failed"); null
    }
}
