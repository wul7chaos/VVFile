package com.vvfile.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = VVAccent,
    onPrimary = Color.White,
    primaryContainer = VVAccentSoft,
    onPrimaryContainer = VVTextPrimary,
    secondary = VVMint,
    onSecondary = Color(0xFF06281C),
    background = VVBackground,
    onBackground = VVTextPrimary,
    surface = VVSurface,
    onSurface = VVTextPrimary,
    surfaceVariant = VVSurfaceHigh,
    onSurfaceVariant = VVTextSecondary,
    outline = VVDivider,
    error = VVDanger,
)

private val LightColors = lightColorScheme(
    primary = VVLightAccent,
    onPrimary = Color.White,
    primaryContainer = VVLightAccentSoft,
    onPrimaryContainer = VVLightTextPrimary,
    secondary = Color(0xFF0E9F6E),
    background = VVLightBackground,
    onBackground = VVLightTextPrimary,
    surface = VVLightSurface,
    onSurface = VVLightTextPrimary,
    surfaceVariant = VVLightSurfaceHigh,
    onSurfaceVariant = VVLightTextSecondary,
    outline = VVLightDivider,
    error = VVDanger,
)

private val RectShape = RoundedCornerShape(0.dp)

private val RectShapes = Shapes(
    extraSmall = RectShape,
    small = RectShape,
    medium = RectShape,
    large = RectShape,
    extraLarge = RectShape,
)

@Composable
fun VVFileTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VVTypography,
        shapes = RectShapes,
        content = content,
    )
}

