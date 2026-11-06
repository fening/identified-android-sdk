package cc.identified.sdk.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

@Composable
internal fun CaptureScreen(m: FlowViewModel, s: FlowState, startAt: String?, selfie: Boolean) {
    val c = LocalCopy.current
    val steps =
        FlowRules.documentSteps(s.session?.requiredDocuments.orEmpty(), CapturePhase.DOCUMENTS)
    val draft = m.drafts
    if (draft.captureRoute != s.route) {
        draft.clearImages()
        draft.captureRoute = s.route
        draft.captureIndex = steps.indexOf(startAt).coerceAtLeast(0)
    }
    var index by draft::captureIndex
    val type = if (selfie) DocType.selfie else steps.getOrNull(index) ?: return
    var camera by remember { mutableStateOf(false) }
    var frames by draft::frames
    BackHandler(enabled = camera) { camera = false }
    if (camera) {
        CameraScreen(
            if (selfie) CaptureKind.SELFIE else CaptureKind.CARD,
            docLabel(type, c),
            onCaptured = { captured ->
                camera = false
                if (selfie && !SelfieRole.all.all { captured[it]?.isNotEmpty() == true })
                    m.errorKey("selfie.too_few")
                else frames = captured
            },
            onClose = { camera = false },
            onNoCamera = m::noCamera,
        )
        return
    }
    if (s.busy) {
        if (selfie)
            Processing(
                listOf("selfie.processing.scanning", "selfie.processing.matching"),
                "selfie.processing.detail",
                m::close,
            )
        else Processing(listOf("documents.uploading"), null, m::close)
        return
    }
    val captured = frames
    val use: () -> Unit = {
        if (selfie) m.uploadSelfie(captured.orEmpty())
        else
            captured?.get("image")?.let { image ->
                m.uploadCard(type, image) {
                    if (s.mode == NavigationMode.RESUME || index == steps.lastIndex)
                        m.completed(FlowScreen.DOCUMENTS)
                    else {
                        index++
                        frames = null
                    }
                }
            }
        Unit
    }
    Shell(
        m,
        s,
        if (selfie) "selfie.title" else null,
        if (!selfie && captured != null) "documents.subtitle" else null,
        if (selfie) FlowScreen.SELFIE else FlowScreen.DOCUMENTS,
        compactTitle = selfie,
        titleCopy = if (selfie) null else docLabel(type, c),
    ) {
        if (captured != null) {
            CapturePreview(
                data = captured[if (selfie) "center" else "image"],
                label =
                    if (selfie) c.text("selfie.review.alt")
                    else c.text("camera.preview", docLabel(type, c)),
                selfie = selfie,
                onUse = use,
                onRetake = {
                    frames = null
                    camera = true
                },
            )
        } else {
            val prefix =
                when {
                    selfie -> "brief.selfie"
                    type == DocType.licenseBack -> "brief.license_back"
                    type == DocType.governmentId -> "brief.government_id"
                    else -> "brief.license_front"
                }
            val pointPrefix = if (selfie || type == DocType.licenseBack) prefix else "brief.card"
            // CaptureBrief owns this spacing rather than inheriting the shell's content gap.
            Column {
                Body(c.text("$prefix.lead"))
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (i in 1..3) BriefPoint(c.text("$pointPrefix.point$i"))
                    Box(Modifier.padding(top = 12.dp)) {
                        Primary(c.text("brief.open_camera"), tag = "identified-open-camera") {
                            camera = true
                        }
                    }
                }
            }
        }
        CapturedStrip(m.acceptedCaptures)
    }
}

/** Pending evidence stays in the scrolling body, just as the hosted capture components do. */
@Composable
internal fun CapturePreview(
    data: ByteArray?,
    label: String,
    selfie: Boolean,
    onUse: () -> Unit,
    onRetake: () -> Unit,
    enabled: Boolean = true,
) {
    val c = LocalCopy.current
    val bitmap =
        remember(data) {
            data?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    val shape = MaterialTheme.shapes.large
    val compactShape =
        androidx.compose.foundation.shape.RoundedCornerShape(
            (LocalAppearance.current.cornerRadius.coerceAtLeast(0f) * .75f).dp
        )
    val actions: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (selfie) 12.dp else 8.dp),
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = enabled,
                modifier =
                    Modifier.weight(1f)
                        .heightIn(min = if (selfie) 46.dp else 52.dp)
                        .testTag("identified-retake"),
                shape = if (selfie) compactShape else shape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    c.text("camera.retake"),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = if (selfie) 15.sp else 16.sp
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onUse,
                enabled = enabled,
                modifier =
                    Modifier.weight(1f)
                        .heightIn(min = if (selfie) 46.dp else 52.dp)
                        .testTag("identified-use-photo"),
                shape = if (selfie) compactShape else shape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            ) {
                Text(
                    c.text("camera.use"),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = if (selfie) 15.sp else 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        ),
                )
            }
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .then(
                if (selfie) Modifier
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(8.dp)
            )
    ) {
        if (bitmap != null) {
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val previewShape = if (selfie) shape else compactShape
            val cap =
                (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .52f)
                    .dp
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth()
                        .height((maxWidth / ratio).coerceAtMost(cap))
                        .background(androidx.compose.ui.graphics.Color.Black, previewShape)
                        .clip(previewShape)
                        .then(if (selfie) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Image(
                        bitmap,
                        label,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (selfie) {
            Text(
                c.text("selfie.review.text"),
                style =
                    MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
            )
        }
        Box(Modifier.padding(top = if (selfie) 16.dp else 12.dp)) { actions() }
    }
}
