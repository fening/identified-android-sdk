package cc.identified.sdk.integration

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import cc.identified.sdk.capture.CameraScreen
import cc.identified.sdk.capture.CaptureKind
import cc.identified.sdk.capture.CaptureOverrides
import cc.identified.sdk.capture.CameraGeometryDiagnostics
import cc.identified.sdk.Appearance
import cc.identified.sdk.ui.IdentifiedTheme
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the emulator's actual camera and bundled detector, without fixture overrides. */
@RunWith(AndroidJUnit4::class)
class NativeCameraSmokeTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test
    fun frontCameraFitsItsFullFrameInsideTheFullscreenMaskedPreview() = assertFullFrame(CaptureKind.SELFIE)

    @Test fun idCameraFitsItsFullFrameInsideTheFullscreenMaskedPreview() = assertFullFrame(CaptureKind.CARD)

    private fun assertFullFrame(kind: CaptureKind) {
        CaptureOverrides.frames = null
        CameraGeometryDiagnostics.reset()
        val shown = mutableStateOf(true)
        var unavailable = false
        lateinit var activity: Activity
        compose.setContent {
            activity = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().first()
            IdentifiedTheme(Appearance()) {
                if (shown.value) CameraScreen(kind, "Test camera", {},
                    { shown.value = false }, { unavailable = true })
            }
        }
        compose.waitUntil(30_000) { unavailable || CameraGeometryDiagnostics.latestFrame != null }
        assertFalse("$kind camera must bind", unavailable)
        compose.waitUntil(10_000) { CameraGeometryDiagnostics.captureResultCount >= 10 }
        assertEquals("Natural preview must not use crop-producing video stabilization",
            android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CameraGeometryDiagnostics.videoStabilization)
        val frame = requireNotNull(CameraGeometryDiagnostics.latestFrame)
        assertEquals("No viewport crop before fitting", 0, frame.left)
        assertEquals(0, frame.top)
        assertEquals(frame.width, frame.right)
        assertEquals(frame.height, frame.bottom)
        val stage = compose.onNodeWithTag("camera-stage").fetchSemanticsNode().boundsInWindow
        val region = compose.onNodeWithTag("camera-preview-region").fetchSemanticsNode().boundsInWindow
        assertEquals("The preview itself fills the stage", stage, region)
        compose.runOnIdle {
            fun find(view: android.view.View): PreviewView? {
                if (view is PreviewView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            val preview = requireNotNull(find(activity.window.decorView))
            assertEquals("Fit the full image without aspect-fill magnification",
                PreviewView.ScaleType.FIT_CENTER, preview.scaleType)
            assertEquals(stage.width, preview.width.toFloat(), 1f)
            assertEquals(stage.height, preview.height.toFloat(), 1f)
            shown.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("camera-stage").assertDoesNotExist()
    }

    @Test
    fun bundledFaceDetectorRunsOfflineOnABlankBitmap() {
        val detector =
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
            )
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val faces =
                Tasks.await(
                    detector.process(InputImage.fromBitmap(bitmap, 0)),
                    30,
                    TimeUnit.SECONDS,
                )
            assertTrue(faces.isEmpty())
        } finally {
            bitmap.recycle()
            detector.close()
        }
    }

    @Test
    fun cameraXBackCameraCapturesAnInMemoryJpegAndDisposes() {
        CaptureOverrides.frames = null
        var result: Map<String, ByteArray>? = null
        var unavailable = false
        val shown = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (shown.value)
                    CameraScreen(
                        CaptureKind.CARD,
                        "Test ID",
                        { result = it },
                        { shown.value = false },
                        { unavailable = true },
                    )
            }
        }
        compose.waitUntil(30_000) {
            unavailable ||
                runCatching {
                        compose
                            .onAllNodes(hasContentDescription("Capture Test ID") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    .getOrDefault(false)
        }
        assertFalse("Emulator must expose a back camera for this acceptance test", unavailable)
        compose.onNodeWithContentDescription("Capture Test ID").performClick()
        compose.waitUntil(30_000) { result != null }
        val bytes = result!!.getValue("image")
        assertTrue(bytes.size > 100)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(image)
        assertEquals("Only the saved shot is cropped to the ID guide",
            1.586,image.width.toDouble()/image.height,0.03)
        image.recycle()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Capture Test ID").assertDoesNotExist()
    }
}
