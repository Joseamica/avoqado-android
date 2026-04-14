package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.model.StockCountType

// MARK: - Stock Count Type Sheet (Bottom Sheet Content)

@Composable
fun StockCountTypeSheet(
    viewModel: InventoryViewModel,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AvoqadoTheme.spacing.xl),
    ) {
        // Header
        Text(
            text = "Tipo de conteo",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.md,
            ),
        )

        // Cycle count
        CountTypeRow(
            icon = Icons.Filled.FormatListBulleted,
            title = StockCountType.CYCLE.label,
            subtitle = StockCountType.CYCLE.description,
            enabled = !isSaving,
            onClick = { viewModel.startCycleCount() },
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 72.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Full count
        CountTypeRow(
            icon = Icons.Filled.Checklist,
            title = StockCountType.FULL.label,
            subtitle = StockCountType.FULL.description,
            enabled = !isSaving,
            onClick = { viewModel.startFullCount() },
        )

        if (isSaving) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun CountTypeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(10.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
