package cc.identified.sdk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cc.identified.sdk.core.DocType
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
class AcceptedCapturesTest {
    @Test
    fun acceptedImageIsIndependentBoundedJpegAndDoesNotChangeEvidence() {
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val original =
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        bitmap.recycle()
        val before = original.copyOf()
        val store = AcceptedCaptures()
        store.record(DocType.selfie, original, "image/jpeg")
        val jpeg = requireNotNull(store.items.single().jpeg)
        assertFalse(store.items.single().pdf)
        assertNotSame(original, jpeg)
        assertArrayEquals(before, original)
        assertTrue(jpeg.size <= 128 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals(320, maxOf(bounds.outWidth, bounds.outHeight))
    }

    @Test
    fun pdfRetainsOnlyMetadataAndReplacementDoesNotAddEntries() {
        val store = AcceptedCaptures()
        store.record(DocType.proofOfAddress, byteArrayOf(1, 2, 3), "application/pdf")
        assertTrue(store.items.single().pdf)
        assertNull(store.items.single().jpeg)
        store.record(DocType.proofOfAddress, byteArrayOf(4, 5), "application/pdf")
        assertEquals(1, store.items.size)
        assertNull(store.items.single().jpeg)
        store.clear()
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun storageIsBoundedToKnownDocumentTypes() {
        val store = AcceptedCaptures()
        val types =
            listOf(
                DocType.governmentId,
                DocType.licenseFront,
                DocType.licenseBack,
                DocType.selfie,
                DocType.proofOfAddress,
            )
        repeat(20) { index ->
            store.record(types[index % types.size], byteArrayOf(1), "application/pdf")
        }
        store.record("unknown", byteArrayOf(1), "application/pdf")
        assertEquals(5, store.items.size)
        assertTrue(store.items.all { it.jpeg == null })
    }
}
