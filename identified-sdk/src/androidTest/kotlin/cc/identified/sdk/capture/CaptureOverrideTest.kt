package cc.identified.sdk.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureOverrideTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun clearHook() {
        CaptureOverrides.frames = null
    }

    @Test
    fun explicitHookRequiresTapAndUsesRequestedKind() {
        var requested: CaptureKind? = null
        val fixture = mapOf("image" to byteArrayOf(1, 2, 3))
        var result: Map<String, ByteArray>? = null
        CaptureOverrides.frames = { kind ->
            requested = kind
            fixture
        }
        compose.setContent {
            MaterialTheme {
                CameraScreen(
                    CaptureKind.CARD,
                    "ID",
                    { result = it },
                    {},
                    { error("must not open a real camera") },
                )
            }
        }
        compose.runOnIdle {
            assertNull(requested)
            assertNull(result)
        }
        compose.onNodeWithText("Capture test frame").performClick()
        compose.runOnIdle {
            assertEquals(CaptureKind.CARD, requested)
            assertSame(fixture, result)
        }
    }
}
