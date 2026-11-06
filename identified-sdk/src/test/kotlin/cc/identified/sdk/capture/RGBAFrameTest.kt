package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class RGBAFrameTest {
    @Test
    fun luminanceUsesUnsignedRec709AndIgnoresAlpha() {
        val p = RGBAFrame(3, 1, byteArrayOf(-1, 0, 0, 0, 0, -1, 0, 127, 0, 0, -1, -1)).luminance()
        assertEquals(54.213, p[0].toDouble(), 0.00001)
        assertEquals(182.376, p[1].toDouble(), 0.00002)
        assertEquals(18.411, p[2].toDouble(), 0.00001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedFrameRejected() {
        RGBAFrame(2, 2, ByteArray(3))
    }

    @Test
    fun luminanceRetainsRowMajorOrder() {
        val pixels = byteArrayOf(10, 10, 10, 0, 20, 20, 20, 0, 30, 30, 30, 0, 40, 40, 40, 0)
        assertArrayEquals(
            floatArrayOf(10f, 20f, 30f, 40f),
            RGBAFrame(2, 2, pixels).luminance(),
            0.00001f,
        )
    }
}
