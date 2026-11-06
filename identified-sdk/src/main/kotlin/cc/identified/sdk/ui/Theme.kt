package cc.identified.sdk.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.*
import cc.identified.sdk.R

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalPalette = staticCompositionLocalOf { Appearance().light }
internal val LocalCopy = staticCompositionLocalOf<Copy> { error("No copy context") }

@Composable
internal fun font(name: String?): FontFamily {
    val context = LocalContext.current
    return remember(context, name) {
        if (name == null)
            FontFamily(
                Font(R.font.identified_dm_sans_regular, FontWeight.Normal),
                Font(R.font.identified_dm_sans_medium, FontWeight.Medium),
                Font(R.font.identified_dm_sans_semibold, FontWeight.SemiBold),
                Font(R.font.identified_dm_sans_bold, FontWeight.Bold),
            )
        else {
            val face =
                if (name.contains('/') || name.endsWith(".ttf") || name.endsWith(".otf"))
                    runCatching { Typeface.createFromAsset(context.assets, name) }
                        .getOrElse { Typeface.create(name, Typeface.NORMAL) }
                else Typeface.create(name, Typeface.NORMAL)
            FontFamily(face)
        }
    }
}

@Composable
internal fun IdentifiedTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark =
        when (appearance.colorScheme) {
            ColorSchemePreference.SYSTEM -> isSystemInDarkTheme()
            ColorSchemePreference.DARK -> true
            ColorSchemePreference.LIGHT -> false
        }
    val p = if (dark) appearance.dark else appearance.light
    val colors =
        (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = Color(p.accent),
            onPrimary = Color(p.onAccent),
            primaryContainer = Color(p.surface),
            onPrimaryContainer = Color(p.textPrimary),
            secondary = Color(p.accent),
            onSecondary = Color(p.onAccent),
            secondaryContainer = Color(p.surface),
            onSecondaryContainer = Color(p.textPrimary),
            tertiary = Color(p.accent),
            onTertiary = Color(p.onAccent),
            background = Color(p.background),
            onBackground = Color(p.textPrimary),
            surface = Color(p.surface),
            surfaceVariant = Color(p.surface),
            surfaceContainer = Color(p.surface),
            surfaceContainerLow = Color(p.surface),
            surfaceContainerLowest = Color(p.background),
            surfaceContainerHigh = Color(p.surface),
            surfaceContainerHighest = Color(p.surface),
            surfaceBright = Color(p.surface),
            surfaceDim = Color(p.background),
            onSurface = Color(p.textPrimary),
            onSurfaceVariant = Color(p.textSecondary),
            outline = Color(p.border),
            error = Color(p.error),
        )
    val body = font(appearance.fontFamily)
    val heading =
        if (appearance.headingFontFamily == null && appearance.fontFamily == null)
            FontFamily(
                Font(R.font.identified_outfit_medium, FontWeight.Medium),
                Font(R.font.identified_outfit_semibold, FontWeight.SemiBold),
                Font(R.font.identified_outfit_bold, FontWeight.Bold),
            )
        else font(appearance.headingFontFamily ?: appearance.fontFamily)
    val defaults = Typography()
    val radius = RoundedCornerShape(appearance.cornerRadius.coerceAtLeast(0f).dp)
    CompositionLocalProvider(LocalAppearance provides appearance, LocalPalette provides p) {
        MaterialTheme(
            colorScheme = colors,
            typography =
                Typography(
                    displayLarge = defaults.displayLarge.copy(fontFamily = heading),
                    displayMedium = defaults.displayMedium.copy(fontFamily = heading),
                    displaySmall = defaults.displaySmall.copy(fontFamily = heading),
                    headlineLarge = defaults.headlineLarge.copy(fontFamily = heading),
                    headlineMedium = defaults.headlineMedium.copy(fontFamily = heading),
                    headlineSmall = defaults.headlineSmall.copy(fontFamily = heading),
                    bodyLarge = TextStyle(fontFamily = body, fontSize = 16.sp, lineHeight = 26.sp),
                    bodyMedium = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 24.sp),
                    bodySmall = TextStyle(fontFamily = body, fontSize = 13.sp, lineHeight = 21.sp),
                    labelLarge =
                        TextStyle(
                            fontFamily = body,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    labelMedium = defaults.labelMedium.copy(fontFamily = body),
                    labelSmall = defaults.labelSmall.copy(fontFamily = body),
                    titleSmall = defaults.titleSmall.copy(fontFamily = heading),
                    titleLarge =
                        TextStyle(
                            fontFamily = heading,
                            fontSize = 30.sp,
                            lineHeight = 35.sp,
                            letterSpacing = (-0.6).sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    titleMedium =
                        TextStyle(
                            fontFamily = heading,
                            fontSize = 22.sp,
                            lineHeight = 27.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                ),
            shapes = Shapes(small = radius, medium = radius, large = radius),
            content = content,
        )
    }
}
