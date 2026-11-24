package app.korvy.launcher.core

/**
 * A screen-shaped crop frame over a picture, in fractions of the picture (0..1). [aspect] is the screen's
 * width / height; the frame always keeps that shape (in pixels) and stays inside the picture.
 */
data class CropFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height

    companion object {
        const val MIN_ZOOM = 0.3f

        /** The biggest screen-shaped frame that fits, scaled by [zoom] and centred on (cx, cy). */
        fun of(imgW: Int, imgH: Int, aspect: Float, zoom: Float = 1f, cx: Float = 0.5f, cy: Float = 0.5f): CropFrame {
            val (maxW, maxH) = if (imgW.toFloat() / imgH > aspect) (aspect * imgH / imgW) to 1f else 1f to (imgW / (aspect * imgH))
            val z = zoom.coerceIn(MIN_ZOOM, 1f)
            val w = maxW * z; val h = maxH * z
            return CropFrame((cx - w / 2).coerceIn(0f, 1f - w), (cy - h / 2).coerceIn(0f, 1f - h), w, h)
        }
    }

    /** Moves by (dx, dy) and scales by [zoomBy] around the frame's centre, staying inside the picture. */
    fun transformed(imgW: Int, imgH: Int, aspect: Float, dx: Float, dy: Float, zoomBy: Float): CropFrame {
        val (maxW, _) = if (imgW.toFloat() / imgH > aspect) (aspect * imgH / imgW) to 1f else 1f to (imgW / (aspect * imgH))
        val zoom = width / maxW * zoomBy
        return of(imgW, imgH, aspect, zoom, left + width / 2 + dx, top + height / 2 + dy)
    }
}
