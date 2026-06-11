package ai.deepmost.triage.vehicle

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import timber.log.Timber

/**
 * On-device QR / barcode analyzer for the dashboard vehicle tag. ML Kit barcode scanning runs
 * entirely locally (no network). Emits the decoded registration string once, then de-duplicates.
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()
    private var lastValue: String? = null

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }?.rawValue
                if (!value.isNullOrBlank() && value != lastValue) {
                    lastValue = value
                    onResult(value.trim())
                }
            }
            .addOnFailureListener { Timber.w(it, "Barcode scan failed") }
            .addOnCompleteListener { imageProxy.close() }
    }
}
