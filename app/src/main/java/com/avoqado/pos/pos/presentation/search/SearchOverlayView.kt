package com.avoqado.pos.pos.presentation.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
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
    // Windows: el renglón marcado con las flechas. Cada letra nueva (también la pistola) la quita: Enter sin marca sigue su camino.
    var marcada by remember(query) { mutableIntStateOf(-1) }
    val marcadaVigente = marcada.takeIf { it in results.indices } ?: -1
    val lista = rememberLazyListState()
    val elegir: (ResultadoDeBusqueda) -> Unit = { resultado ->
        when (resultado) {
            is ResultadoDeBusqueda.DeProducto -> onProductTap(resultado.producto)
            // La familia abre la misma hoja que su botón en la cuadrícula; lo elegido sigue por `onProductTap`.
            is ResultadoDeBusqueda.DeFamilia -> abierta = resultado.familia.groupId
        }
    }
    // Que el marcado se vea: la lista se recorre lo justo (o salta a él si quedó lejos).
    LaunchedEffect(marcadaVigente) {
        if (marcadaVigente < 0) return@LaunchedEffect
        repeat(3) {
            val info = lista.layoutInfo
            val fila = info.visibleItemsInfo.firstOrNull { it.index == marcadaVigente }
            when {
                fila == null -> lista.scrollToItem(marcadaVigente)
                fila.offset < info.viewportStartOffset -> lista.scrollBy((fila.offset - info.viewportStartOffset).toFloat())
                fila.offset + fila.size > info.viewportEndOffset ->
                    lista.scrollBy((fila.offset + fila.size - info.viewportEndOffset).toFloat())
                else -> return@LaunchedEffect
            }
        }
    }

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
                textFieldModifier = Modifier.focusRequester(focusRequester).then(
                    if (LocalEsEscritorio.current) {
                        Modifier.flechasDeResultados(
                            total = { results.size },
                            marcada = { marcada.takeIf { it in results.indices } ?: -1 },
                            onMarcada = { marcada = it },
                        ) { elegir(results[it]) }
                    } else Modifier,
                ),
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
                LazyColumn(state = lista) {
                    itemsIndexed(results, key = { _, it ->
                        when (it) {
                            is ResultadoDeBusqueda.DeProducto -> it.producto.id
                            is ResultadoDeBusqueda.DeFamilia -> "fam_" + it.familia.groupId
                        }
                    }) { indice, resultado ->
                        when (resultado) {
                            is ResultadoDeBusqueda.DeProducto -> FilaDeResultado(
                                titulo = resultado.producto.name,
                                detalle = resultado.producto.sku?.let { "SKU: $it" },
                                precio = resultado.producto.displayPrice,
                                marcada = indice == marcadaVigente,
                                onClick = { elegir(resultado) },
                            )
                            is ResultadoDeBusqueda.DeFamilia -> FilaDeResultado(
                                titulo = resultado.familia.nombre,
                                detalle = TextosVariantes.variantes(resultado.familia.variantes.size),
                                precio = resultado.familia.textoDePrecio,
                                marcada = indice == marcadaVigente,
                                onClick = { elegir(resultado) },
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
private fun FilaDeResultado(titulo: String, detalle: String?, precio: String, marcada: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (marcada) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md, horizontal = AvoqadoTheme.spacing.sm),
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

/**
 * Windows (founder, 17-nov, cliente con teclado: «bajar a seleccionar las opciones que me arroja la búsqueda con un enter»):
 * ↓ y ↑ mueven la marca por los resultados y Enter elige el marcado. Va en el CAMPO, antes que él: el campo se comería las
 * flechas (mueven el cursor). Sin marca (-1), ↑ y Enter siguen su camino: la pistola termina cada código con Enter.
 *
 * 🔴 Cualquier otra tecla quita la marca AL MOMENTO, no al redibujar (Codex r2 #4): la pistola teclea su código y su Enter
 * en ~10 ms por tecla, antes del siguiente cuadro, y con la marca vieja ese Enter elegía el resultado anterior. Por eso
 * [total] y [marcada] se leen al llegar la tecla (estado vivo), no los que había al pintar.
 */
internal fun Modifier.flechasDeResultados(total: () -> Int, marcada: () -> Int, onMarcada: (Int) -> Unit, onElegir: (Int) -> Unit) =
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val hay = total()
        val ahora = marcada()
        when (e.key) {
            Key.DirectionDown -> if (hay == 0) false else { onMarcada((ahora + 1).coerceAtMost(hay - 1)); true }
            Key.DirectionUp -> if (ahora < 0) false else { onMarcada(ahora - 1); true }
            Key.Enter, Key.NumPadEnter -> if (ahora in 0 until hay) { onElegir(ahora); true } else false
            else -> { if (ahora >= 0) onMarcada(-1); false }
        }
    }
