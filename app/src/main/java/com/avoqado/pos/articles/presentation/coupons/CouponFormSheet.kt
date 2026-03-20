package com.avoqado.pos.articles.presentation.coupons

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.articles.data.model.AdminCoupon
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponFormSheet(
    coupon: AdminCoupon?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val discounts by viewModel.discounts.collectAsState()

    var code by remember { mutableStateOf(coupon?.code ?: "") }
    var selectedDiscountId by remember { mutableStateOf(coupon?.discountId ?: "") }
    var maxUses by remember { mutableStateOf(coupon?.maxUses?.toString() ?: "") }
    var maxUsesPerCustomer by remember { mutableStateOf(coupon?.maxUsesPerCustomer?.toString() ?: "") }
    var active by remember { mutableStateOf(coupon?.active ?: true) }

    var discountMenuExpanded by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = coupon != null

    val selectedDiscount = remember(discounts, selectedDiscountId) {
        discounts.firstOrNull { it.id == selectedDiscountId }
    }

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
                text = if (isEditing) "Editar cupon" else "Nuevo cupon",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - CODIGO section
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "CODIGO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Codigo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    isError = code.isBlank(),
                )
                Text(
                    text = "3-30 caracteres, letras y numeros",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // MARK: - DESCUENTO ASOCIADO section
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "DESCUENTO ASOCIADO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = discountMenuExpanded,
                    onExpandedChange = { discountMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = selectedDiscount?.let { "${it.name} (${it.formattedValue})" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Descuento") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = discountMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        isError = selectedDiscountId.isBlank(),
                    )
                    ExposedDropdownMenu(
                        expanded = discountMenuExpanded,
                        onDismissRequest = { discountMenuExpanded = false },
                    ) {
                        discounts.forEach { discount ->
                            DropdownMenuItem(
                                text = { Text("${discount.name} (${discount.formattedValue})") },
                                onClick = {
                                    selectedDiscountId = discount.id
                                    discountMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // MARK: - LIMITES DE USO section
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "LIMITES DE USO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = maxUses,
                    onValueChange = { maxUses = it },
                    label = { Text("Usos totales") },
                    placeholder = { Text("Ilimitado") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxUsesPerCustomer,
                    onValueChange = { maxUsesPerCustomer = it },
                    label = { Text("Usos por cliente") },
                    placeholder = { Text("Ilimitado") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // MARK: - Active toggle
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
                        if (isEditing) {
                            viewModel.updateCoupon(
                                couponId = coupon!!.id,
                                code = code,
                                discountId = selectedDiscountId,
                                maxUses = maxUses.toIntOrNull(),
                                maxUsesPerCustomer = maxUsesPerCustomer.toIntOrNull(),
                                active = active,
                            )
                        } else {
                            viewModel.createCoupon(
                                code = code,
                                discountId = selectedDiscountId,
                                maxUses = maxUses.toIntOrNull(),
                                maxUsesPerCustomer = maxUsesPerCustomer.toIntOrNull(),
                                active = active,
                            )
                        }
                        onDismiss()
                    },
                    enabled = code.isNotBlank() && selectedDiscountId.isNotBlank() && !isSaving,
                ) {
                    Text(text = if (isEditing) "Guardar" else "Crear")
                }
            }
        }
    }
}
