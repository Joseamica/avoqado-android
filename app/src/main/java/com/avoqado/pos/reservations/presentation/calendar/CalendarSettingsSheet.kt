package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var view by remember(state.view) { mutableStateOf(state.view) }
    var visible by remember(state.visibleStatuses) { mutableStateOf(state.visibleStatuses) }
    var cancelled by remember(state.showCancelled) { mutableStateOf(state.showCancelled) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
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
                        viewModel.setView(view)
                        viewModel.setVisibleStatuses(visible)
                        viewModel.setShowCancelled(cancelled)
                        onClose()
                    }) { Text("Guardar") }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Vista del calendario", style = MaterialTheme.typography.titleSmall)
            ViewOptionRow(
                label = "Día",
                selected = view == CalendarView.DAY,
                onSelect = { view = CalendarView.DAY },
            )
            ViewOptionRow(
                label = "Semana",
                selected = view == CalendarView.WEEK,
                onSelect = { view = CalendarView.WEEK },
            )
            ViewOptionRow(
                label = "Lista",
                selected = false,
                onSelect = {},
                enabled = false,
                trailing = "Próximamente",
            )
            HorizontalDivider()
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

@Composable
private fun ViewOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
