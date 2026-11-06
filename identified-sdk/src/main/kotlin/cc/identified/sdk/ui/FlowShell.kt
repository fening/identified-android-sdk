package cc.identified.sdk.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

/** Shared hosted-flow chrome. The action slot measures to zero when it emits no children. */
@Composable
internal fun Shell(
    model: FlowViewModel,
    state: FlowState,
    title: String? = null,
    subtitle: String? = null,
    step: FlowScreen? = null,
    lockup: Boolean = true,
    compactTitle: Boolean = false,
    centeredContent: Boolean = false,
    titleCopy: String? = null,
    subtitleCopy: String? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalCopy.current
    val headingCopy = titleCopy ?: title?.let { c.text(it) }
    val supportingCopy = subtitleCopy ?: subtitle?.let { c.text(it) }
    Column(
        Modifier.fillMaxSize()
            .imePadding()
            .testTag(
                "identified-screen-${step?.name?.lowercase() ?: state.route.javaClass.simpleName.lowercase()}"
            )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (
                    state.path.isNotEmpty() &&
                        state.route != Route.Step(Destination.Checklist) &&
                        state.route != Route.Step(Destination.Status)
                ) {
                    NavigationIcon(c.text("camera.back"), false, !state.busy, action = model::back)
                }
                if (lockup && LocalAppearance.current.showsLockup) BrandLockup()
                Spacer(Modifier.weight(1f))
                NavigationIcon(c.text("flow.close"), true, true, "identified-close", model::close)
            }
            if (step != null && state.session != null) {
                val position =
                    FlowRules.position(
                        step,
                        state.session.requiredDocuments,
                        FlowRules.collectsName(state.session),
                    )
                val accent = MaterialTheme.colorScheme.primary
                val end =
                    if (LocalPalette.current.accent == cc.identified.sdk.Palette.Light.accent)
                        Color(0xFFF6BB64)
                    else accent
                Column(
                    Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) {
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                position.step.toFloat(),
                                0f..position.total.toFloat(),
                            )
                    }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(position.total) { i ->
                            val from = i.toFloat() / position.total
                            val to = (i + 1f) / position.total
                            Box(
                                Modifier.weight(1f)
                                    .height(6.dp)
                                    .background(
                                        if (i < position.step)
                                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                listOf(
                                                    androidx.compose.ui.graphics.lerp(
                                                        accent,
                                                        end,
                                                        from,
                                                    ),
                                                    androidx.compose.ui.graphics.lerp(
                                                        accent,
                                                        end,
                                                        to,
                                                    ),
                                                )
                                            )
                                        else
                                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.outline,
                                                    MaterialTheme.colorScheme.outline,
                                                )
                                            ),
                                        CircleShape,
                                    )
                            )
                        }
                    }
                    Text(
                        c.text("flow.step", position.step, position.total)
                            .uppercase(java.util.Locale.getDefault()),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                letterSpacing = 1.32.sp,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 20.dp, vertical = if (compactTitle) 16.dp else 28.dp),
                verticalArrangement = if (centeredContent) Arrangement.Center else Arrangement.Top,
            ) {
                headingCopy?.let {
                    Text(
                        it,
                        style =
                            if (compactTitle) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                supportingCopy?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (headingCopy == null) 0.dp else 10.dp),
                    )
                }
                Column(
                    Modifier.fillMaxWidth()
                        .padding(
                            top =
                                if (headingCopy != null || supportingCopy != null) {
                                    if (compactTitle) 16.dp else 28.dp
                                } else 0.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content()
                    ErrorMessage(state)
                }
            }
        }
        val footerBorder = MaterialTheme.colorScheme.outline
        // Padding belongs inside each measured action area so an empty slot has no border or blank
        // band.
        androidx.compose.ui.layout.Layout(
            content = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = footer,
                )
            },
            modifier =
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = .85f))
                    .drawWithContent {
                        drawContent()
                        if (size.height > 0f)
                            drawLine(
                                footerBorder,
                                androidx.compose.ui.geometry.Offset.Zero,
                                androidx.compose.ui.geometry.Offset(size.width, 0f),
                                1.dp.toPx(),
                            )
                    },
        ) { measurables, constraints ->
            val horizontal = 20.dp.roundToPx()
            val top = 12.dp.roundToPx()
            val bottom = 16.dp.roundToPx()
            val child =
                measurables
                    .single()
                    .measure(
                        constraints.copy(
                            minWidth = 0,
                            minHeight = 0,
                            maxWidth = (constraints.maxWidth - horizontal * 2).coerceAtLeast(0),
                            maxHeight = (constraints.maxHeight / 2 - top - bottom).coerceAtLeast(0),
                        )
                    )
            val height = if (child.height == 0) 0 else child.height + top + bottom
            layout(constraints.maxWidth, height) {
                child.placeRelative(horizontal, if (height == 0) 0 else top)
            }
        }
    }
}

@Composable
internal fun NavigationIcon(
    label: String,
    close: Boolean,
    enabled: Boolean,
    tag: String = "",
    action: () -> Unit,
) {
    // Only 24dp participates in header layout. The real 44dp button extends downward
    // into non-interactive header spacing, rather than above the OS-safe content edge.
    // No clipping modifier is applied; the glyph stays centered in the 24dp visual row.
    androidx.compose.ui.layout.Layout(
        content = {
            IconButton(
                onClick = action,
                enabled = enabled,
                modifier =
                    Modifier.size(44.dp).testTag(tag).semantics { contentDescription = label },
            ) {
                val ink = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(20.dp).offset(y = (-10).dp).clearAndSetSemantics {}) {
                    val stroke = 1.8.dp.toPx()
                    if (close) {
                        drawLine(
                            ink,
                            androidx.compose.ui.geometry.Offset(
                                size.width * .25f,
                                size.height * .25f,
                            ),
                            androidx.compose.ui.geometry.Offset(
                                size.width * .75f,
                                size.height * .75f,
                            ),
                            stroke,
                            androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        drawLine(
                            ink,
                            androidx.compose.ui.geometry.Offset(
                                size.width * .75f,
                                size.height * .25f,
                            ),
                            androidx.compose.ui.geometry.Offset(
                                size.width * .25f,
                                size.height * .75f,
                            ),
                            stroke,
                            androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    } else {
                        val path =
                            androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * .625f, size.height * .25f)
                                lineTo(size.width * .375f, size.height * .5f)
                                lineTo(size.width * .625f, size.height * .75f)
                            }
                        drawPath(
                            path,
                            ink,
                            style =
                                androidx.compose.ui.graphics.drawscope.Stroke(
                                    stroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                ),
                        )
                    }
                }
            }
        }
    ) { measurables, _ ->
        val target = 44.dp.roundToPx()
        val child =
            measurables.single().measure(androidx.compose.ui.unit.Constraints.fixed(target, target))
        layout(target, 24.dp.roundToPx()) { child.placeRelative(0, 0) }
    }
}

@Composable
internal fun BrandLockup() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        Image(
            androidx.compose.ui.res.painterResource(
                cc.identified.sdk.R.drawable.identified_brand_mark
            ),
            null,
            Modifier.size(16.dp),
        )
        Text(
            "identified.cc",
            style =
                MaterialTheme.typography.titleSmall.copy(
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "↗",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
