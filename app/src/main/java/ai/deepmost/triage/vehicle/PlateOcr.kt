package ai.deepmost.triage.vehicle

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import timber.log.Timber

/**
 * On-device licence-plate OCR (ML Kit Latin text recognition). Used two ways:
 *  - [PlateScanAnalyzer] — live camera scan to auto-identify a vehicle by its plate.
 *  - [plateRegions]      — blocking detection of plate-like text boxes in a bitmap, for export
 *    redaction (privacy). No network.
 */
object PlateOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Indian plate shape after stripping spaces/dashes: 2 letters, 1-2 digits, 1-3 letters, 1-4 digits.
    private val PLATE = Regex("[A-Z]{2}[0-9]{1,2}[A-Z]{1,3}[0-9]{1,4}")

    fun normalize(raw: String): String = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun extractPlate(text: String): String? {
        val n = normalize(text)
        return PLATE.find(n)?.value
    }

    /** Blocking plate-region detection for redaction. Call off the main thread. */
    fun plateRegions(bitmap: Bitmap): List<Rect> {
        return try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(input))
            val boxes = ArrayList<Rect>()
            for (block in result.textBlocks) for (line in block.lines) {
                if (extractPlate(line.text) != null) line.boundingBox?.let { boxes.add(it) }
            }
            boxes
        } catch (t: Throwable) {
            Timber.w(t, "plateRegions failed"); emptyList()
        }
    }
}

/** Live camera analyzer that emits a recognized plate string once. */
class PlateScanAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var done = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null || done) { imageProxy.close(); return }
        val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { text ->
                for (block in text.textBlocks) for (line in block.lines) {
                    val plate = PlateOcr.extractPlate(line.text)
                    if (plate != null && !done) { done = true; onResult(plate); break }
                }
            }
            .addOnFailureListener { Timber.w(it, "Plate OCR failed") }
            .addOnCompleteListener { imageProxy.close() }
    }
}
