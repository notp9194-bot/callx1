package com.callx.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary          = BrandPrimary,
    onPrimary        = TextOnPrimary,
    primaryContainer = BrandGradientEnd,
    onPrimaryContainer = TextOnPrimary,
    secondary        = BrandAccent,
    onSecondary      = TextOnPrimary,
    background       = SurfaceBg,
    onBackground     = TextPrimary,
    surface          = SurfaceCard,
    onSurface        = TextPrimary,
    surfaceVariant   = SurfaceInput,
    onSurfaceVariant = TextSecondary,
    error            = ActionDanger,
    outline          = Divider
)

private val DarkColorScheme = darkColorScheme(
    primary          = BrandGradientEnd,
    onPrimary        = TextOnPrimary,
    primaryContainer = BrandPrimaryDark,
    onPrimaryContainer = TextOnPrimary,
    secondary        = BrandAccent,
    onSecondary      = TextOnPrimary,
    background       = TextPrimary,
    onBackground     = SurfaceCard,
    surface          = TextPrimary,
    onSurface        = SurfaceCard,
    error            = ActionDanger,
    outline          = TextMuted
)

@Composable
fun CallXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = CallXTypography,
        content     = content
    )
}
