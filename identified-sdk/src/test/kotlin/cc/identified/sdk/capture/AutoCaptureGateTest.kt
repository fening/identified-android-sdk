package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class AutoCaptureGateTest {
    private val still = CardBounds(30.0, 40.0, 290.0, 204.0)
    private val moved = CardBounds(45.0, 40.0, 305.0, 204.0)

    private fun assessment(
        ready: Boolean = true,
        detected: Boolean = true,
        bounds: CardBounds = still,
    ) =
        CardAssessment(
            detected,
            ready,
            bounds,
            1.0,
            0.0,
            InteriorQuality(150.0, 0.0, 20.0),
            if (ready) CardHint.capturing else CardHint.holdStillCloser,
        )

    private fun observe(
        gate: AutoCaptureGate,
        time: Double,
        ready: Boolean = true,
        detected: Boolean = true,
        bounds: CardBounds = still,
    ) = gate.observe(assessment(ready, detected, bounds), 320, 240, time)

    @Test
    fun capturesAReadyCardAfterThreeQuartersOfASecondStill() {
        val gate = AutoCaptureGate()
        val fired = (0..10).map { it * 0.12 }.filter { observe(gate, it) }
        assertEquals(listOf(0.84), fired)
    }

    @Test
    fun waitsFourSecondsForADetectedButImperfectCard() {
        val gate = AutoCaptureGate()
        for (i in 0..32) assertFalse(observe(gate, i * 0.12, ready = false))
        assertTrue(observe(gate, 4.08, ready = false))
    }

    @Test
    fun holdThresholdsAreInclusive() {
        for ((ready, hold) in listOf(true to 0.75, false to 4.0)) {
            val gate = AutoCaptureGate()
            assertFalse(observe(gate, 0.0, ready))
            assertFalse(observe(gate, hold - 1e-8, ready))
            assertTrue(observe(gate, hold, ready))
        }
    }

    @Test
    fun movementRestartsTheClock() {
        val gate = AutoCaptureGate()
        assertFalse(observe(gate, 0.0))
        assertFalse(observe(gate, 0.6))
        assertFalse(observe(gate, 0.72, bounds = moved))
        assertFalse(observe(gate, 1.2, bounds = moved))
        assertTrue(observe(gate, 1.5, bounds = moved))
    }

    @Test
    fun losingTheCardRestartsTheClock() {
        val gate = AutoCaptureGate()
        assertFalse(observe(gate, 0.0))
        assertFalse(observe(gate, 0.5, ready = false, detected = false))
        assertFalse(observe(gate, 0.6))
        assertFalse(observe(gate, 1.0))
        assertTrue(observe(gate, 1.4))
    }

    @Test
    fun firesOnlyOnceUntilReset() {
        val gate = AutoCaptureGate()
        assertFalse(observe(gate, 0.0))
        assertTrue(observe(gate, 1.0))
        assertFalse(observe(gate, 2.0))
        assertFalse(observe(gate, 2.5, ready = false, detected = false))
        assertFalse(observe(gate, 3.0))
        gate.reset()
        assertFalse(observe(gate, 3.0))
        assertTrue(observe(gate, 4.0))
    }

    @Test
    fun currentReadinessSelectsHoldWithoutRestartingStability() {
        val improves = AutoCaptureGate()
        assertFalse(observe(improves, 0.0, ready = false))
        assertTrue(observe(improves, 0.8, ready = true))
        val worsens = AutoCaptureGate()
        assertFalse(observe(worsens, 0.0))
        assertFalse(observe(worsens, 0.8, ready = false))
        assertTrue(observe(worsens, 4.0, ready = false))
    }

    @Test
    fun resetClearsUnfinishedHoldAndSnapshotsMutableBounds() {
        val gate = AutoCaptureGate()
        val bounds = still.copy()
        assertFalse(observe(gate, 0.0, bounds = bounds))
        bounds.left += 15
        bounds.right += 15
        assertFalse(observe(gate, 0.7, bounds = bounds))
        assertFalse(observe(gate, 1.0, bounds = bounds))
        gate.reset()
        assertFalse(observe(gate, 1.5, bounds = bounds))
        assertFalse(observe(gate, 2.2, bounds = bounds))
        assertTrue(observe(gate, 2.25, bounds = bounds))
    }
}
