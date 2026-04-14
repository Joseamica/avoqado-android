package com.avoqado.pos.estimates.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Create Estimate Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEstimateSheet(
    viewModel: EstimatesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    var customerName by remember { mutableStateOf("") }
    var customerEmail by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    data class ItemRow(
        val productName: String = "",
        val quantity: String = "1",
        val unitPrice: String = "",
    )

    val items = remember { mutableStateListOf(ItemRow()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            // Title
            Text(
                text = "Nuevo presupuesto",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Customer section
            Text(
                text = "CLIENTE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            OutlinedTextField(
                value = customerName,
                onValueChange = { customerName = it },
                label = { Text("Nombre del cliente") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            OutlinedTextField(
                value = customerEmail,
                onValueChange = { customerEmail = it },
                label = { Text("Correo (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            OutlinedTextField(
                value = customerPhone,
                onValueChange = { customerPhone = it },
                label = { Text("Telefono (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Items section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "ARTICULOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { items.add(ItemRow()) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                    Text("Agregar")
                }
            }

            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = item.productName,
                            onValueChange = { items[index] = item.copy(productName = it) },
                            label = { Text("Articulo") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                            OutlinedTextField(
                                value = item.quantity,
                                onValueChange = { items[index] = item.copy(quantity = it) },
                                label = { Text("Cant.") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = item.unitPrice,
                                onValueChange = { items[index] = item.copy(unitPrice = it) },
                                label = { Text("Precio") },
                                prefix = { Text("$") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                        }
                    }

                    if (items.size > 1) {
                        IconButton(onClick = { items.removeAt(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Eliminar",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notas (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Create button
            PrimaryButton(
                text = if (isSaving) "Creando..." else "Crear presupuesto",
                onClick = {
                    val estimateItems = items
                        .filter { it.productName.isNotBlank() }
                        .map { item ->
                            EstimatesViewModel.EstimateItemInput(
                                productName = item.productName,
                                quantity = item.quantity.toIntOrNull() ?: 1,
                                unitPrice = item.unitPrice.toDoubleOrNull() ?: 0.0,
                            )
                        }
                    viewModel.createEstimate(
                        customerName = customerName.ifBlank { null },
                        customerEmail = customerEmail.ifBlank { null },
                        customerPhone = customerPhone.ifBlank { null },
                        notes = notes.ifBlank { null },
                        items = estimateItems,
                        onSuccess = { onDismiss() },
                    )
                },
                enabled = items.any { it.productName.isNotBlank() } && !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
