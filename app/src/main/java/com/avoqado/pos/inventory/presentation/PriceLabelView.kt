package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PlanGate
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.printing.data.EtiquetaDePrecio

// MARK: - Etiquetas de precio (PRO `PRICE_LABELS`)

/**
 * Elegir artículos y cuántas copias, e imprimir sus etiquetas de precio en la impresora del local.
 * Se abre desde Inventario → Descripción general (botón de impresora) y desde Artículos (dejar
 * presionado → «Imprimir etiqueta», con ese artículo ya elegido). Espejo de `PriceLabelView` iOS.
 *
 * Sin el plan: la hoja se abre igual y explica que es Pro (apagado se ve y se explica).
 */
@Composable
fun PriceLabelSheet(
    onDismiss: () -> Unit,
    preseleccion: List<String> = emptyList(),
    viewModel: PriceLabelViewModel = hiltViewModel(),
) {
    val products by viewModel.products.collectAsState()
    val copias by viewModel.copias.collectAsState()
    val imprimiendo by viewModel.imprimiendo.collectAsState()
    val error by viewModel.error.collectAsState()
    val impresas by viewModel.impresas.collectAsState()
    var busqueda by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.abrir(preseleccion) }

    val total = copias.values.sum()
    val visibles = remember(products, busqueda) {
        val q = busqueda.trim()
        if (q.isEmpty()) {
            products
        } else {
            products.filter { p ->
                listOfNotNull(p.name, p.sku, p.gtin, p.barcode).any { it.contains(q, ignoreCase = true) }
            }
        }
    }

    AvoqadoFullScreenModal(
        title = "Etiquetas de precio",
        onDismiss = onDismiss,
        primaryActionText = if (imprimiendo) "Imprimiendo…" else "Imprimir ($total)",
        onPrimaryAction = { viewModel.imprimir() },
        primaryActionEnabled = !viewModel.locked && total > 0 && !imprimiendo,
        primaryActionDisabledReason = when {
            viewModel.locked -> "Disponible en el plan ${viewModel.tierLabel}"
            total == 0 -> "Elige al menos un artículo"
            else -> null
        },
    ) {
        PlanGate(
            locked = viewModel.locked,
            featureName = "Las etiquetas de precio",
            requiredTierLabel = viewModel.tierLabel,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchPillField(
                    query = busqueda,
                    onQueryChange = { busqueda = it },
                    placeholder = "Buscar por nombre, SKU o GTIN",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg),
                    )
                }
                if (products.isEmpty()) {
                    Text(
                        text = "No hay artículos en este equipo. Conéctate a internet una vez para bajar tu catálogo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AvoqadoTheme.spacing.xxl),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibles, key = { it.id }) { product ->
                        EtiquetaRow(
                            product = product,
                            copias = copias[product.id] ?: 0,
                            onCambiar = { viewModel.cambiarCopias(product.id, it) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    impresas?.let { n ->
        AvoqadoSuccessToast(
            message = "¡Etiquetas impresas!",
            subtitle = if (n == 1) "1 etiqueta" else "$n etiquetas",
            onDismiss = { viewModel.limpiarAviso() },
        )
    }
}

@Composable
private fun EtiquetaRow(product: Product, copias: Int, onCambiar: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = copias == 0) { onCambiar(1) }
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val codigo = EtiquetaDePrecio.codigoDe(gtin = product.gtin, barcode = product.barcode, sku = product.sku)
            Text(
                text = listOfNotNull(product.displayPrice, codigo ?: "Sin código").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (copias == 0) {
            Text(
                text = "Agregar",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            BotonCopias(Icons.Filled.Remove, "Quitar una copia") { onCambiar(-1) }
            Text(
                text = "$copias",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 28.dp),
            )
            BotonCopias(Icons.Filled.Add, "Agregar una copia") { onCambiar(1) }
        }
    }
}

@Composable
private fun BotonCopias(icon: androidx.compose.ui.graphics.vector.ImageVector, descripcion: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = descripcion, tint = MaterialTheme.colorScheme.onSurface)
    }
}
