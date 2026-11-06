package cc.identified.sdk.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.identified.sdk.R
import cc.identified.sdk.capture.core.PixelSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentScannerTest {
    private val view = PixelSize(640.0, 800.0)
    private val corners = floatArrayOf(150f, 73f, 538f, 156f, 474f, 733f, 77f, 639f)

    @Test fun tiltedPageIsDetectedAndStraightenedFromTheStill() {
        val live = fixture(corners)
        val assessment = requireNotNull(DocumentScanner.observe(live, CameraPixels.visible(live, view)))
        val page = requireNotNull(assessment.page)
        assertTrue(assessment.quality.ready)
        page.points.forEachIndexed { index, point ->
            assertEquals(corners[index * 2] / 640.0, point.x, .025)
            assertEquals(corners[index * 2 + 1] / 800.0, point.y, .025)
        }
        live.recycle()

        val still = fixture(corners, scale = 2, horizontalPadding = 100)
        val corrected = decode(CameraPixels.documentStill(still, view, page))
        try {
            assertTrue(corrected.width in 760..850)
            assertTrue(corrected.height in 1130..1200)
            assertTrue(Color.red(corrected.getPixel(14, 14)) > 185)
            assertTrue(Color.red(corrected.getPixel(corrected.width - 15, corrected.height - 15)) > 185)
            // Sample inside a glyph, clear of interpolation at its edge.
            val textPixel = corrected.getPixel(corrected.width / 2, (corrected.height * .224).toInt())
            assertTrue("Printed text survives the warp", Color.red(textPixel) < 110)
        } finally {
            corrected.recycle()
            still.recycle()
        }
    }

    @Test fun invalidAndStaleFramesKeepTheOriginalView() {
        val clipped = corners.copyOf().apply { this[0] = -45f; this[6] = -30f }
        val small = corners.mapIndexed { i, value -> value * .35f + if (i % 2 == 0) 208f else 260f }.toFloatArray()
        for (image in listOf(fixture(null), fixture(clipped), fixture(small))) {
            val observation = requireNotNull(DocumentScanner.observe(image, CameraPixels.visible(image, view)))
            assertNull(observation.page)
            assertArrayEquals(CameraPixels.still(image, view, CaptureKind.DOCUMENT), CameraPixels.documentStill(image, view))
            image.recycle()
        }
        val live = fixture(corners)
        val page = requireNotNull(DocumentScanner.observe(live, CameraPixels.visible(live, view))?.page)
        live.recycle()
        val shifted = fixture(corners.mapIndexed { index, value -> if (index % 2 == 0) value + 45f else value }.toFloatArray())
        assertArrayEquals(CameraPixels.still(shifted, view, CaptureKind.DOCUMENT), CameraPixels.documentStill(shifted, view, page))
        assertArrayEquals(CameraPixels.still(shifted, view, CaptureKind.DOCUMENT), CameraPixels.documentStill(shifted, view, scanner = null))
        shifted.recycle()
    }

    @Test fun twoPlausiblePagesAreAmbiguous() {
        val image = fixture(floatArrayOf(24f, 98f, 291f, 105f, 288f, 693f, 30f, 685f), text = false)
        try {
            Canvas(image).drawRect(329f, 103f, 602f, 692f, Paint().apply { color = Color.LTGRAY })
            val observation = requireNotNull(DocumentScanner.observe(image, CameraPixels.visible(image, view)))
            assertNull(observation.page)
            assertArrayEquals(CameraPixels.still(image, view, CaptureKind.DOCUMENT), CameraPixels.documentStill(image, view))
        } finally {
            image.recycle()
        }
    }

    @Test fun movingAndBlankPagesGuideWithoutAutoCapture() {
        val processor = CaptureProcessor(CaptureKind.DOCUMENT)
        val steady = fixture(corners)
        val shifted = fixture(corners.mapIndexed { index, value -> if (index % 2 == 0) value + 45f else value }.toFloatArray())
        for (i in 0..10) {
            val result = processor.process(if (i % 2 == 0) steady else shifted, view, i * .15)
            assertNotNull(result.documentPage)
            assertFalse(result.autoCapture)
        }
        val blank = fixture(corners, text = false)
        for (i in 11..17) {
            val result = processor.process(blank, view, i * .15)
            assertFalse(result.autoCapture)
            assertEquals(R.string.identified_poa_hint_blurry, result.hint)
        }
        val disabled = CaptureProcessor(CaptureKind.DOCUMENT, documentScanner = null)
        assertFalse(disabled.process(steady, view, 0.0).autoCapture)
        val fires = (18..25).filter { processor.process(steady, view, it * .15).autoCapture }
        assertEquals(1, fires.size)
        assertTrue(fires.single() >= 23)
        steady.recycle()
        shifted.recycle()
        blank.recycle()
    }

    private fun fixture(
        quad: FloatArray?,
        scale: Int = 1,
        text: Boolean = true,
        horizontalPadding: Int = 0,
    ): Bitmap {
        val result = Bitmap.createBitmap((640 + 2 * horizontalPadding) * scale, 800 * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(52, 55, 58))
        if (quad == null) return result
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val perspective = Matrix()
        assertTrue(perspective.setPolyToPoly(floatArrayOf(0f, 0f, 400f, 0f, 400f, 600f, 0f, 600f), 0, quad, 0, 4))
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.translate(horizontalPadding.toFloat(), 0f)
        canvas.concat(perspective)
        paint.color = Color.rgb(224, 224, 219)
        canvas.drawRect(0f, 0f, 400f, 600f, paint)
        if (text) {
            paint.color = Color.rgb(35, 35, 35)
            for (y in 80..530 step 16) {
                for (x in 35..355 step 16) {
                    canvas.drawRect(x.toFloat(), y.toFloat(), x + 11f, y + 7f, paint)
                }
            }
        }
        return result
    }

    private fun decode(jpeg: ByteArray): Bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
}
