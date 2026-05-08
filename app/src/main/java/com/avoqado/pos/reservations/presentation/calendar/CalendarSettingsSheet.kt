package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.presentation.components.accentColor
import com.avoqado.pos.reservations.presentation.components.displayLabel

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
    var showClasses by remember(state.showClassSessions) { mutableStateOf(state.showClassSessions) }

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
                        viewModel.setShowClassSessions(showClasses)
                        onClose()
                    }) { Text("Guardar") }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionHeader("VISTA DEL CALENDARIO")
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    ViewOptionRow(
                        label = "Día",
                        selected = view == CalendarView.DAY,
                        onSelect = { view = CalendarView.DAY },
                    )
                    RowDivider()
                    ViewOptionRow(
                        label = "Semana",
                        selected = view == CalendarView.WEEK,
                        onSelect = { view = CalendarView.WEEK },
                    )
                    RowDivider()
                    ViewOptionRow(
                        label = "Lista",
                        selected = false,
                        onSelect = {},
                        enabled = false,
                        trailing = "Próximamente",
                    )
                }
            }

            SectionHeader("MOSTRAR EN EL CALENDARIO")
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    val items = ReservationStatus.entries.filter { it != ReservationStatus.CANCELLED }
                    items.forEachIndexed { index, st ->
                        StatusToggleRow(
                            status = st,
                            checked = st in visible,
                            onCheckedChange = { on ->
                                visible = if (on) visible + st else visible - st
                            },
                        )
                        if (index < items.lastIndex) RowDivider()
                    }
                }
            }

            SectionHeader("OTROS FILTROS")
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                StatusToggleRow(
                    leadingDotColor = ReservationStatus.CANCELLED.accentColor,
                    label = "Mostrar reservas canceladas",
                    checked = cancelled,
                    onCheckedChange = { cancelled = it },
                )
                RowDivider()
                StatusToggleRow(
                    leadingDotColor = com.avoqado.pos.designsystem.theme.ClassSessionContainerLight,
                    label = "Mostrar clases",
                    checked = showClasses,
                    onCheckedChange = { showClasses = it },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 0.8.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusToggleRow(
    status: ReservationStatus,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    StatusToggleRow(
        leadingDotColor = status.accentColor,
        label = status.displayLabel,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun StatusToggleRow(
    leadingDotColor: androidx.compose.ui.graphics.Color,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(leadingDotColor, CircleShape),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
