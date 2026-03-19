package com.carbide.wallet.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CarbideColorScheme = darkColorScheme(
    primary = Lightning,
    onPrimary = Obsidian,
    primaryContainer = LightningDim,
    onPrimaryContainer = LightningBright,
    secondary = Ash,
    onSecondary = TextPrimary,
    background = Obsidian,
    onBackground = TextPrimary,
    surface = Charcoal,
    onSurface = TextPrimary,
    surfaceVariant = Graphite,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceBorder,
    outlineVariant = Slate,
    error = Negative,
    onError = TextPrimary,
    tertiary = Positive,
    onTertiary = Obsidian,
)

@Composable
fun CarbideTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = CarbideColorScheme,
        typography = CarbideTypography,
        content = content,
    )
}
