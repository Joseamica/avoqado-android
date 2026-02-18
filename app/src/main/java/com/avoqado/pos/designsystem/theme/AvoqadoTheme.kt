package com.avoqado.pos.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = AvoqadoPrimaryLight,
    onPrimary = AvoqadoOnPrimaryLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = Error,
    onError = AvoqadoOnPrimaryLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = AvoqadoPrimaryDark,
    onPrimary = AvoqadoOnPrimaryDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = Error,
    onError = AvoqadoOnPrimaryLight,
)

@Composable
fun AvoqadoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Let enableEdgeToEdge() handle transparent bars
            // Only control icon appearance (light/dark)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp
    val isCompactScreen = screenWidth < 600

    val dimensions = if (isCompactScreen) {
        AvoqadoDimensions(
            buttonSmall = 32.dp,
            buttonMedium = 40.dp,
            buttonLarge = 44.dp,
            iconSmall = 14.dp,
            iconMedium = 18.dp,
            iconLarge = 22.dp,
            iconXLarge = 36.dp,
            touchTarget = 40.dp,
        )
    } else {
        AvoqadoDimensions()
    }

    CompositionLocalProvider(
        LocalSpacing provides AvoqadoSpacing(),
        LocalCornerRadius provides AvoqadoCornerRadius(),
        LocalDimensions provides dimensions,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AvoqadoTypography,
            shapes = AvoqadoShapes,
            content = content,
        )
    }
}

// Convenience accessors
object AvoqadoTheme {
    val spacing: AvoqadoSpacing
        @Composable get() = LocalSpacing.current

    val cornerRadius: AvoqadoCornerRadius
        @Composable get() = LocalCornerRadius.current

    val dimensions: AvoqadoDimensions
        @Composable get() = LocalDimensions.current
}
