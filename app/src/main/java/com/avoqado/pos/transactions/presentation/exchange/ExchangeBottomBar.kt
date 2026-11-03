package com.avoqado.pos.transactions.presentation.exchange

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning

@Composable
fun ExchangeBottomBar(state: ExchangeUiState, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        Text(state.bar.label, style = MaterialTheme.typography.titleMedium,
            color = if (state.bar.isPayout) Warning else MaterialTheme.colorScheme.onSurface)
        state.blockedReason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrimaryButton(text = state.bar.action, onClick = onConfirm, enabled = state.blockedReason == null, fullWidth = true)
    }
}
