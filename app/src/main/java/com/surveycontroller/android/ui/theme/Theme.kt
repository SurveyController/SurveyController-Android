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

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7BBFF),
    onPrimary = Color(0xFF131D3A),
    primaryContainer = Color(0xFF303952),
    onPrimaryContainer = Color(0xFFC5D1FF),
    secondary = Color(0xFFADB7CA),
    onSecondary = Color(0xFF1B2434),
    secondaryContainer = Color(0xFF303747),
    onSecondaryContainer = Color(0xFFDAE1F0),
    tertiary = Color(0xFFCBC1DA),
    tertiaryContainer = Color(0xFF3A3346),
    onTertiaryContainer = Color(0xFFE9DFF4),
    background = Color(0xFF15171C),
    onBackground = Color(0xFFEDF0F6),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFEDF0F6),
    surfaceTint = Color(0xFFA7BBFF),
    surfaceVariant = Color(0xFF2A2D35),
    onSurfaceVariant = Color(0xFFADB7CA),
    surfaceContainerLowest = Color(0xFF181A20),
    surfaceContainerLow = Color(0xFF202329),
    surfaceContainer = Color(0xFF232630),
    surfaceContainerHigh = Color(0xFF2A2D35),
    surfaceContainerHighest = Color(0xFF303440),
    outline = Color(0xFF8B95A9),
    outlineVariant = Color(0xFF383D49),
    error = Color(0xFFFFA5B3),
    onError = Color(0xFF441722),
    errorContainer = Color(0xFF46262E),
    onErrorContainer = Color(0xFFFFCBD4),
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
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
