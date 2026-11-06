package cc.identified.sdk.platform

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilePreparationTest {
    @Test
    fun oversizedInputStopsReadingAtTheLimit() {
        val stream = ByteArrayInputStream(ByteArray(1_000_000))
        try {
            FilePreparation.readBounded(stream, 10)
            fail("must refuse")
        } catch (_: IllegalArgumentException) {
            assertTrue(stream.available() >= 990_000)
        }
    }

    @Test
    fun pdfBytesPassThroughWithoutReencoding() {
        val bytes = "%PDF-1.7\nexample".toByteArray()
        val file = FilePreparation.prepare(bytes, "application/pdf", 1024)
        assertArrayEquals(bytes, file.data)
        assertEquals("application/pdf", file.mimeType)
    }

    @Test
    fun unreadableImageIsRefused() {
        try {
            FilePreparation.prepare(byteArrayOf(1, 2, 3), "image/jpeg", 1024)
            fail("must refuse")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun imageIsJpegAndDoesNotExceedTwoThousandPixels() {
        val input = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888)
        val buffer = ByteArrayOutputStream()
        input.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        input.recycle()
        val result = FilePreparation.prepare(buffer.toByteArray(), "image/png", 8 * 1024 * 1024)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(result.data, 0, result.data.size, bounds)
        assertEquals("image/jpeg", result.mimeType)
        assertTrue(bounds.outWidth <= 2000)
        assertTrue(bounds.outHeight <= 2000)
    }
}
