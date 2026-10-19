package com.avoqado.pos.pos.presentation.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.product.variantes.HojaDeLaFamiliaAbierta
import com.avoqado.pos.pos.presentation.product.variantes.ResultadoDeBusqueda
import com.avoqado.pos.pos.presentation.product.variantes.TextosVariantes
import com.avoqado.pos.pos.presentation.product.variantes.textoDePrecio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchOverlayView(
    viewModel: CartViewModel,
    onProductTap: (Product) -> Unit,
    onCreateProduct: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val query by viewModel.searchQuery.collectAsState()
    // La búsqueda se calcula fuera del hilo principal: puede ir un paso atrás del campo (ver `Busqueda`).
    val busqueda by viewModel.busqueda.collectAsState()
    val results = busqueda.resultadosPara(query)
    val focusRequester = remember { FocusRequester() }
    var abierta by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Buscar") },
                navigationIcon = {
                    CircleBackButton(
                        onClick = {
                            viewModel.updateSearchQuery("")
                            onDismiss()
                        },
                        modifier = Modifier.padding(start = AvoqadoTheme.spacing.sm),
                    )
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            SearchPillField(
                query = query,
                onQueryChange = { viewModel.updateSearchQuery(it) },
                placeholder = "Buscar productos...",
                modifier = Modifier.fillMaxWidth(),
                textFieldModifier = Modifier.focusRequester(focusRequester),
            )

            if (busqueda.sinResultadosPara(query)) {
                // Empty state with create option
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Text(
                            text = "Sin resultados para \"$query\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onCreateProduct != null) {
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                            PrimaryButton(
                                text = "Crear producto",
                                onClick = {
                                    val searchName = query.trim()
                                    viewModel.updateSearchQuery("")
                                    onCreateProduct(searchName)
                                },
                            )
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(results, key = {
                        when (it) {
                            is ResultadoDeBusqueda.DeProducto -> it.producto.id
                            is ResultadoDeBusqueda.DeFamilia -> "fam_" + it.familia.groupId
                        }
                    }) { resultado ->
                        when (resultado) {
                            is ResultadoDeBusqueda.DeProducto -> FilaDeResultado(
                                titulo = resultado.producto.name,
                                detalle = resultado.producto.sku?.let { "SKU: $it" },
                                precio = resultado.producto.displayPrice,
                                onClick = { onProductTap(resultado.producto) },
                            )
                            // La familia abre la misma hoja que su botón en la cuadrícula; lo elegido sigue por `onProductTap`.
                            is ResultadoDeBusqueda.DeFamilia -> FilaDeResultado(
                                titulo = resultado.familia.nombre,
                                detalle = TextosVariantes.variantes(resultado.familia.variantes.size),
                                precio = resultado.familia.textoDePrecio,
                                onClick = { abierta = resultado.familia.groupId },
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    HojaDeLaFamiliaAbierta(viewModel, abierta, onCerrar = { abierta = null }, onElegir = onProductTap)
}

@Composable
private fun FilaDeResultado(titulo: String, detalle: String?, precio: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.bodyLarge,
            )
            detalle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Text(
            text = precio,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
