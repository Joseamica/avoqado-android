package com.avoqado.pos.reservations.presentation.classsessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.data.model.ClassSession
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassSessionDetailScreen(
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onChargeSeeded: () -> Unit = {},
    viewModel: ClassSessionDetailViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var success by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.fold(
            onSuccess = {
                success = it
                if (it == "Sesión cancelada") onClose()
            },
            onFailure = { scope.launch { snackbar.showSnackbar(it.message ?: "No se pudo completar la acción") } },
        )
    }

    // Walk-in "Inscribir y cobrar": seat reserved + cart seeded → jump to register.
    LaunchedEffect(Unit) {
        viewModel.navigateToCheckout.collect {
            showAdd = false
            onChargeSeeded()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AvoqadoFullscreenHeader(
                title = session?.title ?: "Clase",
                onNav = onClose,
                primaryActionText = "Editar",
                onPrimaryAction = onEdit,
                primaryActionEnabled = session != null,
                showDivider = true,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = session
        if (s == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Cargando clase...")
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Column(Modifier.padding(AvoqadoTheme.spacing.lg)) {
                    Text(formatRange(s, viewModel.zone), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${s.enrolled}/${s.capacity} asistentes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Detalles") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Asistentes (${s.enrolled}/${s.capacity})") })
                }
                if (selectedTab == 0) {
                    DetailsTab(s, viewModel.zone, onCancel = { showCancelConfirm = true })
                } else {
                    AttendeesTab(s, onAdd = { showAdd = true }, onRemove = viewModel::removeAttendee)
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(
            onDismissRequest = { showAdd = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AddAttendeeSheet(
                viewModel = viewModel,
                onDone = { showAdd = false },
            )
        }
    }

    if (showCancelConfirm) {
        AvoqadoDialog(
            title = "¿Cancelar esta sesión?",
            description = "Se cancelarán las reservaciones de los asistentes inscritos.",
            onDismiss = { showCancelConfirm = false },
            actionButton = {
                PrimaryButton(
                    text = "Cancelar sesión",
                    onClick = {
                        showCancelConfirm = false
                        viewModel.cancel()
                    },
                    fullWidth = true,
                )
            },
        ) {}
    }

    success?.let { label ->
        AvoqadoSuccessToast(message = label, onDismiss = { success = null })
    }
}

@Composable
private fun DetailsTab(session: ClassSession, zone: ZoneId, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        InfoRow("Clase", session.title)
        InfoRow("Horario", formatRange(session, zone))
        InfoRow("Capacidad", "${session.capacity}")
        InfoRow("Staff", session.assignedStaff?.displayName ?: "Cualquiera")
        InfoRow("Notas", session.internalNotes ?: "Sin notas")
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        TextButton(onClick = onCancel) {
            Text("Cancelar sesión", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AttendeesTab(
    session: ClassSession,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        item {
            val full = session.enrolled >= session.capacity
            PrimaryButton(
                text = if (full) "Sesión llena" else "Agregar asistente",
                onClick = onAdd,
                enabled = !full,
                fullWidth = true,
            )
        }
        items(session.reservations, key = { it.id }) { attendee ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(attendee.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(attendee.displayContact ?: "Invitado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onRemove(attendee.id) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AddAttendeeSheet(
    viewModel: ClassSessionDetailViewModel,
    onDone: () -> Unit,
) {
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    // Cap "Lugares" at the seats actually left; the server still re-checks.
    val available = session?.let { (it.capacity - it.enrolled).coerceAtLeast(1) } ?: 1
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var guestName by remember { mutableStateOf("") }
    var guestPhone by remember { mutableStateOf("") }
    var guestEmail by remember { mutableStateOf("") }
    var partySize by remember { mutableIntStateOf(1) }
    if (partySize > available) partySize = available
    val filtered = remember(customers, query) {
        if (query.isBlank()) customers else customers.filter {
            it.fullName.contains(query, ignoreCase = true) ||
                (it.phone?.contains(query, ignoreCase = true) == true) ||
                (it.email?.contains(query, ignoreCase = true) == true)
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = AvoqadoTheme.spacing.xxl)) {
        Text(
            "Agregar asistente",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Cliente existente") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Invitado") })
        }

        // Shared "Lugares" stepper — applies to whichever attendee is added.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lugares", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilterChip(selected = false, onClick = { if (partySize > 1) partySize-- }, label = { Text("–") })
            Text(
                "$partySize",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.md),
            )
            FilterChip(selected = false, onClick = { if (partySize < available) partySize++ }, label = { Text("+") })
        }

        if (tab == 0) {
            Column(Modifier.padding(AvoqadoTheme.spacing.lg)) {
                SearchPillField(query = query, onQueryChange = { query = it }, placeholder = "Buscar cliente", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(AvoqadoTheme.spacing.md))
                filtered.take(8).forEach { customer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        Text(customer.fullName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        // Solo inscribir (reserva el lugar, no cobra)
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Solo inscribir",
                            modifier = Modifier.clickable {
                                viewModel.addCustomer(customer)
                                onDone()
                            },
                        )
                        // Inscribir y cobrar (reserva + siembra carrito + va a la caja; el VM dispara la navegación)
                        Icon(
                            Icons.Filled.Payments,
                            contentDescription = "Inscribir y cobrar",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                viewModel.addCustomerAndCharge(customer, partySize)
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else {
            Column(
                Modifier.padding(AvoqadoTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                OutlinedTextField(guestName, { guestName = it }, label = { Text("Nombre*") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(guestPhone, { guestPhone = it }, label = { Text("Teléfono") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(guestEmail, { guestEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                PrimaryButton(
                    text = "Inscribir y cobrar",
                    onClick = { viewModel.addGuestAndCharge(guestName, guestPhone, guestEmail, partySize) },
                    enabled = guestName.isNotBlank(),
                    fullWidth = true,
                )
                TextButton(
                    onClick = {
                        viewModel.addGuest(guestName, guestPhone, guestEmail)
                        onDone()
                    },
                    enabled = guestName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Solo inscribir")
                }
            }
        }
    }
}

@Composable
fun ClassSessionEditScreen(
    onClose: () -> Unit,
    viewModel: ClassSessionDetailViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val staff by viewModel.staff.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var capacity by remember(session?.id) { mutableStateOf(session?.capacity ?: 1) }
    var selectedStaffId by remember(session?.id) { mutableStateOf(session?.assignedStaffId) }
    var selectedStaffName by remember(session?.id) { mutableStateOf(session?.assignedStaff?.displayName) }
    var notes by remember(session?.id) { mutableStateOf(session?.internalNotes.orEmpty()) }
    var success by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        message?.onSuccess { success = it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AvoqadoFullscreenHeader(
                title = "Editar clase",
                onNav = onClose,
                primaryActionText = "Guardar",
                onPrimaryAction = { viewModel.update(capacity, selectedStaffId, notes) },
                primaryActionEnabled = session != null,
                showDivider = true,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            session?.let { InfoRow("Clase", it.title); InfoRow("Fecha y hora", formatRange(it, viewModel.zone)) }
            Text("Capacidad", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = false, onClick = { capacity = (capacity - 1).coerceAtLeast(session?.enrolled ?: 1) }, label = { Text("-") })
                Text("$capacity plazas", style = MaterialTheme.typography.titleMedium)
                FilterChip(selected = false, onClick = { capacity++ }, label = { Text("+") })
            }
            Text("Staff", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                FilterChip(selected = selectedStaffId == null, onClick = { selectedStaffId = null; selectedStaffName = null }, label = { Text("Cualquiera") })
            }
            staff.forEach { member ->
                FilterChip(
                    selected = selectedStaffId == member.id,
                    onClick = { selectedStaffId = member.id; selectedStaffName = member.fullName },
                    label = { Text(member.fullName) },
                )
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notas internas") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
    }

    success?.let {
        AvoqadoSuccessToast(message = it, onDismiss = { success = null; onClose() })
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatRange(session: ClassSession, zone: ZoneId): String {
    val start = ZonedDateTime.parse(session.startsAt).withZoneSameInstant(zone)
    val end = ZonedDateTime.parse(session.endsAt).withZoneSameInstant(zone)
    val date = start.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale("es"))).replaceFirstChar { it.uppercase() }
    return "$date · ${start.format(TIME)}-${end.format(TIME)}"
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
