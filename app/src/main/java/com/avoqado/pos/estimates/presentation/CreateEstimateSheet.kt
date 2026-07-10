package com.avoqado.pos.estimates.presentation

import androidx.compose.foundation.layout.Arrangement
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Create Estimate Fullscreen Modal

@Composable
fun CreateEstimateSheet(
    viewModel: EstimatesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    var customerName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    var customerEmail by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    data class ItemRow(
        val productName: String = "",
        val quantity: String = "1",
        val unitPrice: String = "",
    )

    val items = remember { mutableStateListOf(ItemRow()) }

    val adaptive = AvoqadoTheme.adaptive
    val configuration = LocalConfiguration.current
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val compactForm = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val sectionGap = if (compactForm) AvoqadoTheme.spacing.lg else AvoqadoTheme.spacing.xl
    val fieldGap = if (compactForm) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md
    val itemGap = if (compactForm) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm

    fun createEstimate() {
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
            onError = { createError = it },
        )
    }

    if (createError != null) {
        AvoqadoDialog(
            title = "No se pudo crear",
            description = createError ?: "",
            onDismiss = { createError = null },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { createError = null })
            },
            content = {},
        )
    }

    AvoqadoFullScreenModal(
        title = "Nuevo presupuesto",
        onDismiss = onDismiss,
        primaryActionText = if (isSaving) "Creando..." else "Crear",
        onPrimaryAction = { createEstimate() },
        primaryActionEnabled = items.any { it.productName.isNotBlank() } && !isSaving,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compactForm) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg,
                    vertical = if (compactForm) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md,
                )
                .widthIn(max = 960.dp),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(fieldGap)) {
                Text(
                    text = "CLIENTE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text("Nombre del cliente") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = customerEmail,
                    onValueChange = { customerEmail = it },
                    label = { Text("Correo (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )

                OutlinedTextField(
                    value = customerPhone,
                    onValueChange = { customerPhone = it },
                    label = { Text("Telefono (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(fieldGap)) {
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
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.height(AvoqadoTheme.spacing.lg),
                        )
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                        Text("Agregar")
                    }
                }

                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = itemGap),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(itemGap),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(itemGap),
                        ) {
                            OutlinedTextField(
                                value = item.productName,
                                onValueChange = { items[index] = item.copy(productName = it) },
                                label = { Text("Articulo") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(itemGap),
                            ) {
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
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notas (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (compactForm) 2 else 3,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
        }
    }
}
