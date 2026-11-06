package cc.identified.sdk.capture

import androidx.compose.ui.graphics.Color
import cc.identified.sdk.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStageColorsTest {
    @Test fun defaultsMatchWebInBothSchemes() {
        for (defaults in listOf(Palette.Light, Palette.Dark)) {
            val colors = CameraStageColors(defaults, defaults)
            assertEquals(Color(0xff40a99a), colors.success)
            assertEquals(Color(0xff237f74), colors.positive)
            assertEquals(Color(0xffe8f6f0), colors.doneGuide)
            assertEquals(Color(0xfff59e0b), colors.warning)
        }
    }

    @Test fun semanticOverridesWinBeforeAccentFallback() {
        for (defaults in listOf(Palette.Light, Palette.Dark)) {
            val host = defaults.copy(accent = 0xff4455cc, success = 0xff117733, warning = 0xffcc8822)
            val colors = CameraStageColors(host, defaults)
            assertEquals(Color(host.success), colors.success)
            assertEquals(Color(host.success), colors.positive)
            assertEquals(Color(host.success), colors.doneGuide)
            assertEquals(Color(host.warning), colors.warning)
            val accent = CameraStageColors(host.copy(success = defaults.success), defaults)
            assertEquals(Color(host.accent), accent.success)
            assertEquals(Color(host.accent), accent.positive)
            assertEquals(Color(host.accent), accent.doneGuide)
            assertEquals(Color(host.warning), accent.warning)
            val warningOnly = CameraStageColors(defaults.copy(warning = host.warning), defaults)
            assertEquals(Color(host.warning), warningOnly.warning)
        }
    }

    @Test fun comparesOverridesToTheActiveSchemeDefaults() {
        val host = Palette.Dark.copy(success = Palette.Light.success, warning = Palette.Light.warning)
        val colors = CameraStageColors(host, Palette.Dark)
        assertEquals(Color(host.success), colors.success)
        assertEquals(Color(host.warning), colors.warning)
    }
}
