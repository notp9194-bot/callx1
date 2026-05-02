package com.callx.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = BrandAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF003329),
    tertiary = Color(0xFF8B5CF6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF2E1065),
    error = ErrorColor,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF7F1D1D),
    background = SurfaceBg,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFC4C6DC),
    outlineVariant = Color(0xFFE5E7EB),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1E1E30),
    inverseOnSurface = Color(0xFFE8E9FF),
    inversePrimary = DarkBrandPrimary,
    surfaceTint = BrandPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkBrandPrimary,
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = DarkBrandContainer,
    onPrimaryContainer = BrandPrimaryContainer,
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF003329),
    secondaryContainer = Color(0xFF065F46),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),
    error = Color(0xFFF87171),
    onError = Color(0xFF7F1D1D),
    errorContainer = Color(0xFF991B1B),
    onErrorContainer = Color(0xFFFFE4E6),
    background = DarkSurfaceBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurfaceCard,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = Color(0xFF3D3D60),
    outlineVariant = Color(0xFF2D2D45),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE8E9FF),
    inverseOnSurface = Color(0xFF1A1A2E),
    inversePrimary = BrandPrimary,
    surfaceTint = DarkBrandPrimary
)

@Composable
fun CallXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalView.current.context
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CallXTypography,
        content = content
    )
}
