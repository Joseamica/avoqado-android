package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun AvoqadoLoadingState(
    message: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val spinnerSize = if (compact) 20.dp else 24.dp
    val spinnerStroke = if (compact) 2.dp else 2.5.dp
    val textStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(spinnerSize),
                strokeWidth = spinnerStroke,
            )
            Text(
                text = message,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
