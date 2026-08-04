package com.avoqado.pos.kds.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.kds.domain.KDSFilter
import kotlinx.coroutines.delay

// MARK: - Entry Point

@Composable
fun KDSScreen(
    onDismiss: () -> Unit,
    viewModel: KDSViewModel = hiltViewModel(),
) {
    val orders by viewModel.filteredOrders.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val activeCount by viewModel.activeOrderCount.collectAsState()
    val avgTime by viewModel.averageTimeSeconds.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    // Tick every second for elapsed timers
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }

    // Clock display (in venue timezone, not device local)
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        clockText = java.time.Instant.ofEpochMilli(tick)
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(formatter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // MARK: - Top Bar
        KDSTopBar(
            clockText = clockText,
            filter = filter,
            activeCount = activeCount,
            avgTime = avgTime,
            onFilterChange = { viewModel.setFilter(it) },
            onSettingsClick = { showSettings = true },
            onDismiss = onDismiss,
        )

        // MARK: - Order Grid
        if (orders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Sin pedidos",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        text = "Los nuevos pedidos aparecerán aquí",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
                contentPadding = PaddingValues(AvoqadoTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = orders,
                    key = { it.id },
                ) { order ->
                    val elapsedMs = tick - order.createdAt
                    val elapsedText = formatElapsedTime(elapsedMs)

                    KDSOrderCard(
                        order = order,
                        elapsedText = elapsedText,
                        isLargeFont = settings.largeFontEnabled,
                        onAdvanceStatus = { viewModel.advanceStatus(order.id) },
                    )
                }
            }
        }
    }

    // MARK: - Settings Sheet
    if (showSettings) {
        KDSSettingsSheet(
            settings = settings,
            onToggleSound = { viewModel.toggleSound() },
            onToggleAutoBump = { viewModel.toggleAutoBump() },
            onToggleLargeFont = { viewModel.toggleLargeFont() },
            onDismiss = { showSettings = false },
        )
    }
}

// MARK: - Top Bar

@Composable
private fun KDSTopBar(
    clockText: String,
    filter: KDSFilter,
    activeCount: Int,
    avgTime: Long,
    onFilterChange: (KDSFilter) -> Unit,
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleBackButton(onClick = onDismiss)

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Text(
            text = "Cocina",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.lg))

        // Clock
        Text(
            text = clockText,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Filter Chips
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
            KDSFilter.entries.forEach { filterOption ->
                FilterChip(
                    label = filterOption.label,
                    isSelected = filter == filterOption,
                    onClick = { onFilterChange(filterOption) },
                )
            }
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.lg))

        // Stats
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$activeCount activos",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (avgTime > 0) {
                Text(
                    text = "Prom: ${formatElapsedTime(avgTime * 1000)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Settings gear
        Box(
            modifier = Modifier
                .size(AvoqadoTheme.dimensions.touchTarget)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Configuración",
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Filter Chip

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.md,
                vertical = AvoqadoTheme.spacing.xs,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Helpers

/**
 * El reloj de cada comanda.
 *
 * Antes los minutos crecían sin tope: una comanda de hora y media salía como
 * "90:14" y una olvidada de tres días como "4320:07". Medido en el iPad el
 * 2026-08-04 (mismo defecto en las dos plataformas): la pantalla mostraba
 * "30090:13" en TODOS los tickets. La cocina hace UNA cosa con este número
 * —mirarlo de reojo y saber si va tarde— y con cinco dígitos no se puede.
 *
 * Espejo de `KDSTiempo.formatear` en iOS.
 */
internal fun formatElapsedTime(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return when {
        s < 3600 -> "%d:%02d".format(s / 60, s % 60)
        s < 86_400 -> "%dh %02d".format(s / 3600, (s % 3600) / 60)
        else -> "${s / 86_400} d"
    }
}
