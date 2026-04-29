package com.avoqado.pos.reservations.presentation.create.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel

/**
 * DetailsStep — captures party size, optional table, optional staff, and notes
 * for the new reservation flow.
 *
 * Layout (vertically scrollable Column):
 *  - PERSONAS: card with [−] / count / [+] stepper, capped 1..50.
 *  - MESA: horizontal [FilterChip] row (single-select) including a "Sin mesa"
 *    chip; only rendered when active tables are loaded.
 *  - STAFF ASIGNADO: horizontal [FilterChip] row (single-select) including a
 *    "Cualquiera" chip; only rendered when staff are loaded.
 *  - SOLICITUDES ESPECIALES: multi-line [OutlinedTextField] (2..4 lines).
 *  - NOTAS INTERNAS (no visibles al cliente): multi-line [OutlinedTextField]
 *    (2..4 lines).
 *
 * The Continuar pill in the StepperHeader is gated `true` on this step — there
 * is no required-field validation here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsStep(viewModel: CreateReservationViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val tables by viewModel.tables.collectAsStateWithLifecycle()
    val staff by viewModel.staff.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        // PERSONAS
        Column {
            SectionHeader("PERSONAS")
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StepperButton(
                        icon = Icons.Filled.Remove,
                        contentDescription = "Disminuir",
                        enabled = draft.partySize > 1,
                        onClick = {
                            viewModel.update {
                                it.copy(partySize = (it.partySize - 1).coerceAtLeast(1))
                            }
                        },
                    )
                    Text(
                        text = if (draft.partySize == 1) "1 persona" else "${draft.partySize} personas",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    StepperButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "Aumentar",
                        enabled = draft.partySize < 50,
                        onClick = {
                            viewModel.update {
                                it.copy(partySize = (it.partySize + 1).coerceAtMost(50))
                            }
                        },
                    )
                }
            }
        }

        // MESA
        if (tables.isNotEmpty()) {
            Column {
                SectionHeader("MESA")
                ChipRow {
                    item("none") {
                        FilterChip(
                            selected = draft.tableId == null,
                            onClick = {
                                viewModel.update { it.copy(tableId = null, tableNumber = null) }
                            },
                            label = { Text("Sin mesa") },
                        )
                    }
                    items(tables.filter { it.active }, key = { it.id }) { table ->
                        FilterChip(
                            selected = draft.tableId == table.id,
                            onClick = {
                                viewModel.update {
                                    it.copy(tableId = table.id, tableNumber = table.number)
                                }
                            },
                            label = { Text("Mesa ${table.number}") },
                        )
                    }
                }
            }
        }

        // STAFF ASIGNADO
        if (staff.isNotEmpty()) {
            Column {
                SectionHeader("STAFF ASIGNADO")
                ChipRow {
                    item("any") {
                        FilterChip(
                            selected = draft.assignedStaffId == null,
                            onClick = {
                                viewModel.update {
                                    it.copy(assignedStaffId = null, assignedStaffName = null)
                                }
                            },
                            label = { Text("Cualquiera") },
                        )
                    }
                    items(staff, key = { it.id }) { member ->
                        FilterChip(
                            selected = draft.assignedStaffId == member.id,
                            onClick = {
                                viewModel.update {
                                    it.copy(
                                        assignedStaffId = member.id,
                                        assignedStaffName = member.fullName,
                                    )
                                }
                            },
                            label = { Text(member.fullName) },
                        )
                    }
                }
            }
        }

        // SOLICITUDES ESPECIALES
        Column {
            SectionHeader("SOLICITUDES ESPECIALES")
            OutlinedTextField(
                value = draft.specialRequests.orEmpty(),
                onValueChange = { value ->
                    viewModel.update {
                        it.copy(specialRequests = value.ifBlank { null })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ej. mesa lejos de la puerta") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
            )
        }

        // NOTAS INTERNAS
        Column {
            SectionHeader("NOTAS INTERNAS (no visibles al cliente)")
            OutlinedTextField(
                value = draft.internalNotes.orEmpty(),
                onValueChange = { value ->
                    viewModel.update {
                        it.copy(internalNotes = value.ifBlank { null })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ej. cliente VIP, alergias") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
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
private fun StepperButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (enabled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@Composable
private fun ChipRow(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        content = content,
    )
}
