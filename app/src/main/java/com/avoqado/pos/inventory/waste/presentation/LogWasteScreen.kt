package com.avoqado.pos.inventory.waste.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import com.avoqado.pos.inventory.waste.domain.WasteReason
import com.avoqado.pos.inventory.waste.domain.etiquetaDeUnidad

/**
 * «Registrar merma», en el orden del spec §5: buscador → cantidad → motivo → nota →
 * confirmación SIEMPRE. Espejo de `LogWasteView.swift` de avoqado-ios.
 *
 * El botón «Registrar» vive en el encabezado (regla del repo para formularios de pantalla
 * completa) y sólo abre la confirmación; la merma se escribe al confirmar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogWasteScreen(
    onDismiss: () -> Unit,
    /**
     * Qué apertura es ésta. Volver a MOSTRAR la misma (girar, regresar de otra pestaña) no la abre de nuevo: la
     * captura en curso se conserva (🔴 Codex r9). Quien abre el formulario da un número nuevo cada vez.
     */
    idApertura: Long,
    /** Desde la ficha de un artículo en Inventario: llega con ese artículo ya elegido. */
    preseleccion: ArticuloPreseleccionado? = null,
    viewModel: LogWasteViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsState()
    LaunchedEffect(idApertura) { viewModel.abrirFormulario(idApertura, preseleccion) }
    DisposableEffect(Unit) { onDispose { viewModel.formularioCerrado() } }
    BackHandler(onBack = onDismiss)
    // El historial se pinta ENCIMA: salir de la composición del formulario llamaría `formularioCerrado()`
    // y silenciaría el aviso de una merma recién confirmada.
    var verHistorial by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AvoqadoFullscreenHeader(
                title = TextosMerma.TITULO,
                onNav = onDismiss,
                primaryActionText = TextosMerma.REGISTRAR,
                onPrimaryAction = viewModel::pedirConfirmacion,
                primaryActionEnabled = estado.puedeConfirmar,
                showDivider = true,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))

            if (estado.rechazos.isNotEmpty()) {
                LetreroDeRechazos(estado.rechazos, onVisto = viewModel::rechazosVistos)
            }
            if (estado.bloqueadaPorPlan) {
                AvisoDePlan(onActualizar = { viewModel.alAbrir() })
            }
            if (estado.sinRed) {
                TextoSecundario(TextosMerma.SIN_RED)
            }
            estado.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            TextButton(onClick = { verHistorial = true }) { Text(TextosMerma.HISTORIAL) }

            Seccion(TextosMerma.ARTICULO)
            val articulo = estado.articulo
            if (articulo == null) {
                SearchPillField(
                    query = estado.busqueda,
                    onQueryChange = viewModel::buscar,
                    placeholder = TextosMerma.BUSCAR,
                    modifier = Modifier.fillMaxWidth(),
                )
                estado.antiguedadDelCatalogo?.let { TextoSecundario(it) }
                if (estado.resultados.isEmpty() && estado.busqueda.isNotBlank()) {
                    TextoSecundario(TextosMerma.SIN_RESULTADOS)
                }
                Resultados(estado.resultados, onElegir = viewModel::elegirArticulo)
            } else {
                ArticuloElegido(articulo, onCambiar = viewModel::quitarArticulo)

                Seccion(TextosMerma.CANTIDAD)
                AvoqadoPillTextField(
                    value = estado.cantidad,
                    onValueChange = viewModel::escribirCantidad,
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                    trailing = {
                        Text(
                            etiquetaDeUnidad(articulo.unit),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Seccion(TextosMerma.MOTIVO)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
                ) {
                    WasteReason.delPos.forEach { motivo ->
                        FilterChip(
                            selected = estado.motivo == motivo,
                            onClick = { viewModel.elegirMotivo(motivo) },
                            label = { Text(motivo.etiqueta) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                }

                Seccion(TextosMerma.NOTA)
                AvoqadoPillTextField(
                    value = estado.nota,
                    onValueChange = viewModel::escribirNota,
                    placeholder = if (estado.motivo?.exigeNota == true) {
                        TextosMerma.NOTA_OBLIGATORIA
                    } else {
                        TextosMerma.NOTA_OPCIONAL
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }
    if (verHistorial) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            HistorialDeMermaScreen(onDismiss = { verHistorial = false })
        }
    }
    }

    estado.confirmacion?.let { texto ->
        AvoqadoDialog(
            title = TextosMerma.CONFIRMAR_TITULO,
            description = texto,
            onDismiss = viewModel::cancelarConfirmacion,
            actionButton = {
                PrimaryButton(
                    text = TextosMerma.CONFIRMAR,
                    onClick = { viewModel.confirmar() },
                    enabled = !estado.enviando,
                    isLoading = estado.enviando,
                    fullWidth = true,
                )
            },
        ) {}
    }

    estado.aviso?.let { aviso ->
        val id = estado.avisoId
        // `key`: un aviso NUEVO es otro toast, con su propio temporizador; el del viejo no lo cierra.
        key(id) {
            AvoqadoSuccessToast(message = aviso.titulo, subtitle = aviso.detalle, onDismiss = { viewModel.avisoVisto(id) })
        }
    }
}

@Composable
private fun Seccion(titulo: String) {
    Text(titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TextoSecundario(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Lo que el servidor rechazó, fijo hasta que el cajero lo ve: la fila espera en «Mermas por subir». */
@Composable
private fun LetreroDeRechazos(rechazos: List<String>, onVisto: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(Modifier.padding(AvoqadoTheme.spacing.md)) {
            Text(
                textoDeRechazos(rechazos),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onVisto) { Text(TextosMerma.ENTENDIDO) }
        }
    }
}

/** Apagado se VE y se EXPLICA, con la salida a la mano: «Actualizar» vuelve a preguntar. */
@Composable
private fun AvisoDePlan(onActualizar: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(Modifier.padding(AvoqadoTheme.spacing.md)) {
            Text(TextosMerma.AVISO_DE_PLAN, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onActualizar) { Text(TextosMerma.ACTUALIZAR) }
        }
    }
}

/** Nombre, SKU y unidad. Nunca una existencia: el catálogo no las trae (spec §4.4). */
@Composable
private fun Resultados(resultados: List<WasteCatalogEntity>, onElegir: (WasteCatalogEntity) -> Unit) {
    Column {
        resultados.forEachIndexed { i, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onElegir(item) }
                    .padding(vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    TextoSecundario(listOf(item.sku, etiquetaDeUnidad(item.unit)).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
            if (i != resultados.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ArticuloElegido(articulo: WasteCatalogEntity, onCambiar: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(articulo.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                TextoSecundario(listOf(articulo.sku, etiquetaDeUnidad(articulo.unit)).filter { it.isNotBlank() }.joinToString(" · "))
            }
            TextButton(onClick = onCambiar) { Text(TextosMerma.CAMBIAR) }
        }
    }
}
