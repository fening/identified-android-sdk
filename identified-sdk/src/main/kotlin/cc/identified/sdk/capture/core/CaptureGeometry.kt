package cc.identified.sdk.capture.core

import kotlin.math.*

internal data class PixelSize(var width: Double, var height: Double)

internal data class PixelRect(var x: Double, var y: Double, var width: Double, var height: Double) {
    val midX
        get() = x + width / 2

    val midY
        get() = y + height / 2
}

internal data class AnalysisSize(val width: Int, val height: Int)

// Swift rounds ties away from zero, unlike Kotlin's round (ties to even).
internal fun roundedInt(value: Double): Int =
    if (value >= 0) floor(value + 0.5).toInt() else ceil(value - 0.5).toInt()

internal fun clamp(value: Int, lower: Int, upper: Int) = max(lower, min(upper, value))

internal object CaptureGeometry {
    const val ringFraction = 104.0 / 300.0

    /** Place portrait footage above the controls, preserving the complete fitted image. */
    fun selfiePreviewTopInset(stage: PixelSize, sourceAspect: Double): Double =
        if (stage.height > stage.width)
            max(0.0, (stage.height - stage.width / sourceAspect) / 2)
        else 0.0

    fun selfieCenterY(stage: PixelSize, sourceAspect: Double): Double =
        stage.height / 2 - selfiePreviewTopInset(stage, sourceAspect)

    /** Full stage in source coordinates, including letterbox space. Never crop with this rect. */
    fun aspectFitStageRect(source: PixelSize, stage: PixelSize): PixelRect? {
        if (!listOf(source.width, source.height, stage.width, stage.height).all { it.isFinite() && it > 0 }) return null
        val scale = min(stage.width / source.width, stage.height / source.height)
        val w = stage.width / scale
        val h = stage.height / scale
        if (!scale.isFinite() || scale <= 0 || !w.isFinite() || !h.isFinite()) return null
        return PixelRect((source.width - w) / 2, (source.height - h) / 2, w, h)
    }

    /** Guide-centred stage coordinates for face framing; never crop with this rect. */
    fun selfieFramingRect(source: PixelSize, stage: PixelSize): PixelRect? {
        return aspectFitStageRect(source, stage)
    }

    /** The raised guide plus bleed, bounded to actual camera pixels. */
    fun selfieCaptureRect(source: PixelSize, stage: PixelSize): PixelRect? {
        val virtual = selfieFramingRect(source, stage) ?: return null
        val side = min(2 * ringFraction * min(virtual.width, virtual.height) * 1.2,
            min(source.width, source.height))
        return PixelRect((virtual.midX - side / 2).coerceIn(0.0, source.width - side),
            (virtual.midY - side / 2).coerceIn(0.0, source.height - side), side, side)
    }

    fun visibleRect(source: PixelSize, view: PixelSize): PixelRect? {
        if (!(source.width > 0 && source.height > 0 && view.width > 0 && view.height > 0))
            return null
        val scale = max(view.width / source.width, view.height / source.height)
        val w = min(source.width, view.width / scale)
        val h = min(source.height, view.height / scale)
        return PixelRect((source.width - w) / 2, (source.height - h) / 2, w, h)
    }

    fun guideCrop(
        visible: PixelRect,
        widthFraction: Double = CardDetector.guideWidthFraction,
        aspectRatio: Double = CardDetector.aspectRatio,
        margin: Double = 1.08,
    ): PixelRect {
        val w = min(visible.width * widthFraction * margin, visible.width)
        val h = min(w / aspectRatio, visible.height)
        return PixelRect(
            visible.x + (visible.width - w) / 2,
            visible.y + (visible.height - h) / 2,
            w,
            h,
        )
    }

    fun circleCrop(
        visible: PixelRect,
        ringFraction: Double = CaptureGeometry.ringFraction,
        margin: Double = 1.2,
    ): PixelRect {
        val side =
            min(
                2 * ringFraction * min(visible.width, visible.height) * margin,
                min(visible.width, visible.height),
            )
        return PixelRect(
            visible.x + (visible.width - side) / 2,
            visible.y + (visible.height - side) / 2,
            side,
            side,
        )
    }

    fun analysisSize(aspect: Double, pixels: Double = CardDetector.analysisPixels): AnalysisSize {
        val w = max(120, roundedInt(sqrt(pixels * aspect)))
        return AnalysisSize(w, max(120, roundedInt(w / aspect)))
    }

    fun flippedForCoreImage(rect: PixelRect, imageHeight: Double) =
        rect.copy(y = imageHeight - rect.y - rect.height)
}
