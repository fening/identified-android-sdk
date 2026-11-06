package cc.identified.sdk.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*
import kotlinx.coroutines.delay

@Composable
internal fun Primary(
    text: String,
    enabled: Boolean = true,
    tag: String = "identified-continue",
    action: () -> Unit,
) {
    val interaction = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        interactionSource = interaction,
        onClick = action,
        enabled = enabled,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 54.dp)
                .testTag(tag)
                .shadow(
                    if (enabled && !pressed) 8.dp else 0.dp,
                    MaterialTheme.shapes.large,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = .2f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = .2f),
                )
                .graphicsLayer {
                    scaleX = if (pressed) .97f else 1f
                    scaleY = if (pressed) .97f else 1f
                },
        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                disabledElevation = 0.dp,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text)
    }
}

@Composable
internal fun Secondary(text: String, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(
        onClick = action,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun Panel(
    color: Color = webSurface(),
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, borderColor),
        color = color,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun ErrorMessage(state: FlowState) {
    val c = LocalCopy.current
    val error =
        state.error
            ?: state.errorKey?.let {
                if (it == "poa.too_large")
                    c.text(it, ProofOfAddressRules.megabytes(state.policy.maxSizeBytes))
                else c.text(it)
            }
    if (error != null)
        Text(
            error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
}

@Composable
internal fun Loading(text: String, onClose: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Body(text)
        if (onClose != null)
            TextButton(onClick = onClose, modifier = Modifier.testTag("identified-close")) {
                Text(LocalCopy.current.text("flow.close"))
            }
    }
}

@Composable
internal fun Processing(keys: List<String>, detail: String?, onClose: () -> Unit) {
    val c = LocalCopy.current
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(keys) {
        while (index < keys.lastIndex) {
            delay(3000)
            index++
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (LocalAppearance.current.showsLockup) BrandLockup()
            Spacer(Modifier.weight(1f))
            NavigationIcon(c.text("flow.close"), true, true, "identified-close", onClose)
        }
        Column(
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProcessingArt(face = keys.firstOrNull()?.startsWith("selfie.") == true)
            Spacer(Modifier.height(28.dp))
            Text(
                c.text(keys[index]),
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (detail != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    c.text(detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** FloatingLabelField.tsx, including its 64dp input and 18sp value. */
@Composable
internal fun Field(
    label: String,
    value: String,
    tag: String,
    onChange: (String) -> Unit,
    type: KeyboardType = KeyboardType.Text,
) {
    val interactions = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val focused by interactions.collectIsFocusedAsState()
    val floating = focused || value.isNotEmpty()
    val ground = webSurface()
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            interactionSource = interactions,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            cursorBrush =
                androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .testTag(tag)
                    .background(ground, MaterialTheme.shapes.large)
                    .border(
                        if (focused) 3.dp else 1.dp,
                        if (focused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        MaterialTheme.shapes.large,
                    )
                    .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    inner()
                }
            },
        )
        Text(
            label,
            style =
                if (floating) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                if (floating)
                    Modifier.padding(start = 16.dp)
                        .offset(y = (-10).dp)
                        .background(ground)
                        .padding(horizontal = 6.dp)
                        .clearAndSetSemantics {}
                else
                    Modifier.align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .clearAndSetSemantics {},
        )
    }
}

@Composable
internal fun SelectionField(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    calendar: Boolean = false,
    action: () -> Unit,
) {
    val ground = webSurface()
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag(tag)
                .background(ground, MaterialTheme.shapes.large)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                .clickable(enabled = enabled, role = Role.Button, onClick = action)
                .semantics {
                    contentDescription = label
                    stateDescription = value
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                value.ifEmpty { label },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                modifier = Modifier.weight(1f),
            )
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(Modifier.size(18.dp).clearAndSetSemantics {}) {
                val path = androidx.compose.ui.graphics.Path()
                if (calendar) {
                    drawRoundRect(
                        ink,
                        androidx.compose.ui.geometry.Offset(size.width * .1f, size.height * .2f),
                        androidx.compose.ui.geometry.Size(size.width * .8f, size.height * .7f),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                    )
                    drawLine(
                        ink,
                        androidx.compose.ui.geometry.Offset(size.width * .1f, size.height * .4f),
                        androidx.compose.ui.geometry.Offset(size.width * .9f, size.height * .4f),
                        1.dp.toPx(),
                    )
                    drawLine(
                        ink,
                        androidx.compose.ui.geometry.Offset(size.width * .3f, size.height * .05f),
                        androidx.compose.ui.geometry.Offset(size.width * .3f, size.height * .3f),
                        1.dp.toPx(),
                    )
                    drawLine(
                        ink,
                        androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .05f),
                        androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .3f),
                        1.dp.toPx(),
                    )
                } else {
                    path.moveTo(size.width * .25f, size.height * .4f)
                    path.lineTo(size.width * .5f, size.height * .65f)
                    path.lineTo(size.width * .75f, size.height * .4f)
                    drawPath(
                        path,
                        ink,
                        style =
                            androidx.compose.ui.graphics.drawscope.Stroke(
                                1.5.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            ),
                    )
                }
            }
        }
        if (value.isNotEmpty())
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(start = 16.dp)
                        .offset(y = (-10).dp)
                        .background(ground)
                        .padding(horizontal = 6.dp)
                        .clearAndSetSemantics {},
            )
    }
}

/** Default web grounds; an integrator's explicit surface still wins. */
@Composable
internal fun webSurface(): Color =
    if (LocalPalette.current == Palette.Light || LocalPalette.current == Palette.Dark)
        MaterialTheme.colorScheme.background
    else MaterialTheme.colorScheme.surface

/** Entry.tsx's 104dp halo, 88dp tile and 42dp shield/check. */
@Composable
internal fun IdentityHero() {
    val accent = MaterialTheme.colorScheme.primary
    val radiusScale = LocalAppearance.current.cornerRadius.coerceAtLeast(0f) / 16f
    Box(
        Modifier.size(104.dp)
            .background(
                if (LocalPalette.current == Palette.Light || LocalPalette.current == Palette.Dark)
                    Color(0xFFFFF0EA)
                else accent.copy(alpha = .13f),
                androidx.compose.foundation.shape.RoundedCornerShape((34f * radiusScale).dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(88.dp)
                .shadow(
                    10.dp,
                    androidx.compose.foundation.shape.RoundedCornerShape((28f * radiusScale).dp),
                    ambientColor = accent,
                    spotColor = accent,
                )
                .background(
                    accent,
                    androidx.compose.foundation.shape.RoundedCornerShape((28f * radiusScale).dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(42.dp).clearAndSetSemantics {}) {
                val sx = size.width / 24f
                val sy = size.height / 24f
                val shield =
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(12 * sx, 2.5f * sy)
                        lineTo(4 * sx, 5.5f * sy)
                        lineTo(4 * sx, 10.9f * sy)
                        cubicTo(4 * sx, 15.5f * sy, 7.1f * sx, 19.8f * sy, 12 * sx, 21 * sy)
                        cubicTo(16.9f * sx, 19.8f * sy, 20 * sx, 15.5f * sy, 20 * sx, 10.9f * sy)
                        lineTo(20 * sx, 5.5f * sy)
                        close()
                    }
                drawPath(
                    shield,
                    Color.White,
                    style =
                        androidx.compose.ui.graphics.drawscope.Stroke(
                            1.6f * sx,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                )
                val check =
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(9 * sx, 11.8f * sy)
                        lineTo(11.1f * sx, 13.9f * sy)
                        lineTo(15 * sx, 10 * sy)
                    }
                drawPath(
                    check,
                    Color.White,
                    style =
                        androidx.compose.ui.graphics.drawscope.Stroke(
                            1.7f * sx,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                )
            }
        }
    }
}

/** One horizontal document row; only its label column grows at large font scales. */
@Composable
internal fun DocumentRow(
    number: Int,
    label: String,
    complete: Boolean,
    status: String,
    replacement: String? = null,
    enabled: Boolean = true,
    onReplace: () -> Unit = {},
) {
    val success = Color(LocalPalette.current.success)
    Panel(
        color =
            if (LocalPalette.current == Palette.Light) webSurface()
            else MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(44.dp)
                    .background(
                        if (complete) success.copy(alpha = .12f)
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (complete) "✓" else number.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (complete) success else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        ),
                )
                if (replacement != null)
                    TextButton(
                        onClick = onReplace,
                        enabled = enabled,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            replacement,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        )
                    }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (complete) success else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 90.dp),
            )
        }
    }
}

@Composable
internal fun BriefPoint(text: String) {
    Panel {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.padding(top = 9.dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Text(
                text,
                style =
                    MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun Support(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ReviewRows(items: List<cc.identified.sdk.core.ChecklistItem>) {
    // Hosted Review uses one divided card, unlike the separate numbered checklist cards.
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        docLabel(item.key, LocalCopy.current),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        (if (item.complete) "✓  " else "") +
                            LocalCopy.current.text(
                                if (item.complete) "review.captured" else "review.missing"
                            ),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                        color =
                            Color(
                                if (item.complete) LocalPalette.current.success
                                else LocalPalette.current.error
                            ),
                        modifier = Modifier.widthIn(max = 110.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChecklistSkeleton(count: Int) {
    val loading = LocalCopy.current.text("checklist.loading")
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { contentDescription = loading },
    ) {
        repeat(count) {
            Panel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier.size(44.dp)
                            .background(MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Box(
                        Modifier.weight(1f)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Box(
                        Modifier.width(56.dp)
                            .height(12.dp)
                            .background(MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
internal fun TintedPanel(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Panel(
        color =
            if (palette == Palette.Light) Color(0xFFF3F0FF) else MaterialTheme.colorScheme.surface,
        borderColor =
            if (palette == Palette.Light) Color(0xFFDCD6FF)
            else if (palette == Palette.Dark) Color(0xFFDCD6FF).copy(alpha = .3f)
            else MaterialTheme.colorScheme.outline,
        content = content,
    )
}

@Composable
internal fun StatusCard(
    title: String,
    detail: String,
    badge: String,
    note: String? = null,
    active: Boolean = false,
    tone: Color = Color(LocalPalette.current.success),
) {
    val card: @Composable ColumnScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(36.dp)
                    .background(tone.copy(alpha = .12f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(10.dp).background(tone, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        ),
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                badge.uppercase(java.util.Locale.getDefault()),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    ),
                color = tone,
                modifier =
                    Modifier.widthIn(max = 100.dp)
                        .background(tone.copy(alpha = .1f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (active)
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = tone,
                trackColor = tone.copy(alpha = .12f),
            )
        if (note != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (active) TintedPanel(content = card) else Panel(content = card)
}

@Composable
internal fun StatusGlyph(tone: StatusTone, color: Color) {
    Canvas(Modifier.size(44.dp).clearAndSetSemantics {}) {
        val unit = size.width / 24f
        val path =
            androidx.compose.ui.graphics.Path().apply {
                when (tone) {
                    StatusTone.SUCCESS -> {
                        moveTo(5 * unit, 12 * unit)
                        lineTo(10 * unit, 17 * unit)
                        lineTo(19 * unit, 7 * unit)
                    }
                    StatusTone.FAILURE -> {
                        moveTo(17 * unit, 7 * unit)
                        lineTo(7 * unit, 17 * unit)
                        moveTo(7 * unit, 7 * unit)
                        lineTo(17 * unit, 17 * unit)
                    }
                    StatusTone.ATTENTION -> {
                        moveTo(12 * unit, 8 * unit)
                        lineTo(12 * unit, 13 * unit)
                        moveTo(12 * unit, 16 * unit)
                        lineTo(12.01f * unit, 16 * unit)
                    }
                    StatusTone.WAITING -> {
                        moveTo(12 * unit, 7 * unit)
                        lineTo(12 * unit, 12 * unit)
                        lineTo(15 * unit, 14 * unit)
                    }
                }
            }
        drawPath(
            path,
            color,
            style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                    2 * unit,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
        )
    }
}
