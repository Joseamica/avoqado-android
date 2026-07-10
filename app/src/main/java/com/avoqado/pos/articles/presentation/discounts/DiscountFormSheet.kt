package com.avoqado.pos.articles.presentation.discounts

import androidx.compose.foundation.layout.Arrangement
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.data.model.AdminDiscount
import com.avoqado.pos.articles.data.model.DiscountScope
import com.avoqado.pos.articles.data.model.DiscountType
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Info
import com.avoqado.pos.designsystem.theme.Success

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscountFormSheet(
    discount: AdminDiscount?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val products by viewModel.products.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var name by remember { mutableStateOf(discount?.name ?: "") }
    var saveError by remember { mutableStateOf<String?>(null) }
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
    var selectedTargetItemIds by remember {
        mutableStateOf(discount?.targetItemIds?.toSet() ?: emptySet())
    }
    var selectedTargetCategoryIds by remember {
        mutableStateOf(discount?.targetCategoryIds?.toSet() ?: emptySet())
    }

    val isEditing = discount != null

    val formScopes = listOf(DiscountScope.ORDER, DiscountScope.ITEM, DiscountScope.CATEGORY)
    val hasValidTargets = when (scope) {
        DiscountScope.ITEM -> selectedTargetItemIds.isNotEmpty()
        DiscountScope.CATEGORY -> selectedTargetCategoryIds.isNotEmpty()
        else -> true
    }

    LaunchedEffect(Unit) {
        viewModel.loadSectionData(ArticleSection.PRODUCTS)
        viewModel.loadSectionData(ArticleSection.CATEGORIES)
    }

    if (saveError != null) {
        AvoqadoDialog(
            title = "No se pudo guardar",
            description = saveError ?: "",
            onDismiss = { saveError = null },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { saveError = null })
            },
            content = {},
        )
    }

    AvoqadoFullScreenModal(
        title = if (isEditing) "Editar descuento" else "Nuevo descuento",
        onDismiss = onDismiss,
        primaryActionText = if (isEditing) "Guardar" else "Crear",
        onPrimaryAction = {
            val handle: (Boolean) -> Unit = { ok ->
                if (ok) onDismiss() else saveError = "No se pudo guardar. Intenta de nuevo."
            }
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
                    targetItemIds = selectedTargetItemIds.toList(),
                    targetCategoryIds = selectedTargetCategoryIds.toList(),
                    onResult = handle,
                )
            } else {
                viewModel.createDiscount(
                    name = name,
                    type = type,
                    value = resolvedValue,
                    scope = scope,
                    active = active,
                    requiresApproval = requiresApproval,
                    targetItemIds = selectedTargetItemIds.toList(),
                    targetCategoryIds = selectedTargetCategoryIds.toList(),
                    onResult = handle,
                )
            }
        },
        primaryActionEnabled = name.isNotBlank() && !isSaving && hasValidTargets,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
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

            if (scope == DiscountScope.ITEM) {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    Text(
                        text = "ARTICULOS OBJETIVO",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (products.isEmpty()) {
                        Text(
                            text = "No hay artículos disponibles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                        ) {
                            products.forEach { product ->
                                val selected = selectedTargetItemIds.contains(product.id)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedTargetItemIds = if (selected) {
                                            selectedTargetItemIds - product.id
                                        } else {
                                            selectedTargetItemIds + product.id
                                        }
                                    },
                                    label = { Text(product.name) },
                                )
                            }
                        }
                    }
                }
            }

            if (scope == DiscountScope.CATEGORY) {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    Text(
                        text = "CATEGORIAS OBJETIVO",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (categories.isEmpty()) {
                        Text(
                            text = "No hay categorías disponibles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                        ) {
                            categories.forEach { category ->
                                val selected = selectedTargetCategoryIds.contains(category.id)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedTargetCategoryIds = if (selected) {
                                            selectedTargetCategoryIds - category.id
                                        } else {
                                            selectedTargetCategoryIds + category.id
                                        }
                                    },
                                    label = { Text(category.name) },
                                )
                            }
                        }
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
        }
    }
}
