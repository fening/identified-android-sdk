package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class DocumentQualityTest {
    @Test
    fun saysTooDarkFirst() {
        val quality = DocumentQuality.assess(CaptureFixtures.page(20, true))
        assertEquals(DocumentHint.tooDark, quality.hint)
        assertFalse(quality.ready)
    }

    @Test
    fun saysGlareBeforeBrightness() {
        val quality = DocumentQuality.assess(CaptureFixtures.page(255, true))
        assertTrue(quality.interior.glare > 0.45)
        assertEquals(DocumentHint.tooMuchGlare, quality.hint)
        assertFalse(quality.ready)
    }

    @Test
    fun saysTooBrightWithoutGlare() {
        val quality = DocumentQuality.assess(CaptureFixtures.page(250))
        assertEquals(0.0, quality.interior.glare, 0.0)
        assertEquals(DocumentHint.tooBright, quality.hint)
        assertFalse(quality.ready)
    }

    @Test
    fun saysBlurryForAFeaturelessPage() {
        val quality = DocumentQuality.assess(CaptureFixtures.page(176))
        assertEquals(DocumentHint.blurry, quality.hint)
        assertFalse(quality.ready)
    }

    @Test
    fun saysSharpForReadableText() {
        val quality = DocumentQuality.assess(CaptureFixtures.page(176, true))
        assertEquals(DocumentHint.looksSharp, quality.hint)
        assertTrue(quality.ready)
    }

    @Test
    fun assessmentIsShapeAgnosticAndMatchesSharedInteriorSampling() {
        for ((w, h) in listOf(320 to 240, 240 to 320, 300 to 300)) {
            val frame = CaptureFixtures.page(176, true, w, h)
            val quality = DocumentQuality.assess(frame)
            val interior =
                CardDetector.assessInterior(
                    frame,
                    frame.luminance(),
                    CardBounds(w * 0.05, h * 0.05, w * 0.95, h * 0.95),
                )
            assertEquals(interior, quality.interior)
            assertTrue(quality.ready)
        }
    }
}
