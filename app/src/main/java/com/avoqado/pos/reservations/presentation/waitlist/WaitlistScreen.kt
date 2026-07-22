package com.avoqado.pos.reservations.presentation.waitlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.data.model.WaitlistEntry
import com.avoqado.pos.reservations.data.model.WaitlistStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitlistScreen(
    onClose: () -> Unit,
    onPromote: (WaitlistEntry) -> Unit,
    viewModel: WaitlistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AvoqadoFullscreenHeader(
                title = "Lista de espera",
                onNav = onClose,
                primaryActionIcon = Icons.Filled.Add,
                onPrimaryAction = { showAdd = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyRow(
                contentPadding = PaddingValues(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                ),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                item {
                    FilterChip(
                        selected = state.statusFilter == WaitlistStatus.WAITING,
                        onClick = { viewModel.setFilter(WaitlistStatus.WAITING) },
                        label = { Text("Esperando") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.statusFilter == WaitlistStatus.NOTIFIED,
                        onClick = { viewModel.setFilter(WaitlistStatus.NOTIFIED) },
                        label = { Text("Notificados") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.statusFilter == WaitlistStatus.PROMOTED,
                        onClick = { viewModel.setFilter(WaitlistStatus.PROMOTED) },
                        label = { Text("Promovidos") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.statusFilter == null,
                        onClick = { viewModel.setFilter(null) },
                        label = { Text("Todos") },
                    )
                }
            }

            when {
                state.isLoading && state.entries.isEmpty() -> CenteredLoading()
                state.entries.isEmpty() -> EmptyState(onAdd = { showAdd = true })
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.id }) { entry ->
                            WaitlistRow(
                                entry = entry,
                                onPromote = { onPromote(entry) },
                                onRemove = { viewModel.removeEntry(entry.id) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddWaitlistSheet(
            customers = customers,
            zone = viewModel.zone,
            onDismiss = { showAdd = false },
            onSubmit = { req ->
                viewModel.addEntry(req) { success, _ ->
                    if (success) showAdd = false
                }
            },
        )
    }
}

@Composable
private fun WaitlistRow(
    entry: WaitlistEntry,
    onPromote: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.displayName.take(2).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val partyText = if (entry.partySize == 1) "1 persona" else "${entry.partySize} personas"
            val secondary = buildList {
                add(partyText)
                entry.displayPhone?.let { add(it) }
            }.joinToString(" · ")
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.status == WaitlistStatus.WAITING) {
            TextButton(onClick = onPromote) { Text("Promover") }
            TextButton(
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Quitar") }
        } else {
            WaitlistStatusChip(entry.status)
        }
    }
}

@Composable
private fun WaitlistStatusChip(status: WaitlistStatus) {
    val (label, color) = when (status) {
        WaitlistStatus.WAITING -> "Esperando" to MaterialTheme.colorScheme.primary
        WaitlistStatus.NOTIFIED -> "Notificado" to MaterialTheme.colorScheme.tertiary
        WaitlistStatus.PROMOTED -> "Promovido" to MaterialTheme.colorScheme.secondary
        WaitlistStatus.EXPIRED -> "Expirado" to MaterialTheme.colorScheme.outline
        WaitlistStatus.CANCELLED -> "Cancelado" to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = color),
        )
    }
}

@Composable
private fun CenteredLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AvoqadoBrandLoader(size = 72.dp)
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.HourglassEmpty,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = "No hay solicitudes de lista de espera",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
        Text(
            text = "Agrega solicitudes desde el botón + de arriba",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        PrimaryButton(
            text = "Agregar a la lista de espera",
            onClick = onAdd,
        )
    }
}
