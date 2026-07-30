package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.CartItem

// MARK: - Split Payment Types

enum class SplitType(val label: String) {
    FULL_PAYMENT("Pago completo"),
    BY_PRODUCT("Por producto"),
    EQUAL_PARTS("Partes iguales"),
    CUSTOM_AMOUNT("Monto personalizado"),
}

data class SplitConfig(
    val type: SplitType = SplitType.FULL_PAYMENT,
    val selectedItemIds: List<String> = emptyList(),
    val numberOfParts: Int = 2,
    val customAmountCents: Int = 0,
)

// MARK: - Split Payment Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitPaymentSheet(
    totalCents: Int,
    items: List<CartItem>,
    allowByProduct: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (SplitConfig) -> Unit,
) {
    var selectedOption by remember { mutableStateOf<SplitType?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Dividir cuenta",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            if (selectedOption == null) {
                // Option selection
                if (allowByProduct) {
                    SplitOptionRow(
                        title = "Por producto",
                        description = "Selecciona los productos para este pago",
                        onClick = { selectedOption = SplitType.BY_PRODUCT },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SplitOptionRow(
                    title = "Partes iguales",
                    description = "Divide el total entre varias personas",
                    onClick = { selectedOption = SplitType.EQUAL_PARTS },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SplitOptionRow(
                    title = "Monto personalizado",
                    description = "Ingresa el monto a cobrar",
                    onClick = { selectedOption = SplitType.CUSTOM_AMOUNT },
                )
            } else {
                when (selectedOption) {
                    SplitType.BY_PRODUCT -> ByProductContent(
                        items = items,
                        onBack = { selectedOption = null },
                        onConfirm = { selectedIds ->
                            onConfirm(
                                SplitConfig(
                                    type = SplitType.BY_PRODUCT,
                                    selectedItemIds = selectedIds,
                                ),
                            )
                        },
                    )
                    SplitType.EQUAL_PARTS -> EqualPartsContent(
                        totalCents = totalCents,
                        onBack = { selectedOption = null },
                        onConfirm = { parts ->
                            onConfirm(
                                SplitConfig(
                                    type = SplitType.EQUAL_PARTS,
                                    numberOfParts = parts,
                                ),
                            )
                        },
                    )
                    SplitType.CUSTOM_AMOUNT -> CustomAmountContent(
                        totalCents = totalCents,
                        onBack = { selectedOption = null },
                        onConfirm = { amountCents ->
                            onConfirm(
                                SplitConfig(
                                    type = SplitType.CUSTOM_AMOUNT,
                                    customAmountCents = amountCents,
                                ),
                            )
                        },
                    )
                    else -> { /* FULL_PAYMENT not shown here */ }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Option Row

@Composable
private fun SplitOptionRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - By Product Content

@Composable
private fun ByProductContent(
    items: List<CartItem>,
    onBack: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selectedIds = remember { mutableStateListOf<String>() }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Selecciona productos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Atrás",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            items.forEach { item ->
                val isSelected = item.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isSelected) selectedIds.remove(item.id)
                            else selectedIds.add(item.id)
                        }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckBox
                        else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                    Text(
                        text = "${item.name} x${item.quantity}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$${String.format("%.2f", item.totalPrice / 100.0)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Button(
            onClick = { onConfirm(selectedIds.toList()) },
            enabled = selectedIds.isNotEmpty(),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = "Confirmar selección",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MARK: - Equal Parts Content

@Composable
private fun EqualPartsContent(
    totalCents: Int,
    onBack: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var parts by remember { mutableIntStateOf(2) }
    val perPersonCents = (totalCents + parts - 1) / parts

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Partes iguales",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Atrás",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Total display
        Text(
            text = "Total: $${String.format("%.2f", totalCents / 100.0)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // People counter
        Text(
            text = "Número de personas",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (parts > 2) parts-- },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = "Menos",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = "$parts",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xxxl),
            )

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (parts < 10) parts++ },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Mas",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Per-person amount
        Text(
            text = "Monto por persona",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$${String.format("%.2f", perPersonCents / 100.0)}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Button(
            onClick = { onConfirm(parts) },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = "Dividir en $parts partes",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MARK: - Custom Amount Content

@Composable
private fun CustomAmountContent(
    totalCents: Int,
    onBack: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    val amountCents = ((amountText.toDoubleOrNull() ?: 0.0) * 100).toInt()
    val isValid = amountCents > 0 && amountCents <= totalCents

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Monto personalizado",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Atrás",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = "Total de la cuenta: $${String.format("%.2f", totalCents / 100.0)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        OutlinedTextField(
            value = amountText,
            onValueChange = { newValue ->
                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                    amountText = newValue
                }
            },
            label = { Text("Monto a cobrar") },
            prefix = {
                Text(
                    text = "$ ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        if (amountCents > totalCents) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "El monto no puede superar el total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Button(
            onClick = { onConfirm(amountCents) },
            enabled = isValid,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = "Cobrar $${String.format("%.2f", amountCents / 100.0)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
