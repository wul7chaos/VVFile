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

/** 强调色预设；索引持久化在设置里，0 为默认靛蓝。 */
val AccentPresets: List<Color> = listOf(
    Color(0xFF5B5BF0),
    Color(0xFF0E9F6E),
    Color(0xFF2F80ED),
    Color(0xFFE05A2B),
    Color(0xFFD6409F),
    Color(0xFFB08900),
)

@Composable
fun VVFileTheme(
    darkTheme: Boolean = true,
    pureBlack: Boolean = false,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = when {
        !darkTheme -> LightColors
        pureBlack -> DarkColors.copy(
            background = Color.Black,
            surface = Color(0xFF070707),
            surfaceVariant = Color(0xFF101010),
        )
        else -> DarkColors
    }
    val scheme = if (accent == null) {
        base
    } else {
        base.copy(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.28f),
            onPrimaryContainer = if (darkTheme) Color.White else Color(0xFF111111),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = VVTypography,
        shapes = RectShapes,
        content = content,
    )
}

