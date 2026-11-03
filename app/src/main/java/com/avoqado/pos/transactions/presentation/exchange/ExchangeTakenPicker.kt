package com.avoqado.pos.transactions.presentation.exchange

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.presentation.product.variantes.*
import com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExchangeTakenPicker(state: ExchangeUiState, viewModel: ExchangeViewModel, modifier: Modifier = Modifier) {
    val catalog by viewModel.products.collectAsState()
    val loading by viewModel.catalogLoading.collectAsState()
    val error by viewModel.catalogError.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var familyId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanner by rememberSaveable { mutableStateOf(false) }
    val families = remember(catalog) { familiasDe(catalog) }
    val selected = state.returned.filter { it.quantity > 0 }
    val struckProductIds = selected.mapNotNull { it.item.productId }.toSet()
    val results = remember(catalog, query) { viewModel.searchProducts(query) }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchPillField(query, { query = it }, TextosCambio.BUSCAR, Modifier.weight(1f))
                    IconButton(onClick = { scanner = true }) { Icon(Icons.Filled.QrCodeScanner, TextosCambio.ESCANEAR) }
                }
            }
            selected.distinctBy { it.item.productGroupId }.forEach { row ->
                val family = families[row.item.productGroupId] ?: return@forEach
                item(key = "family:${family.groupId}") {
                    TextButton(onClick = { familyId = family.groupId; sourceId = row.item.id }) {
                        Text(TextosCambio.OTRA_TALLA.replace("{producto}", family.nombre))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
                        family.variantes.forEach { p ->
                            val struck = p.id in struckProductIds
                            FilterChip(selected = false,
                                enabled = variantSelectable(p, struckProductIds, disableOutOfStock = true),
                                onClick = { viewModel.addTaken(p, row.item.id) },
                                label = { Text(p.variantOptions.orEmpty().joinToString(" · ") { it.value } +
                                    if (p.isOutOfStock) " · ${TextosCambio.AGOTADO}" else "",
                                    textDecoration = if (struck) TextDecoration.LineThrough else null) })
                        }
                    }
                }
            }
            item { Text(TextosCambio.TODOS_PRODUCTOS, style = MaterialTheme.typography.labelLarge) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item {
                Text(TextosCambio.CATALOGO_NO_DISPONIBLE, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!loading && results.isEmpty()) item {
                Text(TextosCambio.SIN_PRODUCTOS, style = MaterialTheme.typography.bodyMedium)
            }
            items(results, key = { it.id }) { product ->
                val enabled = !product.isOutOfStock && (product.productGroupId == null || product.id !in struckProductIds)
                Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { viewModel.addTaken(product) }
                    .padding(vertical = AvoqadoTheme.spacing.md)) {
                    Text(product.name, style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(exchangeMoney(catalogPriceCents(product)!!), style = MaterialTheme.typography.bodyMedium)
                    if (product.isOutOfStock) Text(TextosCambio.AGOTADO, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        familyId?.let { id -> families[id]?.let { family ->
            HojaDeVariantes(familia = family, onElegir = { viewModel.addTaken(it, sourceId); familyId = null },
                onCerrar = { familyId = null }, struckProductIds = struckProductIds, disableOutOfStock = true)
        } }
        if (scanner) {
            BackHandler { scanner = false }
            BarcodeScannerView(onBarcodeScanned = { code -> query = code; scanner = false }, onDismiss = { scanner = false })
        }
    }
}
