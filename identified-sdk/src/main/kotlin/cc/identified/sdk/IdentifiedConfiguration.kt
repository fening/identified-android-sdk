package cc.identified.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Only pass the short-lived session token created by your backend. Never an API key. */
@Parcelize
public data class IdentifiedConfiguration(
    public val sessionToken: String,
    public val baseUrl: String = "https://api.identified.cc",
    public val appearance: Appearance = Appearance(),
) : Parcelable

@Parcelize
public data class Palette(
    public val accent: Long,
    public val onAccent: Long,
    public val background: Long,
    public val surface: Long,
    public val textPrimary: Long,
    public val textSecondary: Long,
    public val border: Long,
    public val error: Long,
    public val success: Long,
    public val warning: Long,
) : Parcelable {
    public companion object {
        public val Light: Palette =
            Palette(
                0xFFEF6D5B,
                0xFF172A30,
                0xFFFBFAF6,
                0xFFFFFFFF,
                0xFF193A36,
                0xFF56716B,
                0xFFE2E8DF,
                0xFFDC2626,
                0xFF237F74,
                0xFFD97706,
            )
        public val Dark: Palette =
            Palette(
                0xFFEF6D5B,
                0xFF172A30,
                0xFF102A28,
                0xFF193A36,
                0xFFFBFAF6,
                0xFF7A918C,
                0xFF193A36,
                0xFFF87171,
                0xFF40A99A,
                0xFFFBBF24,
            )
    }
}

public enum class ColorSchemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

/** ARGB colours, Android font-family name or font asset path, dimensions in dp. */
@Parcelize
public data class Appearance(
    public val light: Palette = Palette.Light,
    public val dark: Palette = Palette.Dark,
    public val fontFamily: String? = null,
    public val headingFontFamily: String? = null,
    public val cornerRadius: Float = 16f,
    public val showsLockup: Boolean = true,
    public val colorScheme: ColorSchemePreference = ColorSchemePreference.SYSTEM,
) : Parcelable
