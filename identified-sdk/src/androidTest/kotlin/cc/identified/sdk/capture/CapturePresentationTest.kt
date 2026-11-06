package cc.identified.sdk.capture

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.identified.sdk.R
import cc.identified.sdk.Appearance
import cc.identified.sdk.ColorSchemePreference
import cc.identified.sdk.ui.IdentifiedTheme
import cc.identified.sdk.capture.core.CaptureGeometry
import cc.identified.sdk.capture.core.PixelSize
import cc.identified.sdk.capture.core.TurnPhase
import cc.identified.sdk.capture.core.TurnStatus
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use AGP's collected instrumentation output directory when provided. Local
 * manual runs keep the existing app-external capture-presentation fallback. */
private fun cameraSnapshotDirectory(): File {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        ?: InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            File(context.getExternalFilesDir(null) ?: context.filesDir, "capture-presentation")
        }
    assertTrue("Cannot create camera screenshot directory $output", output.isDirectory || output.mkdirs())
    android.util.Log.i("IdentifiedCameraSnapshots", "Camera snapshots: ${output.absolutePath}")
    return output
}

private fun cameraTextLayoutDiagnostic(layout: TextLayoutResult): String = buildString {
    append("text=${layout.layoutInput.text.text} size=${layout.size}")
    append(" constraints=${layout.layoutInput.constraints}")
    append(" density=${layout.layoutInput.density.density} fontScale=${layout.layoutInput.density.fontScale}")
    append(" fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight}")
    append(" didOverflowWidth=${layout.didOverflowWidth} didOverflowHeight=${layout.didOverflowHeight}")
    append(" hasVisualOverflow=${layout.hasVisualOverflow} lineCount=${layout.lineCount}")
    for(line in 0 until layout.lineCount) {
        append(" line[$line]={top=${layout.getLineTop(line)}, bottom=${layout.getLineBottom(line)}, baseline=${layout.getLineBaseline(line)}}")
    }
    append(" style=${layout.layoutInput.style}")
}

@RunWith(AndroidJUnit4::class)
class CapturePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var priorActionBarVisible = false
    private var priorStatusBarVisible = true
    private var priorNavigationBarVisible = true

    @Before fun makeSnapshotActivityFullscreen() {
        compose.activityRule.scenario.onActivity { activity ->
            priorActionBarVisible = activity.actionBar?.isShowing == true
            val window = activity.window
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            priorStatusBarVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
            priorNavigationBarVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
            activity.actionBar?.hide()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            ViewCompat.requestApplyInsets(window.decorView)
        }
    }

    private fun awaitSnapshotWindow() {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
            ViewCompat.requestApplyInsets(activity.window.decorView)
        }
        // Wait after content attaches; waiting before setContent can leave a
        // new activity without the first layout needed to dispatch its insets.

        compose.waitUntil(timeoutMillis=5_000) {
            var hidden=false
            compose.activityRule.scenario.onActivity { activity ->
                val insets=ViewCompat.getRootWindowInsets(activity.window.decorView)
                hidden=insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
            hidden
        }
    }

    @After fun restoreSnapshotActivityChrome() {
        compose.activityRule.scenario.onActivity { activity ->
            val window=activity.window
            val controller=WindowInsetsControllerCompat(window,window.decorView)
            if(priorStatusBarVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            if(priorNavigationBarVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            WindowCompat.setDecorFitsSystemWindows(window,true)
            if(priorActionBarVisible) activity.actionBar?.show()
        }
    }

    @Test fun stageScreenshotsUseLiveOverlayWithoutCameraOrOverride() {
        val scene = mutableStateOf(CaptureFeedback())
        val kind = mutableStateOf(CaptureKind.SELFIE)
        val problem = mutableStateOf(false)
        val scheme = mutableStateOf(ColorSchemePreference.LIGHT)
        compose.setContent {
            IdentifiedTheme(Appearance(colorScheme=scheme.value)) {
                Box(Modifier.fillMaxSize().testTag("presentation-host")) {
                CameraStage(kind.value, scene.value) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
                if(problem.value) CameraProblemPresentation(true, {}, {}, Modifier.align(Alignment.Center))
                }
            }
        }
        awaitSnapshotWindow()
        val scenes = linkedMapOf(
            "position" to CaptureFeedback(hint=R.string.identified_selfie_hint_center),
            "far" to CaptureFeedback(hint=R.string.identified_selfie_hint_closer,status=TurnStatus.far,distanceLevel=1),
            "near" to CaptureFeedback(hint=R.string.identified_selfie_hint_back,status=TurnStatus.near,distanceLevel=3),
            "left" to CaptureFeedback(hint=R.string.identified_selfie_hint_keep_left,phase=TurnPhase.left,status=TurnStatus.ok,yaw=-.38*.55),
            "left-reached" to CaptureFeedback(hint=R.string.identified_selfie_hint_perfect,phase=TurnPhase.left,status=TurnStatus.ok,yaw=-.38),
            "right" to CaptureFeedback(hint=R.string.identified_selfie_hint_keep_right,phase=TurnPhase.right,status=TurnStatus.ok,yaw=.38*.55),
            "countdown" to CaptureFeedback(hint=R.string.identified_selfie_hint_hold_photo,status=TurnStatus.hold,countdown=2,sweep=.5f),
            "done" to CaptureFeedback(hint=R.string.identified_selfie_hint_done,phase=TurnPhase.done,status=TurnStatus.ok),
            "light" to CaptureFeedback(hint=R.string.identified_selfie_hint_hold_photo,status=TurnStatus.hold,dark=true),
            "timed-left" to CaptureFeedback(hint=R.string.identified_selfie_timed_left,timed=true,sweep=.2f),
            "timed-center" to CaptureFeedback(hint=R.string.identified_selfie_timed_center,timed=true,sweep=.45f),
            "timed-right" to CaptureFeedback(hint=R.string.identified_selfie_timed_right,timed=true,sweep=.75f),
        )
        val directory = cameraSnapshotDirectory()
        for ((schemeName, preference) in listOf("light" to ColorSchemePreference.LIGHT, "dark" to ColorSchemePreference.DARK)) {
            compose.runOnIdle { scheme.value=preference; kind.value=CaptureKind.SELFIE; problem.value=false }
            for ((name, feedback) in scenes) {
                compose.runOnIdle { scene.value = feedback }
                compose.waitForIdle()
                val image = compose.onNodeWithTag("camera-stage").captureToImage().asAndroidBitmap()
                assertTrue(image.width > 0 && image.height > 0)
                if(name == "position") {
                    val stage=compose.onNodeWithTag("camera-stage").fetchSemanticsNode().boundsInWindow
                    val picture=compose.onNodeWithTag("camera-preview-region").fetchSemanticsNode().boundsInWindow
                    val expected=PixelSize(stage.width.toDouble(),stage.height.toDouble())
                    assertEquals("Selfie picture width",expected.width.toFloat(),picture.width,1f)
                    assertEquals("Selfie picture height",expected.height.toFloat(),picture.height,1f)
                    assertEquals("Selfie picture remains horizontally centred",stage.center.x,picture.center.x,1f)
                    assertEquals("Selfie picture remains vertically centred",stage.center.y,picture.center.y,1f)
                    val overlay=compose.onNodeWithTag("camera-ring").fetchSemanticsNode().boundsInWindow
                    assertEquals("Overlay remains full-stage",stage,overlay)
                    val chips=compose.onNodeWithText(compose.activity.getString(R.string.identified_selfie_pose_front))
                    chips.assertIsDisplayed()
                    val front=chips.fetchSemanticsNode()
                    val chipBounds=front.boundsInWindow
                    assertTrue("Front chip fully inside stage",chipBounds.top>=stage.top && chipBounds.bottom<=stage.bottom &&
                        chipBounds.left>=stage.left && chipBounds.right<=stage.right)
                    assertTrue("Front chip text not clipped",chipBounds.height>=front.layoutInfo.height-1f)
                    compose.runOnIdle {
                        assertFalse("Default activity action bar must be hidden",compose.activity.actionBar?.isShowing == true)
                        assertEquals("No activity chrome above camera",0f,stage.top,1f)
                        assertEquals("Stage fills the fullscreen activity",compose.activity.window.decorView.height.toFloat(),stage.bottom,1f)
                    }
                }
                if(name == "countdown") {
                    val digit = compose.onNodeWithTag("camera-countdown").fetchSemanticsNode().boundsInRoot
                    val stage = compose.onNodeWithTag("camera-stage").fetchSemanticsNode().boundsInRoot
                    val radius = minOf(stage.width,stage.height) * (104f/300)
                    if(stage.width > stage.height) assertTrue("landscape countdown must use side rail",digit.left > stage.center.x+radius)
                    else assertTrue("portrait countdown must stay below face",digit.top >= stage.top+CaptureGeometry.selfieCenterY(PixelSize(stage.width.toDouble(),stage.height.toDouble()), 0.75).toFloat()+radius)
                }
                File(directory,"$schemeName-camera-$name-${image.width}x${image.height}.png").outputStream().use { assertTrue("Camera PNG encoding failed", image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            }
            compose.runOnIdle { scene.value=CaptureFeedback(); problem.value=true }
            compose.waitForIdle()
            val error=compose.onNodeWithTag("presentation-host").captureToImage().asAndroidBitmap()
            File(directory,"$schemeName-camera-error-${error.width}x${error.height}.png").outputStream().use { assertTrue("Camera error PNG encoding failed", error.compress(Bitmap.CompressFormat.PNG,100,it)) }
            compose.runOnIdle { problem.value=false }
            for (mode in listOf(CaptureKind.CARD,CaptureKind.DOCUMENT)) {
                compose.runOnIdle { kind.value=mode; scene.value=CaptureFeedback(hint=R.string.identified_card_hint_fit_edges) }
                compose.waitForIdle()
                val image=compose.onNodeWithTag("camera-stage").captureToImage().asAndroidBitmap()
                File(directory,"$schemeName-camera-${mode.name.lowercase()}-${image.width}x${image.height}.png").outputStream().use { assertTrue("Camera PNG encoding failed", image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            }
        }
    }

    @Test fun hintsAndCountdownAnnouncePolitely() {
        val feedback = mutableStateOf(CaptureFeedback(hint=R.string.identified_selfie_hint_center))
        compose.setContent {
            IdentifiedTheme(Appearance()) {
                CameraStage(CaptureKind.SELFIE, feedback.value) { Box(Modifier.fillMaxSize().background(Color.Black)) }
            }
        }
        awaitSnapshotWindow()
        compose.onNodeWithTag("camera-hint")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { feedback.value=feedback.value.copy(countdown=2) }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("camera-countdown")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertContentDescriptionEquals(context.getString(R.string.identified_selfie_countdown,2))
    }

    @Test fun landscapeCountdownFontScale15StaysInRailWithLowLight() {
        compose.setContent {
            // A logical landscape stage on any device, kept inside its real
            // pixel bounds. This does not rotate or open a hardware camera.
            val density=LocalDensity.current
            val configuration=LocalConfiguration.current
            val pixelScale=minOf(1f, configuration.screenWidthDp*density.density/640f,
                configuration.screenHeightDp*density.density/360f)
            CompositionLocalProvider(LocalDensity provides Density(pixelScale,fontScale=1.5f)) {
                IdentifiedTheme(Appearance()) {
                    Box(Modifier.requiredSize(640.dp,360.dp)) {
                        CameraStage(CaptureKind.SELFIE, CaptureFeedback(status=TurnStatus.hold,countdown=2,sweep=.5f,dark=true)) {
                            Box(Modifier.fillMaxSize().background(Color.Black))
                        }
                    }
                }
            }
        }
        awaitSnapshotWindow()
        val stage=compose.onNodeWithTag("camera-stage").fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNodeWithTag("camera-hint-viewport").fetchSemanticsNode().boundsInRoot
        val countdownNode=compose.onNodeWithTag("camera-countdown").fetchSemanticsNode()
        val digit=countdownNode.boundsInRoot
        assertTrue("full text layout is visible", digit.height >= countdownNode.layoutInfo.height-1f)
        val radius=stage.height*104f/300
        assertTrue("viewport inside stage", viewport.left>=stage.left && viewport.right<=stage.right && viewport.top>=stage.top && viewport.bottom<=stage.bottom)
        assertTrue("countdown outside face", digit.left>stage.center.x+radius)
        assertTrue("digit fits without clipping", digit.top>=viewport.top && digit.bottom<=viewport.bottom && digit.left>=viewport.left && digit.right<=viewport.right)
        assertTrue("rail leaves footer room",viewport.bottom<=stage.top+stage.height*(248f/360)+1f)
        val directory = cameraSnapshotDirectory()
        val image=compose.onNodeWithTag("camera-stage").captureToImage().asAndroidBitmap()
        File(directory,"camera-countdown-light-fontscale1_5-landscape.png").outputStream().use {
            assertTrue("Camera PNG encoding failed", image.compress(Bitmap.CompressFormat.PNG,100,it))
        }
        // Low-light guidance can scroll in its rail, independently of the face
        // preview and footer. Its final rendered bounds must remain in the rail.
        compose.onNodeWithTag("camera-low-light").performScrollTo()
        val light=compose.onNodeWithTag("camera-low-light").fetchSemanticsNode().boundsInRoot
        assertTrue("low light remains reachable",light.height>0 && light.top>=viewport.top && light.bottom<=viewport.bottom+1f)
    }

    @Test fun compactTurnHintsAndBadgesFitAtFontScale15() {
        val feedback=mutableStateOf(CaptureFeedback(hint=R.string.identified_selfie_hint_keep_left,
            phase=TurnPhase.left,status=TurnStatus.ok,yaw=-.2))
        compose.setContent {
            val density=LocalDensity.current
            val configuration=LocalConfiguration.current
            val pixelScale=minOf(1f,configuration.screenWidthDp*density.density/320f,
                configuration.screenHeightDp*density.density/568f)
            CompositionLocalProvider(LocalDensity provides Density(pixelScale,fontScale=1.5f)) {
                IdentifiedTheme(Appearance(colorScheme=ColorSchemePreference.LIGHT)) {
                    Box(Modifier.requiredSize(320.dp,568.dp)) {
                        CameraStage(CaptureKind.SELFIE,feedback.value) { Box(Modifier.fillMaxSize().background(Color.Black)) }
                    }
                }
            }
        }
        awaitSnapshotWindow()
        val directory=cameraSnapshotDirectory()
        for ((name,value) in listOf(
            "left" to CaptureFeedback(hint=R.string.identified_selfie_hint_keep_left,phase=TurnPhase.left,status=TurnStatus.ok,yaw=-.2),
            "left-reached" to CaptureFeedback(hint=R.string.identified_selfie_hint_perfect,phase=TurnPhase.left,status=TurnStatus.ok,yaw=-.38),
            "right" to CaptureFeedback(hint=R.string.identified_selfie_hint_keep_right,phase=TurnPhase.right,status=TurnStatus.ok,yaw=.2),
        )) {
            compose.runOnIdle { feedback.value=value }
            compose.waitForIdle()
            // Save evidence before layout assertions so a failed first badge
            // cannot prevent its screenshot or the other badges' diagnostics.
            val image=compose.onNodeWithTag("camera-stage").captureToImage().asAndroidBitmap()
            val screenshot=File(directory,"light-camera-$name-fontscale1_5-320x568.png")
            screenshot.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val diagnosticFile=File(directory,"light-camera-$name-fontscale1_5-320x568-layout.txt")
            diagnosticFile.writeText("scene=$name screenshot=${screenshot.absolutePath}\n")
            for(index in 0..2) {
                val badge=compose.onNodeWithTag("camera-pose-badge-$index")
                val results=mutableListOf<TextLayoutResult>()
                badge.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
                val bounds=badge.fetchSemanticsNode().boundsInRoot
                val circle=compose.onNodeWithTag("camera-pose-badge-circle-$index").fetchSemanticsNode().boundsInRoot
                val record="$name badge=$index bounds=$bounds circle=$circle layouts=${results.size} " +
                    results.joinToString("; ") { cameraTextLayoutDiagnostic(it) }
                diagnosticFile.appendText(record+"\n")
                android.util.Log.i("IdentifiedCameraLayout",record)
            }
            val viewport=compose.onNodeWithTag("camera-hint-viewport").fetchSemanticsNode().boundsInRoot
            val hint=compose.onNodeWithTag("camera-hint")
            val layouts=mutableListOf<TextLayoutResult>()
            hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
            assertEquals(1,layouts.size)
            assertFalse("$name hint must not overflow its text layout",layouts.single().hasVisualOverflow)
            val hintNode=hint.fetchSemanticsNode()
            val bounds=hintNode.boundsInRoot
            assertTrue("$name primary hint fully visible without scrolling",bounds.top>=viewport.top &&
                bounds.bottom<=viewport.bottom+1f && bounds.height>=layouts.single().size.height-1f)
            val stageBounds=compose.onNodeWithTag("camera-stage").fetchSemanticsNode().boundsInRoot
            val badgeDiameter=27f*stageBounds.width/320f
            for (index in 0..2) {
                val badge=compose.onNodeWithTag("camera-pose-badge-$index")
                val badgeLayouts=mutableListOf<TextLayoutResult>()
                badge.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(badgeLayouts)) }
                val layout=badgeLayouts.single()
                assertFalse("$name badge $index glyph must not overflow: ${cameraTextLayoutDiagnostic(layout)}",layout.hasVisualOverflow)
                val node=badge.fetchSemanticsNode()
                val badgeBounds=node.boundsInRoot
                assertTrue("$name badge $index text fits its scaled circle",layout.size.width<=badgeDiameter+1f && layout.size.height<=badgeDiameter+1f)
                assertTrue("$name badge $index not clipped by parent",badgeBounds.height>=layout.size.height-1f && badgeBounds.width>=layout.size.width-1f)
                val circle=compose.onNodeWithTag("camera-pose-badge-circle-$index").fetchSemanticsNode().boundsInRoot
                assertTrue("$name badge $index entire line box inside circle",badgeBounds.left>=circle.left-1f &&
                    badgeBounds.top>=circle.top-1f && badgeBounds.right<=circle.right+1f && badgeBounds.bottom<=circle.bottom+1f)
            }
        }
    }

    @Test fun selfiePreviewBoundsFollowPortraitAndLandscapeStage() {
        val landscape=mutableStateOf(false)
        compose.setContent {
            val density=LocalDensity.current
            val configuration=LocalConfiguration.current
            val pixelScale=minOf(1f,configuration.screenWidthDp*density.density/640f,
                configuration.screenHeightDp*density.density/640f)
            CompositionLocalProvider(LocalDensity provides Density(pixelScale)) {
                IdentifiedTheme(Appearance()) {
                    Box(Modifier.requiredSize(if(landscape.value) 640.dp else 360.dp,
                        if(landscape.value) 360.dp else 640.dp)) {
                        CameraStage(CaptureKind.SELFIE,CaptureFeedback()) { Box(Modifier.fillMaxSize().background(Color.Black)) }
                    }
                }
            }
        }
        awaitSnapshotWindow()
        for(rotated in listOf(false,true)) {
            compose.runOnIdle { landscape.value=rotated }
            compose.waitForIdle()
            val stage=compose.onNodeWithTag("camera-stage").fetchSemanticsNode().boundsInRoot
            val preview=compose.onNodeWithTag("camera-preview-region").fetchSemanticsNode().boundsInRoot
            val overlay=compose.onNodeWithTag("camera-ring").fetchSemanticsNode().boundsInRoot
            val expected=PixelSize(stage.width.toDouble(),stage.height.toDouble())
            assertEquals(expected.width.toFloat(),preview.width,1f)
            assertEquals(expected.height.toFloat(),preview.height,1f)
            assertEquals(stage.center.x,preview.center.x,1f)
            assertEquals(stage.center.y,preview.center.y,1f)
            assertEquals("Circle radius uses the unchanged shorter side",minOf(stage.width,stage.height),minOf(preview.width,preview.height),1f)
            assertEquals("Mask and ring stay in full-stage coordinates",stage,overlay)
            val guide=compose.onNodeWithTag("camera-face-guide").fetchSemanticsNode().boundsInRoot
            assertEquals("Portrait guide is higher; landscape stays centred",
                stage.top+CaptureGeometry.selfieCenterY(PixelSize(stage.width.toDouble(),stage.height.toDouble()), 0.75).toFloat(),guide.center.y,1f)
        }
    }

    @Test fun timedFallbackUsesElapsedCueWithoutInventingCapturedPoses() {
        for ((progress, cue) in listOf(.2f to TurnPhase.left, .45f to TurnPhase.center, .75f to TurnPhase.right)) {
            val presentation=CameraPresentation(CaptureFeedback(timed=true,sweep=progress))
            assertEquals(cue,presentation.cue)
            assertFalse(presentation.showsPoseChips)
            assertFalse(presentation.frontDone)
            assertFalse(presentation.leftDone)
            assertFalse(presentation.rightDone)
            assertFalse(presentation.good)
        }
        assertFalse(CameraPresentation(CaptureFeedback(timedWaiting=true)).showsPoseChips)
    }

    @Test fun presentationProgressDoesNotInventCapturedSides() {
        val left=CameraPresentation(CaptureFeedback(phase=TurnPhase.left,status=TurnStatus.ok,yaw=-.38))
        assertTrue(left.reached); assertTrue(left.frontDone); assertFalse(left.leftDone)
        assertFalse(CameraPresentation(left.feedback.copy(status=TurnStatus.far)).reached)
        assertEquals(0f,CameraPresentation(left.feedback.copy(phase=TurnPhase.center)).turn)
        assertTrue(CameraPresentation(left.feedback.copy(phase=TurnPhase.right)).leftDone)
    }
    @Test fun selfieMaskHidesTheFeedOutsideTheCircle() {
        val kind=mutableStateOf(CaptureKind.SELFIE)
        compose.setContent {
            IdentifiedTheme(Appearance()) {
                CameraStage(kind.value,CaptureFeedback()) {
                    Box(Modifier.fillMaxSize().background(Color(0xff40c080)))
                }
            }
        }
        awaitSnapshotWindow()
        for (value in listOf(CaptureKind.SELFIE,CaptureKind.CARD)) {
            compose.runOnIdle { kind.value=value }
            compose.waitForIdle()
            val image=compose.onNodeWithTag("camera-stage").captureToImage().asAndroidBitmap()
            val pixel=image.getPixel((image.width*.03f).toInt(),(image.height*.4f).toInt())
            if (value == CaptureKind.SELFIE) {
                assertEquals(android.graphics.Color.BLACK, pixel)
                val centerY = CaptureGeometry.selfieCenterY(
                    PixelSize(image.width.toDouble(), image.height.toDouble()), 0.75)
                val inside = image.getPixel(image.width / 2, centerY.toInt())
                assertTrue("Camera feed is visible inside the circle", android.graphics.Color.green(inside) > 120)
                continue
            }
            assertTrue("$value outside-guide red remains visible",android.graphics.Color.red(pixel) in 52..62)
            assertTrue("$value outside-guide green remains visible",android.graphics.Color.green(pixel) in 163..175)
            assertTrue("$value outside-guide blue remains visible",android.graphics.Color.blue(pixel) in 106..119)
        }
    }

}
