package com.avoqado.pos.inventory.waste.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.waste.domain.TextosMerma

/**
 * El historial de mermas: sólo lectura, online-only a propósito y SIN pesos (los costos viven en el
 * dashboard). Espejo de `HistorialDeMermaView.swift` de avoqado-ios.
 */
@Composable
fun HistorialDeMermaScreen(
    onDismiss: () -> Unit,
    viewModel: HistorialDeMermaViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsState()
    LaunchedEffect(Unit) { viewModel.cargar() }
    BackHandler(onBack = onDismiss)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { AvoqadoFullscreenHeader(title = estado.titulo, onNav = onDismiss, showDivider = true) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
        ) {
            estado.aviso?.let { aviso ->
                item(key = "aviso") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(aviso, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (estado.filas.isEmpty()) viewModel.cargar() else viewModel.cargarMas() }) {
                            Text(TextosMerma.REINTENTAR)
                        }
                    }
                }
            }
            if (estado.cargado && estado.filas.isEmpty() && estado.aviso == null) {
                item(key = "vacio") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Secundario(TextosMerma.SIN_HISTORIAL)
                        // Codex r6: una merma recién registrada puede llegar después de esta lectura.
                        TextButton(onClick = { viewModel.cargar() }) { Text(TextosMerma.ACTUALIZAR) }
                    }
                }
            }
            items(estado.filas, key = { it.id }) { Renglon(it) }
            if (estado.desactualizada) {
                item(key = "cambio") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Secundario(TextosMerma.HISTORIAL_CAMBIO)
                        TextButton(onClick = { viewModel.cargar() }) { Text(TextosMerma.ACTUALIZAR) }
                    }
                }
            }
            if (estado.filas.isNotEmpty()) {
                item(key = "pie") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Secundario(
                            TextosMerma.MOSTRANDO.replace("{n}", estado.filas.size.toString())
                                .replace("{total}", estado.total.toString()),
                        )
                        if (estado.hayMas && !estado.cargando) {
                            TextButton(onClick = { viewModel.cargarMas() }) { Text(TextosMerma.CARGAR_MAS) }
                        } else if (!estado.cargando && !estado.desactualizada) {
                            // Siempre a la mano (Codex r5): una alta y una baja a media lectura dejan el total cuadrado.
                            TextButton(onClick = { viewModel.cargar() }) { Text(TextosMerma.ACTUALIZAR) }
                        }
                    }
                }
            }
            if (estado.cargando) {
                item(key = "cargando") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun Secundario(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Renglon(fila: FilaDeHistorial) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(fila.articulo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fila.hora, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(fila.detalle, style = MaterialTheme.typography.bodyMedium)
            fila.sinExistencia?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            fila.nota?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            fila.autor?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
