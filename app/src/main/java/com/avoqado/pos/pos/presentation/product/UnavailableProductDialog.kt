package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.pos.data.model.IngredientShortage
import com.avoqado.pos.pos.data.model.Product

/**
 * Modal informativo para un producto agotado (espejo de avoqado-tpv).
 *
 * El tile agotado SIGUE siendo clickeable: tocar no agrega nada — informa QUÉ
 * falta. Con detalle de insumos (recetas) lista exactamente qué se acabó; sin
 * detalle cae a un texto genérico según el método de inventario.
 */
@Composable
fun UnavailableProductDialog(
    product: Product,
    onDismiss: () -> Unit,
) {
    val isRecipe = product.inventoryMethod == "RECIPE"
    // Prefiere los insumos insuficientes; si no vienen usa el cuello de botella;
    // sin nada (backend viejo) → texto genérico.
    val shortages = product.insufficientIngredients.orEmpty()
        .ifEmpty { product.limitingIngredient?.let { listOf(it) }.orEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendido") }
        },
        title = { Text(product.name) },
        text = {
            Column {
                when {
                    isRecipe && shortages.isNotEmpty() -> {
                        Text("No disponible porque falta:")
                        Spacer(modifier = Modifier.height(8.dp))
                        shortages.forEach { ing ->
                            Text(
                                text = "•  " + describeShortage(ing),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Avisa al encargado para resurtir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    isRecipe -> Text(
                        "No disponible. Se agotó un insumo de la receta. " +
                            "Avisa al encargado para resurtir.",
                    )
                    product.trackInventory == true -> Text("No disponible. Se agotó el stock de este producto.")
                    else -> Text("Este producto no está disponible por ahora.")
                }
            }
        },
    )
}

/** "Pan: agotado" · "Pan: quedan 0.5 kg (se necesita 1 kg)" */
private fun describeShortage(ing: IngredientShortage): String {
    val unit = unitLabel(ing.unit)
    return if (ing.available <= 0.0) {
        "${ing.name}: agotado"
    } else {
        "${ing.name}: quedan ${formatQty(ing.available)}$unit (se necesita ${formatQty(ing.required)}$unit)"
    }
}

private fun formatQty(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

private fun unitLabel(unit: String): String = when (unit.uppercase()) {
    "PIECE", "PIEZA", "UNIT", "UNIDAD" -> " pza"
    "GRAM", "GRAMO" -> " g"
    "KILOGRAM", "KILO", "KG" -> " kg"
    "LITER", "LITRO" -> " l"
    "MILLILITER", "ML" -> " ml"
    "", "NONE" -> ""
    else -> " ${unit.lowercase()}"
}
