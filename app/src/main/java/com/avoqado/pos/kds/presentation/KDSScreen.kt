package com.avoqado.pos.kds.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.TierBadge
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.kds.domain.AvisoDeCocina
import com.avoqado.pos.kds.domain.CanalReparto
import com.avoqado.pos.kds.domain.EstadoDeCasilla
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.MotivoSoloApagar
import com.avoqado.pos.kds.domain.TextosDeCocina
import com.avoqado.pos.kds.domain.etiquetaDeEstacion
import com.avoqado.pos.kds.domain.idsParaMarcarTodas
import com.avoqado.pos.kds.domain.puedeApagar
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import kotlinx.coroutines.delay

// MARK: - Entry Point

@Composable
fun KDSScreen(
    onDismiss: () -> Unit,
    viewModel: KDSViewModel = hiltViewModel(),
) {
    val vista by viewModel.vista.collectAsState()
    val comandas by viewModel.comandas.collectAsState()
    val recientes by viewModel.recientes.collectAsState()
    val recientesNoLeidas by viewModel.recientesNoLeidas.collectAsState()
    val sinConexion by viewModel.sinConexion.collectAsState()
    val aviso by viewModel.aviso.collectAsState()
    val exito by viewModel.exito.collectAsState()
    val cambiandoPantalla by viewModel.cambiandoPantalla.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val canalesReparto by viewModel.canalesReparto.collectAsState()
    val config by viewModel.config.collectAsState()

    // 🔴 El sondeo vive MIENTRAS esta pantalla está a la vista: se cancela solo al cerrarla.
    LaunchedEffect(Unit) { viewModel.mientrasSeVe() }

    // Etapa 3 del KDS (3.5, D13): la pantalla de cocina no se apaga sola. Sin esto el aparato duerme, el receptor deja de
    // acusar y la caja imprime papel.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val recibiendoPorWifi by viewModel.recibiendoPorWifi.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showRecientes by remember { mutableStateOf(false) }
    var confirmacion by remember { mutableStateOf<Confirmacion?>(null) }
    // Qué canal está eligiendo duración. `null` = el diálogo está cerrado.
    var pausando by remember { mutableStateOf<CanalReparto?>(null) }

    // Tick every second for elapsed timers
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }

    // Clock display (in venue timezone, not device local)
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        clockText = java.time.Instant.ofEpochMilli(tick)
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(formatter)
    }

    // La estación en pantalla, sea que se esté mostrando su tablero o el aviso de "sin pantalla" —
    // es de ahí de donde salen las confirmaciones de prender/apagar.
    val estacionActual = when (val v = vista) {
        is VistaDeCocina.Tablero -> v.estacion
        is VistaDeCocina.SinPantalla -> v.estacion
        is VistaDeCocina.ElegirEstacion -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (sinConexion) BannerSinConexion(texto = if (recibiendoPorWifi) TextosDeCocina.SIN_INTERNET_CON_WIFI else TextosDeCocina.SIN_CONEXION)
        aviso?.let { BarraDeAviso(it, onCerrar = viewModel::cerrarAviso) }

        when (val v = vista) {
            is VistaDeCocina.ElegirEstacion -> SelectorDeEstacion(
                vista = v,
                sinConexion = sinConexion,
                configVersion = config.version,
                onDismiss = onDismiss,
                onElegir = viewModel::elegirEstacion,
            )

            is VistaDeCocina.SinPantalla -> PanelSinPantalla(
                estacion = v.estacion,
                estado = v.estado,
                cambiandoPantalla = cambiandoPantalla,
                onDismiss = onDismiss,
                onPrender = { confirmacion = Confirmacion.PRENDER },
                onCambiarEstacion = viewModel::cambiarEstacion,
            )

            is VistaDeCocina.Tablero -> TableroDeCocina(
                vista = v,
                comandas = comandas,
                config = config,
                clockText = clockText,
                tick = tick,
                isLargeFont = settings.largeFontEnabled,
                canalesReparto = canalesReparto,
                onDismiss = onDismiss,
                onAbrirRecientes = { showRecientes = true; viewModel.abrirRecientes() },
                onMarcarTodas = { confirmacion = Confirmacion.MARCAR_TODAS },
                onSettings = { showSettings = true },
                onListo = viewModel::listo,
                onAcceptDelivery = viewModel::acceptDeliveryOrder,
                onDenyDelivery = viewModel::denyDeliveryOrder,
                onPausar = { pausando = it },
                onReanudar = viewModel::reanudarReparto,
            )
        }
    }

    // MARK: - Confirmaciones
    confirmacion?.let { c ->
        val cerrar = { confirmacion = null }
        when (c) {
            Confirmacion.PRENDER -> estacionActual?.let { estacion ->
                val impresora = nombreImpresora(estacion, config.printers)
                AvoqadoDialog(
                    title = TextosDeCocina.prenderTitulo(estacion.name),
                    onDismiss = cerrar,
                    description = TextosDeCocina.prenderDetalle(estacion.name) + "\n\n" +
                        (impresora?.let { TextosDeCocina.conImpresora(it) } ?: TextosDeCocina.SIN_IMPRESORA_AL_PRENDER),
                    actionButton = {
                        PrimaryButton(
                            text = TextosDeCocina.PRENDER,
                            fullWidth = true,
                            onClick = { viewModel.cambiarPantalla(true); cerrar() },
                        )
                    },
                    content = {},
                )
            }

            Confirmacion.APAGAR -> estacionActual?.let { estacion ->
                val impresora = nombreImpresora(estacion, config.printers)
                AvoqadoDialog(
                    title = TextosDeCocina.apagarTitulo(estacion.name),
                    onDismiss = cerrar,
                    description = impresora?.let { TextosDeCocina.apagarConImpresora(it) } ?: TextosDeCocina.APAGAR_SIN_IMPRESORA,
                    actionButton = {
                        PrimaryButton(
                            text = TextosDeCocina.APAGAR,
                            fullWidth = true,
                            destructive = true,
                            onClick = { viewModel.cambiarPantalla(false); cerrar() },
                        )
                    },
                    content = {},
                )
            }

            Confirmacion.MARCAR_TODAS -> estacionActual?.let { estacion ->
                val n = idsParaMarcarTodas(comandas).size
                AvoqadoDialog(
                    title = TextosDeCocina.MARCAR_TODAS_TITULO,
                    onDismiss = cerrar,
                    description = TextosDeCocina.marcarTodasDetalle(n, estacion.name),
                    actionButton = {
                        PrimaryButton(
                            text = TextosDeCocina.marcarN(n),
                            fullWidth = true,
                            onClick = { viewModel.marcarTodasListas(); cerrar() },
                        )
                    },
                    content = {},
                )
            }
        }
    }

    // MARK: - ¿Cuánto frenar?
    pausando?.let { canal ->
        val cerrar = { pausando = null }
        AlertDialog(
            onDismissRequest = cerrar,
            title = { Text("Frenar el reparto") },
            text = {
                Column {
                    Text(
                        text = "Dejarás de recibir pedidos de reparto. Se reanuda solo cuando pase el tiempo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    DURACIONES_PAUSA.forEach { (minutos, etiqueta) ->
                        // Botones de ancho completo: esto se toca con las manos ocupadas y
                        // muchas veces con guantes. Un menú desplegable aquí no se acierta.
                        OutlinedButton(
                            onClick = {
                                viewModel.pausarReparto(canal.id, minutos)
                                cerrar()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AvoqadoTheme.spacing.xs),
                        ) {
                            Text(etiqueta, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
        )
    }

    // MARK: - Recientes
    if (showRecientes) {
        RecientesSheet(
            recientes = recientes,
            noLeidas = recientesNoLeidas,
            onDeshacer = viewModel::deshacer,
            onDismiss = { showRecientes = false },
        )
    }

    // MARK: - Settings Sheet
    if (showSettings) {
        val tablero = vista as? VistaDeCocina.Tablero
        KDSSettingsSheet(
            settings = settings,
            estacion = tablero?.estacion?.name,
            puedeApagar = tablero?.let { puedeApagar(it.estado, it.estacion.hasKitchenDisplay) } ?: false,
            detalleApagar = if (tablero?.estado == EstadoDeCasilla.SoloApagar(MotivoSoloApagar.LANZAMIENTO)) TextosDeCocina.PILOTO else null,
            onToggleSound = { viewModel.toggleSound() },
            onToggleLargeFont = { viewModel.toggleLargeFont() },
            onCambiarEstacion = { showSettings = false; viewModel.cambiarEstacion() },
            onApagar = { showSettings = false; confirmacion = Confirmacion.APAGAR },
            onDismiss = { showSettings = false },
        )
    }

    // MARK: - Éxito
    exito?.let { AvoqadoSuccessToast(message = it, onDismiss = viewModel::cerrarExito) }
}

private enum class Confirmacion { PRENDER, APAGAR, MARCAR_TODAS }

/** La impresora de una estación, por nombre — o `null` si no tiene o el nombre ya no se conoce. */
private fun nombreImpresora(estacion: StationInfo, printers: List<PrinterInfo>): String? =
    estacion.printerId?.let { pid -> printers.firstOrNull { it.id == pid }?.name }

// MARK: - Banner sin conexión

/** Sin red: se DICE, neutro, nunca rojo. El tablero conserva lo que ya tenía. */
@Composable
private fun BannerSinConexion(texto: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm)
            .testTag("kds-sin-conexion"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Barra de aviso (fija hasta "Entendido")

@Composable
private fun BarraDeAviso(aviso: AvisoDeCocina, onCerrar: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (aviso.esError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm)
            .testTag("kds-aviso"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = aviso.texto,
            style = MaterialTheme.typography.bodyLarge,
            color = if (aviso.esError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCerrar) {
            Text(
                TextosDeCocina.ENTENDIDO,
                color = if (aviso.esError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Selector de estación

@Composable
private fun SelectorDeEstacion(
    vista: VistaDeCocina.ElegirEstacion,
    sinConexion: Boolean,
    configVersion: String,
    onDismiss: () -> Unit,
    onElegir: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("kds-selector"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onDismiss)
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column {
                Text(
                    text = TextosDeCocina.ELEGIR_TITULO,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = TextosDeCocina.ELEGIR_DETALLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (vista.laGuardadaYaNoExiste) {
            Text(
                text = TextosDeCocina.ELEGIDA_YA_NO_EXISTE,
                style = MaterialTheme.typography.bodyMedium,
                // M5 (Ronda 2): no es un error, es un aviso — el mismo ámbar que AvoqadoWarningToast/ConnectivityBanner.
                color = Warning,
                modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
            )
        }

        when {
            // M5 (Ronda 2): con muchas estaciones la lista no cabía en pantalla, como iOS.
            vista.estaciones.isNotEmpty() -> Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                vista.estaciones.forEach { estacion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onElegir(estacion.id) }
                            .padding(vertical = AvoqadoTheme.spacing.sm)
                            .testTag("kds-estacion-${estacion.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = estacion.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (estacion.hasKitchenDisplay) TextosDeCocina.CON_PANTALLA else TextosDeCocina.SIN_PANTALLA,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            configVersion.isNotEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = TextosDeCocina.SIN_ESTACIONES,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
                )
            }

            !sinConexion -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Text(TextosDeCocina.CARGANDO, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// MARK: - Sin pantalla

@Composable
private fun PanelSinPantalla(
    estacion: StationInfo,
    estado: EstadoDeCasilla,
    cambiandoPantalla: Boolean,
    onDismiss: () -> Unit,
    onPrender: () -> Unit,
    onCambiarEstacion: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("kds-sin-pantalla"),
    ) {
        Box(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
            CircleBackButton(onClick = onDismiss)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(AvoqadoTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (estado is EstadoDeCasilla.RequierePro) {
                Text(
                    text = TextosDeCocina.REQUIERE_PRO,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                TierBadge(tierLabel = "Pro")
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = TextosDeCocina.PIDE_MEJORAR,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = TextosDeCocina.sinPantalla(estacion.name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                when (estado) {
                    is EstadoDeCasilla.SoloLectura -> Text(
                        text = TextosDeCocina.SOLO_QUIEN_CONFIGURA,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    is EstadoDeCasilla.Editable -> {
                        PrimaryButton(
                            text = TextosDeCocina.prenderPara(estacion.name),
                            isLoading = cambiandoPantalla,
                            onClick = onPrender,
                            modifier = Modifier.testTag("kds-prender"),
                        )
                        if (estado.soloAvoqado) {
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                            TierBadge(tierLabel = TextosDeCocina.SOLO_AVOQADO_INSIGNIA)
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                            Text(
                                text = TextosDeCocina.SOLO_AVOQADO,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is EstadoDeCasilla.Oculta -> Text(
                        text = TextosDeCocina.NO_LANZADA,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    else -> Unit
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
            TextButton(onClick = onCambiarEstacion) { Text(TextosDeCocina.CAMBIAR_ESTACION) }
        }
    }
}

// MARK: - Tablero

@Composable
private fun TableroDeCocina(
    vista: VistaDeCocina.Tablero,
    comandas: List<KDSOrder>,
    config: PrintConfig,
    clockText: String,
    tick: Long,
    isLargeFont: Boolean,
    canalesReparto: List<CanalReparto>,
    onDismiss: () -> Unit,
    onAbrirRecientes: () -> Unit,
    onMarcarTodas: () -> Unit,
    onSettings: () -> Unit,
    onListo: (String) -> Unit,
    onAcceptDelivery: (String) -> Unit,
    onDenyDelivery: (String) -> Unit,
    onPausar: (CanalReparto) -> Unit,
    onReanudar: (String) -> Unit,
) {
    Column(modifier = Modifier.testTag("kds-tablero")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onDismiss)
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column {
                Text(
                    text = vista.estacion.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = TextosDeCocina.pendientes(comandas.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.lg))
            Text(
                text = clockText,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onAbrirRecientes, modifier = Modifier.testTag("kds-recientes")) {
                Text(TextosDeCocina.RECIENTES)
            }
            if (idsParaMarcarTodas(comandas).isNotEmpty()) {
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                OutlinedButton(onClick = onMarcarTodas, modifier = Modifier.testTag("kds-marcar-todas")) {
                    Text(TextosDeCocina.MARCAR_TODAS)
                }
            }
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Box(
                modifier = Modifier
                    .size(AvoqadoTheme.dimensions.touchTarget)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Configuración",
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        canalesReparto.forEach { canal ->
            BarraReparto(
                canal = canal,
                ahora = tick,
                onPausar = { onPausar(canal) },
                onReanudar = { onReanudar(canal.id) },
            )
        }

        if (comandas.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = TextosDeCocina.SIN_COMANDAS,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        text = TextosDeCocina.vacioDe(vista.estacion.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
                contentPadding = PaddingValues(AvoqadoTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = comandas, key = { it.id }) { order ->
                    val elapsedMs = tick - order.createdAt
                    val elapsedText = formatElapsedTime(elapsedMs)

                    KDSOrderCard(
                        order = order,
                        elapsedText = elapsedText,
                        isLargeFont = isLargeFont,
                        etiqueta = etiquetaDeEstacion(order.printStationId, vista.estacion.id, config.stations),
                        onListo = { onListo(order.id) },
                        onAcceptDelivery = { onAcceptDelivery(order.id) },
                        onDenyDelivery = { onDenyDelivery(order.id) },
                    )
                }
            }
        }
    }
}

// MARK: - Recientes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecientesSheet(
    recientes: List<KDSOrder>,
    noLeidas: AvisoDeCocina?,
    onDeshacer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ImmersiveWindow()
        // M1: hasta 20 filas en una tablet en horizontal no caben en la altura de la hoja sin esto.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = TextosDeCocina.RECIENTES,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = TextosDeCocina.RECIENTES_DETALLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.lg),
            )

            if (noLeidas != null) {
                // I2: no se pudo leer — nunca se pinta como «no hay nada».
                Text(
                    text = noLeidas.texto,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (noLeidas.esError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xl).testTag("kds-recientes-no-leidas"),
                )
            } else if (recientes.isEmpty()) {
                Text(
                    text = TextosDeCocina.RECIENTES_VACIO,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xl),
                )
            } else {
                recientes.forEach { orden ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "#${orden.orderNumber} · ${orden.items.size} platillos",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(
                            onClick = { onDeshacer(orden.id) },
                            modifier = Modifier.testTag("kds-deshacer-${orden.id}"),
                        ) {
                            Text(TextosDeCocina.DESHACER)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Helpers

/**
 * El reloj de cada comanda.
 *
 * Antes los minutos crecían sin tope: una comanda de hora y media salía como
 * "90:14" y una olvidada de tres días como "4320:07". Medido en el iPad el
 * 2026-08-04 (mismo defecto en las dos plataformas): la pantalla mostraba
 * "30090:13" en TODOS los tickets. La cocina hace UNA cosa con este número
 * —mirarlo de reojo y saber si va tarde— y con cinco dígitos no se puede.
 *
 * Espejo de `KDSTiempo.formatear` en iOS.
 */
internal fun formatElapsedTime(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return when {
        s < 3600 -> "%d:%02d".format(s / 60, s % 60)
        s < 86_400 -> "%dh %02d".format(s / 3600, (s % 3600) / 60)
        else -> "${s / 86_400} d"
    }
}

/**
 * El estado del reparto y su freno, en una línea sobre el tablero.
 *
 * Tres estados y NINGUNO se ve igual, a propósito:
 *  · Recibiendo   → botón para frenar.
 *  · Pausado con reloj (lo pidió alguien del piso) → cuenta regresiva + reanudar.
 *  · Pausado SIN reloj (lo pidió el dueño desde el dashboard) → se ve, se explica, y NO
 *    trae botón. Desde el piso no se reabre lo que el dueño cerró — y una cuenta regresiva
 *    que no corre sería peor que nada, porque prometería una reactivación que no va a pasar.
 */
@Composable
private fun BarraReparto(
    canal: CanalReparto,
    ahora: Long,
    onPausar: () -> Unit,
    onReanudar: () -> Unit,
) {
    val restante = canal.pausadoHasta?.let { hasta ->
        runCatching { java.time.Instant.parse(hasta).toEpochMilli() - ahora }.getOrNull()
    }

    val (fondo, texto) = when {
        !canal.pausado -> MaterialTheme.colorScheme.surface to "Reparto recibiendo pedidos"
        restante != null && restante > 0 -> {
            val minutos = (restante / 60_000).toInt()
            val segundos = ((restante / 1000) % 60).toInt()
            MaterialTheme.colorScheme.tertiaryContainer to
                "Reparto en pausa · se reanuda en %d:%02d".format(minutos, segundos)
        }
        else -> MaterialTheme.colorScheme.errorContainer to "Reparto pausado por el administrador"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(fondo)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = texto, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

        when {
            !canal.pausado -> OutlinedButton(onClick = onPausar) { Text("Me saturé") }
            restante != null && restante > 0 -> TextButton(onClick = onReanudar) { Text("Ya estamos al día") }
            // Pausa del dueño: sin botón, a propósito.
            else -> Unit
        }
    }
}

/**
 * Cuánto frenar. Son las MISMAS cuatro opciones que acepta el servidor
 * (`SNOOZE_MINUTOS_VALIDOS`), espejadas por valor exacto: una quinta aquí daría un 400 que
 * el cocinero no puede interpretar.
 *
 * No hay "indefinido" a propósito. El modo de fallo de este patrón está documentado —en la
 * comunidad de Square, "pause stuck"—: alguien pausa a media cena, se le olvida, y el
 * negocio amanece apagado. Toda pausa desde el piso caduca sola.
 */
private val DURACIONES_PAUSA = listOf(20 to "20 minutos", 40 to "40 minutos", 60 to "1 hora", 120 to "2 horas")
