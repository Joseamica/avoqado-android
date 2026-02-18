package com.avoqado.pos.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AvoqadoDimensions(
    // Button heights
    val buttonSmall: Dp = 36.dp,
    val buttonMedium: Dp = 44.dp,
    val buttonLarge: Dp = 56.dp,

    // Icon sizes
    val iconSmall: Dp = 16.dp,
    val iconMedium: Dp = 20.dp,
    val iconLarge: Dp = 24.dp,
    val iconXLarge: Dp = 40.dp,
    val touchTarget: Dp = 44.dp,
)

val LocalDimensions = staticCompositionLocalOf { AvoqadoDimensions() }
