package com.avoqado.pos.addons.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.addons.domain.Addon
import com.avoqado.pos.addons.domain.AddonsManager
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Addon Detail Content

@Composable
fun AddonDetailContent(
    addon: Addon,
    addonsManager: AddonsManager,
    onBack: () -> Unit,
) {
    val enabledIds by addonsManager.enabledAddonIds.collectAsState()
    val isEnabled = enabledIds.contains(addon.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        // Large icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = detailAddonIcon(addon.iconName),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

        Text(
            text = addon.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        if (isEnabled) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(50),
                    )
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.xxs,
                    ),
            ) {
                Text(
                    text = "Agregado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        }

        Text(
            text = addon.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Activate / Deactivate button
        if (isEnabled) {
            OutlinedButton(
                onClick = {
                    addonsManager.setEnabled(addon.id, false)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AvoqadoTheme.dimensions.buttonLarge),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "Desactivar",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            Button(
                onClick = {
                    addonsManager.setEnabled(addon.id, true)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AvoqadoTheme.dimensions.buttonLarge),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = "Activar",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
    }
}

// MARK: - Icon Resolver

@Composable
private fun detailAddonIcon(iconName: String): ImageVector {
    return when (iconName) {
        "Description" -> Icons.Filled.Description
        "Loyalty" -> Icons.Filled.Loyalty
        "CardGiftcard" -> Icons.Filled.CardGiftcard
        "ShoppingCart" -> Icons.Filled.ShoppingCart
        "Receipt" -> Icons.Filled.Receipt
        "Email" -> Icons.Filled.Email
        "LocalShipping" -> Icons.Filled.LocalShipping
        "CalendarMonth" -> Icons.Filled.CalendarMonth
        else -> Icons.Filled.Description
    }
}
