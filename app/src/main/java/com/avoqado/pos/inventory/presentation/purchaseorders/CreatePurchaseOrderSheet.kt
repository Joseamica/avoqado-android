package com.avoqado.pos.inventory.presentation.purchaseorders

import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.DateVisualTransformation
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.CreatePOItemRequest
import com.avoqado.pos.inventory.data.model.Supplier
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// MARK: - Selected PO Item (local UI state)

private data class SelectedPOItem(
    val productId: String,
    val productName: String,
    val quantity: Int = 1,
)

// MARK: - Date helpers

/** Convert epoch millis (UTC) coming from [rememberDatePickerState] into raw DDMMYYYY digits. */
private fun millisToDDMMYYYY(millis: Long): String {
    val sdf = SimpleDateFormat("ddMMyyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return sdf.format(java.util.Date(millis))
}

/** Convert 8-digit raw DDMMYYYY into an ISO-8601 instant at midnight UTC, or null if invalid. */
private fun ddMMYYYYToIsoOrNull(raw: String): String? {
    if (raw.length != 8) return null
    return try {
        val input = SimpleDateFormat("ddMMyyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val date = input.parse(raw) ?: return null
        val output = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        output.format(date)
    } catch (_: Exception) {
        null
    }
}

// MARK: - Create Purchase Order Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePurchaseOrderSheet(
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val stockItems by viewModel.stockItems.collectAsState()
    val suppliers by viewModel.suppliers.collectAsState()
    var selectedSupplier by remember { mutableStateOf<Supplier?>(null) }
    val invSheetError by viewModel.errorMessage.collectAsState()
    LaunchedEffect(Unit) { viewModel.clearErrorMessage() }
    // Raw digit-only date string (DDMMYYYY), formatted visually via DateVisualTransformation
    var expectedDate by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedItems by remember { mutableStateOf(listOf<SelectedPOItem>()) }
    var showProductPicker by remember { mutableStateOf(false) }
    var showSupplierPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (invSheetError != null) {
        AvoqadoDialog(
            title = "No se pudo completar",
            description = invSheetError ?: "",
            onDismiss = { viewModel.clearErrorMessage() },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { viewModel.clearErrorMessage() })
            },
            content = {},
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
                // a su ventana, asi que el ajuste va en el contenido.
                .imePadding()
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Nueva orden de compra",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Supplier (clickable selector — opens SupplierPickerSheet)
            Text(
                text = "Proveedor",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    )
                    .clickable { showSupplierPicker = true }
                    .padding(horizontal = AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedSupplier?.name ?: "Selecciona un proveedor",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selectedSupplier != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Expected date — digit-only input, slash formatting via VisualTransformation,
            // trailing calendar icon opens a Material3 DatePickerDialog as an alternative.
            Text(
                text = "Fecha esperada de entrega",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            OutlinedTextField(
                value = expectedDate,
                onValueChange = { raw ->
                    expectedDate = raw.filter { it.isDigit() }.take(8)
                },
                placeholder = { Text("DD/MM/AAAA") },
                singleLine = true,
                visualTransformation = DateVisualTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Abrir calendario",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Items section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Artículos",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "+ Agregar artículo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showProductPicker = true },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Display selected items
            if (selectedItems.isEmpty()) {
                Text(
                    text = "Agrega al menos un artículo para crear la orden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                selectedItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            // Initials circle
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = item.productName.take(2).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = item.productName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Cantidad: ${item.quantity}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Quantity controls + remove
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Decrease qty
                            Text(
                                text = "-",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (item.quantity > 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier
                                    .clickable(enabled = item.quantity > 1) {
                                        selectedItems = selectedItems.toMutableList().also {
                                            it[index] = item.copy(quantity = item.quantity - 1)
                                        }
                                    }
                                    .padding(horizontal = 8.dp),
                            )
                            Text(
                                text = "${item.quantity}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            // Increase qty
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        selectedItems = selectedItems.toMutableList().also {
                                            it[index] = item.copy(quantity = item.quantity + 1)
                                        }
                                    }
                                    .padding(horizontal = 8.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            // Remove item
                            IconButton(
                                onClick = {
                                    selectedItems = selectedItems.toMutableList().also {
                                        it.removeAt(index)
                                    }
                                },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Eliminar",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    if (index < selectedItems.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Notes
            Text(
                text = "Notas (opcional)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = { Text("Notas adicionales...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Create button - requires a selected supplier and at least one item
            PrimaryButton(
                text = if (isSaving) "Creando..." else "Crear orden",
                onClick = {
                    val supplier = selectedSupplier ?: return@PrimaryButton
                    val items = selectedItems.map { item ->
                        CreatePOItemRequest(
                            productId = item.productId,
                            productName = item.productName,
                            orderedQuantity = item.quantity,
                        )
                    }
                    viewModel.createPurchaseOrder(
                        supplierName = supplier.name,
                        items = items,
                        notes = notes.ifBlank { null },
                        expectedDeliveryDate = ddMMYYYYToIsoOrNull(expectedDate),
                        onSuccess = { onDismiss() },
                    )
                },
                enabled = selectedSupplier != null && selectedItems.isNotEmpty() && !isSaving,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }

    // MARK: - Product picker sheet
    if (showProductPicker) {
        val alreadySelectedIds = selectedItems.map { it.productId }.toSet()
        val availableProducts = stockItems.filter { it.id !in alreadySelectedIds }
        ProductPickerSheet(
            products = availableProducts,
            onProductSelected = { stock ->
                selectedItems = selectedItems + SelectedPOItem(
                    productId = stock.id,
                    productName = stock.name,
                    quantity = 1,
                )
            },
            onDismiss = { showProductPicker = false },
        )
    }

    // MARK: - Supplier picker sheet
    if (showSupplierPicker) {
        SupplierPickerSheet(
            suppliers = suppliers,
            onSupplierSelected = { selectedSupplier = it },
            onDismiss = { showSupplierPicker = false },
        )
    }

    // MARK: - Date picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            expectedDate = millisToDDMMYYYY(millis)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("Aceptar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancelar")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
