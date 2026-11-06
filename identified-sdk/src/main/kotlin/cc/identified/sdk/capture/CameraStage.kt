package cc.identified.sdk.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.R
import cc.identified.sdk.Palette
import cc.identified.sdk.ColorSchemePreference
import cc.identified.sdk.ui.LocalPalette
import cc.identified.sdk.ui.LocalAppearance
import cc.identified.sdk.capture.core.CaptureGeometry
import cc.identified.sdk.capture.core.PixelSize
import cc.identified.sdk.capture.core.TurnChallenge
import cc.identified.sdk.capture.core.TurnPhase
import cc.identified.sdk.capture.core.TurnStatus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Match web capture hues by default; host semantic overrides take precedence.
 * Custom accent remains the success/turn fallback for existing integrators. */
internal class CameraStageColors(palette: Palette, defaults: Palette) {
    private val hostSuccess = when {
        palette.success != defaults.success -> Color(palette.success)
        palette.accent != defaults.accent -> Color(palette.accent)
        else -> null
    }
    val success = hostSuccess ?: Color(0xff40a99a)
    val positive = hostSuccess ?: Color(0xff237f74)
    val doneGuide = hostSuccess ?: Color(0xffe8f6f0)
    val warning = if (palette.warning != defaults.warning) Color(palette.warning) else Color(0xfff59e0b)
}

@Composable
private fun cameraStageColors(): CameraStageColors {
    val dark = when (LocalAppearance.current.colorScheme) {
        ColorSchemePreference.SYSTEM -> isSystemInDarkTheme()
        ColorSchemePreference.LIGHT -> false
        ColorSchemePreference.DARK -> true
    }
    return CameraStageColors(LocalPalette.current, if (dark) Palette.Dark else Palette.Light)
}

/** Map the ID guide to the full, aspect-fitted camera image, not tall-screen fill. */
internal fun cardGuideSize(width: Float, height: Float, sourceAspect: Float): Size {
    val pictureWidth = min(width, height * sourceAspect)
    val guideWidth = pictureWidth * cc.identified.sdk.capture.core.CardDetector.guideWidthFraction.toFloat()
    return Size(guideWidth, guideWidth / cc.identified.sdk.capture.core.CardDetector.aspectRatio.toFloat())
}

/** Pure presentation facts, shared by the live stage and deterministic screenshot tests. */
internal data class CameraPresentation(val feedback: CaptureFeedback) {
    val good get() = feedback.status == TurnStatus.ok || feedback.status == TurnStatus.hold
    val leftDone get() = feedback.phase in listOf(TurnPhase.center, TurnPhase.right, TurnPhase.done)
    val rightDone get() = feedback.phase == TurnPhase.done
    val frontDone get() = feedback.phase != TurnPhase.position
    val turn get() = (when (feedback.phase) {
        TurnPhase.left -> -feedback.yaw / TurnChallenge.side
        TurnPhase.right -> feedback.yaw / TurnChallenge.side
        else -> 0.0
    }).toFloat().coerceIn(0f, 1f)
    val reached get() = good && turn >= 1
    val showsPoseChips get() = !feedback.timed && !feedback.timedWaiting
    // Timed fallback uses elapsed progress for its cue, never for captured poses.
    val cue get() = if (feedback.timed) {
        when {
            feedback.sweep < .4f -> TurnPhase.left
            feedback.sweep < .55f -> TurnPhase.center
            else -> TurnPhase.right
        }
    } else when (feedback.phase) {
        TurnPhase.left -> TurnPhase.left
        TurnPhase.right -> TurnPhase.right
        else -> TurnPhase.center
    }
}

/** No camera/permissions/timers: live CameraScreen supplies its PreviewView in this slot.
 * Instrumentation supplies a black Box to render the identical overlay without hardware. */
@Composable
internal fun CameraStage(
    kind: CaptureKind,
    feedback: CaptureFeedback,
    showFeedback: Boolean = true,
    preview: @Composable BoxScope.() -> Unit,
) {
    val p = CameraPresentation(feedback)
    val colors = cameraStageColors()
    val fillLight = kind == CaptureKind.SELFIE && feedback.dark
    val ring = if (feedback.phase == TurnPhase.done) colors.success else if (p.good) {
        if (fillLight) colors.positive else Color.White
    } else colors.warning
    val guide = if(feedback.phase == TurnPhase.done) { if(fillLight) colors.positive else colors.doneGuide } else if (p.good) { if (fillLight) colors.positive else colors.success } else colors.warning
    BoxWithConstraints(Modifier.fillMaxSize().background(if (fillLight) Color.White else Color.Black).testTag("camera-stage")) {
        val compact = maxWidth > maxHeight
        val largeText = LocalDensity.current.fontScale > 1.3f
        val footerInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val radius = min(maxWidth.value, maxHeight.value) * CaptureGeometry.ringFraction.toFloat()
        val selfieAspect = feedback.sourceAspect?.toDouble() ?: 0.75
        val guideCenterY = CaptureGeometry.selfieCenterY(PixelSize(maxWidth.value.toDouble(), maxHeight.value.toDouble()), selfieAspect).toFloat()
        val guideOffsetY = (guideCenterY - maxHeight.value / 2).dp
        val rail = (maxWidth.value / 2 - radius - 16).coerceAtLeast(1f).dp
        val previewWidth = if(compact && kind == CaptureKind.DOCUMENT) min(maxWidth.value,maxHeight.value*1.25f).dp else maxWidth
        val previewHeight = maxHeight
        Box(Modifier.align(Alignment.Center).size(previewWidth,previewHeight).clipToBounds().testTag("camera-preview-region"), content = preview)
        // ID/selfie masks remain separate from the full-screen camera view.
        Canvas(Modifier.align(Alignment.Center).width(if(kind == CaptureKind.SELFIE) maxWidth else previewWidth).fillMaxHeight().testTag("camera-ring")) {
            val w = size.width
            val h = size.height
            if (kind == CaptureKind.CARD) {
                val sourceAspect = feedback.sourceAspect ?: if(w > h) 4f/3 else 3f/4
                val guideSize = cardGuideSize(w, h, sourceAspect)
                val gw = guideSize.width
                val gh = guideSize.height
                val rect = Rect((w-gw)/2, (h-gh)/2, (w+gw)/2, (h+gh)/2)
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(rect, CornerRadius(10.dp.toPx())))
                }
                drawPath(path, Color.Black.copy(alpha=.12f))
                drawRoundRect(if (feedback.ready) colors.success else Color.White.copy(alpha=.9f), rect.topLeft,
                    rect.size, CornerRadius(10.dp.toPx()), style=Stroke(2.5.dp.toPx()))
            } else if (kind == CaptureKind.DOCUMENT && showFeedback) {
                feedback.documentPage?.let { page ->
                    val outline = Path().apply {
                        page.points.forEachIndexed { index, point ->
                            val x = point.x.toFloat() * w
                            val y = point.y.toFloat() * h
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawPath(outline, Color.Black.copy(alpha = .65f), style = Stroke(4.dp.toPx()))
                    drawPath(outline, if (feedback.ready) colors.success else Color.White, style = Stroke(2.dp.toPx()))
                }
            } else if (kind == CaptureKind.SELFIE) {
                val r = (min(w,h) * CaptureGeometry.ringFraction).toFloat()
                val c = Offset(w/2, CaptureGeometry.selfieCenterY(PixelSize(w.toDouble(),h.toDouble()), selfieAspect).toFloat())
                val bounds = Rect(c.x-r,c.y-r,c.x+r,c.y+r)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f,0f,w,h)); addOval(bounds)
                }
                drawPath(mask, if (fillLight) Color.White else Color.Black)
                drawCircle(ring.copy(alpha=.4f),r,c,style=Stroke(w/100))
                if (p.cue == TurnPhase.left || p.cue == TurnPhase.right) {
                    val left = p.cue == TurnPhase.left
                    val progress = when {
                        feedback.timed && left -> feedback.sweep / .4f
                        feedback.timed -> (feedback.sweep - .55f) / .45f
                        p.good -> p.turn
                        else -> 0f
                    }.coerceIn(0f, 1f)
                    val dashCount = 25
                    for (index in 0 until dashCount) {
                        val fraction = index.toFloat() / (dashCount - 1)
                        val angle = Math.toRadians((-36 + 72 * fraction).toDouble())
                        val direction = Offset(
                            cos(angle).toFloat() * if (left) -1 else 1,
                            sin(angle).toFloat(),
                        )
                        val start = c + direction * (r * .88f)
                        val end = c + direction * (r * .98f)
                        val opacity = if (progress > 0f && fraction <= progress) 1f else .32f
                        drawLine(Color.White.copy(alpha = opacity), start, end, strokeWidth = 1.5.dp.toPx())
                    }
                }
                if (feedback.countdown != null) drawArc(Color.White, -90f,
                    360*feedback.sweep.coerceIn(0f,1f),false,bounds.topLeft,bounds.size,
                    style=Stroke(w/100,cap=StrokeCap.Round))
            }
        }
        if(kind == CaptureKind.SELFIE) {
            if(feedback.countdown == null) {
                val image = when(p.cue) {
                    TurnPhase.left -> R.drawable.identified_face_guide_left
                    TurnPhase.right -> R.drawable.identified_face_guide_right
                    else -> R.drawable.identified_face_guide_center
                }
                Image(painterResource(image),null,colorFilter=ColorFilter.tint(guide),
                    modifier=Modifier.align(Alignment.Center).offset(y=guideOffsetY).alpha(if(p.good) .12f else .28f).size((radius*1.5f).dp).testTag("camera-face-guide"))
                if((p.cue == TurnPhase.left || p.cue == TurnPhase.right) && !p.reached) {
                    Text(if(p.cue == TurnPhase.left) "‹" else "›",color=guide,fontSize=24.sp,
                        modifier=Modifier.align(Alignment.Center).offset(x=((radius*.82f-17) * if(p.cue == TurnPhase.left) -1 else 1).dp, y=guideOffsetY))
                }
            }
            if(showFeedback && feedback.phase != TurnPhase.done) {
                if(compact) {
                    if(p.showsPoseChips) Column(Modifier.align(Alignment.TopStart).width(rail).padding(top=76.dp,start=8.dp),
                        horizontalAlignment=Alignment.CenterHorizontally) { PoseChips(p, vertical=true) }
                    Column(Modifier.align(Alignment.TopEnd).offset(x=(-8).dp,y=16.dp).width(rail).height((maxHeight-128.dp-footerInset).coerceAtLeast(1.dp))
                        .testTag("camera-hint-viewport").verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                        CameraHints(p)
                    }
                } else {
                    if(p.showsPoseChips) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=12.dp)) { PoseChips(p, vertical=largeText) }
                    val gap = if(largeText) 12.dp else 20.dp
                    val footer = if(largeText) 88.dp else 106.dp
                    val top = guideCenterY.dp + radius.dp + gap
                    Column(Modifier.fillMaxWidth().offset(y=top).height((maxHeight-top-footer-footerInset).coerceAtLeast(1.dp))
                        .padding(horizontal=20.dp).testTag("camera-hint-viewport").verticalScroll(rememberScrollState()),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) { CameraHints(p) }
                }
            }
            if(feedback.phase == TurnPhase.done) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.55f)))
                Text(
                    stringResource(R.string.identified_selfie_hint_done),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).testTag("camera-done"),
                )
            }
        } else if(showFeedback) {
            Text(stringResource(feedback.hint),color=if(feedback.ready) colors.success else Color.White,
                style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center,
                modifier=Modifier.align(if(compact) Alignment.CenterEnd else Alignment.BottomCenter)
                    .then(if(compact) Modifier.width(rail) else Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom=128.dp))
                    .padding(horizontal=16.dp).background(Color.Black.copy(alpha=.55f),RoundedCornerShape(16.dp)).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun PoseChips(p: CameraPresentation, vertical: Boolean = false) {
    val colors = cameraStageColors()
    val badgeSize = (18f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val captured=listOf(p.frontDone,p.leftDone,p.rightDone)
    val active=captured.indexOfFirst { !it }
    val content: @Composable () -> Unit = {
        listOf(R.string.identified_selfie_pose_front,R.string.identified_selfie_pose_left,R.string.identified_selfie_pose_right).forEachIndexed { i,id ->
            val done=captured[i]
            if(i>0 && !vertical) Box(Modifier.width(10.dp).height(1.dp).background(Color.White.copy(alpha=.25f)))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier=Modifier.size(badgeSize)
                        .background(if(done) colors.positive else Color.Transparent,CircleShape)
                        .border(1.dp,if(done) colors.positive else if(active==i) colors.success else Color.White.copy(alpha=.45f),CircleShape)
                        .testTag("camera-pose-badge-circle-$i"),
                    contentAlignment=Alignment.Center,
                ) {
                    // The circle owns sizing; the glyph gets an independent,
                    // centred line box. Leave enough leading for checkmark
                    // fallback fonts without Android's extra font padding.
                    Text(
                        if(done) "✓" else "${i+1}",
                        color=if(done || active==i) Color.White else Color.White.copy(alpha=.55f),
                        style=MaterialTheme.typography.labelSmall.copy(
                            fontSize=10.sp, lineHeight=16.sp, fontWeight=FontWeight.SemiBold,
                            letterSpacing=0.sp, textAlign=TextAlign.Center,
                            platformStyle=PlatformTextStyle(includeFontPadding=false),
                        ),
                        maxLines=1, softWrap=false,
                        modifier=Modifier.fillMaxWidth().testTag("camera-pose-badge-$i"),
                    )
                }
                Text(stringResource(id),color=if(done) colors.success else if(active==i) Color.White else Color.White.copy(alpha=.55f),
                    style=MaterialTheme.typography.labelMedium,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
            }
        }
    }
    val modifier=Modifier.background(Color.Black.copy(alpha=.45f),RoundedCornerShape(24.dp)).padding(horizontal=12.dp,vertical=6.dp)
    if(vertical) Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp),content={content()})
    else Row(modifier,horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,content={content()})
}

@Composable
private fun CameraHints(p: CameraPresentation) {
    val colors = cameraStageColors()
    val f=p.feedback
    if(f.countdown != null) {
        val label = stringResource(R.string.identified_selfie_countdown, f.countdown)
        Text("${f.countdown}",color=if(f.dark) colors.positive else Color.White,fontSize=30.sp,
            fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("camera-countdown").semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = label
            })
    }
    else Text(stringResource(f.hint),color=Color.White,fontSize=16.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,
        modifier=Modifier.background(if(p.reached) colors.positive else Color.Black.copy(alpha=.55f),RoundedCornerShape(24.dp))
            .padding(horizontal=16.dp,vertical=6.dp).testTag("camera-hint").semantics { liveRegion = LiveRegionMode.Polite })
    if(f.status == TurnStatus.far || f.status == TurnStatus.near) {
        Row(Modifier.padding(top=8.dp).background(Color.Black.copy(alpha=.55f),CircleShape).padding(horizontal=12.dp,vertical=5.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                for(bar in 1..3) Box(Modifier.width(4.dp).height((2+bar*4).dp).background(if(bar<=f.distanceLevel) colors.warning else Color.White.copy(alpha=.3f)))
            }
            Text(stringResource(if(f.status == TurnStatus.far) R.string.identified_selfie_too_far else R.string.identified_selfie_too_close),color=colors.warning,fontSize=13.sp)
        }
    }
    if(f.dark) Text(stringResource(R.string.identified_selfie_low_light),color=Color.White,style=MaterialTheme.typography.bodySmall,fontSize=13.sp,textAlign=TextAlign.Center,
        modifier=Modifier.padding(top=8.dp).background(Color.Black.copy(alpha=.55f),RoundedCornerShape(16.dp)).padding(8.dp).testTag("camera-low-light"))
}

/** Permission actions remain outside any noninteractive overlay and can scroll at large fonts. */
@Composable
internal fun CameraProblemPresentation(
    permissionDenied: Boolean,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(28.dp).heightIn(max=440.dp).verticalScroll(rememberScrollState())
        .background(Color.Black.copy(alpha=.95f),RoundedCornerShape(16.dp)).padding(20.dp).testTag("camera-error"),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(stringResource(if(permissionDenied) R.string.identified_camera_denied_title else R.string.identified_camera_failed_title),
            color=Color.White,style=MaterialTheme.typography.titleLarge)
        Text(stringResource(if(permissionDenied) R.string.identified_camera_denied_body else R.string.identified_camera_failed_body),
            color=Color.White,textAlign=TextAlign.Center)
        if(permissionDenied) androidx.compose.material3.Button(onClick=onSettings) {
            Text(stringResource(R.string.identified_camera_denied_settings))
        }
        androidx.compose.material3.Button(onClick=onRetry) { Text(stringResource(R.string.identified_camera_retry)) }
    }
}

@Composable
internal fun CameraShutterIcon() {
    Canvas(Modifier.size(26.dp)) {
        val unit=size.width/24
        val body=Path().apply {
            moveTo(3*unit,8.5f*unit)
            lineTo(3*unit,17*unit); quadraticBezierTo(3*unit,19*unit,5*unit,19*unit)
            lineTo(19*unit,19*unit); quadraticBezierTo(21*unit,19*unit,21*unit,17*unit)
            lineTo(21*unit,8*unit); quadraticBezierTo(21*unit,6*unit,19*unit,6*unit)
            lineTo(17*unit,6*unit); lineTo(15*unit,4*unit); lineTo(9*unit,4*unit)
            lineTo(7*unit,6*unit); lineTo(5*unit,6*unit); quadraticBezierTo(3*unit,6*unit,3*unit,8.5f*unit)
            close()
        }
        drawPath(body,Color.White,style=Stroke(1.6f*unit))
        drawCircle(Color.White,3.4f*unit,Offset(12*unit,12.5f*unit),style=Stroke(1.6f*unit))
    }
}
