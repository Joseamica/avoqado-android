package com.avoqado.pos.addons.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.addons.domain.Addon
import com.avoqado.pos.addons.domain.AddonCategory
import com.avoqado.pos.addons.domain.AddonsManager
import com.avoqado.pos.addons.domain.allAddons
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Sidebar Sections

private enum class AddonsSidebarSection(val label: String) {
    FOR_YOUR_BUSINESS("Para tu negocio"),
    ALL_ADDONS("Todos los complementos"),
}

// MARK: - Entry Point

@Composable
fun AddonsScreen(
    isTablet: Boolean,
    addonsManager: AddonsManager,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        TabletAddonsLayout(addonsManager = addonsManager, onDismiss = onDismiss)
    } else {
        PhoneAddonsLayout(addonsManager = addonsManager, onDismiss = onDismiss)
    }
}

// MARK: - Tablet Layout

@Composable
private fun TabletAddonsLayout(
    addonsManager: AddonsManager,
    onDismiss: () -> Unit,
) {
    var selectedSection by remember { mutableStateOf(AddonsSidebarSection.FOR_YOUR_BUSINESS) }
    var selectedAddon by remember { mutableStateOf<Addon?>(null) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar (280dp)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Complementos",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Section rows
            AddonsSidebarSection.entries.forEach { section ->
                SidebarSectionRow(
                    section = section,
                    isSelected = section == selectedSection,
                    onClick = {
                        selectedSection = section
                        selectedAddon = null
                    },
                )
            }
        }

        // Hairline divider
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            if (selectedAddon != null) {
                AddonDetailContent(
                    addon = selectedAddon!!,
                    addonsManager = addonsManager,
                    onBack = { selectedAddon = null },
                )
            } else {
                AddonsListContent(
                    section = selectedSection,
                    addonsManager = addonsManager,
                    onAddonClick = { selectedAddon = it },
                )
            }
        }
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneAddonsLayout(
    addonsManager: AddonsManager,
    onDismiss: () -> Unit,
) {
    var showingSection by remember { mutableStateOf<AddonsSidebarSection?>(null) }
    var selectedAddon by remember { mutableStateOf<Addon?>(null) }

    when {
        selectedAddon != null -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AvoqadoTheme.spacing.lg,
                            vertical = AvoqadoTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleBackButton(onClick = { selectedAddon = null })
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                    Text(
                        text = selectedAddon!!.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                AddonDetailContent(
                    addon = selectedAddon!!,
                    addonsManager = addonsManager,
                    onBack = { selectedAddon = null },
                )
            }
        }

        showingSection != null -> {
            val section = showingSection!!
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AvoqadoTheme.spacing.lg,
                            vertical = AvoqadoTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleBackButton(onClick = { showingSection = null })
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                    Text(
                        text = section.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(modifier = Modifier.weight(1f)) {
                    AddonsListContent(
                        section = section,
                        addonsManager = addonsManager,
                        onAddonClick = { selectedAddon = it },
                    )
                }
            }
        }

        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AvoqadoTheme.spacing.lg,
                            vertical = AvoqadoTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleBackButton(onClick = onDismiss)
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                    Text(
                        text = "Complementos",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    AddonsSidebarSection.entries.forEach { section ->
                        SidebarSectionRow(
                            section = section,
                            isSelected = false,
                            onClick = { showingSection = section },
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Sidebar Section Row

@Composable
private fun SidebarSectionRow(
    section: AddonsSidebarSection,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left border bar for selected state
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = section.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = if (isSelected) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
        )
    }
}

// MARK: - Addons List Content

@Composable
private fun AddonsListContent(
    section: AddonsSidebarSection,
    addonsManager: AddonsManager,
    onAddonClick: (Addon) -> Unit,
) {
    val enabledIds by addonsManager.enabledAddonIds.collectAsState()

    val addonsToShow = when (section) {
        AddonsSidebarSection.FOR_YOUR_BUSINESS ->
            allAddons.filter { it.category == AddonCategory.RECOMMENDED }
        AddonsSidebarSection.ALL_ADDONS -> allAddons
    }

    val grouped = when (section) {
        AddonsSidebarSection.FOR_YOUR_BUSINESS -> mapOf(AddonCategory.RECOMMENDED to addonsToShow)
        AddonsSidebarSection.ALL_ADDONS -> addonsToShow.groupBy { it.category }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        grouped.forEach { (category, addons) ->
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            addons.forEach { addon ->
                AddonRow(
                    addon = addon,
                    isEnabled = enabledIds.contains(addon.id),
                    onClick = { onAddonClick(addon) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
        }
    }
}

// MARK: - Addon Row

@Composable
private fun AddonRow(
    addon: Addon,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = addonIcon(addon.iconName),
                contentDescription = null,
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = addon.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = addon.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

        if (isEnabled) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(50),
                    )
                    .padding(
                        horizontal = AvoqadoTheme.spacing.sm,
                        vertical = AvoqadoTheme.spacing.xxs,
                    ),
            ) {
                Text(
                    text = "Agregado",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Icon Resolver

@Composable
private fun addonIcon(iconName: String): ImageVector {
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
