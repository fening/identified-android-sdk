package cc.identified.sdk.capture

import kotlin.math.hypot

/** Upright coordinates normalized to the visible document viewport. */
internal data class PagePoint(val x: Double, val y: Double)

internal data class PageQuad(val points: List<PagePoint>) {
    constructor(a: PagePoint, b: PagePoint, c: PagePoint, d: PagePoint) : this(listOf(a, b, c, d))

    fun matches(other: PageQuad, tolerance: Double): Boolean =
        points.indices.all { i ->
            hypot(points[i].x - other.points[i].x, points[i].y - other.points[i].y) <= tolerance
        }
}

internal class PageCaptureGate {
    private var anchor: PageQuad? = null
    private var previous: PageQuad? = null
    private var stableSince = 0.0
    private var lastSeen = 0.0
    private var samples = 0
    private var fired = false

    fun observe(page: PageQuad?, qualityReady: Boolean, now: Double): Boolean {
        if (fired) return false
        if (page == null || !qualityReady) {
            reset()
            return false
        }
        if (anchor?.matches(page, .025) != true || previous?.matches(page, .018) != true ||
            now <= lastSeen || now - lastSeen > .4
        ) {
            anchor = page
            stableSince = now
            samples = 0
        }
        previous = page
        lastSeen = now
        samples++
        fired = samples >= 4 && now - stableSince >= .75
        return fired
    }

    fun reset() {
        anchor = null
        previous = null
        fired = false
    }
}
