package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class SceneLightTest {
    @Test
    fun oneDimSampleIsNotADarkScene() {
        val light = SceneLight()
        light.observe(30.0)
        assertFalse(light.dark)
    }

    @Test
    fun twoDimSamplesInARowAreADarkScene() {
        val light = SceneLight()
        light.observe(30.0)
        light.observe(30.0)
        assertTrue(light.dark)
    }

    @Test
    fun aBrightSampleResetsTheStreak() {
        val light = SceneLight()
        light.observe(30.0)
        light.observe(90.0)
        light.observe(30.0)
        assertFalse(light.dark)
    }

    @Test
    fun ignoresACameraThatHasNoPictureYet() {
        val light = SceneLight()
        light.observe(1.0)
        light.observe(1.0)
        assertFalse(light.dark)
    }

    @Test
    fun noPictureSamplesPreserveAnExistingDimStreak() {
        val light = SceneLight()
        light.observe(30.0)
        light.observe(0.0)
        light.observe(2.999)
        light.observe(Double.NaN)
        light.observe(30.0)
        assertTrue(light.dark)
    }

    @Test
    fun staysDarkOnceTheFillLightIsOn() {
        val light = SceneLight()
        light.observe(30.0)
        light.observe(30.0)
        light.observe(200.0)
        assertTrue(light.dark)
    }

    @Test
    fun exactDarkAndNoPictureThresholds() {
        val light = SceneLight()
        light.observe(3.0)
        light.observe(45.0)
        light.observe(44.999)
        assertFalse(light.dark)
        light.observe(3.0)
        assertTrue(light.dark)
    }

    @Test
    fun measuresMeanLuminanceIncludingEmptyFrame() {
        assertEquals(100.0, SceneLight.meanLuminance(CaptureFixtures.page(100)), 0.01)
        assertEquals(0.0, SceneLight.meanLuminance(RGBAFrame(0, 0, byteArrayOf())), 0.0)
    }
}
