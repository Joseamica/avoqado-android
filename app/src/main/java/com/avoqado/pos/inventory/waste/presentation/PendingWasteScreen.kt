package com.avoqado.pos.inventory.waste.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.AvoqadoWarningToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.waste.domain.TextosMerma

/**
 * «Mermas por subir»: lo que la cola todavía guarda en este aparato. Cada fila dice por qué sigue
 * aquí; «Descartar» anula el folio en el servidor y EXIGE red (spec §4.3). Espejo de
 * `PendingWasteView.swift` de avoqado-ios.
 */
@Composable
fun PendingWasteScreen(
    onDismiss: () -> Unit,
    viewModel: PendingWasteViewModel = hiltViewModel(),
) {
    val filas by viewModel.filas.collectAsState()
    val aviso by viewModel.aviso.collectAsState()
    var porDescartar by remember { mutableStateOf<FilaPorSubir?>(null) }
    LaunchedEffect(Unit) { viewModel.cargar() }
    BackHandler(onBack = onDismiss)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { AvoqadoFullscreenHeader(title = TextosMerma.POR_SUBIR_TITULO, onNav = onDismiss, showDivider = true) },
    ) { padding ->
        if (filas.isEmpty()) {
            Text(
                TextosMerma.SIN_PENDIENTES,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(AvoqadoTheme.spacing.lg),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
            ) {
                items(filas, key = { it.folio }) { fila -> Fila(fila, onDescartar = { porDescartar = fila }) }
            }
        }
    }

    porDescartar?.let { fila ->
        AvoqadoDialog(
            title = TextosMerma.DESCARTAR_TITULO,
            description = TextosMerma.DESCARTAR_CONFIRMACION
                .replace("{cantidad}", fila.cantidad)
                .replace("{articulo}", fila.articulo),
            onDismiss = { porDescartar = null },
            actionButton = {
                PrimaryButton(
                    text = TextosMerma.DESCARTAR,
                    onClick = {
                        porDescartar = null
                        viewModel.descartar(fila.folio)
                    },
                    fullWidth = true,
                )
            },
        ) {}
    }

    aviso?.let {
        when (it.tono) {
            TonoDeAviso.EXITO -> AvoqadoSuccessToast(message = it.texto, onDismiss = viewModel::avisoVisto)
            TonoDeAviso.ADVERTENCIA -> AvoqadoWarningToast(message = it.texto, onDismiss = viewModel::avisoVisto)
            TonoDeAviso.ERROR -> AvoqadoErrorToast(message = it.texto, onDismiss = viewModel::avisoVisto)
        }
    }
}

@Composable
private fun Fila(fila: FilaPorSubir, onDescartar: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(fila.articulo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("${fila.cantidad} · ${fila.motivo}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    fila.explicacion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!fila.propia) {
                    Text(
                        TextosMerma.CAPTURADA_POR_OTRA,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (fila.sePuedeDescartar) {
                TextButton(onClick = onDescartar) { Text(TextosMerma.DESCARTAR) }
            }
        }
    }
}
