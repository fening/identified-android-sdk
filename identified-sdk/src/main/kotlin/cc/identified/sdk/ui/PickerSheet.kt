package cc.identified.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.identified.sdk.Palette

@Composable
internal fun pickerSurface(): Color =
    if (LocalPalette.current == Palette.Light) MaterialTheme.colorScheme.background
    else MaterialTheme.colorScheme.surface

/** Sheet.tsx's elevated, rounded sheet, with the host's palette and radius overrides. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheet(
    label: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val radius = (LocalAppearance.current.cornerRadius.coerceAtLeast(0f) * 1.5f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = radius, topEnd = radius),
        containerColor = pickerSurface(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor =
            if (dark) Color.Black.copy(alpha = .7f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 16.dp)
                    .size(width = 40.dp, height = 6.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (dark) 1f else .5f
                        ),
                        CircleShape,
                    )
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                .semantics { paneTitle = label },
            content = content,
        )
    }
}
