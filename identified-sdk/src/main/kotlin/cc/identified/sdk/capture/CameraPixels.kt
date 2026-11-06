package cc.identified.sdk.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import cc.identified.sdk.capture.core.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Memory-only rendering seam, also callable by instrumentation with synthetic bitmaps. */
internal object CameraPixels {
    private const val maxDocumentDecodeSide = 4096L

    // CameraX RGBA output is R,G,B,A, independent of the native Bitmap byte order.
    fun rgba(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotation: Int,
        bgra: Boolean = false,
    ): Bitmap {
        val bytes = buffer.duplicate()
        val base = bytes.position()
        val colors = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = base + y * rowStride + x * pixelStride
            val a = bytes.get(i + 3).toInt() and 255
            val r = bytes.get(i + if (bgra) 2 else 0).toInt() and 255
            val g = bytes.get(i + 1).toInt() and 255
            val b = bytes.get(i + if (bgra) 0 else 2).toInt() and 255
            colors[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return upright(
            Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888),
            rotation,
        )
    }

    // CameraX 1.4.2 copies RGBA planes through its native stride-aware bitmap path.
    // It does not apply cropRect or rotation; apply those exactly once below.
    fun analysis(image: ImageProxy): Bitmap = viewport(image.toBitmap(), image)

    private fun viewport(raw: Bitmap, image: ImageProxy, sampled: Boolean = false): Bitmap {
        val r = image.cropRect
        val cut = if (sampled) {
            val xScale = raw.width.toDouble() / image.width
            val yScale = raw.height.toDouble() / image.height
            val left = (r.left * xScale).roundToInt().coerceIn(0, raw.width - 1)
            val top = (r.top * yScale).roundToInt().coerceIn(0, raw.height - 1)
            val right = (r.right * xScale).roundToInt().coerceIn(left + 1, raw.width)
            val bottom = (r.bottom * yScale).roundToInt().coerceIn(top + 1, raw.height)
            Bitmap.createBitmap(raw, left, top, right - left, bottom - top)
        } else {
            Bitmap.createBitmap(raw, r.left, r.top, r.width(), r.height())
        }
        if (cut !== raw) raw.recycle()
        return upright(cut, image.imageInfo.rotationDegrees)
    }

    fun photo(image: ImageProxy, bounded: Boolean = false): Bitmap {
        val buffer = image.planes[0].buffer.duplicate()
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val options = if (bounded) BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
            inJustDecodeBounds = false
            inSampleSize = 1
            // Keep the ordinary high-resolution still. Subsample very large sensor outputs
            // before allocating pixels or rotating/cropping the viewport.
            while (maxOf(outWidth, outHeight).toLong() > maxDocumentDecodeSide * inSampleSize) inSampleSize *= 2
        } else null
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        // BitmapFactory does not apply JPEG EXIF. CameraX supplies the equivalent rotation.
        return viewport(bitmap, image, sampled = options != null && options.inSampleSize > 1)
    }

    private fun upright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return bitmap
        val out =
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    fun visible(bitmap: Bitmap, view: PixelSize): PixelRect =
        requireNotNull(
            CaptureGeometry.visibleRect(
                PixelSize(bitmap.width.toDouble(), bitmap.height.toDouble()),
                view,
            )
        )

    fun crop(bitmap: Bitmap, rect: PixelRect): Bitmap {
        val left = rect.x.roundToInt().coerceIn(0, bitmap.width - 1)
        val top = rect.y.roundToInt().coerceIn(0, bitmap.height - 1)
        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            rect.width.roundToInt().coerceIn(1, bitmap.width - left),
            rect.height.roundToInt().coerceIn(1, bitmap.height - top),
        )
    }

    fun frame(bitmap: Bitmap, rect: PixelRect, width: Int, height: Int): RGBAFrame {
        val cut = crop(bitmap, rect)
        val small = Bitmap.createScaledBitmap(cut, width, height, true)
        val colors = IntArray(width * height)
        small.getPixels(colors, 0, width, 0, 0, width, height)
        val bytes = ByteArray(width * height * 4)
        colors.forEachIndexed { i, p ->
            bytes[i * 4] = (p shr 16).toByte()
            bytes[i * 4 + 1] = (p shr 8).toByte()
            bytes[i * 4 + 2] = p.toByte()
            bytes[i * 4 + 3] = (p ushr 24).toByte()
        }
        if (small !== cut && small !== bitmap) small.recycle()
        if (cut !== bitmap) cut.recycle()
        return RGBAFrame(width, height, bytes)
    }

    fun jpeg(bitmap: Bitmap, rect: PixelRect, quality: Int): ByteArray {
        val cut = crop(bitmap, rect)
        return try {
            ByteArrayOutputStream().use {
                check(cut.compress(Bitmap.CompressFormat.JPEG, quality, it))
                it.toByteArray()
            }
        } finally {
            if (cut !== bitmap) cut.recycle()
        }
    }

    fun documentStill(
        bitmap: Bitmap,
        view: PixelSize,
        expectedPage: PageQuad? = null,
        scanner: DocumentScanner? = DocumentScanner,
    ): ByteArray {
        val viewport = visible(bitmap, view)
        val corrected = scanner?.straighten(bitmap, viewport, expectedPage)
        if (corrected == null) return jpeg(bitmap, viewport, 95)
        return try {
            jpeg(corrected, PixelRect(0.0, 0.0, corrected.width.toDouble(), corrected.height.toDouble()), 95)
        } catch (_: RuntimeException) {
            jpeg(bitmap, viewport, 95)
        } finally {
            corrected.recycle()
        }
    }

    fun still(bitmap: Bitmap, view: PixelSize, kind: CaptureKind): ByteArray {
        val rect =
            when (kind) {
                // The live preview stays full-frame. Crop only after ImageCapture returns.
                CaptureKind.CARD -> CaptureGeometry.guideCrop(
                    PixelRect(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble()), margin = 1.0)
                CaptureKind.DOCUMENT -> visible(bitmap, view)
                CaptureKind.SELFIE -> PixelRect(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
            }
        return jpeg(bitmap, rect, if (kind == CaptureKind.SELFIE) 85 else 95)
    }

}
