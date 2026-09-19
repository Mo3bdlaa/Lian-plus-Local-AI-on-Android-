package com.lian.plus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF00696D),
    secondaryContainer = Color(0xFF6FF6FC),
    tertiary = Color(0xFF7B4E7F),
    error = Color(0xFFBA1A1A),
    surfaceVariant = Color(0xFFE2E1EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF041C90),
    primaryContainer = Color(0xFF2334A6),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF4CD9E0),
    secondaryContainer = Color(0xFF004F52),
    tertiary = Color(0xFFECB4EC),
    error = Color(0xFFFFB4AB),
    surfaceVariant = Color(0xFF45464F),
)

@Composable
fun LianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Material You on Android 12+, so the app picks up the user's wallpaper
    // colours instead of imposing its own.
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
