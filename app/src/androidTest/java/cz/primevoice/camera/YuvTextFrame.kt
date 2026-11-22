package cz.primevoice.camera

import android.graphics.*
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Controlled YUV input; recognizer, cropping, admission and all processing remain real. */
internal class YuvTextFrame(text: String, private val rotation: Int = 0,
    private val pixelStep: Int = 1, private val bufferOffset: Int = 0,
    private val invalidFormat: Boolean = false) : ImageProxy {
    val closes = AtomicInteger()
    private val upright = Bitmap.createBitmap(1100, 240, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.BLACK)
            drawText(text, 35f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f })
        }
    }
    private val w = if (rotation == 90 || rotation == 270) upright.height + 32 else upright.width + 32
    private val h = if (rotation == 90 || rotation == 270) upright.width + 32 else upright.height + 32
    private var rect = Rect(16, 16, w - 16, h - 16)
    private val stride = w * pixelStep + 24
    private val bytes = ByteBuffer.allocate(bufferOffset + stride * h).apply {
        for (y in 0 until upright.height) for (x in 0 until upright.width) {
            val sx: Int; val sy: Int
            when (rotation) {
                90 -> { sx = rect.left + y; sy = rect.bottom - 1 - x }
                180 -> { sx = rect.right - 1 - x; sy = rect.bottom - 1 - y }
                270 -> { sx = rect.right - 1 - y; sy = rect.top + x }
                else -> { sx = rect.left + x; sy = rect.top + y }
            }
            put(bufferOffset + sy * stride + sx * pixelStep, (upright.getPixel(x, y) and 255).toByte())
        }
        position(bufferOffset)
        upright.recycle()
    }
    override fun getCropRect() = Rect(rect)
    override fun setCropRect(value: Rect?) { if (value != null) rect = Rect(value) }
    override fun getFormat() = if (invalidFormat) ImageFormat.JPEG else ImageFormat.YUV_420_888
    override fun getWidth() = w
    override fun getHeight() = h
    override fun getPlanes() = arrayOf(object : ImageProxy.PlaneProxy {
        override fun getRowStride() = stride
        override fun getPixelStride() = pixelStep
        override fun getBuffer() = bytes
    })
    override fun getImageInfo() = object : ImageInfo {
        override fun getTagBundle() = TagBundle.emptyBundle()
        override fun getTimestamp() = 0L
        override fun getRotationDegrees() = rotation
        override fun populateExifData(builder: ExifData.Builder) { builder.setOrientationDegrees(rotation) }
    }
    override fun getImage(): Image? = null
    override fun close() { closes.incrementAndGet() }
}
