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
import com.avoqado.pos.tables.data.comoEvidencia
import com.avoqado.pos.tables.data.restanteCents
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.orders.data.CuentaGuardada
import com.avoqado.pos.payment.domain.DatosDelCobroSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import com.avoqado.pos.payment.domain.decidirCobroSinRed
import com.avoqado.pos.payment.domain.llaveDeCuenta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val copiaDeCuentas: com.avoqado.pos.orders.data.CopiaDeCuentas? = null,
    private val cuentasAbiertasAqui: com.avoqado.pos.orders.data.CuentasAbiertasAqui? = null,
    private val cashPaymentRepository: com.avoqado.pos.payment.data.CashPaymentRepository? = null,
    private val planManager: com.avoqado.pos.core.domain.PlanManager? = null,
    private val lanHub: com.avoqado.pos.core.data.lan.LanHubService? = null,
    private val payLaterQueue: com.avoqado.pos.payment.data.PayLaterQueue? = null,
    /** D12 (final fix ronda 2, R2-1): la lista durable de cobros con tarjeta de esta caja (una tarjeta en duda o cobrada da gris). */
    private val terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService? = null,
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
    private val enVuelo = MutableStateFlow(0)
    /** Consultas de la cuenta que aún no vuelven. Es contador y no booleano porque pueden correr dos a la vez. */
    val consultasEnCurso = enVuelo.asStateFlow()
    private val turnos = java.util.concurrent.atomic.AtomicInteger()
    /** Devuelve su turno: si después salió otra consulta, la que manda es ésa. */
    suspend fun cargar(orderId: String): Int {
        enVuelo.update { it + 1 } // Antes de vaciar la foto: «sin foto y sin consulta» significa que ya falló.
        val turno = turnos.incrementAndGet()
        try { consultar(orderId, turno) } finally { enVuelo.update { it - 1 } }
        return turno
    }
    private suspend fun consultar(orderId: String, turno: Int) {
        foto.value = null
        aviso.value = null
        val venue = storage.venueId
        venueDeLaCuenta = venue
        if (venue == null) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return }
        val turnoCopia = copiaDeCuentas?.abrirLectura()
        val resultado = repo.getOrderDetail(venue, orderId)
        // D12 (Codex r3 H1): lo que dice este detalle (o su 404) también pone al día la copia de «Pedidos». Final fix (F1): también si
        // otra consulta ya lo superó en pantalla: la copia ordena con sus propios turnos, y si la nueva no trae nada, ésta es lo más nuevo.
        val evidencia = resultado.fold(
            onSuccess = { d -> d.takeIf { it.id == orderId }?.comoEvidencia() },
            onFailure = { e -> com.avoqado.pos.orders.data.EvidenciaDelDetalle.NoExiste.takeIf { e is retrofit2.HttpException && e.code() == 404 } },
        )
        if (evidencia != null && turnoCopia != null) copiaDeCuentas?.alLeerDetalle(venue, orderId, evidencia, turnoCopia)
        // Si salió otra consulta después, manda ella: una respuesta atrasada no pisa su saldo ni su aviso (Codex D14 r1 P1).
        if (turno != turnos.get() && resultado.exceptionOrNull() !is kotlinx.coroutines.CancellationException) return
        if (storage.venueId != venue) { aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA; return }
        resultado.fold(onSuccess = {
            if (it.id != orderId) aviso.value = TextosDeTarjetas.CUENTA_NO_ENCONTRADA
            else if (it.paymentStatus == "PAID" || it.status in listOf("CANCELLED", "DELETED"))
                aviso.value = "Esta cuenta no tiene saldo pendiente."
            else foto.value = it
        }, onFailure = {
            if (it is kotlinx.coroutines.CancellationException) throw it // `getOrderDetail` la atrapa con runCatching.
            aviso.value = com.avoqado.pos.core.data.network.ServerErrorText.humanize(it)
        })
    }
    suspend fun ofertaParaCobrar(orderId: String, laTraeUnCarrito: Boolean = false): com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta {
        val turno = cargar(orderId)
        val cuenta = foto.value
        val venue = venueDeLaCuenta ?: return com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA
        val pendiente = try { cobrosLocales?.hayPendiente(venue, orderId) ?: true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { true }
        // Una consulta superada no publica oferta: decidiría con la foto de otra (Codex D14 r2 P2).
        if (turno != turnos.get() || storage.venueId != venue || foto.value?.id != orderId) return com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.NADA
        val resultado = com.avoqado.pos.giftcards.domain.ofertaDeLaCuenta(cuenta?.let(::restanteCents),
            cuenta == null || cuenta.esDeMesa != false && !cuenta.esCuentaDeTarjetas, laTraeUnCarrito, pendiente)
        ofertaActual.value = resultado
        return resultado
    }
    // MARK: - D12: cobrar sin red con la copia de «Pedidos»

    private val decisionSinRed = MutableStateFlow<DecisionSinRed?>(null)
    /** Lo que la cuenta ofrece sin red (null = aún no se decide). Lo publican las cuatro funciones de abajo. */
    val sinRed: StateFlow<DecisionSinRed?> = decisionSinRed.asStateFlow()
    /** La llave del candado que sostiene esta pantalla, y si salió de `acquireCoordinado` (caja ajena). */
    private var llave: Pair<String, Boolean>? = null
    /** P11: el toque en curso; un segundo toque espera ESTE resultado en vez de pedir otro candado. */
    private var pedidoEnVuelo: Pair<String, Deferred<DecisionSinRed>>? = null
    /** La revalidación previa al efectivo dijo que no: al volver, la cuenta se queda con ese gris (no se vuelve a ofrecer en el acto). */
    private var rechazadoAntesDelEfectivo = false
    // ponytail: un alcance suelto por pantalla, sólo para soltar el candado también en `onCleared` (cuando `viewModelScope` ya murió).
    private val alcanceDeSoltar = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Lo que decide: la copia VIGENTE de la sucursal ACTUAL (vencida u otra sucursal = sin cuenta) y lo local; leer mal = gris. */
    private suspend fun datosSinRed(orderId: String): DatosDelCobroSinRed {
        val venue = storage.venueId
        suspend fun leer(siFalla: Boolean, bloque: suspend () -> Boolean?): Boolean = try {
            bloque() ?: siFalla
        } catch (e: CancellationException) { throw e } catch (_: Exception) { siFalla }
        // Final fix ronda 2 (R2-1): una tarjeta de ESTA caja en duda o ya cobrada para esta cuenta (su lista durable; un contexto sin
        // negocio también cuenta). Se lee ANTES que la copia: un positivo que vence al leerla deja su marca y la copia la aplica.
        val tarjetaDeEstaCaja = venue != null && leer(true) {
            terminalPaymentService?.let { s -> s.cobrosConTarjetaDelNegocio(venue).any { it.orderId == orderId } } ?: false
        }
        return DatosDelCobroSinRed(
            cuenta = venue?.let { copiaDeCuentas?.cuenta(it, orderId) },
            puedeCobrar = puedeCobrar,
            abiertaAqui = venue != null && leer(false) { cuentasAbiertasAqui?.laAbrioAqui(venue, orderId) },
            // Un cobro local pendiente (también una tarjeta de esta caja), O una cortesía sin confirmar de «Pagar después» (fila REVISAR).
            cobroLocalPendiente = venue == null || tarjetaDeEstaCaja || leer(true) { cobrosLocales?.hayPendiente(venue, orderId) } || leer(true) {
                payLaterQueue?.filas?.value?.any { it.venueId == venue && it.orderId == orderId && it.estado == com.avoqado.pos.payment.data.PayLaterRow.Estado.REVISAR }
            },
            huboEfectivoSubido = leer(true) { cashPaymentRepository?.huboEfectivoSubido(orderId) },
            premiumAcreditado = leer(false) { planManager?.tieneAcreditada("OFFLINE_LAN_HUB") },
            hayOtraCajaDelNegocio = leer(false) { lanHub?.hayOtraCajaDelNegocio() },
            hubEncendido = lanHub?.enabled?.value == true,
        )
    }

    /** Sin candado: lo que la cuenta ofrece antes del toque. */
    suspend fun consultarSinRed(orderId: String) {
        decisionSinRed.value = decidirCobroSinRed(datosSinRed(orderId), candado = null)
    }

    /** El toque en «Cobrar $X en efectivo»: pide el candado si toca (la caja que la abrió con `acquire`, otra con `acquireCoordinado`). */
    suspend fun pedirCobroSinRed(orderId: String): DecisionSinRed {
        pedidoEnVuelo?.let { (id, enCurso) -> if (id == orderId && enCurso.isActive) return enCurso.await() }
        val propio = CompletableDeferred<DecisionSinRed>()
        pedidoEnVuelo = orderId to propio
        try {
            return pedirUnaVez(orderId).also { propio.complete(it) }
        } catch (e: Throwable) {
            propio.completeExceptionally(e)
            throw e
        } finally {
            if (pedidoEnVuelo?.second === propio) pedidoEnVuelo = null
        }
    }

    private suspend fun pedirUnaVez(orderId: String): DecisionSinRed {
        val datos = datosSinRed(orderId)
        val antes = decidirCobroSinRed(datos, candado = null)
        val hub = lanHub
        // Sin hub no hay candado que pedir (y `datosSinRed` ya dio hub apagado y ninguna otra caja).
        if (antes !is DecisionSinRed.Cobrar || !antes.pedirCandado || hub == null) return antes.also { decisionSinRed.value = it }
        val k = llaveDeCuenta(orderId)
        val staffId = storage.selectedStaffIdForCurrentVenue.orEmpty()
        val staffName = storage.selectedStaffNameForCurrentVenue ?: storage.userFirstName.orEmpty()
        // El candado concedido se guarda aunque cancelen el toque: si no, el hub lo renovaría para siempre a nombre de esta caja.
        val candado = try {
            withContext(NonCancellable) {
                val r = try {
                    if (datos.abiertaAqui) hub.acquire(k, staffId, staffName) else hub.acquireCoordinado(k, staffId, staffName)
                } catch (_: Exception) { LeaseOutcome.NoHub }
                if (r is LeaseOutcome.Granted) llave = k to !datos.abiertaAqui
                r
            }.also { currentCoroutineContext().ensureActive() } // `withContext(NonCancellable)` no lanza al salir: aquí se ve la cancelación
        } catch (e: CancellationException) {
            soltarCobroSinRed()
            throw e
        }
        return decidirCobroSinRed(datos, candado).also { decisionSinRed.value = it }
    }

    /** Suelta el candado que sostiene esta pantalla (si sostiene uno). */
    fun soltarCobroSinRed() {
        val (k, _) = llave ?: return
        llave = null
        val hub = lanHub ?: return
        alcanceDeSoltar.launch { runCatching { hub.release(k) } } // soltar nunca tumba la app; si falla, el candado vence a los 30 s
    }

    /**
     * Codex r3 H3: JUSTO antes de recibir el efectivo. null = sigue; un texto = no se cobra (y la cuenta lo enseña). La caja ajena
     * necesita su candado de `acquireCoordinado` vigente en vivo; la que abrió la cuenta no pasa por el hub.
     * T12b (O3): [saldoCents] es lo que cobra el carrito; si la copia ya dice otro saldo (volvió la red y la recarga lo cambió), no
     * se cobra: «Cobrar $X» ya no sería todo lo que se debe.
     */
    suspend fun revalidarCobroSinRed(orderId: String, saldoCents: Int): String? {
        val datos = datosSinRed(orderId)
        val decision = decidirCobroSinRed(datos, candado = null)
        val texto = when {
            decision is DecisionSinRed.Gris -> decision.texto
            decision is DecisionSinRed.Cobrar && decision.saldoCents != saldoCents -> TextosDeCuentaSinRed.saldoCambio(decision.saldoCents)
            datos.abiertaAqui -> null
            else -> {
                val k = llaveDeCuenta(orderId)
                val vigente = llave == (k to true) && try { lanHub?.candadoVigente(k) == true }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                if (vigente) null else TextosDeCuentaSinRed.CANDADO_PERDIDO.also { soltarCobroSinRed() }
            }
        } ?: return null
        rechazadoAntesDelEfectivo = true
        decisionSinRed.value = DecisionSinRed.Gris(texto)
        return texto
    }

    /** Al volver del cobro con la copia. false = la revalidación dijo que no: su gris se queda y la cuenta no se refresca. */
    suspend fun alVolverDelCobroSinRed(orderId: String): Boolean {
        soltarCobroSinRed()
        if (rechazadoAntesDelEfectivo) {
            rechazadoAntesDelEfectivo = false
            return false
        }
        consultarSinRed(orderId)
        return true
    }

    override fun onCleared() {
        soltarCobroSinRed()
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
    /**
     * D12: sin red, el cobro en efectivo de la cuenta de la copia de «Pedidos» por el saldo YA decidido (`DecisionSinRed.Cobrar.saldoCents`,
     * el mismo número del botón). No consulta la red. null = como siempre.
     */
    saldoSinRed: Int? = null,
) {
    if (saldoSinRed != null) { CobroDesdeLaCopia(orderId, saldoSinRed, onSalir, viewModel); return }
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
                    (if (enLinea) avisoLocal ?: mensaje else com.avoqado.pos.giftcards.domain.avisoDeCuentaSinRed(
                        cuenta?.esCuentaDeTarjetas != false, cuenta?.cobrosConSaldo, sePuedeCobrar = (cents ?: 0) > 0))?.let { Text(it) }
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

/**
 * D12: el cobro en efectivo de una cuenta con la copia de «Pedidos». Sólo efectivo, contra SU orden y sin dividir (el saldo esperado
 * tras cobrar es 0); la revalidación corre justo antes de recibir el efectivo y, si dice que no, el flujo sale solo (`debeSalir` →
 * `onCancel`) y la cuenta enseña su gris. Final fix (M-a): SIEMPRE vuelve ABIERTA, nunca «Pagada». El anfitrión no lee un cierre: al
 * volver decide otra vez con la copia y recarga la cuenta, y eso es lo que se ve (un cobro encolado sale «sin sincronizar»; uno que
 * subió ya sacó la cuenta de la copia, final fix I-2, y la recarga con red la trae pagada).
 */
@Composable
private fun CobroDesdeLaCopia(orderId: String, cents: Int, onSalir: (CierreDeCuenta) -> Unit, viewModel: CobroDeCuentaViewModel) {
    var avisoLocal by rememberSaveable(orderId) { mutableStateOf<String?>(null) }
    fun salir() {
        viewModel.soltarCobroSinRed()
        onSalir(CierreDeCuenta.ABIERTA)
    }
    Dialog(onDismissRequest = { salir() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        ImmersiveWindow()
        DisposableEffect(Unit) {
            com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(true)
            onDispose { com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(false) }
        }
        val cart = remember(orderId, cents) { CartState(items = listOf(CartItem(
            type = CartItemType.CustomAmount, name = "Saldo pendiente", unitPrice = cents))) }
        // Como el cobro en línea: si se resolvió un cobro anterior de OTRA venta, este cobro se cierra y su aviso se queda a la vista.
        avisoLocal?.let { texto ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                AvoqadoFullscreenHeader(title = "Cuenta abierta", onNav = { salir() }, showDivider = true)
                Text(texto, Modifier.padding(AvoqadoTheme.spacing.lg))
            }
        } ?: PaymentFlowScreen(cartState = cart, resumeOrderId = orderId, desdeCuentasAbiertas = true,
            origen = com.avoqado.pos.payment.domain.OrigenDelCobro.CUENTA_ABIERTA_DESDE_LA_COPIA,
            antesDeRecibirEfectivo = { viewModel.revalidarCobroSinRed(orderId, cents) },
            onPaymentCommitted = {},
            onDone = { salir() },
            onCancel = { salir() },
            onPreviousChargeResolved = { texto -> avisoLocal = texto },
        )
    }
}

/** D12: el botón sin red de la cuenta de la copia: «Cobrar $X en efectivo», «Revisando…» mientras pide el candado, o el gris con su razón. */
@Composable
private fun CobrarCuentaSinRed(orderId: String, copia: CuentaGuardada, viewModel: CobroDeCuentaViewModel, onCobrar: (saldoCents: Int) -> Unit) {
    val decision by viewModel.sinRed.collectAsState()
    // Si este botón sale de la pantalla (atrás, o volvió la red) a media consulta, su alcance se cancela y el VM suelta lo concedido.
    val scope = rememberCoroutineScope()
    var revisando by remember(orderId) { mutableStateOf(false) }
    var confirmar by remember(orderId) { mutableStateOf<DecisionSinRed.Cobrar?>(null) }
    LaunchedEffect(copia) { viewModel.consultarSinRed(orderId) }
    when (val d = decision) {
        is DecisionSinRed.Cobrar -> PrimaryButton(
            text = if (revisando) TextosDeCuentaSinRed.REVISANDO else TextosDeCuentaSinRed.cobrarEnEfectivo(d.saldoCents),
            enabled = !revisando,
            onClick = {
                revisando = true
                scope.launch {
                    try {
                        val r = viewModel.pedirCobroSinRed(orderId)
                        if (r is DecisionSinRed.Cobrar) { if (r.confirmar != null) confirmar = r else onCobrar(r.saldoCents) }
                    } finally { revisando = false }
                }
            })
        is DecisionSinRed.Gris -> Text(d.texto)
        null -> PrimaryButton(text = TextosDeTarjetas.CONSULTANDO, enabled = false, onClick = {})
    }
    confirmar?.let { c ->
        val volver = { confirmar = null; viewModel.soltarCobroSinRed() }
        AvoqadoDialog(title = c.confirmar.orEmpty(), onDismiss = volver) {
            PrimaryButton(text = TextosDeCuentaSinRed.COBRAR_AQUI, onClick = { confirmar = null; onCobrar(c.saldoCents) })
            TextButton(onClick = volver) { Text(TextosDeCuentaSinRed.VOLVER) }
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

/**
 * I1: qué pinta [AccionesDeCuentaAbierta]. Un cobro abierto (en línea, o sin red con su saldo ya decidido) se pinta SIEMPRE, aunque la
 * cuenta, recargada a medio cobro, ya salga PAGADA o CANCELADA: el cobro termina y la cajera ve su resultado y su cambio. Las acciones
 * (botón, avisos) sólo con una cuenta abierta de Mostrador o de tarjetas.
 */
internal data class LoQueSePinta(val acciones: Boolean, val cobroEnLinea: Boolean, val cobroSinRed: Int?)

internal fun loQueSePinta(order: com.avoqado.pos.orders.data.model.OrderDetail, abierta: Boolean, cobroSinRed: Int?) = LoQueSePinta(
    acciones = order.status !in listOf("CANCELLED", "DELETED") &&
        order.orderPaymentStatus != com.avoqado.pos.orders.data.model.PaymentStatus.PAID && (order.esDeMesa == false || order.esCuentaDeTarjetas),
    cobroEnLinea = abierta,
    cobroSinRed = cobroSinRed,
)

/** I1: recargar la cuenta vuelve a pedir la oferta sólo con red y sin un cobro abierto (pedirla vacía la foto del cobro en línea). */
internal fun pideOfertaAlRecargar(enLinea: Boolean, cobroAbierto: Boolean): Boolean = enLinea && !cobroAbierto

/** «Reintentar» es para una consulta que ya falló; antes de que arranque la primera también se está consultando. */
internal fun cuentaEnConsulta(consultasEnCurso: Int, enLinea: Boolean, consultaPedida: Boolean) =
    consultasEnCurso > 0 || (enLinea && !consultaPedida)

@Composable
fun AccionesDeCuentaAbierta(
    order: com.avoqado.pos.orders.data.model.OrderDetail,
    onActualizar: () -> Unit,
    onCancelada: () -> Unit,
    onSalioSinCobrar: () -> Unit,
    /** D12: la cuenta de la copia que respalda este detalle sin red (`OrdersRepository.detalleDeLaCopia`), o null. */
    copia: CuentaGuardada? = null,
    viewModel: CobroDeCuentaViewModel = hiltViewModel(key = "cuenta-${order.id}"),
) {
    val onResultadoDeTarjetas = LocalResultadoDeTarjetas.current
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val enLinea = controlDeRedDeCuenta(conectado, servidor, true)
    val scope = rememberCoroutineScope()
    var abierta by rememberSaveable(order.id) { mutableStateOf(false) }
    var aviso by rememberSaveable(order.id) { mutableStateOf<String?>(null) }
    // D12: el saldo YA decidido del cobro sin red abierto con la copia (null = ninguno). No depende de `conCopia`: sigue abierto
    // aunque vuelva la red a medio cobro. T12b (O2): sobrevive a reconstruir la Activity, como `abierta`.
    var cobroSinRed by rememberSaveable(order.id) { mutableStateOf<Int?>(null) }
    val cuentaDelCarrito = LocalCuentaDelCarrito.current
    DisposableEffect(viewModel) { onDispose { viewModel.soltarCobroSinRed() } }
    // I1: los cobros abiertos van ANTES de la salida temprana (ver `loQueSePinta`).
    val pinta = loQueSePinta(order, abierta, cobroSinRed)
    if (pinta.cobroEnLinea) CobroDeCuentaScreen(order.id, onSalir = { cierre ->
        abierta = false
        if (cierre == CierreDeCuenta.ABIERTA) aviso = "La venta quedó en Cuentas abiertas."
        alSalirDeCuentaAbierta(cierre, order.esCuentaDeTarjetas, order.id,
            if (cierre == CierreDeCuenta.ABIERTA) onSalioSinCobrar else onActualizar, onCancelada, onResultadoDeTarjetas)
        // T12b (M-n2): la oferta también (recarga la cuenta): si la recarga trae la misma cuenta, el efecto de abajo no corre.
        scope.launch { if (enLinea) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id) }
    }, viewModel = viewModel)
    // Al volver, sin «La venta quedó en Cuentas abiertas» (un cobro guardado no es salir sin cobrar). Si la revalidación dijo que no,
    // la cuenta se queda con ese gris; si no, se vuelve a decidir y se refresca (un cobro encolado se ve «sin sincronizar»).
    pinta.cobroSinRed?.let { saldo ->
        CobroDeCuentaScreen(order.id, onSalir = {
            cobroSinRed = null
            scope.launch { if (viewModel.alVolverDelCobroSinRed(order.id)) onActualizar() }
        }, viewModel = viewModel, saldoSinRed = saldo)
    }
    if (!pinta.acciones) return
    val solicitudes by viewModel.solicitudes.collectAsState()
    val oferta by viewModel.oferta.collectAsState()
    val detalle by viewModel.detalle.collectAsState()
    val consultasEnCurso by viewModel.consultasEnCurso.collectAsState()
    val mensaje by viewModel.mensaje.collectAsState()
    val solicitudesPropias = solicitudesDeLaCuenta(solicitudes, viewModel.venueActual, order.id)
    val porTerminar = solicitudesPropias.any { it.porTerminar }
    val elegible = com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(order.cobrosConSaldo, order, porTerminar)
    var consultaPedida by remember(order.id) { mutableStateOf(false) }
    LaunchedEffect(order, solicitudesPropias) {
        consultaPedida = true
        if (pideOfertaAlRecargar(enLinea, cobroAbierto = abierta || cobroSinRed != null)) viewModel.ofertaParaCobrar(order.id, cuentaDelCarrito() == order.id)
    }
    val cents = detalle?.let(::restanteCents)
    // D12: sin red, la cuenta que respalda la copia se decide con la FILA de la copia, no con este detalle reconstruido (`payments`
    // null abriría la compuerta de abonos por accidente). Con red, todo como siempre.
    val conCopia = !enLinea && copia != null && copia.cuenta.id == order.id
    if (conCopia) CobrarCuentaSinRed(order.id, copia!!, viewModel) { saldo -> cobroSinRed = saldo }
    else if (elegible) {
        if (detalle == null && cuentaEnConsulta(consultasEnCurso, enLinea, consultaPedida)) PrimaryButton(text = TextosDeTarjetas.CONSULTANDO, enabled = false, onClick = {})
        else if (detalle == null) TextButton(enabled = enLinea, onClick = {
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
    if (!conCopia && oferta == com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR) Text(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
    if (!conCopia) (if (enLinea) mensaje ?: aviso else com.avoqado.pos.giftcards.domain.avisoDeCuentaSinRed(
        order.esCuentaDeTarjetas, detalle?.cobrosConSaldo ?: order.cobrosConSaldo,
        sePuedeCobrar = elegible && (detalle == null || (cents ?: 0) > 0)))?.let { Text(it) }
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
