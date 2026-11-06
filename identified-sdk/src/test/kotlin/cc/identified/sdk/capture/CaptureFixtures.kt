package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import kotlin.math.roundToInt

/** Pixel-for-pixel port of Tests/Support/FrameFixtures.swift. */
internal object CaptureFixtures {
    fun card(
        value: Int? = null,
        detail: Boolean = false,
        width: Int = 320,
        height: Int = 240,
    ): RGBAFrame {
        val guide = CardDetector.guideBounds(width, height)
        return rectangle(guide, value, detail, width, height)
    }

    fun rectangle(
        bounds: CardBounds,
        value: Int?,
        detail: Boolean = false,
        width: Int = 320,
        height: Int = 240,
        background: Int = 28,
    ): RGBAFrame {
        val pixels =
            ByteArray(width * height * 4) { if (it % 4 == 3) 255.toByte() else background.toByte() }
        fun paint(x: Int, y: Int, v: Int) {
            for (channel in 0..2) pixels[(y * width + x) * 4 + channel] = v.toByte()
        }
        if (value != null) {
            val left = bounds.left.roundToInt()
            val right = bounds.right.roundToInt()
            val top = bounds.top.roundToInt()
            val bottom = bounds.bottom.roundToInt()
            for (y in top..bottom) {
                for (x in left..right) {
                    paint(x, y, value)
                }
            }
            if (detail) {
                for (y in top + 32 until bottom - 24 step 9) {
                    for (line in 0..1) {
                        for (x in left + 36 until right - 36) {
                            paint(x, y + line, 65)
                        }
                    }
                }
            }
        }
        return RGBAFrame(width, height, pixels)
    }

    fun page(value: Int, stripes: Boolean = false, width: Int = 320, height: Int = 240): RGBAFrame {
        val pixels =
            ByteArray(width * height * 4) { if (it % 4 == 3) 255.toByte() else value.toByte() }
        if (stripes) {
            for (y in 4 until height - 4 step 9) {
                for (line in 0..1) {
                    for (x in 0 until width) {
                        for (channel in 0..2) {

                            pixels[((y + line) * width + x) * 4 + channel] = 65
                        }
                    }
                }
            }
        }
        return RGBAFrame(width, height, pixels)
    }

    val view
        get() = PixelRect(0.0, 0.0, 720.0, 1280.0)

    fun face(
        yaw: Double = 0.0,
        size: Double = 0.55,
        cx: Double = 0.5,
        cy: Double = 0.5,
    ): FaceObservation {
        val width = size * (2 * TurnChallenge.ringFraction * 720)
        val x = cx * 720
        val y = cy * 1280
        val inter = width * 0.4
        return FaceObservation(
            PixelRect(x - width / 2, y - width / 2, width, width),
            FacePoint(x - inter / 2, y - width * 0.1),
            FacePoint(x + inter / 2, y - width * 0.1),
            FacePoint(x - yaw * inter, y + width * 0.05),
        )
    }

    fun feed(
        challenge: TurnChallenge,
        face: FaceObservation?,
        from: Double,
        to: Double,
    ): List<TurnUpdate> =
        (0..((to - from) / 0.05 + 1e-8).toInt()).map {
            challenge.update(face, view, from + it * 0.05)
        }

    fun firstCapture(updates: List<TurnUpdate>): String? =
        updates.firstNotNullOfOrNull { it.capture }

    fun leftPhase(): TurnChallenge =
        TurnChallenge().also {
            it.update(face(), view, 0.0)
            check(it.update(face(), view, 4.25).capture == "center")
            it.didCapture("center")
        }

    fun rightPhase(): TurnChallenge =
        leftPhase().also {
            it.update(face(-0.4), view, 5.0)
            check(it.update(face(-0.4), view, 5.75).capture == "left")
            it.didCapture("left")
            it.update(face(), view, 6.0)
        }
}
