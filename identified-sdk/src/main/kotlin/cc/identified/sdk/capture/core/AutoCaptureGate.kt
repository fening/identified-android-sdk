package cc.identified.sdk.capture.core

internal class AutoCaptureGate {
    private var previous: CardBounds? = null
    private var stableSince: Double? = null
    private var fired = false

    fun observe(
        assessment: CardAssessment,
        frameWidth: Int,
        frameHeight: Int,
        at: Double,
    ): Boolean {
        if (fired) return false
        if (!assessment.ready && !assessment.detected) {
            stableSince = null
            previous = null
            return false
        }
        val old = previous
        previous = assessment.bounds.copy()
        if (
            old == null ||
                !CardDetector.boundsAreStable(old, assessment.bounds, frameWidth, frameHeight)
        ) {
            stableSince = at
            return false
        }
        if (
            stableSince?.let { at - it >= if (assessment.ready) readyHold else detectedHold } ==
                true
        ) {
            fired = true
            return true
        }
        return false
    }

    fun reset() {
        previous = null
        stableSince = null
        fired = false
    }

    companion object {
        const val readyHold = 0.75
        const val detectedHold = 4.0
    }
}
