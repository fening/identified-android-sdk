package cc.identified.sdk.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CapturedStrip(captures: AcceptedCaptures) {
    val items = captures.items
    if (items.isEmpty()) return
    val c = LocalCopy.current
    val shape =
        RoundedCornerShape((LocalAppearance.current.cornerRadius.coerceAtLeast(0f) * .375f).dp)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("identified-captured-strip")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            c.text("captured.title"),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                val label = docLabel(item.docType, c)
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val image =
                        remember(item) {
                            item.jpeg?.let {
                                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }
                    if (item.pdf) {
                        Column(
                            Modifier.size(96.dp, 64.dp)
                                .background(MaterialTheme.colorScheme.surface, shape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = c.text("captured.pdf", label)
                                    role = Role.Image
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            val ink = MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(Modifier.size(22.dp).clearAndSetSemantics {}) {
                                val unit = size.width / 24f
                                val page =
                                    androidx.compose.ui.graphics.Path().apply {
                                        moveTo(6 * unit, 2.75f * unit)
                                        lineTo(14 * unit, 2.75f * unit)
                                        lineTo(18 * unit, 6.75f * unit)
                                        lineTo(18 * unit, 21.25f * unit)
                                        lineTo(6 * unit, 21.25f * unit)
                                        close()
                                        moveTo(14 * unit, 2.75f * unit)
                                        lineTo(14 * unit, 6.75f * unit)
                                        lineTo(18 * unit, 6.75f * unit)
                                    }
                                drawPath(
                                    page,
                                    ink,
                                    style =
                                        androidx.compose.ui.graphics.drawscope.Stroke(
                                            1.5f * unit,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                        ),
                                )
                            }
                            Text(
                                "PDF",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    } else if (image != null)
                        Image(
                            image,
                            label,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier.size(96.dp, 64.dp)
                                    .clip(shape)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, shape),
                        )
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
