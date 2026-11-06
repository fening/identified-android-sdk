package cc.identified.sdk.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.identified.sdk.capture.core.FaceObservation
import cc.identified.sdk.capture.core.FacePoint
import cc.identified.sdk.capture.core.PixelRect
import cc.identified.sdk.capture.core.PixelSize
import cc.identified.sdk.capture.core.TurnPhase
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPixelsTest {
    @Test
    fun rgbaPaddingRotationAndBgraRemainUnmirrored() {
        // Two rows, one padding pixel at the end of each row, nonzero buffer position.
        val bytes =
            byteArrayOf(
                99,
                255.toByte(),
                0,
                0,
                255.toByte(),
                0,
                255.toByte(),
                0,
                255.toByte(),
                8,
                8,
                8,
                8,
                0,
                0,
                255.toByte(),
                255.toByte(),
                255.toByte(),
                255.toByte(),
                255.toByte(),
                255.toByte(),
                8,
                8,
                8,
                8,
            )
        val input = ByteBuffer.wrap(bytes).apply { position(1) }
        val upright = CameraPixels.rgba(input, 2, 2, 12, 4, 90)
        assertEquals(Color.BLUE, upright.getPixel(0, 0))
        assertEquals(Color.RED, upright.getPixel(1, 0))
        assertEquals(Color.WHITE, upright.getPixel(0, 1))
        assertEquals(Color.GREEN, upright.getPixel(1, 1))
        assertEquals(1, input.position())
        upright.recycle()
        val bgra =
            CameraPixels.rgba(
                ByteBuffer.wrap(byteArrayOf(0, 0, 255.toByte(), 255.toByte())),
                1,
                1,
                4,
                4,
                0,
                true,
            )
        assertEquals(Color.RED, bgra.getPixel(0, 0))
        bgra.recycle()
    }

    @Test
    fun postCaptureCardGuideAndFullSelfieEncodeInMemory() {
        val bitmap = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        // Horizontal asymmetry catches accidental front-camera mirroring.
        for (y in 0 until 1000) for (x in 0 until 300) bitmap.setPixel(x, y, Color.RED)
        val view = PixelSize(300.0, 500.0)
        val card = CameraPixels.still(bitmap, view, CaptureKind.CARD)
        val cardImage = BitmapFactory.decodeByteArray(card, 0, card.size)
        assertEquals(492, cardImage.width)
        assertEquals(1.586, cardImage.width.toDouble()/cardImage.height,.015)
        val selfie = CameraPixels.still(bitmap, view, CaptureKind.SELFIE)
        val face = BitmapFactory.decodeByteArray(selfie, 0, selfie.size)
        assertEquals(600, face.width)
        assertEquals(1000, face.height)
        assertTrue(Color.green(face.getPixel(10, face.height / 2)) < 30)
        assertTrue(Color.green(face.getPixel(face.width - 10, face.height / 2)) > 220)
        val rect = requireNotNull(cc.identified.sdk.capture.core.CaptureGeometry.selfieCaptureRect(PixelSize(600.0, 1000.0), view))
        assertEquals(400.0, rect.midY, .001)
        bitmap.recycle()
        cardImage.recycle()
        face.recycle()
    }

    @Test
    fun noFaceFallbackRequiresExplicitStartAndProducesThreeRealCrops() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val processor = CaptureProcessor(CaptureKind.SELFIE)
        val view = PixelSize(300.0, 500.0)
        assertFalse(processor.process(bitmap, view, 0.0).timedWaiting)
        assertFalse(processor.process(bitmap, view, 4.9).timedWaiting)
        assertTrue(processor.process(bitmap, view, 5.0).timedWaiting)
        assertNull(processor.process(bitmap, view, 20.0).completed)
        processor.startTimed(20.0)
        assertNull(processor.process(bitmap, view, 22.4).completed)
        assertNull(processor.process(bitmap, view, 23.3).completed)
        val output = processor.process(bitmap, view, 26.0).completed!!
        assertEquals(setOf("left", "center", "right"), output.keys)
        output.values.forEach { jpeg ->
            val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertNotNull(image)
            assertEquals(300, image.width)
            assertEquals(500, image.height)
            image.recycle()
        }
        bitmap.recycle()
    }

    private fun observation(box: PixelRect) =
        FaceObservation(
            box,
            FacePoint(120.0, 190.0),
            FacePoint(180.0, 190.0),
            FacePoint(150.0, 210.0),
        )

    @Test
    fun acceptedManualTapSurvivesNewlyGoodFrame() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        try {
            val processor = CaptureProcessor(CaptureKind.SELFIE)
            val view = PixelSize(300.0, 500.0)
            val far = observation(PixelRect(140.0, 200.0, 20.0, 20.0))
            val good = observation(PixelRect(90.0, 150.0, 120.0, 120.0))
            processor.processObserved(bitmap, view, 0.0, far)
            assertTrue(processor.processObserved(bitmap, view, 8.0, far).manual)
            processor.manual()
            // This frame would clear stallSince before pressShutter in the old ordering.
            assertEquals(TurnPhase.left, processor.processObserved(bitmap, view, 8.1, good).phase)
            processor.manual() // Front-only shutter must not capture a side pose.
            assertEquals(TurnPhase.left, processor.processObserved(bitmap, view, 8.2, good).phase)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun prematureManualTapIsNotQueuedUntilAvailable() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        try {
            val processor = CaptureProcessor(CaptureKind.SELFIE)
            val view = PixelSize(300.0, 500.0)
            val far = observation(PixelRect(140.0, 200.0, 20.0, 20.0))
            processor.processObserved(bitmap, view, 0.0, far)
            processor.manual()
            assertEquals(
                TurnPhase.position,
                processor.processObserved(bitmap, view, 1.0, far).phase,
            )
            val later = processor.processObserved(bitmap, view, 8.1, far)
            assertTrue(later.manual)
            assertEquals(TurnPhase.position, later.phase)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun lowerFaceOutsideUploadSquareCannotAutoCapture() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        try {
            val processor = CaptureProcessor(CaptureKind.SELFIE)
            val view = PixelSize(300.0, 500.0)
            // Centre y=.72 passes the old broad framing gate, but its chin is
            // below the square ending near y=375 around the actual .50 guide.
            val lower = observation(PixelRect(90.0, 300.0, 120.0, 120.0))
            processor.processObserved(bitmap, view, 0.0, lower)
            processor.processObserved(bitmap, view, 1.2, lower)
            val afterCountdown = processor.processObserved(bitmap, view, 4.3, lower)
            assertEquals(TurnPhase.position, afterCountdown.phase)
            assertNull(afterCountdown.countdown)
            assertNull(afterCountdown.completed)
        } finally {
            bitmap.recycle()
        }
    }
    @Test fun tallFullStageUsesFitRingForTheChallenge() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        try {
            val processor = CaptureProcessor(CaptureKind.SELFIE)
            val view = PixelSize(300.0, 1000.0)
            val good = observation(PixelRect(90.0, 90.0, 120.0, 120.0))
            // Aspect fill would halve the source width and reject this as too near.
            assertEquals(cc.identified.sdk.capture.core.TurnStatus.hold,
                processor.processObserved(bitmap, view, 0.0, good).status)
            processor.processObserved(bitmap, view, 1.2, good)
            assertEquals(TurnPhase.left, processor.processObserved(bitmap, view, 4.3, good).phase)
        } finally { bitmap.recycle() }
    }

    @Test fun fitStageLightSamplesBrightSourceBordersOutsideTheFillRegion() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 20, 20))
        try {
            for (y in 0 until 500) for (x in 0 until 300) {
                if (x < 75 || x >= 225) bitmap.setPixel(x, y, Color.WHITE)
            }
            val processor = CaptureProcessor(CaptureKind.SELFIE)
            val view = PixelSize(300.0, 1000.0)
            assertFalse(processor.process(bitmap, view, 0.0).dark)
            assertFalse(processor.process(bitmap, view, 0.5).dark)
        } finally { bitmap.recycle() }
    }

    @Test fun mismatchedStageSelfieKeepsTheEntireSourceAndBorderPixels() {
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        try {
            // Even an opposite-aspect stage must not change the saved selfie field of view.
            // Red/blue outer bands catch aspect-fill cropping and accidental mirroring.
            for (y in 0 until 500) for (x in 0 until 300) {
                if (x < 40) bitmap.setPixel(x, y, Color.RED)
                if (x >= 260) bitmap.setPixel(x, y, Color.BLUE)
            }
            val view = PixelSize(500.0, 300.0)
            val jpeg = CameraPixels.still(bitmap, view, CaptureKind.SELFIE)
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            try {
                assertEquals(300, decoded.width)
                assertEquals(500, decoded.height)
                assertTrue(Color.red(decoded.getPixel(10, 150)) > 220)
                assertTrue(Color.green(decoded.getPixel(150, 150)) > 220)
                assertTrue(Color.blue(decoded.getPixel(290, 150)) > 220)
            } finally { decoded.recycle() }
            val processor = CaptureProcessor(CaptureKind.SELFIE)
            processor.process(bitmap, view, 0.0)
            processor.process(bitmap, view, 5.0)
            processor.startTimed(6.0)
            val captures = requireNotNull(processor.process(bitmap, view, 12.0).completed)
            assertEquals(setOf("left", "center", "right"), captures.keys)
            for (bytes in captures.values) {
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    assertEquals(300, image.width)
                    assertEquals(500, image.height)
                } finally { image.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun cardOutputDoesNotInheritTheTallScreenCrop() {
        val bitmap = Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        try {
            val bytes = CameraPixels.still(bitmap,PixelSize(300.0,650.0),CaptureKind.CARD)
            val image = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try {
                assertEquals(492,image.width)
                assertEquals(1.586,image.width.toDouble()/image.height,.015)
            } finally { image.recycle() }
        } finally { bitmap.recycle() }
    }

}
