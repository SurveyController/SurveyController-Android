package com.surveycontroller.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D50CB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EEFC),
    onPrimaryContainer = Color(0xFF233E9E),
    secondary = Color(0xFF545E70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF4),
    onSecondaryContainer = Color(0xFF263144),
    tertiary = Color(0xFF5D566D),
    tertiaryContainer = Color(0xFFF0EBF5),
    onTertiaryContainer = Color(0xFF30283D),
    background = Color(0xFFF3F4F7),
    onBackground = Color(0xFF1B2230),
    surface = Color(0xFFF3F4F7),
    onSurface = Color(0xFF1B2230),
    surfaceTint = Color(0xFF2D50CB),
    surfaceVariant = Color(0xFFEBEDF2),
    onSurfaceVariant = Color(0xFF545E70),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = Color(0xFFF0F2F6),
    surfaceContainerHigh = Color(0xFFE9EDF4),
    surfaceContainerHighest = Color(0xFFE5E8F0),
    outline = Color(0xFF778193),
    outlineVariant = Color(0xFFDBE0E9),
    error = Color(0xFFAD3349),
    onError = Color.White,
    errorContainer = Color(0xFFFBE8ED),
    onErrorContainer = Color(0xFF7F2638),
)

internal object ChromeDarkColors {
    val canvas = Color(0xFF202124)
    val toolbar = Color(0xFF292A2D)
    val panel = toolbar
    val popup = Color(0xFF2D2E31)
    val emblem = Color(0xFF303134)
    val outline = Color(0xFF454649)
    val separator = Color(0xFF3E3F42)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF9AA0A6)
    val emblemInk = Color(0xFFBDC1C6)
    val accent = Color(0xFF8AB4F8)
    val selection = Color(0xFF353B45)
    val error = Color(0xFFF28B82)
    val success = Color(0xFF81C995)
}

internal val LocalChromeDarkTheme = staticCompositionLocalOf { false }

private val DarkColors = darkColorScheme(
    primary = ChromeDarkColors.accent,
    onPrimary = ChromeDarkColors.canvas,
    primaryContainer = ChromeDarkColors.selection,
    onPrimaryContainer = ChromeDarkColors.accent,
    secondary = ChromeDarkColors.muted,
    onSecondary = ChromeDarkColors.canvas,
    secondaryContainer = ChromeDarkColors.selection,
    onSecondaryContainer = ChromeDarkColors.accent,
    tertiary = ChromeDarkColors.muted,
    onTertiary = ChromeDarkColors.canvas,
    tertiaryContainer = ChromeDarkColors.toolbar,
    onTertiaryContainer = ChromeDarkColors.text,
    background = ChromeDarkColors.canvas,
    onBackground = ChromeDarkColors.text,
    surface = ChromeDarkColors.canvas,
    onSurface = ChromeDarkColors.text,
    surfaceTint = Color.Transparent,
    surfaceVariant = ChromeDarkColors.emblem,
    onSurfaceVariant = ChromeDarkColors.muted,
    surfaceContainerLowest = ChromeDarkColors.canvas,
    surfaceContainerLow = ChromeDarkColors.panel,
    surfaceContainer = ChromeDarkColors.panel,
    surfaceContainerHigh = ChromeDarkColors.popup,
    surfaceContainerHighest = ChromeDarkColors.popup,
    outline = ChromeDarkColors.outline,
    outlineVariant = ChromeDarkColors.separator,
    inverseSurface = ChromeDarkColors.text,
    inverseOnSurface = ChromeDarkColors.canvas,
    inversePrimary = Color(0xFF185ABC),
    error = ChromeDarkColors.error,
    onError = Color(0xFF401A18),
    errorContainer = Color(0xFF502927),
    onErrorContainer = Color(0xFFFAD2CF),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun SurveyControllerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamicColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalChromeDarkTheme provides (darkTheme && !useDynamicColor)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
