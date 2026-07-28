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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenueSwitcherSheet(
    venues: List<StoredVenue>,
    currentVenueId: String?,
    hasCartItems: Boolean = false,
    cartItemCount: Int = 0,
    cartTotal: String = "$0.00",
    onSaveCartBeforeSwitch: (() -> Unit)? = null,
    onVenueSelected: (StoredVenue) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var pendingVenue by remember { mutableStateOf<StoredVenue?>(null) }
    var showCartAlert by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
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
                        onClick = {
                            if (isSelected) return@VenueRow
                            if (hasCartItems) {
                                pendingVenue = venue
                                showCartAlert = true
                            } else {
                                onVenueSelected(venue)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }

    // Cart in progress confirmation (matching iOS VenueSwitcherView)
    if (showCartAlert && pendingVenue != null) {
        AlertDialog(
            onDismissRequest = {
                showCartAlert = false
                pendingVenue = null
            },
            title = { Text("Carrito en progreso") },
            text = {
                Text("Tienes $cartItemCount artículo${if (cartItemCount == 1) "" else "s"} en el carrito ($cartTotal). ¿Que deseas hacer?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCartAlert = false
                    pendingVenue?.let { onVenueSelected(it) }
                    pendingVenue = null
                }) {
                    Text("Descartar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                if (onSaveCartBeforeSwitch != null) {
                    TextButton(onClick = {
                        showCartAlert = false
                        onSaveCartBeforeSwitch()
                        pendingVenue?.let { onVenueSelected(it) }
                        pendingVenue = null
                    }) {
                        Text("Guardar carrito")
                    }
                } else {
                    TextButton(onClick = {
                        showCartAlert = false
                        pendingVenue = null
                    }) {
                        Text("Cancelar")
                    }
                }
            },
        )
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
