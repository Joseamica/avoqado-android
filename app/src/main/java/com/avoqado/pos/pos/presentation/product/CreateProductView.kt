package com.avoqado.pos.pos.presentation.product

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.model.CreateProductRequest
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProductView(
    productsRepository: ProductsRepository,
    onProductCreated: (Product) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }
    var sku by remember { mutableStateOf(generateRandomSku()) }
    var gtin by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var menuCategories by remember { mutableStateOf<List<ProductCategory>>(emptyList()) }

    val coroutineScope = rememberCoroutineScope()
    val canSave = name.isNotBlank() && price.isNotBlank() && selectedCategory != null

    // Fetch menu categories on appear
    LaunchedEffect(Unit) {
        menuCategories = productsRepository.fetchMenuCategories()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: X close | spacer | "Guardar" button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        val priceDouble = price.toDoubleOrNull() ?: return@Button
                        val category = selectedCategory ?: return@Button
                        isSaving = true
                        coroutineScope.launch {
                            val request = CreateProductRequest(
                                name = name,
                                price = priceDouble,
                                type = "FOOD_AND_BEV",
                                categoryId = category.id,
                                sku = sku.ifBlank { null },
                                gtin = gtin.ifBlank { null },
                            )
                            val result = productsRepository.createProduct(request)
                            result.fold(
                                onSuccess = { product ->
                                    Log.d("📦", "Product created: ${product.name}")
                                    onProductCreated(product)
                                },
                                onFailure = { error ->
                                    Log.e("📦", "Create failed: ${error.message}")
                                    isSaving = false
                                },
                            )
                        }
                    },
                    enabled = canSave && !isSaving,
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                    }
                    Text(
                        text = "Guardar",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Form
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AvoqadoTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
            ) {
                Text(
                    text = "Crear articulo nuevo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                // Name (required)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Category picker (required)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (selectedCategory == null) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp),
                        )
                        .clickable { showCategoryPicker = true }
                        .padding(AvoqadoTheme.spacing.lg),
                ) {
                    if (selectedCategory != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                        ) {
                            selectedCategory!!.color?.let { colorHex ->
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(parseHexColor(colorHex)),
                                )
                            }
                            Text(
                                text = selectedCategory!!.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        Text(
                            text = "Categoria *",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Price (required)
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Precio *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix = { Text("$") },
                )

                // GTIN + SKU side by side
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = gtin,
                        onValueChange = { gtin = it },
                        label = { Text("GTIN") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it },
                        label = { Text("SKU") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
            }
        }

        // Category picker bottom sheet
        if (showCategoryPicker) {
            ModalBottomSheet(
                onDismissRequest = { showCategoryPicker = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AvoqadoTheme.spacing.xxxl),
                ) {
                    Text(
                        text = "Seleccionar categoria",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = AvoqadoTheme.spacing.xl,
                            vertical = AvoqadoTheme.spacing.lg,
                        ),
                    )

                    if (menuCategories.isEmpty()) {
                        Text(
                            text = "No hay categorias disponibles",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
                        )
                    } else {
                        menuCategories.forEach { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCategory = category
                                        showCategoryPicker = false
                                    }
                                    .padding(
                                        horizontal = AvoqadoTheme.spacing.xl,
                                        vertical = AvoqadoTheme.spacing.md,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                            ) {
                                // Color dot
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(
                                            category.color?.let { parseHexColor(it) }
                                                ?: MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                )
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun generateRandomSku(): String {
    val letters = ('A'..'Z').toList()
    val digits = ('0'..'9').toList()
    val randomDigits = (1..5).map { digits.random() }.joinToString("")
    val randomLetters = (1..2).map { letters.random() }.joinToString("")
    return "$randomDigits$randomLetters"
}

private fun parseHexColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        Color(android.graphics.Color.parseColor("#$cleaned"))
    } catch (_: Exception) {
        Color.Gray
    }
}
