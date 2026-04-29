package com.avoqado.pos.reservations.presentation.create.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.domain.CreateStep
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ConfirmStep — final step of the create-reservation wizard.
 *
 * Renders a single summary card with four sections (Cliente, Servicio,
 * Fecha y hora, Detalles), each with its own "Editar" pill that jumps the
 * draft back to the corresponding step via [CreateReservationViewModel.goTo].
 *
 * The actual `submit()` call is wired to the StepperHeader's "Crear" pill
 * (in T5) — this composable only renders the summary plus inline submit
 * feedback (progress while submitting, error banner on failure).
 */
@Composable
fun ConfirmStep(viewModel: CreateReservationViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val errorMessage = result?.exceptionOrNull()?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        SectionHeader("REVISAR Y CONFIRMAR")

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                // Cliente
                SummarySection(
                    label = "Cliente",
                    primary = if (draft.isGuest) {
                        draft.guestName ?: "Invitado"
                    } else {
                        draft.customerName ?: "—"
                    },
                    secondaryLines = listOfNotNull(
                        if (draft.isGuest) draft.guestPhone?.takeUnless { it.isBlank() } else null,
                        if (draft.isGuest) draft.guestEmail?.takeUnless { it.isBlank() } else null,
                    ),
                    onEdit = { viewModel.goTo(CreateStep.CUSTOMER) },
                )
                RowDivider()

                // Servicio
                SummarySection(
                    label = "Servicio",
                    primary = draft.productName ?: "Sin servicio",
                    secondaryLines = listOf("${draft.durationMinutes} min"),
                    onEdit = { viewModel.goTo(CreateStep.SERVICE) },
                )
                RowDivider()

                // Fecha y hora
                SummarySection(
                    label = "Fecha y hora",
                    primary = formatDateTime(draft.date, draft.time),
                    secondaryLines = emptyList(),
                    onEdit = { viewModel.goTo(CreateStep.DATETIME) },
                )
                RowDivider()

                // Detalles
                SummarySection(
                    label = "Detalles",
                    primary = buildList {
                        add(
                            if (draft.partySize == 1) "1 persona"
                            else "${draft.partySize} personas",
                        )
                        draft.tableNumber?.let { add("Mesa $it") }
                    }.joinToString(" · "),
                    secondaryLines = listOfNotNull(
                        draft.assignedStaffName?.let { "Staff: $it" },
                        draft.specialRequests
                            ?.takeUnless { it.isBlank() }
                            ?.let { "Solicitudes: $it" },
                        draft.internalNotes
                            ?.takeUnless { it.isBlank() }
                            ?.let { "Notas internas: $it" },
                    ),
                    onEdit = { viewModel.goTo(CreateStep.DETAILS) },
                )
            }
        }

        if (isSubmitting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!isSubmitting && errorMessage != null) {
            ErrorBanner(
                message = errorMessage,
                onRetry = { viewModel.submit() },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SummarySection(
    label: String,
    primary: String,
    secondaryLines: List<String>,
    onEdit: () -> Unit,
) {
    Column(modifier = Modifier.padding(AvoqadoTheme.spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onEdit,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Editar")
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = primary,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        secondaryLines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            start = AvoqadoTheme.spacing.md,
            end = AvoqadoTheme.spacing.md,
        ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(
                text = "Reintentar",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private fun formatDateTime(
    date: java.time.LocalDate,
    time: java.time.LocalTime,
): String {
    val datePart = date
        .format(DateTimeFormatter.ofPattern("EEE d 'de' MMM", Locale("es")))
        .replaceFirstChar { it.uppercase(Locale("es")) }
    val timePart = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return "$datePart, $timePart"
}
