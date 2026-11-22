package cz.primevoice.core

data class Region(val left: Float = 0.08f, val top: Float = 0.65f,
                  val right: Float = 0.92f, val bottom: Float = 0.94f) {
    fun valid(): Region {
        val l = left.coerceIn(0f, 0.90f); val t = top.coerceIn(0f, 0.90f)
        return Region(l, t, right.coerceIn(l + 0.08f, 1f), bottom.coerceIn(t + 0.08f, 1f))
    }
}

/** Preview FIT_CENTER and analysis share a CameraX ViewPort; region is relative to visible content. */
data class DisplayRect(val left: Float, val top: Float, val width: Float, val height: Float)
fun fitRect(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): DisplayRect {
    val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val w = imageWidth * scale; val h = imageHeight * scale
    return DisplayRect((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
}
