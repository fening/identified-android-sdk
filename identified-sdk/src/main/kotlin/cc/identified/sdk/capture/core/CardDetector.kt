package cc.identified.sdk.capture.core

import kotlin.math.*

internal data class CardBounds(
    var left: Double,
    var top: Double,
    var right: Double,
    var bottom: Double,
)

internal enum class CardHint {
    fitEdges,
    moveToLight,
    removeGlare,
    holdStillCloser,
    capturing,
}

internal data class InteriorQuality(
    var brightness: Double,
    var glare: Double,
    var sharpness: Double,
)

internal data class CardAssessment(
    var detected: Boolean,
    var ready: Boolean,
    var bounds: CardBounds,
    var edgeCoverage: Double,
    var straightness: Double,
    var interior: InteriorQuality,
    var hint: CardHint,
)

internal object CardDetector {
    const val aspectRatio = 1.586
    const val guideWidthFraction = 0.82
    const val analysisPixels = 90_000.0
    const val analysisInterval = 0.12
    const val edgeThreshold = 24f
    const val minEdgeCoverage = 0.5
    const val maxStraightness = 0.045
    const val minSharpness = 6.0
    const val minBrightness = 48.0
    const val maxBrightness = 246.0
    const val maxGlare = 0.45

    fun guideBounds(width: Int, height: Int): CardBounds {
        val w = width * guideWidthFraction
        val h = w / aspectRatio
        return CardBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    fun assess(frame: RGBAFrame): CardAssessment {
        val g = guideBounds(frame.width, frame.height)
        val l = frame.luminance()
        val gw = g.right - g.left
        val gh = g.bottom - g.top
        val hs = max(5.0, gw * 0.075)
        val vs = max(5.0, gh * 0.075)
        val left = edge(l, frame.width, frame.height, true, g.left, g.top, g.bottom, hs)
        val right = edge(l, frame.width, frame.height, true, g.right, g.top, g.bottom, hs)
        val top = edge(l, frame.width, frame.height, false, g.top, g.left, g.right, vs)
        val bottom = edge(l, frame.width, frame.height, false, g.bottom, g.left, g.right, vs)
        val b = CardBounds(left.position, top.position, right.position, bottom.position)
        val coverage = minOf(left.coverage, right.coverage, top.coverage, bottom.coverage)
        val straightness =
            maxOf(left.straightness, right.straightness, top.straightness, bottom.straightness)
        val w = max(1.0, b.right - b.left)
        val h = max(1.0, b.bottom - b.top)
        val detected =
            coverage >= minEdgeCoverage &&
                straightness <= maxStraightness &&
                abs(w / h - aspectRatio) / aspectRatio <= 0.14 &&
                w >= gw * 0.86 &&
                h >= gh * 0.86
        val q = assessInterior(frame, l, b)
        // The central-difference detector reports left/top one pixel before
        // the first card pixel. Compare actual raster edges with the guide,
        // rather than accepting its much wider edge-search region.
        val insideGuide = b.left >= roundedInt(g.left) - 1 && b.top >= roundedInt(g.top) - 1 &&
            b.right <= roundedInt(g.right) && b.bottom <= roundedInt(g.bottom)
        val ready =
            detected && insideGuide &&
                q.brightness >= minBrightness &&
                q.brightness <= maxBrightness &&
                q.glare <= maxGlare &&
                q.sharpness >= minSharpness
        val hint =
            when {
                detected && !insideGuide -> CardHint.fitEdges
                detected && q.brightness < minBrightness -> CardHint.moveToLight
                detected && (q.brightness > maxBrightness || q.glare > maxGlare) ->
                    CardHint.removeGlare
                detected && q.sharpness < minSharpness -> CardHint.holdStillCloser
                ready -> CardHint.capturing
                else -> CardHint.fitEdges
            }
        return CardAssessment(detected, ready, b, coverage, straightness, q, hint)
    }

    fun assessInterior(
        frame: RGBAFrame,
        luminance: FloatArray,
        bounds: CardBounds,
    ): InteriorQuality {
        val w = frame.width
        val left = clamp(roundedInt(bounds.left + 5), 2, w - 3)
        val right = clamp(roundedInt(bounds.right - 5), 2, w - 3)
        val top = clamp(roundedInt(bounds.top + 5), 2, frame.height - 3)
        val bottom = clamp(roundedInt(bounds.bottom - 5), 2, frame.height - 3)
        var brightness = 0.0
        var sharpness = 0.0
        var glare = 0
        var samples = 0
        for (y in top..bottom step 3) {
            for (x in left..right step 3) {
                val i = y * w + x
                val v = luminance[i]
                val s = i * 4
                brightness += v.toDouble()
                if (
                    (frame.pixels[s].toInt() and 255) >= 252 &&
                        (frame.pixels[s + 1].toInt() and 255) >= 252 &&
                        (frame.pixels[s + 2].toInt() and 255) >= 252
                )
                    glare++
                sharpness +=
                    abs(
                            4 * v -
                                luminance[i - 1] -
                                luminance[i + 1] -
                                luminance[i - w] -
                                luminance[i + w]
                        )
                        .toDouble()
                samples++
            }
        }
        return if (samples > 0)
            InteriorQuality(brightness / samples, glare.toDouble() / samples, sharpness / samples)
        else InteriorQuality(0.0, 1.0, 0.0)
    }

    fun boundsAreStable(
        previous: CardBounds,
        current: CardBounds,
        frameWidth: Int,
        frameHeight: Int,
    ): Boolean =
        abs(previous.left - current.left) <= frameWidth * 0.018 &&
            abs(previous.right - current.right) <= frameWidth * 0.018 &&
            abs(previous.top - current.top) <= frameHeight * 0.018 &&
            abs(previous.bottom - current.bottom) <= frameHeight * 0.018

    private data class EdgeEstimate(
        val position: Double,
        val coverage: Double,
        val straightness: Double,
    )

    /** Same sampling as Swift: vertical edges sample 42 rows, horizontal edges 54 columns. */
    private fun edge(
        l: FloatArray,
        w: Int,
        h: Int,
        vertical: Boolean,
        expected: Double,
        start: Double,
        end: Double,
        radius: Double,
    ): EdgeEstimate {
        val alongLimit = if (vertical) h else w
        val acrossLimit = if (vertical) w else h
        val a = clamp(roundedInt(start + (end - start) * 0.08), 2, alongLimit - 3)
        val b = clamp(roundedInt(end - (end - start) * 0.08), 2, alongLimit - 3)
        val step = max(2, roundedInt((b - a).toDouble() / if (vertical) 42 else 54))
        val c = clamp(roundedInt(expected - radius), 2, acrossLimit - 3)
        val d = clamp(roundedInt(expected + radius), 2, acrossLimit - 3)
        val positions = mutableListOf<Double>()
        var samples = 0
        for (along in a..b step step) {
            var strongest = 0f
            var position = roundedInt(expected)
            for (across in c..d) {
                val gradient =
                    if (vertical) abs(l[along * w + across + 1] - l[along * w + across - 1])
                    else abs(l[(across + 1) * w + along] - l[(across - 1) * w + along])
                if (gradient > strongest) {
                    strongest = gradient
                    position = across
                }
            }
            samples++
            if (strongest >= edgeThreshold) positions.add(position.toDouble())
        }
        val sorted = positions.sorted()
        val m = sorted.size / 2
        val position =
            if (sorted.isEmpty()) expected
            else if (sorted.size % 2 == 0) (sorted[m - 1] + sorted[m]) / 2 else sorted[m]
        val deviation =
            if (sorted.isEmpty()) 1.0
            else
                sqrt(sorted.sumOf { (it - position) * (it - position) } / sorted.size) /
                    max(1.0, end - start)
        return EdgeEstimate(
            position,
            if (samples > 0) positions.size.toDouble() / samples else 0.0,
            deviation,
        )
    }
}
