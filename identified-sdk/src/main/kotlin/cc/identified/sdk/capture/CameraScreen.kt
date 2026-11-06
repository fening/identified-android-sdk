package cc.identified.sdk.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import cc.identified.sdk.capture.core.CaptureGeometry
import cc.identified.sdk.capture.core.PixelSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.identified.sdk.R

internal enum class CaptureKind {
    CARD,
    SELFIE,
    DOCUMENT,
}

@Composable
internal fun CameraScreen(
    kind: CaptureKind,
    label: String,
    onCaptured: (Map<String, ByteArray>) -> Unit,
    onClose: () -> Unit,
    onNoCamera: () -> Unit,
) {
    val capture by rememberUpdatedState(onCaptured)
    val close by rememberUpdatedState(onClose)
    val unavailable by rememberUpdatedState(onNoCamera)
    BackHandler { close() }
    val fixture = CaptureOverrides.frames
    if (fixture != null) {
        // Explicit instrumentation entry point, with no settings/property fallback.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TextButton(
                onClick = { close() },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
            ) {
                Text(stringResource(R.string.identified_camera_back))
            }
            Button(
                onClick = { capture(fixture(kind)) },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text("Capture test frame")
            }
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    var active by remember {
        mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var retry by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var state by remember { mutableStateOf(CameraRunState.STARTING) }
    var feedback by remember { mutableStateOf(CaptureFeedback()) }
    var binding by remember { mutableStateOf<CameraBinding?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
        }
    val preview =
        remember(context, kind) {
            PreviewView(context).apply {
                scaleType = if (kind != CaptureKind.DOCUMENT) PreviewView.ScaleType.FIT_CENTER
                    else PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    LaunchedEffect(Unit) { if (!permission) launcher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permission = granted()
                active = true
            }
            if (event == Lifecycle.Event.ON_PAUSE) active = false
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(permission, active, size, retry, kind, lifecycle) {
        val camera =
            if (permission && active && size.width > 0 && size.height > 0) {
                state = CameraRunState.STARTING
                feedback = CaptureFeedback()
                CameraBinding(
                        context,
                        lifecycle,
                        preview,
                        kind,
                        { feedback = it },
                        { state = it },
                        { capture(it) },
                    )
                    .also {
                        binding = it
                        it.start()
                    }
            } else null
        onDispose {
            camera?.close()
            if (binding === camera) binding = null
        }
    }
    LaunchedEffect(state) { if (state == CameraRunState.UNAVAILABLE) unavailable() }
    val window = context.activity()?.window
    DisposableEffect(window, feedback.dark, active, kind) {
        val prior = window?.attributes?.screenBrightness
        if (kind == CaptureKind.SELFIE && feedback.dark && active && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (prior != null && window != null)
                window.attributes = window.attributes.apply { screenBrightness = prior }
        }
    }
    val dark = kind == CaptureKind.SELFIE && feedback.dark
    val back = stringResource(R.string.identified_camera_back)
    val shutter =
        if (kind == CaptureKind.SELFIE) stringResource(R.string.identified_selfie_shutter)
        else stringResource(R.string.identified_camera_capture, label)
    val problem = !permission || state == CameraRunState.FAILED
    BoxWithConstraints(
        Modifier.fillMaxSize().background(if (dark) Color.White else Color.Black)
    ) {
        CameraStage(kind, feedback, showFeedback = !problem) {
            AndroidView(
                factory = { preview },
                modifier = Modifier.fillMaxSize().onSizeChanged { size = it }.graphicsLayer {
                    if (kind == CaptureKind.SELFIE) {
                        val stage = PixelSize(size.width.toDouble(), size.height.toDouble())
                        val sourceAspect = feedback.sourceAspect?.toDouble() ?: 0.75
                        translationY = -CaptureGeometry.selfiePreviewTopInset(stage, sourceAspect).toFloat()
                    }
                },
            )
        }
        TextButton(
            onClick = { close() },
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Text("‹", color = Color.White, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = .45f), CircleShape)
                    .wrapContentSize().semantics { contentDescription = back })
        }
        if (problem) {
            CameraProblemPresentation(
                permissionDenied = !permission,
                onSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
                onRetry = { if (!permission) launcher.launch(Manifest.permission.CAMERA) else retry++ },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            if (feedback.timedWaiting)
                Button(
                    onClick = { binding?.startTimed() },
                    modifier =
                        Modifier.align(if (maxWidth > maxHeight) Alignment.BottomEnd else Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(24.dp),
                ) {
                    Text(stringResource(R.string.identified_selfie_timed_start))
                }
            else if (kind != CaptureKind.SELFIE || feedback.manual) {
                OutlinedButton(
                    onClick = { binding?.shutter() },
                    enabled = state == CameraRunState.RUNNING,
                    shape = CircleShape,
                    contentPadding = PaddingValues(6.dp),
                    modifier =
                        Modifier.align(if (maxWidth > maxHeight) Alignment.BottomEnd else Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 24.dp, end = if(maxWidth > maxHeight) 24.dp else 0.dp)
                            .size(64.dp)
                            .semantics { contentDescription = shutter },
                ) {
                    CameraShutterIcon()
                }
            }
        }
    }
}

private fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
