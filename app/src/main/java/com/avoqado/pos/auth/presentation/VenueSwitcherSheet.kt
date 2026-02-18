package com.avoqado.pos.auth.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenueSwitcherSheet(
    venues: List<StoredVenue>,
    currentVenueId: String?,
    onVenueSelected: (StoredVenue) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Seleccionar establecimiento",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.lg),
            )

            LazyColumn {
                items(venues) { venue ->
                    val isSelected = venue.id == currentVenueId
                    VenueRow(
                        venue = venue,
                        isSelected = isSelected,
                        onClick = { onVenueSelected(venue) },
                    )
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

@Composable
private fun VenueRow(
    venue: StoredVenue,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Store,
            contentDescription = null,
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = venue.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = venue.displayRole,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isSelected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Seleccionado",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
