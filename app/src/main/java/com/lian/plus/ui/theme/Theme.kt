package com.lian.plus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Brand palette, sampled from the app artwork.
 *
 * Lian+ is dark-only on purpose. The wordmark, every gradient and the whole
 * screen design assume a near-black backdrop; a light variant would be a
 * second design, not a colour swap, so [LianTheme] ignores the system setting.
 */
object Lian {
    val Magenta = Color(0xFFF178FC)
    val Purple = Color(0xFF8B5CF6)
    val Blue = Color(0xFF3B82F6)
    val Cyan = Color(0xFF22E3FC)

    val Background = Color(0xFF080A16)
    val Surface = Color(0xFF12152B)
    val SurfaceRaised = Color(0xFF1A1E38)
    val Outline = Color(0xFF272C4C)
    val TextPrimary = Color(0xFFF2F3FA)
    val TextMuted = Color(0xFF9AA0BF)
    val Danger = Color(0xFFFF6B7A)
    val Success = Color(0xFF3DDC97)

    /** The left-to-right ramp used by the wordmark and every primary action. */
    val gradient = Brush.linearGradient(listOf(Magenta, Purple, Blue, Cyan))

    /** Same ramp, vertical — for tall surfaces where the horizontal one skews. */
    val gradientVertical = Brush.verticalGradient(listOf(Magenta, Purple, Blue, Cyan))

    /** A restrained two-stop version for card borders and accents. */
    val accent = Brush.linearGradient(listOf(Purple, Cyan))

    /** The violet bloom that sits behind the top of most screens. */
    val glow = Brush.verticalGradient(
        listOf(Color(0xFF221B4A), Color(0xFF0D1024), Background),
    )
}

private val LianColors = darkColorScheme(
    primary = Lian.Purple,
    onPrimary = Color.White,
    primaryContainer = Lian.SurfaceRaised,
    onPrimaryContainer = Lian.TextPrimary,
    secondary = Lian.Cyan,
    onSecondary = Color(0xFF00202A),
    secondaryContainer = Color(0xFF14304A),
    onSecondaryContainer = Lian.Cyan,
    tertiary = Lian.Magenta,
    background = Lian.Background,
    onBackground = Lian.TextPrimary,
    surface = Lian.Surface,
    onSurface = Lian.TextPrimary,
    surfaceVariant = Lian.SurfaceRaised,
    onSurfaceVariant = Lian.TextMuted,
    outline = Lian.Outline,
    outlineVariant = Lian.Outline,
    error = Lian.Danger,
    onError = Color(0xFF3A0008),
    errorContainer = Color(0xFF3A1620),
    onErrorContainer = Lian.Danger,
)

private val LianTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        ),
    )
}

@Composable
fun LianTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LianColors,
        typography = LianTypography,
        content = content,
    )
}
