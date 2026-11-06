package cc.identified.sdk.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCaptureGateTest {
    private fun page(x: Double = 0.0, skew: Double = 0.0) = PageQuad(
        PagePoint(.20 + x + skew, .15), PagePoint(.82 + x, .20),
        PagePoint(.78 + x + skew, .84), PagePoint(.15 + x, .80),
    )

    @Test fun stableSharpPageFiresOnceAndResetRearms() {
        val gate = PageCaptureGate()
        for (i in 0..5) assertFalse(gate.observe(page(i * .001), true, i * .14))
        assertTrue(gate.observe(page(), true, .90))
        assertFalse(gate.observe(page(), true, 1.05))
        gate.reset()
        assertFalse(gate.observe(page(), true, 1.20))
    }

    @Test fun missingOrUnsharpFramesBreakTheHold() {
        val gate = PageCaptureGate()
        for (i in 0..20) {
            assertFalse(gate.observe(if (i % 3 == 0) null else page(), i % 3 != 1, i * .14))
        }
    }

    @Test fun changingCornersAndSlowDriftCannotAccumulateAHold() {
        val gate = PageCaptureGate()
        for (i in 0..20) {
            // Move two opposite corners while keeping the outer bounding box unchanged.
            assertFalse(gate.observe(page(skew = if (i % 2 == 0) 0.0 else .045), true, i * .14))
        }
        gate.reset()
        for (i in 0..20) assertFalse(gate.observe(page(i * .008), true, i * .14))
    }

    @Test fun longGapsAndSparseObservationsDoNotCountAsStability() {
        val gate = PageCaptureGate()
        assertFalse(gate.observe(page(), true, 0.0))
        assertFalse(gate.observe(page(), true, .15))
        assertFalse(gate.observe(page(), true, 2.0))
        assertFalse(gate.observe(page(), true, 2.20))
        assertFalse(gate.observe(page(), true, 2.40))
        assertFalse(gate.observe(page(), true, 2.60))
        assertTrue(gate.observe(page(), true, 2.85))
    }
}
