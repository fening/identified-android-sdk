package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class CardDetectorTest {
    private fun assertVerdict(
        frame: RGBAFrame,
        detected: Boolean,
        ready: Boolean,
        hint: CardHint,
    ): CardAssessment {
        val actual = CardDetector.assess(frame)
        assertEquals("detected", detected, actual.detected)
        assertEquals("ready", ready, actual.ready)
        assertEquals(hint, actual.hint)
        return actual
    }

    @Test
    fun acceptsASharpEvenlyLitIDAlignedWithTheGuide() {
        val a = assertVerdict(CaptureFixtures.card(176, true), true, true, CardHint.capturing)
        assertEquals(CardBounds(28.0, 36.0, 291.0, 203.0), a.bounds)
        assertEquals(1.0, a.edgeCoverage, 0.0)
        assertEquals(0.0, a.straightness, 0.0)
    }

    @Test
    fun doesNotDetectACardInAUniformFrame() {
        val a = assertVerdict(CaptureFixtures.card(), false, false, CardHint.fitEdges)
        assertEquals(0.0, a.edgeCoverage, 0.0)
        assertEquals(1.0, a.straightness, 0.0)
        assertEquals(CardDetector.guideBounds(320, 240), a.bounds)
    }

    @Test
    fun waitsForASharperImageBeforeAutoCapture() {
        val a = assertVerdict(CaptureFixtures.card(176), true, false, CardHint.holdStillCloser)
        assertEquals(0.0, a.interior.sharpness, 0.00003)
    }

    @Test
    fun rejectsAnOverexposedCard() {
        val a = assertVerdict(CaptureFixtures.card(255, true), true, false, CardHint.removeGlare)
        assertTrue(a.interior.glare > 0.45)
    }

    @Test
    fun acceptsAPaleCardBack() {
        assertVerdict(CaptureFixtures.card(240, true), true, true, CardHint.capturing)
    }

    @Test
    fun aCardTooDarkToStandOutIsNotDetected() {
        assertVerdict(CaptureFixtures.card(44, true), false, false, CardHint.fitEdges)
    }

    @Test
    fun detectsADarkCardAgainstALightBackgroundButAsksForLight() {
        val frame =
            CaptureFixtures.rectangle(CardDetector.guideBounds(320, 240), 20, background = 176)
        val a = assertVerdict(frame, true, false, CardHint.moveToLight)
        assertTrue(a.interior.brightness < 48)
    }

    @Test
    fun brightCardWithoutGlareIsRejectedBeforeBlur() {
        val a = assertVerdict(CaptureFixtures.card(250), true, false, CardHint.removeGlare)
        assertEquals(0.0, a.interior.glare, 0.0)
        assertTrue(a.interior.brightness > 246)
    }

    @Test
    fun rejectsWrongAspectEvenWhenAllFourStraightEdgesAreFound() {
        // Wider and shorter than guide; every edge remains inside its search band.
        val a =
            assertVerdict(
                CaptureFixtures.rectangle(CardBounds(12.0, 48.0, 308.0, 192.0), 176),
                false,
                false,
                CardHint.fitEdges,
            )
        assertEquals(1.0, a.edgeCoverage, 0.0)
        assertEquals(0.0, a.straightness, 0.0)
        val aspect = (a.bounds.right - a.bounds.left) / (a.bounds.bottom - a.bounds.top)
        assertTrue(kotlin.math.abs(aspect - 1.586) / 1.586 > 0.14)
    }

    @Test
    fun rejectsUndersizedCardEvenWithMatchingAspect() {
        val guide = CardDetector.guideBounds(320, 240)
        val w = (guide.right - guide.left) * 0.85
        val h = (guide.bottom - guide.top) * 0.85
        val a =
            assertVerdict(
                CaptureFixtures.rectangle(
                    CardBounds(160 - w / 2, 120 - h / 2, 160 + w / 2, 120 + h / 2),
                    176,
                ),
                false,
                false,
                CardHint.fitEdges,
            )
        assertEquals(1.0, a.edgeCoverage, 0.0)
        assertEquals(0.0, a.straightness, 0.0)
        val aspect = (a.bounds.right - a.bounds.left) / (a.bounds.bottom - a.bounds.top)
        assertTrue(kotlin.math.abs(aspect - 1.586) / 1.586 <= 0.14)
        assertTrue(a.bounds.right - a.bounds.left < (guide.right - guide.left) * 0.86)
    }

    @Test
    fun edgeGradientThresholdIsInclusive() {
        // These values produce exactly 24f after Rec.709 arithmetic in Swift and Kotlin.
        // 52 against 28 produces 23.999994f and is correctly below the threshold.
        val guide = CardDetector.guideBounds(320, 240)
        val below = CardDetector.assess(CaptureFixtures.rectangle(guide, 23, background = 0))
        val at = CardDetector.assess(CaptureFixtures.rectangle(guide, 24, background = 0))
        assertEquals(0.0, below.edgeCoverage, 0.0)
        assertFalse(below.detected)
        assertEquals(1.0, at.edgeCoverage, 0.0)
        assertTrue(at.detected)
    }

    @Test
    fun jaggedEdgesFailStraightnessDespiteFullCoverage() {
        val guide = CardDetector.guideBounds(320, 240)
        val pixels = CaptureFixtures.page(28).pixels
        for (y in 37..203) {
            val left = if (y % 8 < 4) 17 else 41
            for (x in left..291) {
                for (channel in 0..2) {
                    pixels[(y * 320 + x) * 4 + channel] = 176.toByte()
                }
            }
        }
        val a = CardDetector.assess(RGBAFrame(320, 240, pixels))
        assertEquals(1.0, a.edgeCoverage, 0.0)
        assertTrue(a.straightness > 0.045)
        assertFalse(a.detected)
        assertEquals(CardHint.fitEdges, a.hint)
        assertTrue(a.bounds.right - a.bounds.left >= (guide.right - guide.left) * 0.86)
    }

    @Test
    fun toleratesSmallMovement() {
        assertTrue(
            CardDetector.boundsAreStable(
                CardBounds(30.0, 40.0, 290.0, 204.0),
                CardBounds(33.0, 42.0, 292.0, 202.0),
                320,
                240,
            )
        )
    }

    @Test
    fun resetsStabilityAfterMaterialMovement() {
        assertFalse(
            CardDetector.boundsAreStable(
                CardBounds(30.0, 40.0, 290.0, 204.0),
                CardBounds(43.0, 40.0, 303.0, 204.0),
                320,
                240,
            )
        )
    }

    @Test
    fun stabilityChecksEachEdgeAndScalesWithFrameSize() {
        val baseline = CardBounds(0.0, 0.0, 0.0, 0.0)
        for (scale in listOf(1, 2, 4)) {
            val x = 100 * scale * 0.018
            val y = 200 * scale * 0.018
            for (edge in 0..3) {
                fun shifted(delta: Double) =
                    when (edge) {
                        0 -> baseline.copy(left = delta)
                        1 -> baseline.copy(right = delta)
                        2 -> baseline.copy(top = delta)
                        else -> baseline.copy(bottom = delta)
                    }
                val tolerance = if (edge < 2) x else y
                for (sign in listOf(-1, 1)) {
                    assertTrue(
                        CardDetector.boundsAreStable(
                            baseline,
                            shifted(sign * tolerance),
                            100 * scale,
                            200 * scale,
                        )
                    )
                    assertFalse(
                        CardDetector.boundsAreStable(
                            baseline,
                            shifted(sign * (tolerance + 1e-8)),
                            100 * scale,
                            200 * scale,
                        )
                    )
                }
            }
        }
    }

    @Test
    fun glareRequiresAllThreeChannelsAtLeast252() {
        val bounds = CardBounds(0.0, 0.0, 30.0, 30.0)
        for ((rgb, expected) in
            listOf(
                listOf(252, 252, 252) to 1.0,
                listOf(251, 255, 255) to 0.0,
                listOf(255, 251, 255) to 0.0,
                listOf(255, 255, 251) to 0.0,
            )) {
            val pixels =
                ByteArray(30 * 30 * 4) { if (it % 4 == 3) 255.toByte() else rgb[it % 4].toByte() }
            val frame = RGBAFrame(30, 30, pixels)
            assertEquals(
                expected,
                CardDetector.assessInterior(frame, frame.luminance(), bounds).glare,
                0.0,
            )
        }
    }

    @Test
    fun interiorSamplingUsesInsetStrideAndAbsoluteLaplacian() {
        // Bounds inset to (5,5)..(11,11): exactly nine samples, three columns/rows.
        val frame = CaptureFixtures.page(100, width = 20, height = 20)
        val plane = FloatArray(400) { 100f }
        plane[5 * 20 + 5] = 109f
        val q = CardDetector.assessInterior(frame, plane, CardBounds(0.0, 0.0, 16.0, 16.0))
        assertEquals(101.0, q.brightness, 0.0)
        assertEquals(4.0, q.sharpness, 0.0)
        assertEquals(0.0, q.glare, 0.0)
    }

    @Test
    fun emptyInteriorReturnsFailClosedQuality() {
        val frame = CaptureFixtures.page(100)
        assertEquals(
            InteriorQuality(0.0, 1.0, 0.0),
            CardDetector.assessInterior(
                frame,
                frame.luminance(),
                CardBounds(200.0, 180.0, 100.0, 80.0),
            ),
        )
    }
    @Test fun cardsBeyondAnySavedGuideEdgeCannotAutoCapture() {
        val g=CardDetector.guideBounds(320,240)
        for (b in listOf(g.copy(left=g.left-5,right=g.right-5),g.copy(left=g.left+5,right=g.right+5),
            g.copy(top=g.top-5,bottom=g.bottom-5),g.copy(top=g.top+5,bottom=g.bottom+5))) {
            val a=CardDetector.assess(CaptureFixtures.rectangle(b,176,true))
            assertTrue(a.detected)
            assertFalse(a.ready)
            assertEquals(CardHint.fitEdges,a.hint)
        }
    }

}
