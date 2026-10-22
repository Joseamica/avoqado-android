package com.avoqado.pos.onlineorders.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.payment.domain.OrigenDelCobro
import com.avoqado.pos.payment.domain.PaymentOverlayState
import com.avoqado.pos.payment.presentation.PaymentFlowScreen
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel

/**
 * El sondeo de la bandeja vive MIENTRAS la pantalla está a la vista (`STARTED`), no mientras exista la composición: con la app en
 * segundo plano nadie mira. Fuera del Composable para poder probarlo.
 */
internal suspend fun OnlineOrdersViewModel.mientrasEsteALaVista(owner: LifecycleOwner) {
    owner.repeatOnLifecycle(Lifecycle.State.STARTED) { mientrasSeVe() }
}

/**
 * «Pedidos en línea»: cuatro secciones (Por aceptar · En preparación · Listos · Hoy) con las acciones de cada estado.
 * Pantalla completa dentro de «Más» (como Pedidos). Sin plan y sin pedidos abiertos es sólo el teaser.
 *
 * «Cobrar» (A5) abre AQUÍ MISMO el flujo de pago de siempre ([CobroDelPedido]), encima de la bandeja: efectivo (también sin
 * red: se guarda antes del POST y se sube solo) o terminal, contra la orden del pedido y nunca contra una mesa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnlineOrdersScreen(
    onDismiss: () -> Unit,
    viewModel: OnlineOrdersViewModel = hiltViewModel(),
    planRequerido: String = "Pro",
) {
    val espacio = AvoqadoTheme.spacing
    val teaser = remember(viewModel) { viewModel.esTeaser }

    if (!teaser) {
        // 🔴 Sólo mientras se ve: cerrada la pantalla o con la app en segundo plano no se consulta (y no late la tienda).
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) { viewModel.mientrasEsteALaVista(owner) }
        // Una caja que atiende pedidos no se apaga sola. `keepScreenOn` de la vista (nunca FLAG_KEEP_SCREEN_ON: no existe en el
        // adaptador de escritorio); en WINDOWS sólo guarda el booleano y NO despierta la PC.
        val view = LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    val secciones by viewModel.secciones.collectAsState()
    val sinConexion by viewModel.sinConexion.collectAsState()
    val aviso by viewModel.aviso.collectAsState()
    val ocupados by viewModel.ocupados.collectAsState()
    val hayMas by viewModel.hayMas.collectAsState()
    val cargandoMas by viewModel.cargandoMas.collectAsState()
    val hoyRecortado by viewModel.hoyRecortado.collectAsState()
    val cargando by viewModel.cargando.collectAsState()
    val leyoAlgunaVez by viewModel.leyoAlgunaVez.collectAsState()
    val noSePudoLeer by viewModel.noSePudoLeer.collectAsState()
    var pausando by remember { mutableStateOf(false) }
    val cobro by viewModel.cobroAbierto.collectAsState()
    var cobroAnteriorResuelto by remember { mutableStateOf<String?>(null) }
    val puedeActualizar = viewModel.puedeActualizar
    val puedeCancelar = viewModel.puedeCancelar

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = espacio.lg, vertical = espacio.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(TextosPedidosEnLinea.MENU, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!teaser) {
                            OutlinedButton(onClick = { pausando = true }, enabled = !sinConexion, modifier = Modifier.testTag("pausar")) {
                                Text(TextosPedidosEnLinea.PAUSAR_TIENDA)
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("cerrar-pedidos")) {
                            Icon(Icons.Outlined.Close, contentDescription = TextosPedidosEnLinea.CERRAR)
                        }
                    }
                }

                if (teaser) {
                    Teaser(planRequerido)
                    return@Column
                }

                // Sin red = estado normal: texto neutro, nunca rojo de error.
                if (sinConexion) AvisoNeutro(TextosPedidosEnLinea.SIN_CONEXION)
                if (noSePudoLeer) AvisoNeutro(TextosPedidosEnLinea.NO_SE_PUDO_LEER)
                aviso?.let { texto ->
                    AvisoNeutro(texto, accion = TextosPedidosEnLinea.ENTENDIDO, onAccion = viewModel::cerrarAviso)
                }

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    if (cargando && secciones.vacia) {
                        // Primera lectura: nada que mostrar todavía (el banner de sin conexión, si aplica, ya está arriba).
                        Text(
                            "…",
                            Modifier.padding(espacio.xl),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (secciones.vacia) {
                        // «No hay pedidos» sólo dice la verdad tras una lectura buena; sin ella (sin red o fallo) basta el aviso de arriba.
                        if (leyoAlgunaVez) Text(
                            TextosPedidosEnLinea.SIN_PEDIDOS,
                            Modifier.padding(espacio.xl),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(espacio.lg),
                            verticalArrangement = Arrangement.spacedBy(espacio.md),
                        ) {
                            fun tarjetas(titulo: String, lista: List<OnlineOrder>, seccion: SeccionDePedido, pie: (@Composable () -> Unit)? = null) {
                                if (lista.isEmpty()) return
                                item(key = "h-$titulo") {
                                    Text(
                                        "$titulo (${lista.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = espacio.sm).testTag("seccion-$titulo"),
                                    )
                                }
                                items(lista, key = { "$titulo-${it.orderId}" }) { pedido ->
                                    OnlineOrderCard(
                                        pedido = pedido,
                                        seccion = seccion,
                                        puedeActualizar = puedeActualizar,
                                        puedeCancelar = puedeCancelar,
                                        sinConexion = sinConexion,
                                        ocupado = pedido.orderId in ocupados,
                                        onAceptar = { viewModel.aceptar(pedido.orderId, it) },
                                        onRechazar = { viewModel.rechazar(pedido.orderId, it) },
                                        onListo = { viewModel.marcarListo(pedido.orderId) },
                                        onEntregado = { viewModel.marcarEntregado(pedido.orderId) },
                                        onCancelar = { viewModel.cancelar(pedido.orderId) },
                                        onCobrar = { viewModel.cobrar(pedido) },
                                        onReimprimir = { viewModel.reimprimir(pedido.orderId) },
                                    )
                                }
                                if (pie != null) item(key = "pie-$titulo") { pie() }
                            }

                            val cargarMas: @Composable () -> Unit = {
                                if (hayMas) {
                                    OutlinedButton(
                                        onClick = viewModel::cargarMas,
                                        enabled = !cargandoMas && !sinConexion,
                                        modifier = Modifier.fillMaxWidth().testTag("cargar-mas"),
                                    ) { Text(TextosPedidosEnLinea.CARGAR_MAS) }
                                }
                            }
                            tarjetas(TextosPedidosEnLinea.SECCION_POR_ACEPTAR, secciones.porAceptar, SeccionDePedido.POR_ACEPTAR)
                            tarjetas(TextosPedidosEnLinea.SECCION_EN_PREPARACION, secciones.enPreparacion, SeccionDePedido.EN_PREPARACION, cargarMas)
                            tarjetas(TextosPedidosEnLinea.SECCION_LISTOS, secciones.listos, SeccionDePedido.LISTOS, cargarMas)
                            tarjetas(
                                TextosPedidosEnLinea.SECCION_HOY, secciones.hoy, SeccionDePedido.HOY,
                                pie = if (hoyRecortado) {
                                    {
                                        Text(
                                            TextosPedidosEnLinea.SE_MUESTRAN_ULTIMOS,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth().testTag("hoy-recortado"),
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }

        // El cobro va ENCIMA de la bandeja (que sigue leyendo debajo). Uno a la vez; al cerrarse, la bandeja se relee.
        cobro?.let { abierto ->
            CobroDelPedido(
                cobro = abierto,
                onTerminado = viewModel::cobroTerminado,
                onCobroAnteriorResuelto = { cobroAnteriorResuelto = it },
            )
        }
    }

    cobroAnteriorResuelto?.let { mensaje ->
        // El MISMO ViewModel del cobro (mismo dueño de navegación), como en las mesas: cerrar el aviso lo reconoce.
        val cobroViewModel: PaymentFlowViewModel = hiltViewModel()
        val reconocer = {
            cobroAnteriorResuelto = null
            cobroViewModel.reconocerCobroAnteriorResuelto()
        }
        AlertDialog(
            onDismissRequest = reconocer,
            title = { Text(TextosPedidosEnLinea.COBRO_ANTERIOR_TITULO) },
            text = { Text(TextosPedidosEnLinea.cobroAnteriorResuelto(mensaje)) },
            confirmButton = { Button(onClick = reconocer) { Text(TextosPedidosEnLinea.ENTENDIDO) } },
        )
    }

    if (pausando) {
        AlertDialog(
            onDismissRequest = { pausando = false },
            title = { Text(TextosPedidosEnLinea.PAUSAR_TITULO) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    Text(TextosPedidosEnLinea.PAUSAR_AYUDA, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                        TextosPedidosEnLinea.MINUTOS_PAUSA.forEach { minutos ->
                            Button(onClick = { pausando = false; viewModel.pausar(minutos) }, modifier = Modifier.testTag("pausa-$minutos")) {
                                Text(TextosPedidosEnLinea.minutosEtiqueta(minutos))
                            }
                        }
                        OutlinedButton(onClick = { pausando = false; viewModel.pausar(null) }, modifier = Modifier.testTag("pausa-reanudar")) {
                            Text(TextosPedidosEnLinea.REANUDAR)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pausando = false }) { Text(TextosPedidosEnLinea.CERRAR) } },
        )
    }
}

/**
 * El flujo de pago de siempre para un pedido sin mesa (molde: el cobro de `TableOrderScreen`). 🔴 DINERO:
 * - carrito ESTABLE (`remember(cobro)`): es llave del arranque del cobro; uno nuevo por recomposición lo reiniciaba;
 * - la apertura viene del ViewModel de la bandeja: UNA por «Cobrar», aunque la superposición se vuelva a abrir;
 * - `resumeOrderId` = la orden del pedido y origen `PEDIDO_EN_LINEA`: una mesa en PAYING no lo captura;
 * - al terminar (cobrado, encolado o cancelado) sólo se relee la bandeja: nunca `delivered`.
 */
@Composable
private fun CobroDelPedido(cobro: CobroDePedido, onTerminado: () -> Unit, onCobroAnteriorResuelto: (String) -> Unit) {
    val carrito = remember(cobro) { carritoDelCobro(cobro) }
    // Mientras se cobra, la barra de pestañas no se puede tocar (igual que el cobro de mesas).
    DisposableEffect(Unit) {
        PaymentOverlayState.setPaying(true)
        onDispose { PaymentOverlayState.setPaying(false) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {})
            .testTag("cobro-pedido-${cobro.orderId}"),
    ) {
        PaymentFlowScreen(
            cartState = carrito,
            onPaymentCommitted = { /* El saldo y la entrega los dice el servidor: la bandeja se relee al cerrar. */ },
            onDone = onTerminado,
            onCancel = onTerminado,
            onPreviousChargeResolved = { mensaje ->
                onTerminado()
                onCobroAnteriorResuelto(mensaje)
            },
            resumeOrderId = cobro.orderId,
            origen = OrigenDelCobro.PEDIDO_EN_LINEA,
            apertura = cobro.apertura,
        )
    }
}

/** Banda neutra (nunca rojo de error): sin conexión, o lo último que pasó con una acción. */
@Composable
private fun AvisoNeutro(texto: String, accion: String? = null, onAccion: () -> Unit = {}) {
    val espacio = AvoqadoTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = espacio.lg, vertical = espacio.xxs),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = espacio.md, vertical = espacio.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (accion != null) TextButton(onClick = onAccion) { Text(accion) }
        }
    }
}

@Composable
private fun Teaser(plan: String) {
    val espacio = AvoqadoTheme.spacing
    Column(
        Modifier.fillMaxSize().padding(espacio.xl).testTag("teaser-pedidos"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(TextosPedidosEnLinea.TEASER_TITULO, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = espacio.sm))
        Text(
            TextosPedidosEnLinea.TEASER_CUERPO,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = espacio.xs),
        )
        Text(
            TextosPedidosEnLinea.teaserPlan(plan),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = espacio.sm),
        )
    }
}
