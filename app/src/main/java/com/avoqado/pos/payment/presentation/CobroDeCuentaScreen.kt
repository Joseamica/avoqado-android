package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.restanteCents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CobroDeCuentaViewModel @Inject constructor(
    private val repo: TableServiceRepository,
    private val storage: SecureStorage,
    private val red: ConnectivityMonitor,
    private val roles: RoleManager,
    private val store: SolicitudesDeTarjetaStore,
    private val cobrosLocales: com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta? = null,
) : ViewModel() {
    private val foto = MutableStateFlow<OrderDetail?>(null)
    val detalle = foto.asStateFlow()
    val conectado get() = red.isConnected
    val servidor get() = red.isServerReachable
    private val aviso = MutableStateFlow<String?>(null)
    val mensaje = aviso.asStateFlow()
    val solicitudes = store.solicitudes
    val venueActual: String? get() = storage.venueId
    val puedeCobrar: Boolean get() = roles.hasVenuePermission("payments:create")
    val puedeCancelar: Boolean get() = roles.hasVenuePermission("orders:cancel-unpaid")
    private var venueDeLaCuenta: String? = null
    private val ofertaActual = MutableStateFlow(com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA)
    val oferta = ofertaActual.asStateFlow()
    suspend fun cargar(orderId: String) {
        foto.value = null
        aviso.value = null
        val venue = storage.venueId
        venueDeLaCuenta = venue
        if (venue == null) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return }
        val resultado = repo.getOrderDetail(venue, orderId)
        if (storage.venueId != venue) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return }
        resultado.fold(onSuccess = {
            if (it.id != orderId) aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA
            else if (it.paymentStatus == "PAID" || it.status in listOf("CANCELLED", "DELETED"))
                aviso.value = "Esta cuenta no tiene saldo pendiente."
            else foto.value = it
        }, onFailure = { aviso.value = com.avoqado.pos.core.data.network.ServerErrorText.humanize(it) })
    }
    suspend fun ofertaParaCobrar(orderId: String, laTraeUnCarrito: Boolean = false): com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta {
        cargar(orderId)
        val cuenta = foto.value
        val venue = venueDeLaCuenta ?: return com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA
        val pendiente = try { cobrosLocales?.hayPendiente(venue, orderId) ?: true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { true }
        if (storage.venueId != venue || foto.value?.id != orderId) return com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA
        val resultado = com.avoqado.pos.giftcards.domain.ofertaDeLaCuenta(cuenta?.let(::restanteCents),
            cuenta == null || cuenta.esDeMesa != false && !cuenta.esCuentaDeTarjetas, laTraeUnCarrito, pendiente)
        ofertaActual.value = resultado
        return resultado
    }
    suspend fun cancelar(reason: String): Boolean {
        val cuenta = foto.value ?: return false
        val venue = venueDeLaCuenta
        if (venue == null || venue != storage.venueId) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return false }
        if (!cuenta.esCuentaDeTarjetas || !puedeCancelar || reason.isBlank()) return false
        if (!red.isConnected.value || !red.isServerReachable.value) {
            aviso.value = TextosDeTarjetas.CUENTA_NO_SE_CANCELO_SIN_RED
            return false
        }
        val resultado = repo.cancelarEnLinea(venue, cuenta.id, reason)
        if (resultado.isSuccess) {
            foto.value = null
            val ventas = store.delNegocio(venue).filter { it.tipo == "GIFT_CARD_SALE" && it.orderId == cuenta.id }
            if (ventas.any { !store.quitar(it.llave) }) {
                aviso.value = "La cuenta se canceló. No se pudo actualizar el registro del aparato; vuelve a consultarla."
                return false
            }
            aviso.value = null
            return true
        }
        val error = resultado.exceptionOrNull()
        aviso.value = if (error is java.io.IOException || (error is retrofit2.HttpException && error.code() in 502..504))
            TextosDeTarjetas.CUENTA_NO_SE_CANCELO_SIN_RED
        else com.avoqado.pos.core.data.network.ServerErrorText.humanizeCancelacionDeCuenta(error, "No se pudo cancelar la cuenta.")
        return false
    }
}

enum class CierreDeCuenta { ABIERTA, PAGADA, CANCELADA }

@Composable
fun CobroDeCuentaScreen(
    orderId: String,
    onSalir: (CierreDeCuenta) -> Unit,
    viewModel: CobroDeCuentaViewModel = hiltViewModel(key = "cuenta-$orderId"),
) {
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val detalle by viewModel.detalle.collectAsState()
    val oferta by viewModel.oferta.collectAsState()
    val cuentaDelCarrito = LocalCuentaDelCarrito.current
    val cerrarResultadoDeSaldo = LocalCerrarCuentaDeSaldo.current
    fun salir(cierre: CierreDeCuenta) {
        if (cierre != CierreDeCuenta.ABIERTA) cerrarResultadoDeSaldo(orderId)
        onSalir(cierre)
    }
    val mensaje by viewModel.mensaje.collectAsState()
    val scope = rememberCoroutineScope()
    var cobrar by rememberSaveable(orderId) { mutableStateOf(false) }
    var avisoLocal by rememberSaveable(orderId) { mutableStateOf<String?>(null) }
    var restanteTrasCobrar by rememberSaveable(orderId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(orderId) { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) }
    Dialog(onDismissRequest = { onSalir(CierreDeCuenta.ABIERTA) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        ImmersiveWindow()
        DisposableEffect(Unit) {
            com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(true)
            onDispose { com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(false) }
        }
        val cuenta = detalle
        val cents = cuenta?.let(::restanteCents)
        if (cobrar && cuenta != null && cents != null && cents > 0) {
            val cart = remember(cuenta.id, cents) { CartState(items = listOf(CartItem(
                type = CartItemType.CustomAmount, name = if (cuenta.esCuentaDeTarjetas) TextosDeTarjetas.NOMBRE_EN_CUENTAS else "Saldo pendiente", unitPrice = cents))) }
            PaymentFlowScreen(cartState = cart, resumeOrderId = cuenta.id,
                cuentaDeTarjetas = cuenta.esCuentaDeTarjetas, desdeCuentasAbiertas = true,
                onCuentaConSaldo = { cobrar = false; scope.launch { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) } },
                onPaymentCommitted = { restanteTrasCobrar = it.remainingBalanceCents },
                onDone = {
                    if (restanteTrasCobrar == 0) salir(CierreDeCuenta.PAGADA)
                    else {
                        cobrar = false
                        restanteTrasCobrar = null
                        scope.launch { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) }
                    }
                },
                onCancel = { onSalir(CierreDeCuenta.ABIERTA) },
                onPreviousChargeResolved = { texto -> cobrar = false; avisoLocal = texto; scope.launch { if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) } },
            )
        } else {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                AvoqadoFullscreenHeader(title = if (cuenta?.esCuentaDeTarjetas == true) TextosDeTarjetas.NOMBRE_EN_CUENTAS else "Cuenta abierta",
                    onNav = { onSalir(CierreDeCuenta.ABIERTA) }, showDivider = true)
                Column(Modifier.padding(AvoqadoTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
                    if (!enLinea) Text(TextosDeTarjetas.SIN_RED) else (avisoLocal ?: mensaje)?.let { Text(it) }
                    when {
                        cents == null && cuenta != null -> Text("No se pudo consultar lo que falta. Vuelve a abrir la cuenta.")
                        cents == 0 -> Text("Esta cuenta no tiene saldo pendiente.")
                        cents != null && cents > 0 -> PrimaryButton(text = TextosDeTarjetas.cobrar(cents), enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCobrar && oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER),
                            onClick = { scope.launch { if (enLinea && viewModel.puedeCobrar && viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER) cobrar = true } })
                    }
                    if (oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR) Text(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
                    if (!viewModel.puedeCobrar) Text("No tienes permiso para cobrar. Pídeselo a tu gerente.")
                    TextButton(enabled = enLinea, onClick = { scope.launch { avisoLocal = null; if (enLinea) viewModel.ofertaParaCobrar(orderId, cuentaDelCarrito() == orderId) } }) { Text("Volver a consultar") }
                    if (cuenta != null && !cuenta.esCuentaDeTarjetas) RegresarSaldoDeCuenta(cuenta.id, cuenta.cobrosConSaldo ?: 0,
                        cuenta.paymentStatus == "PAID" || cuenta.status in listOf("CANCELLED", "DELETED"),
                        onCancelada = { salir(CierreDeCuenta.CANCELADA) })
                    if (cuenta?.esCuentaDeTarjetas == true && cuenta.paymentStatus != "PAID" &&
                        cuenta.status !in listOf("CANCELLED", "DELETED")) CancelarCuenta(viewModel) { salir(CierreDeCuenta.CANCELADA) }
                }
            }
        }
    }
}

@Composable
private fun CancelarCuenta(viewModel: CobroDeCuentaViewModel, onCancelada: () -> Unit) {
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val scope = rememberCoroutineScope()
    var confirmar by rememberSaveable { mutableStateOf(false) }
    var motivo by rememberSaveable { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    TextButton(enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCancelar && !enviando),
        onClick = { confirmar = true }) { Text(TextosDeTarjetas.CANCELAR_CUENTA) }
    if (!enLinea) Text(TextosDeTarjetas.SIN_RED)
    if (!viewModel.puedeCancelar) Text("No tienes permiso para cancelar cuentas. Pídeselo a tu gerente.")
    if (confirmar) AvoqadoDialog(onDismiss = { if (!enviando) confirmar = false },
        title = TextosDeTarjetas.CANCELAR_CUENTA, dismissOnClickOutside = !enviando) {
        if (!enLinea) Text(TextosDeTarjetas.SIN_RED)
        AvoqadoPillTextField(value = motivo, onValueChange = { motivo = it }, placeholder = "Motivo")
        PrimaryButton(text = TextosDeTarjetas.CANCELAR_CUENTA, enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCancelar && motivo.isNotBlank() && !enviando), onClick = {
            if (!viewModel.conectado.value || !viewModel.servidor.value) return@PrimaryButton
            confirmar = false
            enviando = true
            scope.launch {
                try { cancelarCuentaDesdeDialogo(motivo, viewModel::cancelar, onCancelada) }
                finally { enviando = false }
            }
        })
    }
}

val LocalCuentaDelCarrito = staticCompositionLocalOf<() -> String?> { { null } }
internal val LocalSolicitudesDeSaldo = compositionLocalOf<List<SolicitudDeTarjeta>> { emptyList() }
internal val LocalCuentasDeSaldoCerradas = compositionLocalOf<List<String>> { emptyList() }
internal val LocalCerrarCuentaDeSaldo = compositionLocalOf<(String) -> Unit> { {} }

val LocalResultadoDeTarjetas = staticCompositionLocalOf<(String) -> Unit> {
    { error("Falta el anfitrión del resultado de tarjetas") }
}

internal fun alSalirDeCuentaAbierta(cierre: CierreDeCuenta, esCuentaDeTarjetas: Boolean, orderId: String,
    onActualizar: () -> Unit, onCancelada: () -> Unit, onResultadoDeTarjetas: (String) -> Unit) {
    if (cierre == CierreDeCuenta.PAGADA && esCuentaDeTarjetas) onResultadoDeTarjetas(orderId)
    if (cierre == CierreDeCuenta.CANCELADA) onCancelada()
    onActualizar()
}

@Composable
fun AccionesDeCuentaAbierta(
    order: com.avoqado.pos.orders.data.model.OrderDetail,
    onActualizar: () -> Unit,
    onCancelada: () -> Unit,
    viewModel: CobroDeCuentaViewModel = hiltViewModel(key = "cuenta-${order.id}"),
) {
    if (order.status in listOf("CANCELLED", "DELETED") || order.orderPaymentStatus == com.avoqado.pos.orders.data.model.PaymentStatus.PAID ||
        (order.esDeMesa != false && !order.esCuentaDeTarjetas)) return
    val onResultadoDeTarjetas = LocalResultadoDeTarjetas.current
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val solicitudes by viewModel.solicitudes.collectAsState()
    val oferta by viewModel.oferta.collectAsState()
    val cuentaDelCarrito = LocalCuentaDelCarrito.current
    val detalle by viewModel.detalle.collectAsState()
    val mensaje by viewModel.mensaje.collectAsState()
    val scope = rememberCoroutineScope()
    var abierta by rememberSaveable(order.id) { mutableStateOf(false) }
    var aviso by rememberSaveable(order.id) { mutableStateOf<String?>(null) }
    val solicitudesPropias = solicitudesDeLaCuenta(solicitudes, viewModel.venueActual, order.id)
    val porTerminar = solicitudesPropias.any { it.porTerminar }
    val elegible = com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(order.cobrosConSaldo, order, porTerminar)
    LaunchedEffect(order, solicitudesPropias) { if (enLinea) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) }
    val cents = detalle?.let(::restanteCents)
    if (elegible) {
        if (detalle == null) TextButton(enabled = enLinea, onClick = {
            scope.launch { if (enLinea) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) }
        }) { Text(TextosDeTarjetas.REINTENTAR) }
        else if (cents == null && detalle != null) Text("No se pudo consultar lo que falta. Vuelve a abrir la cuenta.")
        else if (cents != null && cents > 0) PrimaryButton(text = TextosDeTarjetas.cobrar(cents), enabled = controlDeRedDeCuenta(conectado, servidor, viewModel.puedeCobrar && oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER),
            onClick = { scope.launch { if (enLinea && viewModel.puedeCobrar && viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.OFRECER) abierta = true } })
        if (!viewModel.puedeCobrar) Text("No tienes permiso para cobrar. Pídeselo a tu gerente.")
    }
    if (!order.esCuentaDeTarjetas) RegresarSaldoDeCuenta(order.id, order.cobrosConSaldo ?: 0,
        order.orderPaymentStatus == com.avoqado.pos.orders.data.model.PaymentStatus.PAID || order.status in listOf("CANCELLED", "DELETED"),
        onCancelada = { onCancelada(); onActualizar() })
    if (detalle?.esCuentaDeTarjetas == true) CancelarCuenta(viewModel) { onCancelada(); onActualizar() }
    if (oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR) Text(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
    if (!enLinea) Text(TextosDeTarjetas.SIN_RED) else (aviso ?: mensaje)?.let { Text(it) }
    if (abierta) CobroDeCuentaScreen(order.id, onSalir = { cierre ->
        abierta = false
        if (cierre == CierreDeCuenta.ABIERTA) aviso = "La venta quedó en Cuentas abiertas."
        alSalirDeCuentaAbierta(cierre, order.esCuentaDeTarjetas, order.id, onActualizar, onCancelada, onResultadoDeTarjetas)
        scope.launch { if (enLinea) viewModel.cargar(order.id) }
    }, viewModel = viewModel)
}

@Composable
fun RegresarSaldoDeCuenta(orderId: String, cobrosConSaldo: Int, pagada: Boolean, onCancelada: () -> Unit,
    viewModel: PaymentFlowViewModel = hiltViewModel(key = "regreso-$orderId")) {
    val conectado by viewModel.conexionDelSaldo.collectAsState()
    val servidor by viewModel.servidorDelSaldo.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    var abierta by remember(orderId) { mutableStateOf(false) }
    if (viewModel.ofreceRegresarSaldo(cobrosConSaldo, pagada)) TextButton(enabled = enLinea, onClick = { abierta = true }) {
        Text(TextosDeTarjetas.REGRESAR_SALDO_Y_CANCELAR)
    }
    if (!enLinea && viewModel.ofreceRegresarSaldo(cobrosConSaldo, pagada)) Text(TextosDeTarjetas.SIN_RED)
    if (abierta) DialogoDeRegresoDeSaldo(orderId, cobrosConSaldo, pagada,
        onDismiss = { abierta = false }, onCancelada = { abierta = false; onCancelada() }, viewModel = viewModel)
}

@Composable
fun DialogoDeRegresoDeSaldo(orderId: String, cobrosConSaldo: Int, pagada: Boolean, onDismiss: () -> Unit,
    onCancelada: () -> Unit, viewModel: PaymentFlowViewModel = hiltViewModel(key = "regreso-$orderId")) {
    val cerrarResultadoDeSaldo = LocalCerrarCuentaDeSaldo.current
    val conectado by viewModel.conexionDelSaldo.collectAsState()
    val servidor by viewModel.servidorDelSaldo.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val scope = rememberCoroutineScope()
    var preparada by remember(orderId) { mutableStateOf(false) }
    var ocupada by remember { mutableStateOf(false) }
    val mensaje by viewModel.cancelFailure.collectAsState()
    LaunchedEffect(orderId) { preparada = viewModel.prepararRegresoDeSaldo(orderId, cobrosConSaldo, pagada) }
    AvoqadoDialog(title = TextosDeTarjetas.REGRESAR_SALDO_Y_CANCELAR,
        description = TextosDeTarjetas.REGRESAR_SALDO_CUERPO,
        onDismiss = { if (!ocupada) onDismiss() }, dismissOnClickOutside = !ocupada) {
        if (!enLinea) Text(TextosDeTarjetas.SIN_RED) else mensaje?.let { Text(it) }
        PrimaryButton(text = TextosDeTarjetas.REGRESAR_SALDO_Y_CANCELAR, enabled = controlDeRedDeCuenta(conectado, servidor, preparada && !ocupada),
            onClick = {
                ocupada = true
                scope.launch { try { if (viewModel.regresarSaldoYCancelar()) { cerrarResultadoDeSaldo(orderId); onCancelada() } } finally { ocupada = false } }
            })
    }
}

internal fun controlDeRedDeCuenta(conectado: Boolean, servidor: Boolean, disponible: Boolean): Boolean = conectado && servidor && disponible

internal suspend fun cancelarCuentaDesdeDialogo(motivo: String, cancelar: suspend (String) -> Boolean,
    onCancelada: () -> Unit): Boolean {
    val cancelada = cancelar(motivo)
    if (cancelada) onCancelada()
    return !cancelada
}

internal fun solicitudesDeLaCuenta(solicitudes: List<SolicitudDeTarjeta>, venueId: String?, orderId: String): Set<SolicitudDeTarjeta> =
    solicitudes.filter { it.venueId == venueId && it.orderId == orderId }.toSet()
