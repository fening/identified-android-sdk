package cc.identified.sdk.capture.core

internal class SceneLight {
    var dark = false
        private set

    private var streak = 0

    fun observe(meanLuminance: Double) {
        if (dark || !(meanLuminance >= noPictureBelow)) return
        if (meanLuminance >= darkBelow) {
            streak = 0
            return
        }
        streak++
        if (streak >= darkSamples) dark = true
    }

    companion object {
        const val sampleInterval = 0.5
        const val darkBelow = 45.0
        const val darkSamples = 2
        const val noPictureBelow = 3.0

        fun meanLuminance(frame: RGBAFrame): Double {
            val p = frame.luminance()
            return if (p.isEmpty()) 0.0 else p.sumOf { it.toDouble() } / p.size
        }
    }
}
