package ai.deepmost.triage.export

import ai.deepmost.triage.cv.Images
import ai.deepmost.triage.data.DiffStatus
import ai.deepmost.triage.data.FileStore
import ai.deepmost.triage.data.FindingHead
import ai.deepmost.triage.data.FindingRepository
import ai.deepmost.triage.data.FindingType
import ai.deepmost.triage.data.InspectionRepository
import ai.deepmost.triage.data.VehicleRepository
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a local PDF inspection report (no network): header with identity + timestamps +
 * manifest hash, a photo contact sheet, the findings grouped NEW / PRE-EXISTING / RESOLVED with
 * cleanliness scores, the driver signature and the per-photo SHA-256 hashes.
 */
class PdfGenerator(
    private val inspectionRepo: InspectionRepository,
    private val findingRepo: FindingRepository,
    private val vehicleRepo: VehicleRepository,
    private val fileStore: FileStore
) {
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val accent = Color.rgb(0xFF, 0x4D, 0x2E)

    suspend fun generate(inspectionId: String, redactPlates: Boolean = false): File? {
        val inspection = inspectionRepo.byId(inspectionId) ?: return null
        val vehicle = vehicleRepo.byId(inspection.vehicleId)
        val photos = inspectionRepo.photos(inspectionId).sortedBy { it.capturePoint }
        val findings = findingRepo.byInspection(inspectionId)

        val doc = PdfDocument()
        val pageW = 595; val pageH = 842 // A4 @ 72dpi
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var canvas = page.canvas
        var y = 40f

        val title = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        val mono = Paint().apply { color = Color.DKGRAY; textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE }
        val label = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val body = Paint().apply { color = Color.DKGRAY; textSize = 11f }

        canvas.drawText("TRIAGE inspection report", 40f, y, title); y += 26f
        canvas.drawText("Vehicle: ${vehicle?.registration ?: inspection.vehicleId} (${vehicle?.model ?: ""})", 40f, y, body); y += 16f
        canvas.drawText("Type: ${inspection.type}   Status: ${inspection.status}", 40f, y, body); y += 16f
        canvas.drawText("Started: ${df.format(Date(inspection.startedAtDevice))}", 40f, y, body); y += 16f
        inspection.finalizedAtDevice?.let { canvas.drawText("Finalized: ${df.format(Date(it))}", 40f, y, body); y += 16f }
        inspection.lat?.let { lat -> canvas.drawText("Location: $lat, ${inspection.lon}", 40f, y, body); y += 16f }
        canvas.drawText("manifestHash:", 40f, y, label); y += 14f
        canvas.drawText(inspection.manifestHash ?: "(not finalized)", 40f, y, mono); y += 14f
        canvas.drawText("prevHash: ${inspection.prevHash ?: "(none)"}", 40f, y, mono); y += 16f
        // Verification QR of the record fingerprint (scan from another device to verify).
        inspection.manifestHash?.let { mh ->
            val qr = ai.deepmost.triage.integrity.QrCode.generate(
                ai.deepmost.triage.integrity.QrCode.payload(inspection.id, mh), 240
            )
            if (qr != null) {
                canvas.drawText("Verify QR:", 40f, y, label)
                canvas.drawBitmap(qr, null, Rect(pageW - 130, (y - 10).toInt(), pageW - 50, (y + 70).toInt()), null)
            }
        }
        y += 22f

        // Contact sheet.
        canvas.drawText("Photos", 40f, y, label); y += 14f
        val thumbW = 120; val thumbH = 90; val gap = 12
        var x = 40
        for (p in photos) {
            if (x + thumbW > pageW - 40) { x = 40; y += thumbH + 26 }
            if (y + thumbH > pageH - 60) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                canvas = page.canvas; y = 40f; x = 40
            }
            val thumb = decodeThumb(File(p.filePath), thumbW, thumbH, redactPlates)
            if (thumb != null) canvas.drawBitmap(thumb, null, Rect(x, y.toInt(), x + thumbW, y.toInt() + thumbH), null)
            else canvas.drawRect(x.toFloat(), y, (x + thumbW).toFloat(), y + thumbH, Paint().apply { color = Color.LTGRAY })
            canvas.drawText(p.capturePoint, x.toFloat(), y + thumbH + 12, mono)
            x += thumbW + gap
        }
        y += thumbH + 30

        // Findings, grouped.
        fun ensureSpace(needed: Float) {
            if (y + needed > pageH - 50) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                canvas = page.canvas; y = 40f
            }
        }

        fun drawGroup(heading: String, group: List<ai.deepmost.triage.data.entity.FindingEntity>, accentColor: Boolean) {
            ensureSpace(30f)
            val h = Paint().apply { color = if (accentColor) accent else Color.BLACK; textSize = 13f; isFakeBoldText = true }
            canvas.drawText("$heading (${group.size})", 40f, y, h); y += 16f
            for (f in group) {
                ensureSpace(16f)
                val line = "• ${f.capturePoint}/${f.zone ?: "-"} ${f.type} sev=${"%.2f".format(f.severity)} conf=${"%.2f".format(f.confidence)} [${f.engine}]"
                canvas.drawText(line, 48f, y, body); y += 14f
            }
            y += 8f
        }

        val newOnes = findings.filter { it.diffStatus == DiffStatus.NEW }
        val preExisting = findings.filter { it.diffStatus == DiffStatus.PRE_EXISTING && it.type != FindingType.CLEANLINESS_SCORE }
        val resolved = findings.filter { it.diffStatus == DiffStatus.RESOLVED }
        val review = findings.filter { it.diffStatus == DiffStatus.REVIEW_REQUIRED }
        val cleanliness = findings.filter { it.head == FindingHead.CLEANLINESS && it.type == FindingType.CLEANLINESS_SCORE }

        ensureSpace(20f)
        canvas.drawText("Findings", 40f, y, label); y += 16f
        drawGroup("NEW", newOnes, accentColor = true)
        drawGroup("PRE-EXISTING", preExisting, accentColor = false)
        drawGroup("RESOLVED", resolved, accentColor = false)
        if (review.isNotEmpty()) drawGroup("REVIEW REQUIRED", review, accentColor = false)

        ensureSpace(30f)
        canvas.drawText("Cleanliness scores", 40f, y, label); y += 16f
        for (c in cleanliness) {
            ensureSpace(14f)
            canvas.drawText("• ${c.capturePoint}: ${"%.0f".format((1f - c.severity) * 100f)}/100${if (c.lowConfidence) " (low-confidence)" else ""}", 48f, y, body); y += 14f
        }
        y += 12f

        // Photo hashes.
        ensureSpace(30f)
        canvas.drawText("Photo hashes (SHA-256)", 40f, y, label); y += 14f
        for (p in photos) {
            ensureSpace(12f)
            canvas.drawText("${p.capturePoint}: ${p.sha256}", 48f, y, mono); y += 12f
        }
        y += 12f

        // Signature.
        inspection.signaturePath?.let { sp ->
            val sig = decodeThumb(File(sp), 200, 80)
            if (sig != null) {
                ensureSpace(100f)
                canvas.drawText("Driver signature", 40f, y, label); y += 8f
                canvas.drawBitmap(sig, null, Rect(48, y.toInt(), 248, y.toInt() + 80), null)
                y += 90f
            }
        }

        doc.finishPage(page)
        val outFile = fileStore.exportFile("report_${inspectionId}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        Timber.i("Generated PDF report %s", outFile.name)
        return outFile
    }

    private fun decodeThumb(file: File, w: Int, h: Int, redactPlates: Boolean = false): Bitmap? {
        if (!file.exists()) return null
        return try {
            val source: Bitmap? = if (redactPlates) {
                val bytes = Redaction.exportBytes(file, true, maxDim = maxOf(w, h) * 4)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                Images.decodeForAnalysis(file, maxDim = maxOf(w, h) * 3)
            }
            source?.let { Bitmap.createScaledBitmap(it, w, h, true) }
        } catch (t: Throwable) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { Bitmap.createScaledBitmap(it, w, h, true) }
        }
    }
}
