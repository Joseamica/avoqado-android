package com.avoqado.pos.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import kotlin.math.max
import kotlin.math.min

enum class AvoqadoAdaptiveSizeClass {
    Compact,
    Medium,
    Expanded,
}

@Immutable
data class AvoqadoAdaptiveTokens(
    val sizeClass: AvoqadoAdaptiveSizeClass = AvoqadoAdaptiveSizeClass.Compact,
    val typographyScale: Float = 1f,
    val isPortrait: Boolean = true,
    val isAggressiveCompact: Boolean = false,
    val primaryButtonHorizontalPadding: androidx.compose.ui.unit.Dp = 24.dp,
    val circularIconButtonSize: androidx.compose.ui.unit.Dp = 44.dp,
    val circularIconButtonIconSize: androidx.compose.ui.unit.Dp = 20.dp,
    val circleBackButtonSize: androidx.compose.ui.unit.Dp = 36.dp,
    val circleBackButtonIconSize: androidx.compose.ui.unit.Dp = 16.dp,
    val listSearchFieldHeight: androidx.compose.ui.unit.Dp = 44.dp,
    val listRowVerticalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    val listLeadingVisualSize: androidx.compose.ui.unit.Dp = 44.dp,
    val listLeadingIconSize: androidx.compose.ui.unit.Dp = 20.dp,
    val listPriceColumnWidth: androidx.compose.ui.unit.Dp = 72.dp,
)

val LocalAdaptiveTokens = staticCompositionLocalOf { AvoqadoAdaptiveTokens() }

private val compactDimensions = AvoqadoDimensions(
    buttonSmall = 32.dp,
    buttonMedium = 40.dp,
    buttonLarge = 44.dp,
    iconSmall = 14.dp,
    iconMedium = 18.dp,
    iconLarge = 22.dp,
    iconXLarge = 36.dp,
    touchTarget = 40.dp,
)

private val aggressiveCompactDimensions = AvoqadoDimensions(
    buttonSmall = 32.dp,
    buttonMedium = 39.dp,
    buttonLarge = 43.dp,
    iconSmall = 14.dp,
    iconMedium = 18.dp,
    iconLarge = 21.dp,
    iconXLarge = 35.dp,
    touchTarget = 39.dp,
)

internal fun resolveAdaptiveSizeClass(
    windowSizeClass: WindowSizeClass?,
    screenWidthDp: Int,
): AvoqadoAdaptiveSizeClass {
    val widthClass = windowSizeClass?.widthSizeClass ?: when {
        screenWidthDp < 600 -> WindowWidthSizeClass.Compact
        screenWidthDp < 840 -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    return when (widthClass) {
        WindowWidthSizeClass.Compact -> AvoqadoAdaptiveSizeClass.Compact
        WindowWidthSizeClass.Medium -> AvoqadoAdaptiveSizeClass.Medium
        WindowWidthSizeClass.Expanded -> AvoqadoAdaptiveSizeClass.Expanded
        else -> AvoqadoAdaptiveSizeClass.Compact
    }
}

private fun isAggressiveCompactLayout(
    sizeClass: AvoqadoAdaptiveSizeClass,
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
    densityDpi: Int,
): Boolean {
    if (sizeClass == AvoqadoAdaptiveSizeClass.Compact) return false

    val isPortrait = screenHeightDp >= screenWidthDp
    val shortSideDp = min(screenWidthDp, screenHeightDp)
    val longSideDp = max(screenWidthDp, screenHeightDp)
    val effectiveSmallestDp = if (smallestScreenWidthDp > 0) {
        smallestScreenWidthDp
    } else {
        shortSideDp
    }

    // Target only physically small/medium tablets, avoid full-size tablet layouts.
    val isSmallTablet = effectiveSmallestDp in 600..840
    val isPortraitSmallTablet = isSmallTablet && isPortrait && screenWidthDp <= 760
    val isLandscapeSmallTablet = isSmallTablet && !isPortrait &&
        shortSideDp <= 820 &&
        longSideDp <= 1180

    // Some budget tablets ship with low density, making UI look physically huge.
    // Keep this responsive by targeting density class, not specific models.
    val isLowDensityTablet = densityDpi in 1..220
    val isLowDensityPortraitTablet = isLowDensityTablet && isPortrait && screenWidthDp <= 900
    val isLowDensityLandscapeTablet = isLowDensityTablet && !isPortrait &&
        shortSideDp <= 900 &&
        longSideDp <= 1400

    return isPortraitSmallTablet ||
        isLandscapeSmallTablet ||
        isLowDensityPortraitTablet ||
        isLowDensityLandscapeTablet
}

internal fun adaptiveDimensionsFor(
    sizeClass: AvoqadoAdaptiveSizeClass,
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
    densityDpi: Int,
): AvoqadoDimensions = when {
    isAggressiveCompactLayout(sizeClass, screenWidthDp, screenHeightDp, smallestScreenWidthDp, densityDpi) -> aggressiveCompactDimensions
    else -> when (sizeClass) {
    AvoqadoAdaptiveSizeClass.Compact -> compactDimensions
    // User preference: keep tablet UI at compact physical sizes.
    AvoqadoAdaptiveSizeClass.Medium -> compactDimensions
    AvoqadoAdaptiveSizeClass.Expanded -> compactDimensions
    }
}

internal fun adaptiveTokensFor(
    sizeClass: AvoqadoAdaptiveSizeClass,
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
    densityDpi: Int,
): AvoqadoAdaptiveTokens {
    val isPortrait = screenHeightDp >= screenWidthDp
    val isAggressiveCompact = isAggressiveCompactLayout(sizeClass, screenWidthDp, screenHeightDp, smallestScreenWidthDp, densityDpi)
    val aggressiveScale = if (isPortrait) 0.94f else 0.92f
    val aggressiveSearchHeight = if (isPortrait) 43.dp else 41.dp
    val aggressiveRowPadding = if (isPortrait) 11.dp else 9.dp
    val aggressiveLeadingSize = if (isPortrait) 41.dp else 37.dp
    val aggressiveLeadingIconSize = if (isPortrait) 18.dp else 16.dp
    val aggressivePriceWidth = if (isPortrait) 69.dp else 64.dp

    return when (sizeClass) {
    AvoqadoAdaptiveSizeClass.Compact -> AvoqadoAdaptiveTokens(
        sizeClass = sizeClass,
        typographyScale = 1f,
        isPortrait = isPortrait,
        isAggressiveCompact = false,
        primaryButtonHorizontalPadding = 24.dp,
        circularIconButtonSize = 44.dp,
        circularIconButtonIconSize = 20.dp,
        circleBackButtonSize = 36.dp,
        circleBackButtonIconSize = 16.dp,
        listSearchFieldHeight = 44.dp,
        listRowVerticalPadding = 14.dp,
        listLeadingVisualSize = 44.dp,
        listLeadingIconSize = 20.dp,
        listPriceColumnWidth = 72.dp,
    )
    AvoqadoAdaptiveSizeClass.Medium -> AvoqadoAdaptiveTokens(
        sizeClass = sizeClass,
        typographyScale = if (isAggressiveCompact) aggressiveScale else 0.96f,
        isPortrait = isPortrait,
        isAggressiveCompact = isAggressiveCompact,
        primaryButtonHorizontalPadding = if (isAggressiveCompact) 20.dp else 24.dp,
        circularIconButtonSize = if (isAggressiveCompact) 41.dp else 44.dp,
        circularIconButtonIconSize = if (isAggressiveCompact) 19.dp else 20.dp,
        circleBackButtonSize = if (isAggressiveCompact) 35.dp else 36.dp,
        circleBackButtonIconSize = if (isAggressiveCompact) 16.dp else 16.dp,
        listSearchFieldHeight = if (isAggressiveCompact) aggressiveSearchHeight else 44.dp,
        listRowVerticalPadding = if (isAggressiveCompact) aggressiveRowPadding else 14.dp,
        listLeadingVisualSize = if (isAggressiveCompact) aggressiveLeadingSize else 44.dp,
        listLeadingIconSize = if (isAggressiveCompact) aggressiveLeadingIconSize else 20.dp,
        listPriceColumnWidth = if (isAggressiveCompact) aggressivePriceWidth else 72.dp,
    )
    AvoqadoAdaptiveSizeClass.Expanded -> AvoqadoAdaptiveTokens(
        sizeClass = sizeClass,
        typographyScale = 0.96f,
        isPortrait = isPortrait,
        isAggressiveCompact = false,
        primaryButtonHorizontalPadding = 24.dp,
        circularIconButtonSize = 44.dp,
        circularIconButtonIconSize = 20.dp,
        circleBackButtonSize = 36.dp,
        circleBackButtonIconSize = 16.dp,
        listSearchFieldHeight = 44.dp,
        listRowVerticalPadding = 14.dp,
        listLeadingVisualSize = 44.dp,
        listLeadingIconSize = 20.dp,
        listPriceColumnWidth = 72.dp,
    )
    }
}

internal fun adaptiveTypographyFor(
    sizeClass: AvoqadoAdaptiveSizeClass,
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
    densityDpi: Int,
): Typography {
    val isPortrait = screenHeightDp >= screenWidthDp
    val aggressiveScale = if (isPortrait) 0.94f else 0.92f
    val scale = when (sizeClass) {
        AvoqadoAdaptiveSizeClass.Compact -> 1f
        AvoqadoAdaptiveSizeClass.Medium -> if (isAggressiveCompactLayout(sizeClass, screenWidthDp, screenHeightDp, smallestScreenWidthDp, densityDpi)) aggressiveScale else 0.96f
        AvoqadoAdaptiveSizeClass.Expanded -> 0.96f
    }
    return AvoqadoTypography.scaled(scale)
}

private fun Typography.scaled(scale: Float): Typography = copy(
    displayLarge = displayLarge.scaled(scale),
    displayMedium = displayMedium.scaled(scale),
    displaySmall = displaySmall.scaled(scale),
    headlineLarge = headlineLarge.scaled(scale),
    headlineMedium = headlineMedium.scaled(scale),
    headlineSmall = headlineSmall.scaled(scale),
    titleLarge = titleLarge.scaled(scale),
    titleMedium = titleMedium.scaled(scale),
    titleSmall = titleSmall.scaled(scale),
    bodyLarge = bodyLarge.scaled(scale),
    bodyMedium = bodyMedium.scaled(scale),
    bodySmall = bodySmall.scaled(scale),
    labelLarge = labelLarge.scaled(scale),
    labelMedium = labelMedium.scaled(scale),
    labelSmall = labelSmall.scaled(scale),
)

private fun TextStyle.scaled(scale: Float): TextStyle = copy(
    fontSize = fontSize.scaled(scale),
    lineHeight = lineHeight.scaled(scale),
)

private fun TextUnit.scaled(scale: Float): TextUnit = if (isUnspecified) this else this * scale
