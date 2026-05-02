package com.callx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary          = BrandPrimary,
    onPrimary        = TextOnPrimary,
    primaryContainer = Color(0xFFE8E8FE),
    onPrimaryContainer = BrandPrimaryDark,
    secondary        = BrandAccent,
    onSecondary      = TextOnPrimary,
    secondaryContainer = Color(0xFFD1FAF0),
    onSecondaryContainer = Color(0xFF006B55),
    tertiary         = BrandGradientEnd,
    onTertiary       = TextOnPrimary,
    background       = SurfaceBg,
    onBackground     = TextPrimary,
    surface          = SurfaceCard,
    onSurface        = TextPrimary,
    surfaceVariant   = SurfaceInput,
    onSurfaceVariant = TextSecondary,
    outline          = Divider,
    error            = ActionDanger,
    onError          = TextOnPrimary
)

private val DarkColors = darkColorScheme(
    primary          = Color(0xFF8080FF),
    onPrimary        = DarkBackground,
    primaryContainer = Color(0xFF3D3D99),
    onPrimaryContainer = Color(0xFFCCCCFF),
    secondary        = BrandAccent,
    onSecondary      = DarkBackground,
    secondaryContainer = Color(0xFF003D30),
    onSecondaryContainer = Color(0xFF70EFCE),
    background       = DarkBackground,
    onBackground     = Color(0xFFE2E8F0),
    surface          = DarkSurface,
    onSurface        = Color(0xFFE2E8F0),
    surfaceVariant   = DarkSurfaceVar,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline          = Color(0xFF334155),
    error            = Color(0xFFFF6B6B),
    onError          = DarkBackground
)

@Composable
fun CallXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CallXTypography,
        content = content
    )
}
