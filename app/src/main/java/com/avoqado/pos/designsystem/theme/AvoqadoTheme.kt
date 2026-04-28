package com.avoqado.pos.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = AvoqadoPrimaryLight,
    onPrimary = AvoqadoOnPrimaryLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = Error,
    onError = AvoqadoOnPrimaryLight,
    // Neutral surface containers (prevent Material3 pink/purple tint)
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    surfaceBright = SurfaceLight,
    surfaceDim = SurfaceContainerLight,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = OnSurfaceLight,
    tertiaryContainer = SurfaceVariantLight,
    onTertiaryContainer = OnSurfaceLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = AvoqadoPrimaryDark,
    onPrimary = AvoqadoOnPrimaryDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = Error,
    onError = AvoqadoOnPrimaryLight,
    // Neutral surface containers
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    surfaceBright = SurfaceContainerHighDark,
    surfaceDim = SurfaceDark,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = OnSurfaceDark,
    tertiaryContainer = SurfaceVariantDark,
    onTertiaryContainer = OnSurfaceDark,
)

@Composable
fun AvoqadoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    windowSizeClass: WindowSizeClass? = null,
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

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    val smallestScreenWidth = configuration.smallestScreenWidthDp
    val densityDpi = configuration.densityDpi
    val adaptiveSizeClass = remember(windowSizeClass, screenWidth) {
        resolveAdaptiveSizeClass(
            windowSizeClass = windowSizeClass,
            screenWidthDp = screenWidth,
        )
    }
    val dimensions = remember(adaptiveSizeClass, screenWidth, screenHeight, smallestScreenWidth, densityDpi) {
        adaptiveDimensionsFor(
            sizeClass = adaptiveSizeClass,
            screenWidthDp = screenWidth,
            screenHeightDp = screenHeight,
            smallestScreenWidthDp = smallestScreenWidth,
            densityDpi = densityDpi,
        )
    }
    val adaptiveTokens = remember(adaptiveSizeClass, screenWidth, screenHeight, smallestScreenWidth, densityDpi) {
        adaptiveTokensFor(
            sizeClass = adaptiveSizeClass,
            screenWidthDp = screenWidth,
            screenHeightDp = screenHeight,
            smallestScreenWidthDp = smallestScreenWidth,
            densityDpi = densityDpi,
        )
    }
    val typography = remember(adaptiveSizeClass, screenWidth, screenHeight, smallestScreenWidth, densityDpi) {
        adaptiveTypographyFor(
            sizeClass = adaptiveSizeClass,
            screenWidthDp = screenWidth,
            screenHeightDp = screenHeight,
            smallestScreenWidthDp = smallestScreenWidth,
            densityDpi = densityDpi,
        )
    }

    CompositionLocalProvider(
        LocalSpacing provides AvoqadoSpacing(),
        LocalCornerRadius provides AvoqadoCornerRadius(),
        LocalDimensions provides dimensions,
        LocalAdaptiveTokens provides adaptiveTokens,
        // Material3's LocalContentColor defaults to Color.Black when no Surface
        // wraps the content; without this override, Text composables without an
        // explicit color render black on a dark background.
        LocalContentColor provides colorScheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
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

    val adaptive: AvoqadoAdaptiveTokens
        @Composable get() = LocalAdaptiveTokens.current
}
