package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarTabHost(
    onOpenReservation: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val venueZone = remember(viewModel) { viewModel.venueZoneId }
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pending by viewModel.pendingActionsCount.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.selectedDate.format(
                            DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es"))
                        ).replaceFirstChar { it.uppercase() },
                    )
                },
                actions = {
                    IconButton(onClick = { /* TODO Phase 2 — create flow */ }) {
                        Icon(Icons.Filled.Add, "Crear")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.MoreHoriz, "Ajustes")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isOnline) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = if (pending > 0) "Sin conexión — $pending acciones pendientes" else "Sin conexión",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            SegmentedRow(
                view = state.view,
                onViewChange = viewModel::setView,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when (state.view) {
                CalendarView.DAY -> CalendarDayView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                )
                CalendarView.WEEK -> CalendarWeekView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                )
            }
        }
    }
}

@Composable
private fun SegmentedRow(
    view: CalendarView,
    onViewChange: (CalendarView) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = view == CalendarView.DAY,
            onClick = { onViewChange(CalendarView.DAY) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("Día") }
        SegmentedButton(
            selected = view == CalendarView.WEEK,
            onClick = { onViewChange(CalendarView.WEEK) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("Semana") }
    }
}
