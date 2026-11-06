package cc.identified.sdk.capture.core

/** Upright, unmirrored RGBA bytes; row-major with top-left origin. */
internal class RGBAFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size.toLong() == width.toLong() * height * 4) {
            "RGBAFrame needs width * height * 4 bytes"
        }
    }

    fun luminance(): FloatArray =
        FloatArray(width * height) { p ->
            val i = p * 4
            (pixels[i].toInt() and 255) * 0.2126f +
                (pixels[i + 1].toInt() and 255) * 0.7152f +
                (pixels[i + 2].toInt() and 255) * 0.0722f
        }
}
