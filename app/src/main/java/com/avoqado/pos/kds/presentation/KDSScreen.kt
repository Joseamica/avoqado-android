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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import com.avoqado.pos.kds.domain.CanalReparto
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
    val errorMessage by viewModel.errorMessage.collectAsState()
    val canalesReparto by viewModel.canalesReparto.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    // Qué canal está eligiendo duración. `null` = el diálogo está cerrado.
    var pausando by remember { mutableStateOf<CanalReparto?>(null) }

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
        // 🔴 Una barra FIJA arriba, no un aviso que se va solo. Esta pantalla vive colgada en
        // una cocina y nadie la está mirando en el segundo exacto en que algo falla: un
        // mensaje que desaparece a los tres segundos es un mensaje que nadie leyó. Y lo que
        // dice importa de verdad — por ejemplo, que el plazo del pedido venció y no sirve
        // reintentar. Se queda hasta que alguien lo cierra.
        errorMessage?.let { mensaje ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = mensaje,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("Entendido", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

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

        // MARK: - "Me saturé" — el freno del reparto
        // Sólo se dibuja si el venue REALMENTE vende por reparto y este puesto tiene el
        // permiso: si la lista viene vacía —sin canales, sin plan, o sin permiso— no hay
        // control. Mostrarle a un cocinero un botón que le va a dar error es peor que no
        // mostrarle nada.
        canalesReparto.forEach { canal ->
            BarraReparto(
                canal = canal,
                ahora = tick,
                onPausar = { pausando = canal },
                onReanudar = { viewModel.reanudarReparto(canal.id) },
            )
        }

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
                        onAcceptDelivery = { viewModel.acceptDeliveryOrder(order.id) },
                        onDenyDelivery = { viewModel.denyDeliveryOrder(order.id) },
                    )
                }
            }
        }
    }

    // MARK: - ¿Cuánto frenar?
    pausando?.let { canal ->
        val cerrar = { pausando = null }
        AlertDialog(
            onDismissRequest = cerrar,
            title = { Text("Frenar el reparto") },
            text = {
                Column {
                    Text(
                        text = "Dejarás de recibir pedidos de reparto. Se reanuda solo cuando pase el tiempo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    DURACIONES_PAUSA.forEach { (minutos, etiqueta) ->
                        // Botones de ancho completo: esto se toca con las manos ocupadas y
                        // muchas veces con guantes. Un menú desplegable aquí no se acierta.
                        OutlinedButton(
                            onClick = {
                                viewModel.pausarReparto(canal.id, minutos)
                                cerrar()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AvoqadoTheme.spacing.xs),
                        ) {
                            Text(etiqueta, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
        )
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

/**
 * El estado del reparto y su freno, en una línea sobre el tablero.
 *
 * Tres estados y NINGUNO se ve igual, a propósito:
 *  · Recibiendo   → botón para frenar.
 *  · Pausado con reloj (lo pidió alguien del piso) → cuenta regresiva + reanudar.
 *  · Pausado SIN reloj (lo pidió el dueño desde el dashboard) → se ve, se explica, y NO
 *    trae botón. Desde el piso no se reabre lo que el dueño cerró — y una cuenta regresiva
 *    que no corre sería peor que nada, porque prometería una reactivación que no va a pasar.
 */
@Composable
private fun BarraReparto(
    canal: CanalReparto,
    ahora: Long,
    onPausar: () -> Unit,
    onReanudar: () -> Unit,
) {
    val restante = canal.pausadoHasta?.let { hasta ->
        runCatching { java.time.Instant.parse(hasta).toEpochMilli() - ahora }.getOrNull()
    }

    val (fondo, texto) = when {
        !canal.pausado -> MaterialTheme.colorScheme.surface to "Reparto recibiendo pedidos"
        restante != null && restante > 0 -> {
            val minutos = (restante / 60_000).toInt()
            val segundos = ((restante / 1000) % 60).toInt()
            MaterialTheme.colorScheme.tertiaryContainer to
                "Reparto en pausa · se reanuda en %d:%02d".format(minutos, segundos)
        }
        else -> MaterialTheme.colorScheme.errorContainer to "Reparto pausado por el administrador"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(fondo)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = texto, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

        when {
            !canal.pausado -> OutlinedButton(onClick = onPausar) { Text("Me saturé") }
            restante != null && restante > 0 -> TextButton(onClick = onReanudar) { Text("Ya estamos al día") }
            // Pausa del dueño: sin botón, a propósito.
            else -> Unit
        }
    }
}

/**
 * Cuánto frenar. Son las MISMAS cuatro opciones que acepta el servidor
 * (`SNOOZE_MINUTOS_VALIDOS`), espejadas por valor exacto: una quinta aquí daría un 400 que
 * el cocinero no puede interpretar.
 *
 * No hay "indefinido" a propósito. El modo de fallo de este patrón está documentado —en la
 * comunidad de Square, "pause stuck"—: alguien pausa a media cena, se le olvida, y el
 * negocio amanece apagado. Toda pausa desde el piso caduca sola.
 */
private val DURACIONES_PAUSA = listOf(20 to "20 minutos", 40 to "40 minutos", 60 to "1 hora", 120 to "2 horas")
