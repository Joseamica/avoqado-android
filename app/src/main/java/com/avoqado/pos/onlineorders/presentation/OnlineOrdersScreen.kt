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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
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
    val dialogo = rememberDialogoDePedido()

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
                        ListaDeBandeja(
                            secciones = secciones,
                            sinConexion = sinConexion,
                            ocupados = ocupados,
                            hayMas = hayMas,
                            cargandoMas = cargandoMas,
                            hoyRecortado = hoyRecortado,
                            puedeActualizar = puedeActualizar,
                            puedeCancelar = puedeCancelar,
                            puedeCobrar = viewModel.puedeCobrar,
                            onPedirDialogo = dialogo::abrir,
                            onListo = viewModel::marcarListo,
                            onEntregado = viewModel::marcarEntregado,
                            onCobrar = viewModel::cobrar,
                            onReimprimir = viewModel::reimprimir,
                            onCargarMas = viewModel::cargarMas,
                        )
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

    // Los diálogos de las tarjetas viven AQUÍ, fuera de la lista: una fila que sale de la ventana ya no se lleva su diálogo.
    DialogosDeLaBandeja(
        dialogo = dialogo,
        secciones = secciones,
        onAceptar = viewModel::aceptar,
        onRechazar = viewModel::rechazar,
        onCancelar = viewModel::cancelar,
    )

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
            origen = cobro.origen,
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

/** La clave de la primera fila que la lista va a emitir: la cabecera de la primera sección con pedidos (`h-$titulo`). */
internal fun primeraClaveDeLaLista(secciones: SeccionesBandeja): String? = when {
    secciones.porAceptar.isNotEmpty() -> "h-${TextosPedidosEnLinea.SECCION_POR_ACEPTAR}"
    secciones.enPreparacion.isNotEmpty() -> "h-${TextosPedidosEnLinea.SECCION_EN_PREPARACION}"
    secciones.listos.isNotEmpty() -> "h-${TextosPedidosEnLinea.SECCION_LISTOS}"
    secciones.hoy.isNotEmpty() -> "h-${TextosPedidosEnLinea.SECCION_HOY}"
    else -> null
}

/** «Si estabas hasta arriba, te quedas hasta arriba»: sólo si la primera fila de la lista cambió respecto a la composición anterior. PURA. */
internal fun debeQuedarseArriba(estabaArriba: Boolean, primeraClaveAntes: String?, primeraClaveAhora: String?): Boolean =
    estabaArriba && primeraClaveAntes != null && primeraClaveAntes != primeraClaveAhora

/**
 * El pedido de un diálogo abierto, o `null` si ya no vale: ACEPTAR y RECHAZAR sólo mientras siga en «Por aceptar»; CANCELAR mientras
 * siga en «En preparación» o «Listos» y sin dinero. PURA.
 */
internal fun pedidoDelDialogo(orderId: String, tipo: TipoDeDialogo, secciones: SeccionesBandeja): OnlineOrder? = when (tipo) {
    TipoDeDialogo.ACEPTAR, TipoDeDialogo.RECHAZAR -> secciones.porAceptar.firstOrNull { it.orderId == orderId }
    TipoDeDialogo.CANCELAR ->
        (secciones.enPreparacion + secciones.listos).firstOrNull { it.orderId == orderId && sinDinero(it) }
}

/** El diálogo abierto de la bandeja (de qué pedido y cuál). Sobrevive a un giro de la tableta: se guarda como dos textos. */
@Stable
internal class DialogoDePedido(orderId: String? = null, tipo: TipoDeDialogo? = null) {
    var orderId by mutableStateOf(orderId)
        private set
    var tipo by mutableStateOf(tipo)
        private set

    fun abrir(orderId: String, tipo: TipoDeDialogo) {
        this.orderId = orderId
        this.tipo = tipo
    }

    fun cerrar() {
        orderId = null
        tipo = null
    }

    companion object {
        val Guardador: Saver<DialogoDePedido, ArrayList<String>> = Saver(
            save = { arrayListOf(it.orderId.orEmpty(), it.tipo?.name.orEmpty()) },
            restore = { (id, nombre) ->
                val tipo = TipoDeDialogo.entries.firstOrNull { it.name == nombre }
                if (id.isEmpty() || tipo == null) DialogoDePedido() else DialogoDePedido(id, tipo)
            },
        )
    }
}

@Composable
internal fun rememberDialogoDePedido(): DialogoDePedido = rememberSaveable(saver = DialogoDePedido.Guardador) { DialogoDePedido() }

/** La lista de la bandeja: las cuatro secciones con sus tarjetas. Los diálogos de las tarjetas NO viven aquí ([DialogosDeLaBandeja]). */
@Composable
internal fun ListaDeBandeja(
    secciones: SeccionesBandeja,
    sinConexion: Boolean,
    ocupados: Set<String>,
    hayMas: Boolean,
    cargandoMas: Boolean,
    hoyRecortado: Boolean,
    puedeActualizar: Boolean,
    puedeCancelar: Boolean,
    puedeCobrar: Boolean,
    onPedirDialogo: (orderId: String, tipo: TipoDeDialogo) -> Unit,
    onListo: (String) -> Unit,
    onEntregado: (String) -> Unit,
    onCobrar: (OnlineOrder) -> Unit,
    onReimprimir: (String) -> Unit,
    onCargarMas: () -> Unit,
    estadoLista: LazyListState = rememberLazyListState(),
) {
    val espacio = AvoqadoTheme.spacing

    // 🔴 «Si estabas hasta arriba, te quedas hasta arriba» (W77Q9). El anclaje por clave de LazyColumn deja como primera fila a la que
    // ya estaba (`h-En preparación`) y un pendiente que llega ARRIBA queda fuera de la ventana. Se mira ANTES de la medición nueva:
    // sin gesto en curso y en 0/0 hay que seguir en 0; con la primera fila distinta, `requestScrollToItem(0)` salta el anclaje sólo
    // en esa medición. Las claves de las filas no se tocan, y si la persona está más abajo (o deslizando) manda el anclaje de siempre.
    val estabaArriba = estadoLista.firstVisibleItemIndex == 0 && estadoLista.firstVisibleItemScrollOffset == 0 && !estadoLista.isScrollInProgress
    val primeraClaveAhora = primeraClaveDeLaLista(secciones)
    val primeraClaveAntes = remember { arrayOfNulls<String>(1) }
    val quedarseArriba = debeQuedarseArriba(estabaArriba, primeraClaveAntes[0], primeraClaveAhora)
    SideEffect {
        if (quedarseArriba) estadoLista.requestScrollToItem(0)
        primeraClaveAntes[0] = primeraClaveAhora
    }

    LazyColumn(
        state = estadoLista,
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
                    puedeCobrar = puedeCobrar,
                    sinConexion = sinConexion,
                    ocupado = pedido.orderId in ocupados,
                    onPedirDialogo = { onPedirDialogo(pedido.orderId, it) },
                    onListo = { onListo(pedido.orderId) },
                    onEntregado = { onEntregado(pedido.orderId) },
                    onCobrar = { onCobrar(pedido) },
                    onReimprimir = { onReimprimir(pedido.orderId) },
                )
            }
            if (pie != null) item(key = "pie-$titulo") { pie() }
        }

        val cargarMas: @Composable () -> Unit = {
            if (hayMas) {
                OutlinedButton(
                    onClick = onCargarMas,
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

/** Los tres diálogos de las tarjetas (aceptar, rechazar, cancelar), fuera de la lista. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DialogosDeLaBandeja(
    dialogo: DialogoDePedido,
    secciones: SeccionesBandeja,
    onAceptar: (orderId: String, minutos: Int) -> Unit,
    onRechazar: (orderId: String, motivo: String) -> Unit,
    onCancelar: (orderId: String) -> Unit,
) {
    val orderId = dialogo.orderId
    val tipo = dialogo.tipo
    if (orderId == null || tipo == null) return
    // Si el pedido ya no está donde la acción vale (otro aparato lo atendió), el diálogo se cierra, igual que cuando vivía en la tarjeta.
    val pedido = pedidoDelDialogo(orderId, tipo, secciones)
    if (pedido == null) {
        SideEffect { dialogo.cerrar() }
        return
    }
    val cerrar = dialogo::cerrar
    when (tipo) {
        TipoDeDialogo.ACEPTAR -> AlertDialog(
            onDismissRequest = cerrar,
            title = { Text(TextosPedidosEnLinea.ACEPTAR_TITULO) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    TextosPedidosEnLinea.MINUTOS_ACEPTAR.forEach { minutos ->
                        Button(onClick = { cerrar(); onAceptar(orderId, minutos) }, modifier = Modifier.testTag("minutos-$minutos")) {
                            Text(TextosPedidosEnLinea.minutosEtiqueta(minutos))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cerrar) { Text(TextosPedidosEnLinea.CERRAR) } },
        )
        TipoDeDialogo.RECHAZAR -> AlertDialog(
            onDismissRequest = cerrar,
            title = { Text(TextosPedidosEnLinea.RECHAZAR_TITULO) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    TextosPedidosEnLinea.MOTIVOS.forEach { (texto, codigo) ->
                        OutlinedButton(
                            onClick = { cerrar(); onRechazar(orderId, codigo) },
                            modifier = Modifier.fillMaxWidth().testTag("motivo-$codigo"),
                        ) { Text(texto) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cerrar) { Text(TextosPedidosEnLinea.CERRAR) } },
        )
        TipoDeDialogo.CANCELAR -> AlertDialog(
            onDismissRequest = cerrar,
            title = { Text(TextosPedidosEnLinea.CANCELAR_TITULO) },
            text = { Text(TextosPedidosEnLinea.pedidoNumero(pedido.orderNumber)) },
            confirmButton = {
                TextButton(onClick = { cerrar(); onCancelar(orderId) }, modifier = Modifier.testTag("cancelar-confirma")) {
                    Text(TextosPedidosEnLinea.CANCELAR_CONFIRMA, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = cerrar) { Text(TextosPedidosEnLinea.CANCELAR_VOLVER) } },
        )
    }
}
