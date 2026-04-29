package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.reservations.data.model.ReservationStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsSheet(
    onClose: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var visible by remember(state.visibleStatuses) { mutableStateOf(state.visibleStatuses) }
    var cancelled by remember(state.showCancelled) { mutableStateOf(state.showCancelled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes del calendario") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, "Cerrar")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.setVisibleStatuses(visible)
                        viewModel.setShowCancelled(cancelled)
                        onClose()
                    }) { Text("Guardar") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Estados visibles", style = MaterialTheme.typography.titleSmall)
            ReservationStatus.entries.filter { it != ReservationStatus.CANCELLED }.forEach { st ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = st in visible,
                        onCheckedChange = { on ->
                            visible = if (on) visible + st else visible - st
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(st.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            Text("Filtros adicionales", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cancelled, onCheckedChange = { cancelled = it })
                Spacer(Modifier.width(8.dp))
                Text("Mostrar reservas canceladas")
            }
        }
    }
}
