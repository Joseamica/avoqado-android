package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Circular icon button matching iOS style:
 * - Compact uses 44dp circle with 1dp gray border
 * - Medium/Expanded scale through adaptive tokens
 * - Used for back button and close button
 */
@Composable
fun CircularIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    contentDescription: String = "Regresar",
) {
    val adaptive = AvoqadoTheme.adaptive

    Box(
        modifier = modifier
            .size(adaptive.circularIconButtonSize)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(adaptive.circularIconButtonIconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Back button variant
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircularIconButton(
        onClick = onClick,
        modifier = modifier,
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Regresar",
    )
}

/**
 * Close button variant (X)
 */
@Composable
fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircularIconButton(
        onClick = onClick,
        modifier = modifier,
        icon = Icons.Filled.Close,
        contentDescription = "Cerrar",
    )
}

/**
 * Circle back button matching iOS CircleBackButton:
 * - Compact uses 36dp circle with surfaceContainerHigh background
 * - Chevron left / ArrowBack icon at 16dp in compact
 * - Medium/Expanded scale through adaptive tokens
 * - No border, filled style
 */
@Composable
fun CircleBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptive = AvoqadoTheme.adaptive

    Box(
        modifier = modifier
            .size(adaptive.circleBackButtonSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Regresar",
            modifier = Modifier.size(adaptive.circleBackButtonIconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
