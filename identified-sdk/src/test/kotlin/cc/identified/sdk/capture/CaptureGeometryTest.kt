package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class CaptureGeometryTest {
    private fun assertRect(expected: PixelRect, actual: PixelRect?) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.width, actual.width, 1e-9)
        assertEquals(expected.height, actual.height, 1e-9)
    }

    @Test
    fun aspectFillShowsTheMiddleOfAWiderFrame() {
        val width = 390.0 / (844.0 / 1920.0)
        assertRect(
            PixelRect((1080 - width) / 2, 0.0, width, 1920.0),
            CaptureGeometry.visibleRect(PixelSize(1080.0, 1920.0), PixelSize(390.0, 844.0)),
        )
    }

    @Test
    fun aspectFillShowsTheMiddleOfATallerFrame() {
        assertRect(
            PixelRect(0.0, 750.0, 1000.0, 500.0),
            CaptureGeometry.visibleRect(PixelSize(1000.0, 2000.0), PixelSize(800.0, 400.0)),
        )
    }

    @Test
    fun aMatchingViewShowsTheWholeFrame() {
        assertRect(
            PixelRect(0.0, 0.0, 1080.0, 1920.0),
            CaptureGeometry.visibleRect(PixelSize(1080.0, 1920.0), PixelSize(540.0, 960.0)),
        )
    }

    @Test
    fun anEmptyViewHasNoVisibleRect() {
        assertNull(CaptureGeometry.visibleRect(PixelSize(1080.0, 1920.0), PixelSize(0.0, 0.0)))
    }

    @Test
    fun nonpositiveAndNaNDimensionsHaveNoVisibleRect() {
        for (bad in listOf(0.0, -1.0, Double.NaN)) {
            for (index in 0..3) {

                val dimensions = mutableListOf(1080.0, 1920.0, 390.0, 844.0)
                dimensions[index] = bad
                assertNull(
                    "dimension $index = $bad",
                    CaptureGeometry.visibleRect(
                        PixelSize(dimensions[0], dimensions[1]),
                        PixelSize(dimensions[2], dimensions[3]),
                    ),
                )
            }
        }
    }

    @Test
    fun cropsTheCardGuideWithBleed() {
        val width = 1000 * 0.82 * 1.08
        val height = width / 1.586
        assertRect(
            PixelRect((1000 - width) / 2, (800 - height) / 2, width, height),
            CaptureGeometry.guideCrop(PixelRect(0.0, 0.0, 1000.0, 800.0)),
        )
    }

    @Test
    fun cropParametersPreserveVisibleOriginAndClampToVisibleRegion() {
        val visible = PixelRect(100.0, 200.0, 1000.0, 300.0)
        assertRect(
            visible,
            CaptureGeometry.guideCrop(visible, widthFraction = 1.0, aspectRatio = 1.0, margin = 2.0),
        )
        assertRect(
            PixelRect(350.0, 225.0, 500.0, 250.0),
            CaptureGeometry.guideCrop(visible, widthFraction = 0.5, aspectRatio = 2.0, margin = 1.0),
        )
        assertRect(
            PixelRect(450.0, 200.0, 300.0, 300.0),
            CaptureGeometry.circleCrop(visible, margin = 10.0),
        )
    }

    @Test
    fun cropsASquareAroundTheFaceCircle() {
        val side = 2 * (104.0 / 300.0) * 720 * 1.2
        assertRect(
            PixelRect((720 - side) / 2, (1280 - side) / 2, side, side),
            CaptureGeometry.circleCrop(PixelRect(0.0, 0.0, 720.0, 1280.0)),
        )
    }

    @Test
    fun sizesTheAnalysisFrameToAboutNinetyThousandPixels() {
        assertEquals(AnalysisSize(204, 441), CaptureGeometry.analysisSize(390.0 / 844.0))
        assertEquals(120, CaptureGeometry.analysisSize(0.01).width)
        assertEquals(120, CaptureGeometry.analysisSize(100.0).height)
        assertEquals(AnalysisSize(300, 300), CaptureGeometry.analysisSize(1.0))
        assertEquals(AnalysisSize(400, 200), CaptureGeometry.analysisSize(2.0, pixels = 80_000.0))
    }

    @Test
    fun roundingMatchesSwiftTiesAwayFromZero() {
        for ((input, expected) in listOf(0.5 to 1, 2.5 to 3, -0.5 to -1, -2.5 to -3)) assertEquals(
            expected,
            roundedInt(input),
        )
    }

    @Test
    fun flipsARectIntoCoreImageCoordinates() {
        assertRect(
            PixelRect(10.0, 40.0, 30.0, 40.0),
            CaptureGeometry.flippedForCoreImage(PixelRect(10.0, 20.0, 30.0, 40.0), 100.0),
        )
    }

    @Test
    fun aspectFillAndCropPropertiesAcrossPortraitAndLandscapeFrames() {
        for (w in listOf(120.0, 720.0, 1080.0)) {
            for (h in listOf(240.0, 1280.0, 1920.0)) {
                for (view in listOf(PixelSize(390.0, 844.0), PixelSize(844.0, 390.0))) {

                    val visible = CaptureGeometry.visibleRect(PixelSize(w, h), view)!!
                    assertEquals(view.width / view.height, visible.width / visible.height, 1e-12)
                    assertEquals(w / 2, visible.midX, 1e-10)
                    assertEquals(h / 2, visible.midY, 1e-10)
                    assertTrue(visible.width <= w && visible.height <= h)
                    for (crop in
                        listOf(
                            CaptureGeometry.circleCrop(visible),
                            CaptureGeometry.guideCrop(visible),
                        )) {
                        assertEquals(visible.midX, crop.midX, 1e-10)
                        assertEquals(visible.midY, crop.midY, 1e-10)
                        assertTrue(crop.width <= visible.width && crop.height <= visible.height)
                    }
                    assertRect(
                        visible,
                        CaptureGeometry.flippedForCoreImage(
                            CaptureGeometry.flippedForCoreImage(visible, h),
                            h,
                        ),
                    )
                }
            }
        }
    }
    @Test fun fitStagePreservesFullFieldOfViewAndMapsTheStageCircle() {
        val sources = listOf(PixelSize(720.0, 1280.0), PixelSize(1280.0, 720.0), PixelSize(960.0, 1280.0), PixelSize(1000.0, 200.0))
        val stages = listOf(PixelSize(1080.0, 2400.0), PixelSize(2400.0, 1080.0), PixelSize(500.0, 500.0), PixelSize(100.0, 110.0))
        for (source in sources) for (stage in stages) {
            val virtual = requireNotNull(CaptureGeometry.aspectFitStageRect(source, stage))
            val scale = minOf(stage.width / source.width, stage.height / source.height)
            assertEquals(stage.width, virtual.width * scale, 1e-9)
            assertEquals(stage.height, virtual.height * scale, 1e-9)
            assertEquals(source.width / 2, virtual.midX, 1e-9)
            assertEquals(source.height / 2, virtual.midY, 1e-9)
            // All four source edges lie within the stage: no camera FoV is lost.
            assertTrue(virtual.x <= 1e-9 && virtual.y <= 1e-9)
            assertTrue(virtual.x + virtual.width >= source.width - 1e-9)
            assertTrue(virtual.y + virtual.height >= source.height - 1e-9)
            val crop = requireNotNull(CaptureGeometry.selfieCaptureRect(source, stage))
            val ringDiameter = 2 * CaptureGeometry.ringFraction * minOf(stage.width, stage.height)
            assertEquals(ringDiameter, 2 * CaptureGeometry.ringFraction * minOf(virtual.width, virtual.height) * scale, 1e-9)
            assertEquals(minOf(ringDiameter * 1.2 / scale, source.width, source.height), crop.width, 1e-9)
            assertEquals(crop.width, crop.height, 0.0)
            assertEquals(stage.width / 2, (crop.midX - virtual.x) * scale, 1e-9)
            val desiredY = source.height / 2
            assertEquals(desiredY.coerceIn(crop.height / 2, source.height - crop.height / 2), crop.midY, 1e-9)
            assertTrue(crop.x >= 0 && crop.y >= 0)
            assertTrue(crop.x + crop.width <= source.width && crop.y + crop.height <= source.height)
        }
    }

    @Test fun portraitAndLandscapeLetterboxesHaveNegativeVirtualOrigins() {
        assertRect(PixelRect(0.0, -160.0, 720.0, 1600.0),
            CaptureGeometry.aspectFitStageRect(PixelSize(720.0, 1280.0), PixelSize(1080.0, 2400.0)))
        assertRect(PixelRect(-160.0, 0.0, 1600.0, 720.0),
            CaptureGeometry.aspectFitStageRect(PixelSize(1280.0, 720.0), PixelSize(2400.0, 1080.0)))
        assertRect(PixelRect(0.0, 400.0, 200.0, 200.0),
            CaptureGeometry.selfieCaptureRect(PixelSize(200.0, 1000.0), PixelSize(1000.0, 200.0)))
    }

    @Test fun invalidFitInputsHaveNoVirtualOrCaptureRect() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (index in 0..3) {
                val d = mutableListOf(720.0, 1280.0, 1080.0, 2400.0)
                d[index] = bad
                val source = PixelSize(d[0], d[1])
                val stage = PixelSize(d[2], d[3])
                assertNull(CaptureGeometry.aspectFitStageRect(source, stage))
                assertNull(CaptureGeometry.selfieCaptureRect(source, stage))
            }
        }
    }
    @Test fun portraitGuideCentersOnTheFullPreviewAboveControls() {
        val source = PixelSize(960.0, 1280.0)
        val stage = PixelSize(1080.0, 2209.0)
        val virtual = requireNotNull(CaptureGeometry.aspectFitStageRect(source, stage))
        val framing = requireNotNull(CaptureGeometry.selfieFramingRect(source, stage))
        val crop = requireNotNull(CaptureGeometry.selfieCaptureRect(source, stage))
        val scale = 1080.0 / 960.0
        val aspect = source.width / source.height
        val lift = CaptureGeometry.selfiePreviewTopInset(stage, aspect)
        assertEquals(0.0, -virtual.y * scale - lift, 1e-8)
        assertEquals(720.0, CaptureGeometry.selfieCenterY(stage, aspect), 1e-8)
        assertEquals(source.height / 2, framing.midY, 1e-8)
        assertEquals(framing.midY.coerceIn(crop.height / 2, source.height - crop.height / 2), crop.midY, 1e-8)
        assertEquals(source.height / 2, crop.midY, 1e-8)
        assertTrue(crop.y >= 0 && crop.y + crop.height <= source.height)
        assertEquals(540.0, CaptureGeometry.selfieCenterY(PixelSize(2209.0, 1080.0), 4.0 / 3), 0.0)
    }

}
