package com.avoqado.pos.articles.presentation.discounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.articles.data.model.AdminDiscount
import com.avoqado.pos.articles.data.model.DiscountScope
import com.avoqado.pos.articles.data.model.DiscountType
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Info
import com.avoqado.pos.designsystem.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscountFormSheet(
    discount: AdminDiscount?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    var name by remember { mutableStateOf(discount?.name ?: "") }
    var type by remember { mutableStateOf(discount?.discountType ?: DiscountType.PERCENTAGE) }
    var value by remember {
        mutableStateOf(
            if (discount?.discountType == DiscountType.COMP) "" else discount?.value?.let {
                if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            } ?: "",
        )
    }
    var scope by remember { mutableStateOf(discount?.discountScope ?: DiscountScope.ORDER) }
    var active by remember { mutableStateOf(discount?.active ?: true) }
    var requiresApproval by remember { mutableStateOf(discount?.requiresApproval ?: false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = discount != null

    val formScopes = listOf(DiscountScope.ORDER, DiscountScope.ITEM, DiscountScope.CATEGORY)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            // MARK: - Title
            Text(
                text = if (isEditing) "Editar descuento" else "Nuevo descuento",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - DETALLES
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre del descuento") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = name.isBlank(),
            )

            // MARK: - TIPO Y VALOR
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "TIPO Y VALOR",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    DiscountType.entries.forEach { dt ->
                        FilterChip(
                            selected = type == dt,
                            onClick = {
                                type = dt
                                if (dt == DiscountType.COMP) value = ""
                            },
                            label = { Text(dt.label) },
                        )
                    }
                }

                // Value field — hidden for COMP type
                if (type != DiscountType.COMP) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = {
                            Text(
                                if (type == DiscountType.PERCENTAGE) "Porcentaje" else "Monto",
                            )
                        },
                        prefix = if (type == DiscountType.FIXED) {
                            { Text("$") }
                        } else {
                            null
                        },
                        suffix = if (type == DiscountType.PERCENTAGE) {
                            { Text("%") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            // MARK: - ALCANCE
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "ALCANCE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    formScopes.forEach { ds ->
                        FilterChip(
                            selected = scope == ds,
                            onClick = { scope = ds },
                            label = { Text(ds.label) },
                        )
                    }
                }
            }

            // MARK: - OPCIONES
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "OPCIONES",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Activo switch (green tint)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Activo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = active,
                        onCheckedChange = { active = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = Success,
                        ),
                    )
                }

                // Requiere aprobacion switch (blue tint)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Requiere aprobacion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = requiresApproval,
                        onCheckedChange = { requiresApproval = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = Info,
                        ),
                    )
                }
            }

            // MARK: - Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancelar")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        val resolvedValue = if (type == DiscountType.COMP) {
                            100.0
                        } else {
                            value.toDoubleOrNull() ?: 0.0
                        }
                        if (isEditing) {
                            viewModel.updateDiscount(
                                discountId = discount!!.id,
                                name = name,
                                type = type,
                                value = resolvedValue,
                                scope = scope,
                                active = active,
                                requiresApproval = requiresApproval,
                            )
                        } else {
                            viewModel.createDiscount(
                                name = name,
                                type = type,
                                value = resolvedValue,
                                scope = scope,
                                active = active,
                                requiresApproval = requiresApproval,
                            )
                        }
                        onDismiss()
                    },
                    enabled = name.isNotBlank() && !isSaving,
                ) {
                    Text(text = if (isEditing) "Guardar" else "Crear")
                }
            }
        }
    }
}
