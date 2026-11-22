package cz.primevoice.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cz.primevoice.core.OcrReading
import cz.primevoice.core.Region
import kotlinx.coroutines.tasks.await
import kotlin.math.*

data class Crop(val bitmap: Bitmap, val signature: IntArray, val contrast: Float, val sharpness: Float)
data class OcrResult(val reading: OcrReading, val elapsedMs: Long, val blurry: Boolean)

class OCRProcessor {
    private var recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var failures = 0

    /** Crop directly from the luminance plane. No full-size RGB frame and no frame is saved. */
    fun crop(image: ImageProxy, region: Region, enhance: Boolean): Crop {
        val rect = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val rotated = rotation == 90 || rotation == 270
        val rw = if (rotated) rect.height() else rect.width()
        val rh = if (rotated) rect.width() else rect.height()
        val valid = region.valid()
        val left = (valid.left * rw).roundToInt().coerceIn(0, rw - 1)
        val top = (valid.top * rh).roundToInt().coerceIn(0, rh - 1)
        val width = ((valid.right - valid.left) * rw).roundToInt().coerceIn(1, rw - left)
        val height = ((valid.bottom - valid.top) * rh).roundToInt().coerceIn(1, rh - top)
        val factor = min(1f, 1100f / width)
        val ow = max(1, (width * factor).roundToInt()); val oh = max(1, (height * factor).roundToInt())
        val plane = image.planes[0]; val buffer = plane.buffer
        val gray = IntArray(ow * oh); val histogram = IntArray(256)
        var sum = 0.0; var sumSquares = 0.0
        for (y in 0 until oh) for (x in 0 until ow) {
            val dx = left + (x / factor).toInt().coerceAtMost(width - 1)
            val dy = top + (y / factor).toInt().coerceAtMost(height - 1)
            val sx: Int; val sy: Int
            when (rotation) {
                90 -> { sx = rect.left + dy; sy = rect.bottom - 1 - dx }
                180 -> { sx = rect.right - 1 - dx; sy = rect.bottom - 1 - dy }
                270 -> { sx = rect.right - 1 - dy; sy = rect.top + dx }
                else -> { sx = rect.left + dx; sy = rect.top + dy }
            }
            val value = buffer.get(sy * plane.rowStride + sx * plane.pixelStride).toInt() and 255
            gray[y * ow + x] = value; histogram[value]++; sum += value; sumSquares += value * value
        }
        val signature = IntArray(32 * 16) { index ->
            gray[((index / 32) * oh / 16).coerceAtMost(oh - 1) * ow + ((index % 32) * ow / 32).coerceAtMost(ow - 1)]
        }
        var edges = 0.0; var count = 0
        for (y in 1 until oh - 1 step 2) for (x in 1 until ow - 1 step 2) {
            val i = y * ow + x
            val laplace = gray[i - 1] + gray[i + 1] + gray[i - ow] + gray[i + ow] - 4 * gray[i]
            edges += laplace.toDouble() * laplace; count++
        }
        val contrast = sqrt(max(0.0, sumSquares / gray.size - (sum / gray.size).pow(2))).toFloat()
        var low = 0; var high = 255
        if (enhance && contrast < 50) {
            var n = 0
            while (low < 254 && n + histogram[low] < gray.size * .01) { n += histogram[low]; low++ }
            n = 0
            while (high > low + 20 && n + histogram[high] < gray.size * .01) { n += histogram[high]; high-- }
        }
        val pixels = IntArray(gray.size) { i ->
            val value = if (enhance && high - low >= 20) ((gray[i] - low) * 255 / (high - low)).coerceIn(0, 255) else gray[i]
            (255 shl 24) or (value shl 16) or (value shl 8) or value
        }
        val bitmap = Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
        return Crop(bitmap, signature, contrast, (edges / max(count, 1)).toFloat())
    }

    suspend fun recognize(crop: Crop, capturedAt: Long): OcrResult {
        val start = SystemClock.elapsedRealtime()
        try {
            val result = recognizer.process(InputImage.fromBitmap(crop.bitmap, 0)).await()
            val lines = result.textBlocks.flatMap { it.lines }.filter { it.text.isNotBlank() }
            // Sort complete lines by baseline rows, then left-to-right for blocks on the same row.
            val sorted = lines.sortedBy { it.boundingBox?.top ?: 0 }
            val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
            for (line in sorted) {
                val box = line.boundingBox ?: continue
                val row = rows.lastOrNull()
                val anchor = row?.firstOrNull()?.boundingBox
                if (anchor != null && abs(box.centerY() - anchor.centerY()) < min(box.height(), anchor.height()) * .55f)
                    row.add(line) else rows.add(mutableListOf(line))
            }
            val text = rows.joinToString("\n") { row -> row.sortedBy { it.boundingBox?.left }.joinToString(" ") { it.text } }
            val bad = text.count { it == '�' || it == '|' }
            val blurry = crop.sharpness < 40 && crop.contrast > 7
            // Heuristic score, not a calibrated probability from ML Kit.
            val score = if (text.isBlank()) 0f else
                (0.9f - bad * .1f - (if (blurry) .22f else 0f) - (if (crop.contrast < 15) .1f else 0f)).coerceIn(.3f, .95f)
            failures = 0
            return OcrResult(OcrReading(text, capturedAt, score), SystemClock.elapsedRealtime() - start, blurry)
        } catch (e: Exception) {
            failures++
            if (failures >= 3) {
                recognizer.close(); recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); failures = 0
            }
            throw e
        }
    }
    fun close() = recognizer.close()
}
