package com.avoqado.pos.reservations.presentation.create.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * DetailsSection — embedded picker for party size, optional table, optional
 * staff, and notes. Lives inside a [ModalBottomSheet] (PickerSheet) — owns its
 * own scroll via LazyColumn so the keyboard can push the active text field into
 * view without nesting scrollables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSection(viewModel: CreateReservationViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val tables by viewModel.tables.collectAsStateWithLifecycle()
    val staff by viewModel.staff.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        item("party") {
            Column {
                SectionHeader("PERSONAS")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
        }

        if (tables.isNotEmpty()) {
            item("table") {
                Column {
                    SectionHeader("MESA")
                    ChipRow {
                        item("none") {
                            FilterChip(
                                selected = draft.tableId == null,
                                onClick = {
                                    viewModel.update {
                                        it.copy(tableId = null, tableNumber = null)
                                    }
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
        }

        if (staff.isNotEmpty()) {
            item("staff") {
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
        }

        item("special-requests") {
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
        }

        item("internal-notes") {
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

        item("bottom-spacer") {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
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
