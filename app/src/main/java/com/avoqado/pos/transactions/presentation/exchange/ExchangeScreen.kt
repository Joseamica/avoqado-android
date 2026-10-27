package com.avoqado.pos.transactions.presentation.exchange

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.FullscreenHeaderNav
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun ExchangeScreen(viewModel: ExchangeViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var showCart by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding().navigationBarsPadding()) {
        val isTablet = maxWidth >= 600.dp
        BackHandler { if (!isTablet && showCart) showCart = false else onClose() }
        Column(Modifier.fillMaxSize()) {
            AvoqadoFullscreenHeader(
                title = TextosCambio.TITULO.replace("{folio}", state.transaction?.orderNumber.orEmpty())
                    .replace("{cuando}", state.transaction?.dateGroup.orEmpty()),
                onNav = { if (!isTablet && showCart) showCart = false else onClose() },
                navStyle = if (!isTablet && showCart) FullscreenHeaderNav.BACK else FullscreenHeaderNav.CLOSE,
                backContentDescription = TextosCambio.REGRESAR,
                showDivider = true,
            )
            if (isTablet) {
                Row(Modifier.weight(1f)) {
                    ExchangeTakenPicker(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExchangeCart(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                }
            } else if (showCart) {
                ExchangeCart(state, viewModel, Modifier.weight(1f))
            } else {
                ExchangeTakenPicker(state, viewModel, Modifier.weight(1f))
                Box(Modifier.padding(AvoqadoTheme.spacing.md).fillMaxWidth()
                    .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                    .clickable { showCart = true }.padding(AvoqadoTheme.spacing.lg), contentAlignment = Alignment.Center) {
                    Text(if (state.calculation.takenPieces.size == state.taken.size)
                        TextosCambio.VER_CAMBIO.replace("{precio}", exchangeMoney(state.calculation.differenceCents))
                        else TextosCambio.CAMBIO,
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun ExchangeCart(state: ExchangeUiState, viewModel: ExchangeViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ExchangeReturnedList(state, viewModel)
            Text(TextosCambio.SE_LLEVA, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.taken.forEachIndexed { index, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.product.name, style = MaterialTheme.typography.bodyLarge)
                        state.calculation.takenPieces.getOrNull(index)?.let { piece ->
                            Text("+" + exchangeMoney(piece.priceCents), style = MaterialTheme.typography.titleMedium)
                        }
                        viewModel.inheritedLabel(index)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = { viewModel.removeTaken(index) }) {
                        Icon(Icons.Filled.Close, TextosCambio.QUITAR.replace("{producto}", row.product.name))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ExchangeBottomBar(state, onConfirm = { viewModel.prepareExchange() })
    }
}
