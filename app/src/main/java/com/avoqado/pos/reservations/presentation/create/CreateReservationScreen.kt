package com.avoqado.pos.reservations.presentation.create

import androidx.compose.foundation.clickable
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.domain.CreateReservationDraft
import com.avoqado.pos.reservations.presentation.create.sections.CustomerSection
import com.avoqado.pos.reservations.presentation.create.sections.DateTimeSection
import com.avoqado.pos.reservations.presentation.create.sections.DetailsSection
import com.avoqado.pos.reservations.presentation.create.sections.ServiceSection
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Square-style summary form: four tappable rows (Cliente, Servicio, Fecha y hora,
 * Detalles), each opens a [ModalBottomSheet] with the actual picker.
 *
 * Replaces the previous 1-pager that stacked all four pickers inline — that
 * design hid the structure of the form and read as just "a long customer list".
 *
 * Cliente and Servicio sheets auto-close the moment the user picks something.
 * Fecha y hora and Detalles sheets stay open (user dismisses with X) since they
 * accept multiple coordinated edits (date + time, party size + table + staff +
 * notes).
 */
@Composable
fun CreateReservationScreen(
    onClose: () -> Unit,
    viewModel: CreateReservationViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val isEditing by viewModel.isEditing.collectAsStateWithLifecycle()
    val staffAware by viewModel.staffAware.collectAsStateWithLifecycle()
    val eligibleStaffAvailable by viewModel.eligibleStaffAvailable.collectAsStateWithLifecycle()
    val requiresSlotReselection by viewModel.requiresSlotReselection.collectAsStateWithLifecycle()
    val overCapacityConfirmation by viewModel.overCapacityConfirmation.collectAsStateWithLifecycle()

    var showSuccess by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(result) {
        result?.onSuccess { showSuccess = true }
        // Failure branch was silently dropped: the button just re-enabled
        // and nothing happened (fields ARE preserved in the VM's draft).
        result?.onFailure { submitError = it.message ?: "No se pudo guardar la reserva. Intenta de nuevo." }
    }

    var activeSheet by remember { mutableStateOf<CreateSheet?>(null) }

    val title = if (isEditing) "Editar reserva" else "Crear cita"
    val actionLabel = if (isEditing) "Guardar" else "Crear"
    val staffAwareAppointment = staffAware && draft.productType == "APPOINTMENTS_SERVICE"
    val canSubmit = draft.canSubmit && (
        isEditing || !staffAwareAppointment || (eligibleStaffAvailable && !requiresSlotReselection)
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AvoqadoFullscreenHeader(
                title = title,
                onNav = onClose,
                primaryActionText = actionLabel,
                onPrimaryAction = { viewModel.submit() },
                primaryActionEnabled = canSubmit && !isSubmitting,
                showDivider = true,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))

            PickerRow(
                sectionLabel = "Cliente",
                icon = Icons.Filled.Person,
                primaryText = customerSummary(draft),
                placeholder = "Seleccionar cliente",
                secondaryText = customerSubtitle(draft),
                onClick = { activeSheet = CreateSheet.CUSTOMER },
            )

            PickerRow(
                sectionLabel = "Servicio",
                icon = Icons.Filled.Inventory2,
                primaryText = draft.productName,
                placeholder = "Seleccionar servicio",
                secondaryText = draft.productName?.let { "${draft.durationMinutes} min" },
                onClick = { activeSheet = CreateSheet.SERVICE },
            )

            PickerRow(
                sectionLabel = "Fecha y hora",
                icon = Icons.Filled.Schedule,
                primaryText = dateTimeSummary(draft),
                placeholder = dateTimeSummary(draft),
                enabled = !isEditing,
                hint = "Para cambiar fecha u hora usa Reprogramar".takeIf { isEditing },
                onClick = { activeSheet = CreateSheet.DATE_TIME },
            )

            PickerRow(
                sectionLabel = "Detalles",
                icon = Icons.Filled.Tune,
                primaryText = detailsSummary(draft),
                placeholder = detailsSummary(draft),
                secondaryText = detailsSecondary(draft),
                onClick = { activeSheet = CreateSheet.DETAILS },
            )

            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }

    activeSheet?.let { which ->
        PickerSheet(
            title = which.title,
            onDismiss = { activeSheet = null },
        ) { close ->
            when (which) {
                CreateSheet.CUSTOMER -> CustomerSection(viewModel, onCustomerPicked = close)
                CreateSheet.SERVICE -> ServiceSection(viewModel, onServicePicked = close)
                CreateSheet.DATE_TIME -> DateTimeSection(viewModel)
                CreateSheet.DETAILS -> DetailsSection(viewModel)
            }
        }
    }

    if (submitError != null) {
        AvoqadoDialog(
            title = "No se pudo guardar",
            description = submitError ?: "",
            onDismiss = { submitError = null },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { submitError = null })
            },
            content = {},
        )
    }

    if (overCapacityConfirmation != null) {
        AvoqadoDialog(
            title = "Horario lleno",
            description = overCapacityConfirmation,
            onDismiss = viewModel::dismissOverCapacityConfirmation,
            actionButton = {
                PrimaryButton(
                    text = "Sobre-agendar",
                    onClick = viewModel::confirmOverCapacity,
                    isLoading = isSubmitting,
                )
            },
            content = {
                Text(
                    text = "El profesionista y los demás conflictos duros seguirán protegidos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    if (showSuccess) {
        AvoqadoSuccessToast(
            message = if (isEditing) "¡Reserva actualizada!" else "¡Reserva creada!",
            onDismiss = {
                showSuccess = false
                onClose()
            },
        )
    }
}

private enum class CreateSheet(val title: String) {
    CUSTOMER("Cliente"),
    SERVICE("Servicio"),
    DATE_TIME("Fecha y hora"),
    DETAILS("Detalles"),
}

@Composable
private fun PickerRow(
    sectionLabel: String,
    icon: ImageVector,
    primaryText: String?,
    placeholder: String,
    secondaryText: String? = null,
    enabled: Boolean = true,
    hint: String? = null,
    onClick: () -> Unit,
) {
    val isSet = primaryText != null && primaryText.isNotBlank()

    Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg)) {
        Text(
            text = sectionLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (enabled) it.clickable(onClick = onClick) else it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSet) primaryText!! else placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSet) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSet) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                    secondaryText?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (enabled) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        hint?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val close: () -> Unit = {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(0.95f),
        ) {
            AvoqadoFullscreenHeader(
                title = title,
                onNav = close,
                showDivider = true,
            )
            // Sections own their own scroll (LazyColumn), so no wrapper here.
            Box(modifier = Modifier.fillMaxSize()) {
                content(close)
            }
        }
    }
}

// region — summaries

private fun customerSummary(draft: CreateReservationDraft): String? = when {
    draft.isGuest -> draft.guestName?.takeIf { it.isNotBlank() }
    else -> draft.customerName
}

private fun customerSubtitle(draft: CreateReservationDraft): String? {
    if (draft.isGuest) {
        return listOfNotNull(
            "Invitado",
            draft.guestPhone?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
    }
    return null
}

private fun dateTimeSummary(draft: CreateReservationDraft): String {
    val dateLabel = draft.date
        .format(DateTimeFormatter.ofPattern("EEE d 'de' MMM", Locale("es")))
        .replaceFirstChar { it.uppercase() }
    val timeLabel = draft.time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return "$dateLabel · $timeLabel"
}

private fun detailsSummary(draft: CreateReservationDraft): String {
    val party = if (draft.partySize == 1) "1 persona" else "${draft.partySize} personas"
    return party
}

private fun detailsSecondary(draft: CreateReservationDraft): String? {
    val parts = buildList {
        draft.tableNumber?.let { add("Mesa $it") }
        draft.assignedStaffName?.let { add(it) }
        if (!draft.specialRequests.isNullOrBlank() || !draft.internalNotes.isNullOrBlank()) {
            add("Con notas")
        }
    }
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

// endregion
