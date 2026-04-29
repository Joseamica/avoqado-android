package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.reservations.presentation.components.ActionSheetCenter
import com.avoqado.pos.reservations.presentation.components.ActionSheetItem
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarTabHost(
    onOpenReservation: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCreateReservation: (LocalDate, LocalTime) -> Unit = { _, _ -> },
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val venueZone = remember(viewModel) { viewModel.venueZoneId }
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pending by viewModel.pendingActionsCount.collectAsStateWithLifecycle()

    var pendingSlot by remember { mutableStateOf<Pair<LocalDate, LocalTime>?>(null) }
    var showSheetForNow by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun openSheetAtSlot(date: LocalDate, time: LocalTime) {
        pendingSlot = date to time
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
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
                    IconButton(onClick = { showSheetForNow = true }) {
                        Icon(Icons.Filled.Add, "Crear")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.MoreHoriz, "Ajustes")
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                        textAlign = TextAlign.Center,
                    )
                }
            }
            when (state.view) {
                CalendarView.DAY -> CalendarDayView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                    onSlotTap = ::openSheetAtSlot,
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

    val sheetSlot = pendingSlot
    if (sheetSlot != null || showSheetForNow) {
        val target = sheetSlot
            ?: (state.selectedDate to LocalTime.now(venueZone))
        ActionSheetCenter(
            onDismiss = {
                pendingSlot = null
                showSheetForNow = false
            },
            actions = listOf(
                ActionSheetItem(
                    label = "Crear cita",
                    onClick = {
                        onCreateReservation(target.first, target.second)
                        pendingSlot = null
                        showSheetForNow = false
                    },
                ),
                ActionSheetItem(
                    label = "Crear clase",
                    onClick = { showComingSoon(scope, snackbar, "Crear clase", 3) },
                ),
                ActionSheetItem(
                    label = "Crear evento personal",
                    onClick = { showComingSoon(scope, snackbar, "Crear evento personal", 5) },
                ),
                ActionSheetItem(
                    label = "Cancelar",
                    onClick = {},
                    destructive = true,
                ),
            ),
        )
    }
}

private fun showComingSoon(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    action: String,
    phase: Int,
) {
    scope.launch {
        snackbar.showSnackbar("$action — disponible en Fase $phase")
    }
}

