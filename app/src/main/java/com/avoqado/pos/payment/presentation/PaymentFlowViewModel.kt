package com.avoqado.pos.payment.presentation

import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import kotlinx.serialization.json.*
import com.avoqado.pos.payment.data.model.ObjetivoDeLaDeclaracion
import com.avoqado.pos.payment.domain.CajonDeDinero
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.tieneAbono
import com.avoqado.pos.areatickets.data.NormalCheckoutItem
import com.avoqado.pos.areatickets.data.moneyToCents
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.data.SaleStampReason
import com.avoqado.pos.loyalty.domain.RenglonDelSello
import com.avoqado.pos.loyalty.domain.TextosDelSello
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.ContextoDeCobro
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.ColaDelCobro
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.PaymentContext
import com.avoqado.pos.payment.data.model.PaymentErrorSource
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentItem
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.payment.data.model.MENSAJE_PREMIO_SIN_RED
import com.avoqado.pos.payment.data.model.avisoDeEfectivoCortoPorPremio
import com.avoqado.pos.payment.data.model.cobroConPremio
import com.avoqado.pos.payment.data.model.totalACobrarCents
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.printing.NoStationsFallback
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.ReplayDeComandasPendientes
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.ComboPrintLines
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.avoqado.pos.pos.data.model.CambioDePrecio
import com.avoqado.pos.pos.data.model.MENSAJE_RESERVA_SIN_RETIRAR
import com.avoqado.pos.pos.data.model.acumularCambiosDePrecio
import com.avoqado.pos.pos.data.model.avisoDePrecioDeLaCreacion
import javax.inject.Inject

data class PaymentCompletion(
    val splitType: String,
    val remainingBalanceCents: Int,
    val conSaldo: Boolean = false,
    /** D7 (27-oct): `remainingBalanceCents` lo confirmó el SERVIDOR (efectivo o lectura tras tarjeta), no la resta local. */
    val saldoDelServidor: Boolean = false,
    val paidItemIds: Set<String> = emptySet(),
    /**
     * La orden que quedó cobrada, cuando la hubo (una venta rápida sin orden la
     * deja en null). El upsell la necesita para atar su métrica a la venta REAL:
     * el ingreso del reporte sale de las líneas cobradas, nunca de lo que reportó
     * el POS.
     */
    val orderId: String? = null,
    /**
     * 🔴 Lo que el servidor CONFIRMÓ del premio de cartilla al crear la orden (0 = lo rechazó); null =
     * la venta no llevaba premio. El carrito lo usa para que la parte siguiente de un pago dividido
     * descuente lo confirmado y no vuelva a estimar (`CartViewModel.aplicarCobroConfirmado`).
     */
    val premioConfirmadoCents: Int? = null,
    val carritoGuardadoDelCobro: String? = null,
    val identidadDeVenta: String? = null,
    /**
     * 🔴 Lo que el servidor corrigió en las líneas de ESTA venta (acumulado: un 409 y el 201 del reintento), para que el carrito
     * de la caja quede con el precio de la orden. Vacío = nada que corregir (el carrito ni el descuento se tocan).
     */
    val cambiosDePrecio: List<com.avoqado.pos.pos.data.model.CambioDePrecio> = emptyList(),
    /** El descuento de cuenta que quedó fijo en la orden (sólo tras un 201 con cambios). Ver `CartState.conPreciosDelServidor`. */
    val descuentoDeCuentaCongelado: com.avoqado.pos.pos.data.model.DescuentoCongelado? = null,
    /**
     * D1 (26-oct): este cobro NO subió todavía; quedó en la cola sin red con esta llave (`pending_payments`). Con saldo, el
     * resto de la venta espera a que suba (`CartViewModel.esperarParteEnCola`). null = subió, o no se encoló.
     */
    val parteEnCola: String? = null,
)

const val LOCAL_PRINTER_UNAVAILABLE = "__LOCAL_PRINTER_UNAVAILABLE__"

/** D7 (27-oct): el texto del aviso cuando, tras una parte con tarjeta, no se pudo confirmar el saldo con el SERVIDOR. */
const val AVISO_SALDO_SIN_CONFIRMAR =
    "El cobro sí quedó, pero no se pudo confirmar con Avoqado cuánto falta de esta venta. Revísala en Ventas antes de cobrar el resto."

/** Mismo texto en iOS (`AreaTicketPaymentError.ventaConAbono`) y en «Vaciar» (D5, 20-oct). */
const val VALES_CON_ABONO = "Esta venta de vales ya tiene un abono: cobra su saldo pendiente para terminarla."

/** Mismo texto en iOS (`AreaTicketRepositoryError.noActiveCheckout`). */
const val VALES_SIN_APARTADO = "Estos vales ya no están apartados para esta venta: quítalos y vuelve a escanear el vale."

/** Ronda final (A2): el cobro en efectivo no se pudo guardar en el aparato tras fallar la red; su apartado sigue del flujo. */
const val MENSAJE_EFECTIVO_SIN_GUARDAR = "No se pudo guardar el cobro en el aparato. Intenta de nuevo o cancélalo."

/**
 * 🔴 DINERO. ¿El carrito trae exactamente los vales de la sesión del servidor? Renglón por renglón y en centavos, como los pone
 * `CartViewModel.replaceAreaTicketLines`. Lo usa `PaymentFlowViewModel.valesNoCoinciden` antes de cobrar.
 */
internal fun cobraLosValesDeLaSesion(items: List<CartItem>, checkout: AreaTicketCheckout): Boolean =
    items.mapNotNull { item -> item.areaTicketLineId?.let { it to item.unitPrice * item.quantity } }.sortedBy { it.first } ==
        checkout.tickets.flatMap { it.lines }.map { it.id to it.total.moneyToCents() }.sortedBy { it.first }

/**
 * El aviso de un cobro con tarjeta de OTRA venta que quedó sin confirmar, o que SÍ pasó. 🔴 Founder, 25-sep: esa duda ya no
 * frena la tarjeta de esta venta; se DICE en la selección de terminal.
 *
 * @param yaCobrado consta que ese cobro SÍ pasó: se ofrece «Entendido» en vez de «Revisar».
 */
data class AvisoDeOtroCobro(val requestId: String, val texto: String, val yaCobrado: Boolean)

@HiltViewModel
class PaymentFlowViewModel @Inject constructor(
    private val tableSession: com.avoqado.pos.tables.data.TableSession,
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val orderRepository: OrderRepository,
    private val cashPaymentRepository: CashPaymentRepository,
    private val tenderTypeRepository: com.avoqado.pos.payment.data.TenderTypeRepository,
    private val terminalPaymentService: TerminalPaymentService,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val paymentSyncService: PaymentSyncService,
    private val cashDrawerRepository: CashDrawerRepository,
    // ponytail: sin uso desde la fase 3.3 del KDS (el servidor arma la comanda al cobrar). Se quedan porque ocho tests
    // de cobro los pasan (dos son WIP de otra sesión el 27-sep); se quitan en la tarea de seguimiento del Cierre.
    private val kdsRepository: KDSRepository,
    private val kdsOrderBus: KDSOrderBus,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    private val comandaDispatcher: ComandaDispatcher,
    /** Sobrevive a que la app muera con una comanda sin salir — ver [ComandasPendientesStore]. */
    private val comandasPendientesStore: ComandasPendientesStore,
    /**
     * 🔴 El botón «Volver a imprimir» pasa por AQUÍ, no por el dispatcher directo: es el mismo
     * ejecutor —y el mismo candado— que usa el reloj que reintenta sola. Si cada uno mandara por
     * su cuenta, el tic y el toque del cajero podrían coincidir y la cocina recibiría DOS.
     */
    private val replayDeComandas: ReplayDeComandasPendientes,
    private val customerDisplay: com.avoqado.pos.customerdisplay.CustomerDisplayState,
    private val areaTicketRepository: AreaTicketRepository,
    /**
     * 🔴 La cancelación de un cobro con tarjeta NO vive en esta pantalla: se guarda en disco antes
     * de tocar la red y la reproduce un coordinador que sobrevive a que el cajero se vaya y a que
     * el proceso muera. Ver [com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator].
     */
    private val cancelacionDeCobro: com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    /**
     * Tras un 409 de precio se pide el catálogo nuevo (un refresco en vuelo deja UNO más para después). Lleva default por el
     * mismo patrón que `CartViewModel.serviceCoursesRepository`: las pruebas que construyen el VM a mano no lo conocen; Hilt (y
     * Guice en Windows) inyecta siempre el singleton real.
     */
    private val refrescoDelCatalogo: com.avoqado.pos.pos.data.RefrescoDelCatalogo? = null,
    private val connectivityMonitor: com.avoqado.pos.core.util.ConnectivityMonitor? = null,
    private val cobroConSaldo: com.avoqado.pos.giftcards.data.CobroConSaldoCoordinator? = null,
    private val tarjetasRepository: com.avoqado.pos.giftcards.data.GiftCardsRepository? = null,
    private val rolesDeSaldo: com.avoqado.pos.core.domain.RoleManager? = null,
    private val cobrosLocalesDeSaldo: com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta? = null,
    /**
     * Familia B (rediseño r9, paridad con iOS `handleTablePaymentSuccess(original:venueId:expectedEpoch:)`): la mesa de una venta
     * que ya no está en pantalla se libera con SU contexto. Default por el mismo patrón que [refrescoDelCatalogo].
     */
    private val tableServiceRepository: com.avoqado.pos.tables.data.TableServiceRepository? = null,
    private val lanHub: com.avoqado.pos.core.data.lan.LanHubService? = null,
) : ViewModel() {
    private val cuentaDeTarjetas = MutableStateFlow(false)
    val metodosHabilitados: StateFlow<Boolean> by lazy {
        combine(cuentaDeTarjetas, connectivityMonitor?.isConnected ?: flowOf(false),
            connectivityMonitor?.isServerReachable ?: flowOf(false)) { cuenta, ruta, servidor ->
            !cuenta || (ruta && servidor)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, !cuentaDeTarjetas.value ||
            (connectivityMonitor?.isConnected?.value == true && connectivityMonitor.isServerReachable.value))
    }

    private fun bloquearCuentaSinRed(): Boolean {
        if (bloqueoDelSaldoActual()) return true
        if (!cuentaDeTarjetas.value || (connectivityMonitor?.isConnected?.value == true &&
                connectivityMonitor.isServerReachable.value)) return false
        _state.value = PaymentFlowState.Error(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.CUENTA_SIN_RED,
            PaymentErrorSource.NETWORK)
        return true
    }

    private val _state = MutableStateFlow<PaymentFlowState>(PaymentFlowState.Loading)
    val state: StateFlow<PaymentFlowState> = _state.asStateFlow()

    /**
     * 🔴 DINERO. El premio de cartilla NO se pudo aplicar (o se aplicó por otro monto que el del
     * carrito): el cajero tiene que saberlo, porque el total que cobra ya no es el que anunció.
     * null = nada que decir. Nunca en silencio.
     */
    private val _avisoDelPremio = MutableStateFlow<String?>(null)
    val avisoDelPremio: StateFlow<String?> = _avisoDelPremio.asStateFlow()

    fun descartarAvisoDelPremio() {
        _avisoDelPremio.value = null
    }

    /**
     * 🔴 DINERO. El servidor cobró un precio distinto del que mostró la caja (201 con `priceChanges`) o lo rechazó en línea
     * (409 `ORDER_PRICE_CHANGED`): se dice con palabras y «Entendido» (no se esfuma solo). null = nada que decir. Espejo de
     * `avisoDePrecio` de iOS.
     */
    private val _avisoDePrecio = MutableStateFlow<String?>(null)
    val avisoDePrecio: StateFlow<String?> = _avisoDePrecio.asStateFlow()

    fun descartarAvisoDePrecio() {
        _avisoDePrecio.value = null
    }

    /** Lo que el servidor corrigió en ESTA venta (un 409 y el 201 del reintento, acumulados). Va al carrito en la completion. */
    private var cambiosDeEstaVenta: List<CambioDePrecio> = emptyList()

    /** El total que vio el cajero antes del primer 409 de esta venta. null = no hubo 409: no se dice «Total nuevo». */
    private var totalAntesDelRechazoDePrecio: Int? = null

    /** El último aviso de precio publicado: uno idéntico no reaparece tras «Entendido». */
    private var ultimoAvisoDePrecio: String? = null

    /**
     * Apartados de efectivo de una venta que YA terminó cobrada por otro camino (otra llave, tarjeta) y cuyo borrado NO se pudo
     * escribir en el aparato. Mientras existan, ningún cobro sale: primero se reintenta el borrado (ver
     * [primeroDescartarLosPendientes]). No se sueltan al empezar otra venta: soltarlos los daría por cobrados (D2 v7).
     */
    private val porDescartar = linkedSetOf<String>()

    /** Lo que el servidor CONFIRMÓ del premio al crear la venta (0 si no lo aplicó). null = aún no se sabe. */
    private var premioConfirmadoCents: Int? = null

    /**
     * 🔴 Cuánto bajó la cuenta el premio, para el ticket y la pantalla del cliente: lo confirmado si ya se
     * sabe, si no el estimado del carrito. Sin esto el ticket diría «Subtotal $100 · Total $70» sin el
     * renglón que explica la diferencia.
     */
    private fun premioEnLaCuenta(cart: CartState): Int = premioConfirmadoCents ?: cart.stampRewardCents
    private var completionConsumed = false

    /**
     * Propina y calificación las captura el CLIENTE en su pantalla: requiere
     * pantalla montada Y que el negocio lo haya activado en Ajustes (tener el
     * hardware no garantiza que el cliente esté enfrente).
     */
    val customerDisplayActive: StateFlow<Boolean> = customerDisplay.customerCapturesInput

    private val _onlineTerminals = MutableStateFlow<List<OnlineTerminal>>(emptyList())
    val onlineTerminals: StateFlow<List<OnlineTerminal>> = _onlineTerminals.asStateFlow()

    /// Terminal availability, probed UP-FRONT at flow start (the audit's
    /// flagship late-validation fix). Before, availability was only checked
    /// AFTER tip+rating when the user picked CARD — a venue with zero online
    /// terminals walked the whole flow only to dead-end at "No hay terminales".
    enum class TerminalAvailability { CHECKING, AVAILABLE, NONE, ERROR }
    private val _terminalAvailability = MutableStateFlow(TerminalAvailability.CHECKING)
    val terminalAvailability: StateFlow<TerminalAvailability> = _terminalAvailability.asStateFlow()

    private var cartState: CartState? = null
    // Copias del cobro, anteriores a cualquier suspensión; el piso/sucursal vivos pueden cambiar después.
    private var mesaDelCobro: com.avoqado.pos.tables.data.TableSession.Active? = null
    /** El contexto de la sesión de mesas al abrir el cobro: con él se libera la mesa de ESTA venta aunque ya haya otra. */
    private var contextoDeLaMesaDelCobro = 0L

    /** La mesa de una venta, fotografiada antes de su primera suspensión (familia B). */
    private data class MesaCapturada(
        val mesa: com.avoqado.pos.tables.data.TableSession.Active,
        val venueId: String,
        val contexto: Long,
    )

    private fun mesaCapturada(venueId: String): MesaCapturada? =
        mesaDelCobro?.let { MesaCapturada(it, venueId, contextoDeLaMesaDelCobro) }
    private var nombreDelNegocioDelCobro: String = "Avoqado"
    /** D4 (mesas40): quien tiene la sesión al abrir el cobro — «Atendió» sin mesero ni vendedor elegido. Nunca «Staff». */
    private var nombreDeLaSesionDelCobro: String? = null
    private var selectedMethod: PaymentMethod? = null
    private var currentRating: Int? = null
    private var currentTipCents: Int = 0
    private var createdOrderId: String? = null
    private var createdOrderNumber: String? = null  // folio real del backend
    /**
     * La última orden que este cobro creó o cobró, con su folio. El carrito liga la parte 2 de una venta dividida sólo por el
     * id (`resumeOrderId`); sin esto su ticket caía a los últimos 4 del id y cada parte decía otro folio (Codex plan-r2 #2).
     * En memoria, igual que ese vínculo: con la app cerrada entre partes se pierden los dos.
     */
    private var ultimoFolio: Pair<String, String>? = null

    private fun recordarFolio() {
        val orden = createdOrderId?.takeIf { it.isNotBlank() } ?: return
        val folio = createdOrderNumber?.takeIf { it.isNotBlank() } ?: return
        ultimoFolio = orden to folio
    }

    /**
     * Etapa 3 del KDS (3.4): el `externalId` con el que se creó (o encoló) la orden de ESTA venta — la base del folio
     * `sale:<externalId>` de la marca del papel de respaldo. Se copia aquí porque `paymentIdempotencyKey` se limpia al
     * llegar a Success, antes de que la comanda salga.
     */
    private var externalIdDeLaVenta: String? = null

    /// Re-entrancy guard: a fast double-tap on a cash preset or a terminal row
    /// used to enter processCashPayment/confirmPayment TWICE — both saw
    /// createdOrderId == null and created two orders + recorded two payments.
    /// Set synchronously at entry, cleared centrally when the flow reaches a
    /// terminal state (Success/Error) — see the collector in startFlowGuard().
    private var isProcessingPayment = false

    /// One idempotency key per payment SESSION (not per attempt): a retry after
    /// a network-error-but-server-recorded response reuses the key so the
    /// backend can dedupe instead of recording a SECOND payment. Cleared on
    /// Success (same collector as the processing flag) and on cancel/reset.
    private var paymentIdempotencyKey: String? = null

    private fun sessionIdempotencyKey(): String =
        paymentIdempotencyKey ?: java.util.UUID.randomUUID().toString().also { paymentIdempotencyKey = it }

    /**
     * 🔴 D2 v7 (24-oct): las llaves de efectivo que ESTA venta guardó en el aparato ANTES de mandarlas (ver `reservarEfectivo`):
     * la del intento actual y las de intentos anteriores de la misma venta (p. ej. la del 409 de precio). Son del flujo mientras
     * la venta viva, sea cual sea el desenlace que vio la cajera: el aparato nunca las borra por lo que contestó la red.
     * - Las borra (y espera el borrado) una ACCIÓN de la cajera sobre esta venta: «Cancelar» ([descartarApartadosDeEstaVenta]),
     *   volver a cobrarla con otra llave (el apartado nuevo reemplaza a los viejos) o terminarla cobrada por otro camino.
     * - Empezar otra venta, recrear la pantalla u `onCleared` NO deciden nada: se sueltan y se dan por cobradas (Toast).
     */
    private val apartadosDeEstaVenta = linkedSetOf<String>()

    /** La llave que apartó el intento de efectivo EN CURSO (null = el intento actual no apartó). Es la del cobro si termina bien. */
    private var apartadoDelIntento: String? = null

    /** Un «Cancelar» está escribiendo el borrado de los apartados: ni otro «Cancelar» ni un cobro nuevo hasta que termine. */
    private var descartandoApartados = false

    /** Codex r15 #1: `onCleared` ya corrió. Un borrado que termina después suelta lo que no pudo borrar ([descartarApartadosDeEstaVenta]). */
    private var destruido = false

    /**
     * 🔴 Ronda final (A5, espejo de iOS `alFallarElIntento`): el apartado del intento EN CURSO si es «Ya pagó de otra forma»
     * (método manual o tipo del catálogo). No es de [apartadosDeEstaVenta]: nunca se borra ni se da por cobrado; si el intento
     * falla se suelta y queda «sin confirmar», como hoy.
     */
    private var reservaNoEfectivo: String? = null

    /**
     * Ronda final (M-1, espejo de iOS): el último «Cancelar» no pudo borrar los apartados. Mientras siga así, «Reintentar» de la
     * pantalla de error reintenta la CANCELACIÓN, nunca el cobro.
     */
    private var cancelacionFallida = false

    /**
     * Ronda final (A3, Codex r8 #5): cuál venta está en pantalla. Sólo `startPaymentFlow` la cambia (la generación cambia también
     * al cancelar): una respuesta tardía de una venta ANTERIOR no escribe nada en la nueva.
     */
    private var ventaEnCurso = 0

    /**
     * Qué dice la pantalla de resultado de un cobro encolado, EN VIVO (30-sep-2026: «Se sincronizará
     * cuando haya conexión» se quedaba pegado aunque la cola ya hubiera subido la venta).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sincronizacionDelCobro: StateFlow<com.avoqado.pos.payment.data.model.SincronizacionDelCobro> =
        _state
            .map { (it as? PaymentFlowState.Success)?.takeIf { exito -> exito.isQueued } }
            .distinctUntilChanged()
            .flatMapLatest { exito ->
                if (exito == null) flowOf(SincronizacionDelCobro.NINGUNA) else sincronizacionDe(exito.colaDelCobro)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, com.avoqado.pos.payment.data.model.SincronizacionDelCobro.NINGUNA)

    /**
     * El estado de UN cobro encolado según su cola, en vivo. Lo leen el aviso «Venta sincronizada» y el renglón del sello
     * ([cambiarLaPromesaAlSincronizar]): la MISMA señal, para que la pantalla nunca diga dos cosas distintas.
     */
    private fun sincronizacionDe(cola: ColaDelCobro?): Flow<SincronizacionDelCobro> = when (cola) {
        is ColaDelCobro.Pagos -> cashPaymentRepository.observarEstado(cola.id).map(SincronizacionDelCobro::dePago)
        is ColaDelCobro.Outbox -> syncOutbox.observarEstado(cola.intentId).map(SincronizacionDelCobro::deIntent)
        // Encolado sin fila que mirar: se queda como hasta hoy, pendiente.
        null -> flowOf(SincronizacionDelCobro.PENDIENTE)
    }

    /**
     * 🔴 Un 4xx al crear la orden NO significa "no pasó nada".
     *
     * Con promociones el server crea la orden PRIMERO y aplica el combo después:
     * si el combo no se puede aplicar, anula la orden y libera el `externalId`
     * — pero esa limpieza es best-effort. Si falla, la llave queda tomada por
     * una orden CANCELADA y vacía, y el reintento con la MISMA llave entra por
     * el atajo de idempotencia: 201 con una orden de $0 y el combo regalado.
     *
     * Por eso un rechazo de negocio estrena llave. Es seguro: si la orden no se
     * creó, no hubo cobro que deduplicar.
     *
     * 🔴 Y SÓLO un rechazo de negocio (4xx de nuestra API). Un fallo de RED
     * —timeout, socket cerrado— conserva la llave a propósito: ahí el intento
     * lento SÍ pudo aterrizar, y estrenarla crearía una SEGUNDA orden en vez de
     * deduplicar contra la primera. Es la regla 2.4 de offline-first.
     */
    private fun estrenarLlaveTrasRechazoDeOrden(error: Throwable) {
        val esRechazoDeNegocio = error is OrderRepository.ServerException && error.code in 400..499
        if (esRechazoDeNegocio) paymentIdempotencyKey = null
    }

    /**
     * 🔴 Guarda el cobro en efectivo en el aparato ANTES de mandarlo (regla «se persiste antes de tocar
     * la red», `.claude/rules/todo-funciona-sin-red.md` pregunta 2). Con la MISMA llave que el intento
     * en línea, la creación de la orden y la cola: si la app muere a media petición, al reabrir se da
     * por cobrado (D2 v7) y sube con esa llave, y el servidor deduplica en vez de crear otra venta.
     *
     * 🔴 D2 v7: es volver a cobrar ESTA venta. Si es con otra llave (tras el 409 de precio o un rechazo que la estrenó), el
     * apartado nuevo borra los de los intentos anteriores en la MISMA escritura; con la misma llave reemplaza su propia fila.
     * Ya no suelta nada al empezar: los apartados de la venta son del flujo hasta que la cajera decide.
     *
     * No se reserva donde no hay cola que lo pueda reproducir: fichas de área (su cobro no se encola),
     * mesa abierta sin red (va por el outbox, que ya guarda antes) y venta con premio de lealtad (el
     * canje es en línea a propósito).
     *
     * @return 🔴 qué apartado quedó en disco (también si la venta cambió mientras se escribía). Con [Apartado.EFECTIVO] la venta en el
     *   cajón la anota su salida de `EN_VUELO` (`SalidaDelApartado`), no este intento (rediseño r9). Con CUALQUIER apartado la orden de
     *   esta venta no se cancela si otra empezó (ruling N1 de la ronda corta) y la fila lleva su orden e importe antes del `/pay`.
     */
    private suspend fun reservarEfectivo(
        generacion: Int,
        /**
         * 🔴 Ronda final (A1, Codex r8 #1): la foto de los apartados de ESTA venta, tomada al EMPEZAR el intento (antes de la primera
         * suspensión, en su generación). Leerla al escribir podía traer los de OTRA venta que empezó mientras tanto, y borrarlos.
         */
        anterioresDeEstaVenta: List<String>,
        cart: CartState?,
        total: Int,
        cashReceivedCents: Int,
        changeCents: Int,
        tipCapturado: Int,
        splitCapturado: String,
        llave: String,
        manualCapturado: com.avoqado.pos.payment.domain.ManualPaymentMethod?,
        tenderCapturado: com.avoqado.pos.payment.domain.TenderTypeOption?,
        pedido: CreateOrderRequest?,
        ordenCapturada: String?,
        empleadoCapturado: String,
        clienteCapturado: String?,
        ratingCapturado: Int?,
        venueCapturado: String,
        provisionalCapturada: Boolean,
        permiteColaCapturada: Boolean,
        cajaCapturada: String?,
        /** Agregado 2 de la ronda corta: quién cobra (la sesión al confirmar). La venta que el flujo anote va a su nombre. */
        actorCapturado: String,
        nombreActorCapturado: String,
    ): Apartado {
        if (cart == null || pedido == null) return Apartado.NINGUNO
        if (!permiteColaCapturada) return Apartado.NINGUNO
        if (provisionalCapturada) return Apartado.NINGUNO
        if (cart.pendingStampRewardId != null && cart.premioAplica) return Apartado.NINGUNO
        val ordenExistente = ordenCapturada?.takeIf { it.isNotBlank() }
        try {
            cashPaymentRepository.reservarCobro(
                orderRequest = pedido,
                amountCents = total - tipCapturado,
                tipCents = tipCapturado,
                splitType = splitCapturado,
                staffId = empleadoCapturado,
                cashTenderedCents = cashReceivedCents,
                changeCents = changeCents,
                rating = ratingCapturado,
                orderId = ordenExistente,
                // La MISMA identidad con que se crea la orden en línea: si la creación sí llegó, el
                // reintento la encuentra en vez de crear otra.
                orderExternalId = if (ordenExistente == null) llave else null,
                customerId = clienteCapturado,
                idempotencyKey = llave,
                manualMethod = manualCapturado,
                tenderType = tenderCapturado,
                venueId = venueCapturado,
                cajaDelCobro = cajaCapturada,
                // Si la venta ya cambió, este intento guarda SU captura sin borrar nada.
                enLugarDe = if (generacion == paymentGeneration) anterioresDeEstaVenta else emptyList(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No POST without its original durable capture. The caller presents the error. Los apartados viejos siguen del flujo.
            throw IllegalStateException("No se pudo guardar el cobro. Intenta de nuevo antes de recibir el pago.", e)
        }
        cashPaymentRepository.recordarCajero(llave, actorCapturado, nombreActorCapturado)
        val efectivoPuro = manualCapturado == null && tenderCapturado == null
        val apartado = if (efectivoPuro) Apartado.EFECTIVO else Apartado.OTRA_FORMA
        if (generacion != paymentGeneration) {
            // La venta de este intento ya no está en pantalla (otra venta empezó mientras se escribía): nadie decide por él.
            cashPaymentRepository.olvidarReservaEnMemoria(llave)
            barrerYa() // ronda final: lo soltado se da por cobrado en el acto
            return apartado
        }
        apartadosDeEstaVenta.removeAll(anterioresDeEstaVenta.toSet()) // se borraron en la misma escritura (o es su misma llave)
        if (efectivoPuro) {
            apartadosDeEstaVenta += llave
        } else {
            reservaNoEfectivo = llave // «Ya pagó de otra forma»: sigue como hoy (A5)
        }
        apartadoDelIntento = llave
        return apartado
    }

    /** Lo que [reservarEfectivo] dejó en disco para este intento. */
    private enum class Apartado {
        /** Sin apartado (premio, vales, mesa provisional, sin carrito): nada durable antes de la red. */
        NINGUNO,
        /** Efectivo puro: si nadie decide, se da por cobrado; su venta la anota su salida de `EN_VUELO`. */
        EFECTIVO,
        /** «Ya pagó de otra forma» (método manual o tipo del catálogo): queda «sin confirmar» y lo decide una persona. */
        OTRA_FORMA,
    }

    /**
     * Borra, y ESPERA a que quede escrito, cada apartado de [llaves] (sólo los `EN_VUELO` con captura). false = el disco falló:
     * los que no se pudieron borrar siguen siendo del flujo.
     */
    private suspend fun descartar(llaves: Collection<String>): Boolean = try {
        withContext(kotlinx.coroutines.NonCancellable) { llaves.forEach { cashPaymentRepository.descartarReservaRechazada(it) } }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("💵", "No se pudieron borrar los apartados $llaves: ${e.message}")
        false
    }

    /**
     * 🔴 D2 v7 — «Cancelar» de la cajera sobre ESTA venta: se borran, y se espera a que quede escrito, todos sus apartados de
     * efectivo, sea cual sea el desenlace que vio (es su decisión, tomada sabiendo si tiene el billete). true = ya no queda
     * ninguno y la pantalla puede cerrarse. false = no se pudo: la pantalla dice «No se pudo cancelar el cobro. Intenta de
     * nuevo.», no se cierra y los apartados siguen del flujo. Con el POST del efectivo en vuelo no hay cancelación (punto 5).
     */
    suspend fun descartarApartadosDeEstaVenta(): Boolean {
        if (efectivoEnVuelo() || descartandoApartados) return false
        val llaves = apartadosDeEstaVenta.toList()
        if (llaves.isEmpty()) return true
        // 🔴 Codex r14 B1: la venta dueña de estas llaves, y las llaves fuera de la lista ANTES del disco: si otra venta empieza mientras
        // se borran, no las suelta (soltarlas las daría por cobradas a medio borrar). El borrado de las llaves de A termina igual.
        val venta = ventaEnCurso
        apartadosDeEstaVenta.removeAll(llaves.toSet())
        descartandoApartados = true
        try {
            // 🔴 Codex r15 #1: el desenlace se decide en NonCancellable, así que también corre si la pantalla se destruyó mientras se
            // borraba. Lo que no se pudo borrar y ya no tiene dueño (pantalla destruida u otra venta) se suelta y se barre en el acto,
            // como en cualquier suelta; si no, quedaba EN_VUELO reservado por un ViewModel muerto, sin barrido, hasta reiniciar.
            val borrados = withContext(kotlinx.coroutines.NonCancellable) {
                val ok = descartar(llaves)
                if (!ok && (destruido || otraVenta(venta))) {
                    llaves.forEach(cashPaymentRepository::olvidarReservaEnMemoria)
                    barrerYa()
                }
                ok
            }
            // Nadie decide ya por A: nada se escribe en B ni en una pantalla destruida.
            if (destruido || otraVenta(venta)) return false
            if (!borrados) {
                apartadosDeEstaVenta += llaves // siguen del flujo
                _cancelFailure.value = com.avoqado.pos.payment.domain.CancelacionDeCobro.NO_SE_PUDO_CANCELAR_EL_COBRO
                return false
            }
            cancelacionFallida = false
            if (apartadoDelIntento in llaves) apartadoDelIntento = null
            return true
        } finally {
            descartandoApartados = false
        }
    }

    /** El POST del efectivo viaja (punto 5 de D2 v6): la pantalla no ofrece «Cancelar» y el ViewModel lo ignora. */
    private fun efectivoEnVuelo(): Boolean = (_state.value as? PaymentFlowState.Processing)?.efectivoEnVuelo == true

    /**
     * La venta se cobró (D2 v5, punto 2). El apartado del intento que cobró sólo se suelta (ya subió, se encoló o se da por
     * cobrado); los de intentos anteriores de esta venta sobran y se borran. Si ese borrado falla, se reintenta antes del
     * siguiente cobro ([primeroDescartarLosPendientes]).
     */
    private fun alCobrarLaVenta(st: PaymentFlowState.Success) {
        val cobrado = apartadoDelIntento?.takeIf { st.method == PaymentMethod.CASH }
        val anteriores = apartadosDeEstaVenta.filter { it != cobrado }
        apartadosDeEstaVenta.clear()
        apartadoDelIntento = null
        reservaNoEfectivo?.takeIf { it != cobrado }?.let(cashPaymentRepository::olvidarReservaEnMemoria)
        reservaNoEfectivo = null
        if (cobrado == null && anteriores.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            cobrado?.let { llave ->
                runCatching { cashPaymentRepository.soltarReserva(llave) }
                    .onFailure { Log.e("💵", "No se pudo soltar el apartado cobrado $llave: ${it.message}") }
            }
            if (anteriores.isNotEmpty() && !descartar(anteriores)) porDescartar += anteriores
        }
    }

    /**
     * La venta ya no está en pantalla sin que la cajera decidiera (otra venta, pantalla recreada, `onCleared`): sus apartados
     * dejan de ser del flujo y la cola los da por cobrados con su misma llave (D2 v7, punto 3). Nunca borra.
     */
    private fun soltarLosApartadosDeEstaVenta() {
        soltarLaReservaNoEfectivo()
        if (apartadosDeEstaVenta.isEmpty()) return
        apartadosDeEstaVenta.forEach(cashPaymentRepository::olvidarReservaEnMemoria)
        apartadosDeEstaVenta.clear()
        apartadoDelIntento = null
        barrerYa()
    }

    /** A5: «Ya pagó de otra forma» que no se cobró se suelta (queda «sin confirmar», como hoy); nunca se borra ni se da por cobrado. */
    private fun soltarLaReservaNoEfectivo() {
        val llave = reservaNoEfectivo ?: return
        reservaNoEfectivo = null
        if (apartadoDelIntento == llave) apartadoDelIntento = null
        cashPaymentRepository.olvidarReservaEnMemoria(llave)
    }

    /** Ronda 1 (M4): lo soltado se da por cobrado EN EL ACTO (aunque haya una vuelta corriendo) y la cola lo sube ya. */
    private fun barrerYa() {
        runCatching { paymentSyncService.darPorCobradasLasAbandonadasYa(); paymentSyncService.syncNow() }
            .onFailure { Log.e("💵", "No se pudo pedir la vuelta de la cola; los apartados suben en la siguiente: ${it.message}") }
    }

    /**
     * 🔴 Ronda final (A2, Codex r8 #6): un fallo inesperado del cobro en efectivo (p. ej. el encolado sin red que truena) no tumba la
     * app ni deja «Procesando» sin salida: sale el error del efectivo y la cajera decide; su apartado sigue del flujo («Cancelar» lo
     * borra; salir sin cancelar lo da por cobrado, v7).
     */
    private fun alFallarElEfectivo(generacion: Int) = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        Log.e("💵", "El cobro en efectivo falló sin desenlace: ${e.message}", e)
        if (generacion != paymentGeneration) return@CoroutineExceptionHandler
        isProcessingPayment = false
        _state.value = PaymentFlowState.Error(MENSAJE_EFECTIVO_SIN_GUARDAR, PaymentErrorSource.UNKNOWN)
    }

    /**
     * 🔴 LA CAJA DE LA VENTA, leída UNA vez al confirmar el cobro (efectivo sin red en el turno correcto, 21-oct-2026).
     *
     * Va en la reserva (antes de la red) y en cada encolado, siempre la MISMA: si la caja abierta cambiara a media petición (una
     * adopción, otro aparato que cierra), la venta seguiría siendo de la caja en que se cobró. Es el id que el aparato tiene
     * (local si la apertura no ha llegado; el del servidor si se abrió con red o se adoptó de otro aparato); la cola lo
     * traduce al reproducir. Leerla nunca detiene una venta: si falla, el cobro va sin caja, como hoy.
     */
    /**
     * 🔴 Codex apps r1 #1: la caja de ESTA venta todavía espera a que su apertura llegue al servidor (la cola del cajón la retiene
     * detrás del cierre de la caja anterior, que espera a sus ventas). Mandar el efectivo EN LÍNEA ahora lo sumaría a la caja
     * anterior, que el servidor sigue viendo abierta. Se trata como sin red: va a la cola con su caja y sube detrás de su apertura.
     * Leerlo nunca detiene una venta: si falla, va en línea como hoy.
     */
    private fun laCajaEsperaSuApertura(caja: String?): Boolean =
        caja != null && runCatching { caja in cashDrawerRepository.cajasQueEsperanSuApertura() }.getOrDefault(false)

    /** El «sin red» de [laCajaEsperaSuApertura]: un IOException, así que cada camino lo encola como siempre ([OrderRepository.isQueueableError]). */
    private class CajaEsperandoSuApertura : java.io.IOException("La caja de esta venta todavía espera su apertura en el servidor")

    private suspend fun cajaAbiertaAlConfirmar(): String? = try {
        cashDrawerRepository.getOpenSession()?.id?.takeIf { it.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("💵", "No se pudo leer la caja abierta; el cobro sigue sin caja: ${e.message}")
        null
    }

    // MARK: - Se cobra el precio que vio el cliente (17-oct)

    /**
     * El carrito del cobro con el precio que decidió el servidor. Con [cambios] vacío no toca nada y devuelve false. Las partes
     * por artículo o iguales se recalculan; el monto que eligió el cajero se conserva, topado al total nuevo.
     *
     * ponytail: el carrito de `CartViewModel` sólo se corrige al confirmar el cobro (la completion). Si el cajero cancela tras un
     * 409 sin cobrar, conserva el precio viejo; el siguiente cobro vuelve a recibir el 409 y se corrige solo.
     */
    private fun corregirPrecios(cambios: List<CambioDePrecio>, congelar: Boolean): Boolean {
        if (cambios.isEmpty()) return false
        val corregido = cartState?.conPreciosDelServidor(cambios, congelar) ?: return false
        cartState = corregido
        // D10 (Codex r1 #4): con el precio corregido la parte puede pasar a ser TODO lo que falta: se lleva todos los productos.
        if (_splitType.value == "BYPRODUCT" && parteDeLaSeleccion(corregido).todoLoQueFalta) {
            splitSelectedItemIds = corregido.items.map { it.id }.toSet()
        }
        if (splitBaseAmountOverride != null) splitBaseAmountOverride = resolveSplitBaseAmount(corregido)
        cambiosDeEstaVenta = acumularCambiosDePrecio(cambiosDeEstaVenta, cambios)
        return true
    }

    /** El aviso de lo acumulado en esta venta; el «Total nuevo» sólo cuenta si hubo un 409. null limpia uno que ya no aplica. */
    private fun publicarAvisoDePrecio(totalNuevoCents: Int, cambioDeMasCents: Int = 0) {
        val aviso = avisoDePrecioDeLaCreacion(cambiosDeEstaVenta, totalNuevoCents, totalAntesDelRechazoDePrecio, cambioDeMasCents)
        if (aviso == ultimoAvisoDePrecio) return
        ultimoAvisoDePrecio = aviso
        _avisoDePrecio.value = aviso
    }

    /**
     * 🔴 DINERO. 409 `ORDER_PRICE_CHANGED` al crear la orden: el servidor no creó nada y no se tomó dinero (la cola sin red nunca
     * lo recibe). 🔴 D2 v7: el apartado de este intento NO se borra aquí —recibir un «no» no es una decisión de la cajera—; sigue
     * del flujo y se borra cuando ella decide: al guardar el apartado del nuevo intento (otra llave) o al tocar «Cancelar». Si
     * la pantalla se va sin que decida, se da por cobrado. Llave nueva, precios corregidos SIN congelar, catálogo nuevo y el
     * total nuevo a la vista.
     */
    private fun volverAElegirTrasCambioDePrecio(error: Throwable, cambios: List<CambioDePrecio>, totalAntes: Int, tipCapturado: Int) {
        estrenarLlaveTrasRechazoDeOrden(error)
        if (totalAntesDelRechazoDePrecio == null) totalAntesDelRechazoDePrecio = totalAntes
        corregirPrecios(cambios, congelar = false)
        val nuevo = currentBaseAmount() + tipCapturado
        publicarAvisoDePrecio(totalNuevoCents = nuevo)
        refrescoDelCatalogo?.refrescarAhora()
        isProcessingPayment = false
        _state.value = PaymentFlowState.SelectingPaymentMethod(nuevo)
    }

    /**
     * Un borrado que no se pudo escribir ([porDescartar]) se reintenta ANTES de cualquier cobro, y sólo si sale bien el cobro
     * sigue ([seguir]). true = había borrados pendientes y esta función se encargó.
     */
    private fun primeroDescartarLosPendientes(seguir: () -> Unit): Boolean {
        if (porDescartar.isEmpty()) return false
        val llaves = porDescartar.toList()
        val venta = ventaEnCurso
        isProcessingPayment = true
        // Es sólo una escritura en el aparato, pero sin «Cancelar»: al terminar arranca el cobro que la cajera pidió.
        _state.value = PaymentFlowState.Processing(currentBaseAmount() + currentTipCents, efectivoEnVuelo = true)
        viewModelScope.launch {
            val borrados = descartar(llaves)
            if (borrados) porDescartar.removeAll(llaves.toSet())
            // 🔴 Codex r14 B1: si otra venta empezó mientras se borraba, ni la bandera, ni el error, ni el cobro de A van sobre ella.
            if (otraVenta(venta)) return@launch
            if (!borrados) {
                isProcessingPayment = false
                _state.value = PaymentFlowState.Error(MENSAJE_RESERVA_SIN_RETIRAR, PaymentErrorSource.UNKNOWN)
                return@launch
            }
            isProcessingPayment = false
            seguir()
        }
        return true
    }

    /// Invalidates in-flight terminal sends: cancel() bumps it, and a late
    /// result from a cancelled send is ignored instead of overwriting the
    /// screen, marking Success and PRINTING a receipt for a cancelled payment.
    private var paymentGeneration = 0

    /**
     * La consulta de «cómo quedó el cobro» que va en vuelo, con la generación (pantalla) que la lanzó y si sigue siendo la
     * automática de D2 (mesas40) o ya la hizo suya el cajero con «Volver a consultar».
     *
     * 🔴 Ronda 2 de Codex: la consulta vive en el VM y sobrevive a salir. Una pantalla NUEVA no hereda su espera —si la hacía
     * suya, el desenlace se descartaba por la generación y se quedaba en «Consultando…» para siempre—: lanza la suya, y el
     * servicio la une al mismo GET por `requestId`.
     */
    private class ConsultaEnVuelo(val requestId: String, val generacion: Int, var automatica: Boolean)
    private var consultaEnVuelo: ConsultaEnVuelo? = null

    /** El toque de «Cobrar» que arrancó el cobro en pantalla. Ver `startPaymentFlow`. */
    private var aperturaEnCurso: String? = null

    /** Quién abrió el cobro en curso (`startPaymentFlow`). Ver [esCobroDeVales]. */
    private var origenDelCobro: com.avoqado.pos.payment.domain.OrigenDelCobro = com.avoqado.pos.payment.domain.OrigenDelCobro.NORMAL

    /** ¿Esta apertura ya arrancó el cobro que está en pantalla? Entonces la pantalla no reparte ni arranca otra vez. */
    fun esElCobroEnCurso(apertura: String): Boolean = apertura == aperturaEnCurso

    // MARK: - Cancelación durable del cobro (§C.4)

    /**
     * 🔴 La orden la creó ESTE flujo, así que cancelarlo puede cancelarla.
     *
     * Sin esta distinción, cancelar el cobro de una mesa (sesión PAYING) o de la parte 2 de un
     * split borraba una cuenta que ya existía y que nadie pidió cancelar.
     */
    private var ordenCreadaPorEsteFlujo = false

    /** El último cobro que ESTE flujo mandó a una terminal, y si consta que no llegó a crearse. */
    private data class UltimoIntentoDeCobro(val requestId: String?, val noSeCreo: Boolean)
    private var ultimoIntento: UltimoIntentoDeCobro? = null

    /** La intención de cancelar que esta pantalla está observando. */
    private var cancelacionObservada: String? = null
    private var observadorDeCancelacion: kotlinx.coroutines.Job? = null

    /** El cobro que esta pantalla ya aplicó tras descubrir que la terminal sí cobró. */
    private var cobroAplicadoTrasCancelar: String? = null

    private val _debeSalir = MutableStateFlow(false)

    /** El flujo terminó por una cancelación: la pantalla anfitriona lo cierra. */
    val debeSalir: StateFlow<Boolean> = _debeSalir.asStateFlow()

    fun consumirSalida() { _debeSalir.value = false }

    init {
        // Refresca el catálogo de tipos de pago al entrar al flujo de cobro. Es
        // cache-first: si falla, conserva la última lista buena — nunca deja al
        // cajero sin poder registrar la venta que acaba de entregar.
        viewModelScope.launch { tenderTypeRepository.refresh() }
        // La pantalla del cliente llama a los MISMOS métodos que el cajero
        // (submitRating/submitTip): la calificación y la propina no tienen dos
        // caminos, solo dos superficies de entrada.
        customerDisplay.onRatingPicked = { submitRating(it) }
        customerDisplay.onTipPicked = { submitTip(it) }
        // El cliente teclea su WhatsApp/correo en SU pantalla (teclado propio) y
        // dispara los MISMOS envíos que el cajero. Solo aplica en pantallas táctiles
        // (la detección de hardware ya apaga la interacción donde el dedo no llega).
        customerDisplay.onWhatsAppSubmit = { sendReceiptWhatsApp(it) }
        customerDisplay.onEmailSubmit = { sendReceiptEmail(it) }
        // OJO: el espejo del estado de envío (enviando/enviado/error) vive en un
        // init MÁS ABAJO, después de declarar los StateFlow que colecta. Aquí
        // arriba crashea: viewModelScope usa Main.immediate y correría el collect
        // de inmediato, cuando _whatsAppSending/etc aún son null.

        viewModelScope.launch {
            _state.collect { st ->
                // Undetermined también libera el guard: si no, la pantalla honesta se queda
                // sin poder re-consultar ni cobrar de nuevo (el cajero atrapado sin salida).
                if (st is PaymentFlowState.Success ||
                    st is PaymentFlowState.Error ||
                    st is PaymentFlowState.Undetermined
                ) {
                    isProcessingPayment = false
                }
                if (st is PaymentFlowState.Success) {
                    paymentIdempotencyKey = null
                    // 🔴 D2 v7: sólo el COBRO termina los apartados de la venta. Un rechazo, un error o un 409 ya no los sueltan:
                    // siguen del flujo hasta que la cajera vuelve a cobrar o toca «Cancelar».
                    alCobrarLaVenta(st)
                } else if (st !is PaymentFlowState.Processing) {
                    // A5: un «Ya pagó de otra forma» que no se cobró sigue como hoy (se suelta, queda «sin confirmar»).
                    soltarLaReservaNoEfectivo()
                }
                mirrorToCustomerDisplay(st)
            }
        }
    }

    override fun onCleared() {
        desvincularRecuperacionDeFoto?.invoke()
        desvincularRecuperacionDeFoto = null
        destruido = true
        // 🔴 D2 v7: la pantalla se fue sin que la cajera decidiera (con o sin el POST en vuelo): los apartados se quedan
        // guardados, nunca se borran aquí, y la cola los da por cobrados con su misma llave.
        // ponytail: un borrado ya decidido que el disco no dejó escribir ([porDescartar]) también se suelta y se da por cobrado:
        // venta doble que el gerente cancela (riesgo aceptado de v7). Mejorar si el disco que falla deja de ser raro.
        val habiaPorDescartar = porDescartar.isNotEmpty()
        porDescartar.forEach(cashPaymentRepository::olvidarReservaEnMemoria)
        porDescartar.clear()
        if (habiaPorDescartar && apartadosDeEstaVenta.isEmpty()) barrerYa() // ronda final: también en el acto
        soltarLosApartadosDeEstaVenta()
        super.onCleared()
    }

    /** Los mensajes de éxito de envío contienen "enviado"; los de error, no. */
    private fun receiptSendFrom(msg: String) =
        if (msg.contains("enviado", ignoreCase = true))
            com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Sent
        else
            com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Error

    /**
     * Espejo del flujo de pago a la pantalla del cliente. Traduce estado →
     * contenido; NO decide nada y los montos llegan ya resueltos (la propina, con la misma cuenta que el cajero).
     */
    private fun mirrorToCustomerDisplay(st: PaymentFlowState) {
        customerDisplay.show(
            when (st) {
                is PaymentFlowState.CollectingRating ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Rating(st.amount)

                is PaymentFlowState.CollectingTip ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Tip(
                        amountCents = st.amount,
                        // Montos ya calculados sobre la MISMA base que ve el cajero (sin IVA): ver tipCentsForPercent.
                        options = currentTipPercentageBaseCents().let { base ->
                            settings.tipSuggestions.map {
                                com.avoqado.pos.customerdisplay.TipOption(it, tipCentsForPercent(base, it))
                            }
                        },
                        selectedTipCents = null,
                    )

                is PaymentFlowState.Confirming ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Charging(
                        totalCents = st.amount + st.tip,
                        message = "Confirmando tu pago…",
                    )

                is PaymentFlowState.SentToTerminal ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Charging(
                        totalCents = st.totalAmount,
                        message = "Sigue las instrucciones en la terminal",
                    )

                is PaymentFlowState.Processing ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Charging(
                        totalCents = st.totalAmount,
                        message = "Procesando tu pago…",
                    )

                is PaymentFlowState.Success ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Done(
                        totalCents = st.totalAmount,
                        // La URL del backend si vino; si no, se arma contra el DASHBOARD.
                        // Sin llave de recibo no se dibuja QR: mejor nada que un
                        // código que no lleva a ningún lado.
                        receiptUrl = com.avoqado.pos.core.data.network.resolveReceiptUrl(
                            st.receiptUrl,
                            st.receiptAccessKey,
                        ),
                        // «Ofrecer recibo» de la copia del cobro (`settings` = `flowSettings`), igual que la caja.
                        offerReceipt = settings.showReceiptScreen,
                    )

                // Le toca al cajero: el cliente ve su total, sin nada tocable.
                // Antes esto era `return` (no actualizar) y la pantalla del
                // cliente se quedaba con la propina puesta y sus botones VIVOS
                // después de que el cajero ya había avanzado.
                // Turno del cajero: el cliente ve el DESGLOSE tipo recibo
                // (productos + subtotal + descuento + propina + total), armado
                // desde el carrito + propina actuales, no un total pelón.
                is PaymentFlowState.SelectingPaymentMethod,
                is PaymentFlowState.CollectingCashAmount,
                is PaymentFlowState.SelectingTerminal,
                ->
                    checkoutBreakdown()

                // Sin venta que mostrar (cargando, error): de vuelta a la marca.
                // Undetermined es asunto del CAJERO —él revisa la terminal—: al cliente no se
                // le pone ni un éxito que no consta ni un error que quizá no ocurrió.
                is PaymentFlowState.Loading,
                is PaymentFlowState.Error,
                is PaymentFlowState.Undetermined,
                // La cancelación es del mismo tipo: es asunto del cajero y de la terminal.
                is PaymentFlowState.CancelandoCobro,
                is PaymentFlowState.CancelacionPendiente,
                ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Idle
            },
        )
    }

    /**
     * Desglose tipo recibo para la pantalla del cliente durante el cobro. Espeja
     * el carrito (productos, subtotal, descuento, impuestos) y le suma la propina
     * ya elegida. Sin productos (monto personalizado) se cae a "solo total".
     * Cero fuente de verdad: los montos vienen ya calculados de CartState.
     */
    private fun checkoutBreakdown(): com.avoqado.pos.customerdisplay.CustomerContent.Total {
        val cart = cartState
        return com.avoqado.pos.customerdisplay.desgloseDelCobro(
            cart = cart,
            tipCents = currentTipCents,
            premioCents = cart?.let { premioEnLaCuenta(it) } ?: 0,
            // Una parte de cuenta dividida se ve como «Tu parte» con lo que de verdad se cobra.
            esPagoCompleto = esPagoCompleto(),
            montoACobrarCents = currentBaseAmount(),
        )
    }
    private var selectedTerminalId: String? = null
    /**
     * `requestId` del cobro con tarjeta cuyo desenlace no consta, **según esta pantalla**.
     * Mientras no sea null, `retry()` tiene prohibido cobrar: primero re-consulta.
     *
     * Vive en `SavedStateHandle` para sobrevivir a la muerte del proceso. La copia
     * autoritativa y durable es la del servicio (disco); ésta sirve para distinguir
     * "el cobro sin resolver es MÍO" de "es de una venta anterior" — sin esa distinción,
     * re-consultar un cobro viejo podría marcar como pagada una venta que nadie cobró.
     */
    private var undeterminedRequestId: String?
        get() = savedStateHandle[KEY_UNDETERMINED_REQUEST]
        set(value) { savedStateHandle[KEY_UNDETERMINED_REQUEST] = value }

    private var lastPaymentId: String? = null
    /** Marca y últimos 4 del cobro con tarjeta que devolvió la terminal. Van al ticket impreso. */
    private var lastCardBrand: String? = null
    private var lastCardLastFour: String? = null
    private var lastReceiptAccessKey: String? = null
    /** URL del recibo tal como la mandó el backend (dashboard). Ver `resolveReceiptUrl`. */
    private var lastReceiptUrl: String? = null
    private var lastAreaDeliveryCode: String? = null
    private var lastCashTenderedCents: Int? = null
    private var splitSelectedItemIds: Set<String> = emptySet()

    /**
     * D7 (27-oct): este cobro sigue una venta YA partida en tienda (`resumeOrderId`, no mesa). Sólo aquí el saldo del
     * SERVIDOR manda también en el pago completo del resto: otra caja o la terminal pudieron cambiar la venta entre partes.
     */
    private var continuaVentaPartida = false

    /** D7: tras una parte con tarjeta no se pudo confirmar el saldo con el SERVIDOR (el éxito lo dice). */
    private var saldoSinConfirmar = false
    private var splitNumberOfParts: Int? = null
    private var splitCustomAmountCents: Int? = null
    private var splitBaseAmountOverride: Int? = null

    /**
     * 🔴 DINERO. Total (centavos, propina incluida) de la orden que el server
     * acaba de crear, cuando difiere del estimado del carrito. Manda sobre el
     * estimado: la orden es la deuda real y el server tolera 1 centavo antes de
     * dejarla PARTIAL. null = se cobra el carrito, como siempre.
     *
     * Se llena SÓLO en ventas con promoción y pago completo (ver
     * `totalACobrarCents`), y se limpia al arrancar cada venta.
     */
    private var serverTotalOverrideCents: Int? = null

    /**
     * 🔴 DINERO. Total (centavos, propina incluida) que el server le puso a la
     * orden de ESTA venta, cuando la venta llevaba promoción.
     *
     * Se guarda SIEMPRE que la orden se crea — también en pago DIVIDIDO, donde
     * no manda sobre lo que se cobra ahora (ese importe lo eligió el cajero)
     * pero sí sobre **lo que queda por cobrar**: ver `buildCompletion`.
     */
    private var serverOrderTotalCents: Int? = null

    /**
     * 🔴 DINERO. Saldo (centavos) que el SERVER dice que le queda a la orden
     * después del pago que acaba de entrar. Manda sobre la aritmética local del
     * carrito en [buildCompletion]: es el único que conoce los pagos que este
     * dispositivo no vio (otra caja, un link, un abono anterior).
     *
     * null = el server no lo mandó (versión vieja, camino de tarjeta, cobro
     * encolado sin red) y se usa el cálculo de siempre. Se limpia al arrancar
     * cada venta: arrastrarlo dejaría un saldo fantasma en la siguiente.
     */
    private var serverRemainingBalanceCents: Int? = null

    // WhatsApp receipt sending state
    private val _whatsAppSending = MutableStateFlow(false)
    val whatsAppSending: StateFlow<Boolean> = _whatsAppSending.asStateFlow()

    private val _whatsAppResult = MutableStateFlow<String?>(null)
    val whatsAppResult: StateFlow<String?> = _whatsAppResult.asStateFlow()

    fun clearWhatsAppResult() {
        _whatsAppResult.value = null
    }

    // Email receipt sending state
    private val _emailSending = MutableStateFlow(false)
    val emailSending: StateFlow<Boolean> = _emailSending.asStateFlow()

    private val _emailResult = MutableStateFlow<String?>(null)
    val emailResult: StateFlow<String?> = _emailResult.asStateFlow()

    fun clearEmailResult() {
        _emailResult.value = null
    }

    // Espejo del estado de envío del recibo hacia la pantalla del cliente
    // (enviando/enviado/error). En un init APARTE porque viewModelScope usa
    // Main.immediate y colecta de inmediato: debe correr DESPUÉS de declarar
    // los StateFlow de arriba, o sería null (crash de orden de init).
    init {
        viewModelScope.launch {
            _whatsAppSending.collect { if (it) customerDisplay.setReceiptSend(com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Sending) }
        }
        viewModelScope.launch {
            _emailSending.collect { if (it) customerDisplay.setReceiptSend(com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Sending) }
        }
        viewModelScope.launch {
            _whatsAppResult.collect { r -> r?.let { customerDisplay.setReceiptSend(receiptSendFrom(it)) } }
        }
        viewModelScope.launch {
            _emailResult.collect { r -> r?.let { customerDisplay.setReceiptSend(receiptSendFrom(it)) } }
        }
    }

    // Manual receipt reprint state
    private var lastReceipt: ReceiptData? = null

    private val _printSending = MutableStateFlow(false)
    val printSending: StateFlow<Boolean> = _printSending.asStateFlow()

    private val _printResult = MutableStateFlow<String?>(null)
    val printResult: StateFlow<String?> = _printResult.asStateFlow()

    /**
     * Se resolvió un cobro pendiente que venía de OTRA venta: el flujo debe CERRARSE y
     * devolver al cajero a donde estaba, con este mensaje.
     *
     * 🔴 No basta con avisar y seguir: el cajero vino a resolver un pendiente, no a cobrar.
     * Antes se le soltaba en el primer paso de la venta nueva mientras un toast verde se
     * desvanecía encima — o sea que el desenlace del cobro viejo (¡dinero!) pasaba volando
     * mientras la pantalla ya le pedía otra cosa. El mensaje lo pinta quien queda en
     * pantalla, no la pantalla que se va.
     */
    private val _previousChargeResolved = MutableStateFlow<String?>(null)
    val previousChargeResolved: StateFlow<String?> = _previousChargeResolved.asStateFlow()

    fun clearPreviousChargeResolved() { _previousChargeResolved.value = null }

    /**
     * 🔴 DINERO. Un cobro de vales se detuvo sin cobrar porque el carrito no traía los vales de la sesión del servidor
     * ([valesNoCoinciden]). Lo pinta el carrito, que es quien se queda, y al verlo toma los vales de la sesión.
     */
    private val _valesNoCoincidian = MutableStateFlow(false)
    val valesNoCoincidian: StateFlow<Boolean> = _valesNoCoincidian.asStateFlow()

    fun reconocerValesNoCoincidian() { _valesNoCoincidian.value = false }

    /**
     * H2 (26-sep): el cobro que SÍ pasó del que habla el mensaje de [previousChargeResolved]. Sigue en la lista durable,
     * marcado, hasta que el cajero cierra «Cobro anterior resuelto» ([reconocerCobroAnteriorResuelto]) o vence su ventana.
     * ponytail: sólo en memoria — si el proceso muere antes de cerrarlo, la entrada sigue en disco (su ventana) y se vuelve a decir.
     */
    private var cobroQueSiPasoPorReconocer: String? = null

    /** El mensaje de un cobro anterior resuelto; si SÍ pasó, trae su `requestId` para que «Entendido» lo quite (H2). */
    private fun resolverCobroAnterior(mensaje: String, cobroQueSiPaso: String? = null) {
        cobroQueSiPasoPorReconocer = cobroQueSiPaso
        _previousChargeResolved.value = mensaje
    }

    /** Cerrar «Cobro anterior resuelto» («Entendido» o la X): si ese cobro SÍ pasó, sale de la lista durable — sólo ése. */
    fun reconocerCobroAnteriorResuelto() {
        cobroQueSiPasoPorReconocer?.let { terminalPaymentService.reconocerCobro(it) }
        cobroQueSiPasoPorReconocer = null
    }

    /**
     * El cobro sin confirmar (o que SÍ pasó) de OTRA venta, pintado en la selección de terminal. Es la única protección
     * contra volver a cobrar esa duda, así que sale AL INSTANTE de la lista guardada, sin red; la consulta sólo lo afina.
     */
    private val _avisoDeOtroCobro = MutableStateFlow<AvisoDeOtroCobro?>(null)
    val avisoDeOtroCobro: StateFlow<AvisoDeOtroCobro?> = _avisoDeOtroCobro.asStateFlow()

    /** I-2 (re-revisión): con un «SÍ pasó» en la primera línea, la duda vigente más nueva de otra venta va DEBAJO — nunca la tapa. */
    private val _segundoAviso = MutableStateFlow<AvisoDeOtroCobro?>(null)
    val segundoAviso: StateFlow<AvisoDeOtroCobro?> = _segundoAviso.asStateFlow()

    /**
     * «Entendido» sobre el aviso de un cobro de otra venta que SÍ pasó: sale de la lista durable SÓLO ése (H2), y el
     * siguiente confirmado —si hay— toma su lugar. Un aviso SIN confirmar no se descarta: su dinero todavía no consta.
     */
    fun descartarAvisoDeOtroCobro(requestId: String) {
        if (_avisoDeOtroCobro.value?.let { it.requestId == requestId && it.yaCobrado } != true) return
        terminalPaymentService.reconocerCobro(requestId)
        publicarAviso(terminalPaymentService.pendientesDeOtrasVentas(ordenEnCurso()))
    }

    /**
     * M-5: el título «Cobro anterior sin confirmar» sólo cuando el cobro en revisión es de OTRA venta. Con el encabezado
     * «…de esta venta.» el título es «Cobro sin confirmar».
     */
    fun revisaCobroDeOtraVenta(): Boolean =
        (_state.value as? PaymentFlowState.Undetermined)?.fromPreviousSale == true &&
            cobroEnRevision?.encabezado != SAME_SALE_CHARGE_PREFIX

    /** H7: el reloj del vencimiento del aviso. Se inyecta en pruebas para moverlo con el tiempo virtual. */
    @androidx.annotation.VisibleForTesting
    internal var reloj: () -> Long = System::currentTimeMillis

    /** H7: el recálculo local programado al vencimiento del aviso SIN confirmar que está en pantalla. */
    private var vencimientoDelAviso: kotlinx.coroutines.Job? = null

    /** Sólo publica la revisión más reciente: una que contesta tarde no pisa el aviso de la venta o la pantalla actual. */
    private var revisionDeOtrasVentas = 0

    /**
     * Un cobro abierto como REVISIÓN (no adoptado): del que habla «Cobro anterior sin confirmar», y con qué encabezado —
     * «de otra venta» si llegó por «Revisar», «de esta venta» si es de esta orden pero no se adoptó.
     */
    private data class CobroEnRevision(val requestId: String, val encabezado: String)
    private var cobroEnRevision: CobroEnRevision? = null

    /**
     * La cancelación de la orden fue RECHAZADA por el server (típicamente 409: ya está pagada).
     * Antes esto sólo se logueaba mientras la app navegaba afuera, así que el cajero se quedaba
     * creyendo que canceló algo que sigue vivo — y encima el cobro podía aterrizar sobre esa
     * orden. Ahora se ve.
     */
    private val _cancelFailure = MutableStateFlow<String?>(null)
    val cancelFailure: StateFlow<String?> = _cancelFailure.asStateFlow()

    fun clearCancelFailure() { _cancelFailure.value = null }

    /**
     * Estado de la comanda automática post-cobro: `null` cuando salió bien (o todavía no se
     * sabe), [EstadoDeComanda.Insistiendo] mientras [ComandaDispatcher] sigue reintentando, y
     * [EstadoDeComanda.NoSalio] cuando se rindió — con la causa REAL del servidor, no un texto
     * genérico.
     *
     * 🔴 El cobro nunca se frena por una impresora — pero callar el fallo deja al barista
     * sin enterarse del pedido: Testarudo (2026-08-31) cobró cafés durante días sin que
     * saliera la comanda de barra y el único rastro era una línea de logcat. Antes esto se
     * daba por vencido al primer intento; ahora insiste (ver [ComandaDispatcher.dispatch]) y
     * el aviso final trae botón para volver a intentarlo a mano ([reintentarComanda]).
     */
    /**
     * El aviso EN CURSO («reintentando…»), que es transitorio y de esta pantalla.
     *
     * 🔴 El fallo FINAL no vive aquí: vive en [ComandasPendientesStore.pendiente], que es único
     * en toda la app. Antes cada ViewModel tenía su propia copia y dos pantallas podían ofrecer
     * imprimir la misma comanda por separado (P1 #6 de la 2ª auditoría de Codex).
     */
    private val _avisoEnCurso = MutableStateFlow<EstadoDeComanda?>(null)

    /** El pedido cuyo aviso el cajero se quitó de encima sin resolverlo (toque fuera / Atrás). */
    private val _ocultoSinResolver = MutableStateFlow<String?>(null)

    val comandaWarning: StateFlow<EstadoDeComanda?> =
        combine(_avisoEnCurso, comandasPendientesStore.pendiente, _ocultoSinResolver) { enCurso, pendiente, oculto ->
            enCurso ?: pendiente?.takeIf { it.orderNumber != oculto }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * «Ya la canté» — SÓLO el toque explícito del botón. Resuelve la libreta únicamente si el aviso que se ve ES la
     * libreta: entonces `yaLaCante` (no `limpiar`) la suelta y cierra sus entregas por WiFi, para que el replay no la
     * imprima sola después (KDS 3.5, rondas 3 y 4 de la Task 6).
     *
     * 🔴 N-I1 (ronda 4): cualquier otro aviso —el «Reintentando» de otra venta, un «no salió» sin trabajo de OTRA venta—
     * sólo se OCULTA. Resolver ahí la libreta cerraba las entregas de una comanda que nadie cantó: con la impresora
     * caída, esa comanda no quedaba en ningún lado.
     */
    fun clearComandaWarning() {
        val pendiente = comandasPendientesStore.pendiente.value
        val visto = _avisoEnCurso.value ?: pendiente
        if (pendiente != null && visto == pendiente) {
            _avisoEnCurso.value = null
            comandasPendientesStore.yaLaCante()
        } else {
            ocultarAvisoDeComanda()
        }
    }

    /**
     * Quita el aviso de la pantalla SIN darlo por resuelto.
     *
     * 🔴 Es lo que corresponde a un toque fuera del recuadro o al botón Atrás: el cajero está
     * ocupado y se lo quita de encima, pero la comanda sigue sin salir. Lo guardado se conserva,
     * así que vuelve al siguiente arranque en vez de desaparecer para siempre por un dedazo
     * (P2 #11 de la 2ª auditoría de Codex, 2026-09-07).
     */
    fun ocultarAvisoDeComanda() {
        _ocultoSinResolver.value = (comandaWarning.value as? EstadoDeComanda.NoSalio)?.orderNumber
        _avisoEnCurso.value = null
    }

    // 🔴 Ya NO se lee el disco aquí: lo pendiente lo carga `AppState` una sola vez al abrir
    // sesión, y este ViewModel lo OBSERVA. Leerlo por instancia era lo que producía dos copias.

    private val _canPrintOnTerminal = MutableStateFlow(false)
    val canPrintOnTerminal: StateFlow<Boolean> = _canPrintOnTerminal.asStateFlow()

    fun clearPrintResult() {
        _printResult.value = null
    }

    // Customer attachment state
    private val _customerAttachSending = MutableStateFlow(false)
    val customerAttachSending: StateFlow<Boolean> = _customerAttachSending.asStateFlow()

    private val _customerAttachResult = MutableStateFlow<String?>(null)
    val customerAttachResult: StateFlow<String?> = _customerAttachResult.asStateFlow()

    /**
     * Cliente de ESTA venta. Se siembra con el que ya venía elegido en el
     * carrito y lo sobrescribe el alta desde la pantalla de recibo.
     *
     * 🔴 Antes vivía sólo como estado local de la pantalla de recibo, arrancando
     * en null: el cajero elegía "Juan Pérez" en el carrito, cobraba, y al
     * terminar la pantalla le ofrecía "Agregar cliente" como si no hubiera
     * nadie. No era sólo la etiqueta — la orden se creaba SIN `customerId`, así
     * que la venta quedaba anónima en el server: sin historial de compra, sin
     * lealtad y sin cliente que facturar.
     */
    private val _attachedCustomerName = MutableStateFlow<String?>(null)
    val attachedCustomerName: StateFlow<String?> = _attachedCustomerName.asStateFlow()

    /** Id del cliente que viaja en `POST /orders`. */
    private var attachedCustomerId: String? = null

    /**
     * Renglón del sello en «Cobro exitoso» (sellos tardíos, 14-oct): «✓ +1 sello · 3 de 7», el premio, o por qué no
     * sumó. Es estado APARTE de [customerAttachResult] a propósito: «Imprimir» y «Enviar» borran ese aviso y el cajero
     * tiene que seguir viendo si la venta sumó. null = no se pinta nada.
     */
    private val _renglonDelSello = MutableStateFlow<RenglonDelSello?>(null)
    val renglonDelSello: StateFlow<RenglonDelSello?> = _renglonDelSello.asStateFlow()

    /**
     * La venta cuyo renglón del sello ya se decidió. `Success` se vuelve a emitir con `copy(...)` (avisos que se
     * limpian) y eso no puede volver a preguntar ni pisar lo que dijo la liga.
     */
    private var ventaDelSello: String? = null
    private var consultaDelSello: kotlinx.coroutines.Job? = null

    /**
     * 🔴 Nunca bloquea ni retrasa «Cobro exitoso»: corre aparte, y cualquier fallo es silencio (el renglón no sale).
     * - Cuenta dividida con saldo: la venta aún no está saldada; el renglón (o la promesa sin red) lo da la parte que la
     *   salda. Va ANTES de lo encolado: la parte 1 sin red tampoco puede prometer un sello que sólo llega al saldarse.
     * - Venta encolada sin red: no hay pago que consultar; si este aparato ya vio que el negocio tiene sellos, dice
     *   «El sello se suma al sincronizar» — sólo si la cola LLEVA al cliente (ver [laColaLlevaAlCliente]).
     *   Al sincronizar, la promesa se cambia por el sello real (ver [cambiarLaPromesaAlSincronizar]).
     * - PENDING: la lealtad aún no termina de procesarse; UN reintento a los [REINTENTO_DEL_SELLO_MS] y luego nada.
     */
    private fun alLlegarAExito(exito: PaymentFlowState.Success) {
        val paymentId = exito.paymentId ?: lastPaymentId
        val clave = paymentId ?: exito.colaDelCobro?.toString() ?: return
        if (clave == ventaDelSello) return
        ventaDelSello = clave
        consultaDelSello?.cancel()
        if (attachedCustomerId == null) return
        if (buildCompletion().remainingBalanceCents > 0) return

        if (exito.isQueued) {
            val venueId = secureStorage.venueId
            val cola = exito.colaDelCobro as? ColaDelCobro.Pagos
            if (cola != null && laColaLlevaAlCliente(exito) && venueId != null && orderRepository.programaDeSellosActivo(venueId) == true) {
                _renglonDelSello.value = RenglonDelSello.SE_SUMA_AL_SINCRONIZAR
                cambiarLaPromesaAlSincronizar(cola, clave)
            }
            return
        }
        if (paymentId == null) return

        consultaDelSello = viewModelScope.launch {
            val sello = consultarSelloConReintento(paymentId)
            if (ventaDelSello != clave) return@launch
            _renglonDelSello.value = RenglonDelSello.desde(sello)
        }
    }

    /** La consulta del sello de una venta, con UN reintento a los [REINTENTO_DEL_SELLO_MS] si la lealtad sigue PENDING. */
    private suspend fun consultarSelloConReintento(paymentId: String): SaleStampOutcome? {
        var sello = orderRepository.consultarSelloDeLaVenta(paymentId)
        if (sello != null && sello.stampsEnabled && sello.reasonCode == SaleStampReason.PENDING) {
            delay(REINTENTO_DEL_SELLO_MS)
            sello = orderRepository.consultarSelloDeLaVenta(paymentId)
        }
        return sello
    }

    /**
     * Info-7 (QA en la D3, 15-oct): la venta cobrada sin red decía «El sello se suma al sincronizar» y lo SEGUÍA diciendo
     * con «Venta sincronizada» arriba, aunque el servidor ya hubiera dado el sello. Ahora, en cuanto la cola confirma ESTA
     * venta (la misma señal del aviso, [sincronizacionDe]), se pregunta por su sello con el id que le dio el servidor y se
     * pinta lo que pasó. Si no se puede saber (consulta fallida, PENDING tras el reintento, fila ya limpiada) o la cola la
     * RECHAZA, se QUITA la promesa: una promesa vieja miente.
     * 🔴 Vive en [consultaDelSello]: «Venta nueva» la cancela. Y antes de pintar se revisa que la pantalla siga siendo la de
     * ESTA venta (misma venta, misma generación y el mismo cobro encolado en «Cobro exitoso»); si no, no pinta nada.
     */
    private fun cambiarLaPromesaAlSincronizar(cola: ColaDelCobro.Pagos, clave: String) {
        val generacion = paymentGeneration
        consultaDelSello = viewModelScope.launch {
            // El primer desenlace: sincronizada o RECHAZADA. Rechazada, la venta no subió y la promesa se quita.
            val desenlace = sincronizacionDe(cola).firstOrNull { it != SincronizacionDelCobro.PENDIENTE } ?: return@launch
            val sello = if (desenlace == SincronizacionDelCobro.SINCRONIZADA) {
                cashPaymentRepository.pagoDelServidor(cola.id)?.let { consultarSelloConReintento(it) }
            } else {
                null
            }
            val sigueEnPantalla = ventaDelSello == clave && generacion == paymentGeneration &&
                (_state.value as? PaymentFlowState.Success)?.colaDelCobro == cola
            if (!sigueEnPantalla) return@launch
            _renglonDelSello.value = RenglonDelSello.desde(sello)
        }
    }

    /**
     * ¿Al sincronizar, la venta llega al servidor CON su cliente? Sólo entonces se puede prometer el sello.
     * - Cola de pagos (`pending_payments`) de MOSTRADOR: sí — el cobro rápido manda `customerId` en el replay, y la orden
     *   creada (en línea o al reproducir, con `orderRequestJson`) nació con él.
     * - Mesa: no se sabe. El `PAY_CASH` de una mesa provisional (outbox) y su `OPEN_TABLE` no llevan cliente, y el pago
     *   encolado de una mesa existente va a `/orders/:id/pay`, que no lo liga: la orden de la mesa puede no tenerlo.
     */
    private fun laColaLlevaAlCliente(exito: PaymentFlowState.Success): Boolean =
        exito.colaDelCobro is com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos && mesaDelCobro == null

    fun clearCustomerAttachResult() {
        _customerAttachResult.value = null
    }

    fun attachCustomerToCurrentPayment(customerId: String, customerName: String) {
        // 🔴 Un doble toque en la fila del cliente llegaba aquí dos veces antes de que la hoja se cerrara: dos POST de
        // liga. Se marca «enviando» ANTES de lanzar, así el segundo toque ya lo ve.
        if (_customerAttachSending.value) return
        // 🔴 Venta ENCOLADA (cola de pagos, outbox de una mesa, o sin fila): sólo SU pago. «El último pago de $X del mismo
        // empleado en 15 min» puede ser otra venta igual (dos cafés de $100) y el cliente y su sello caerían ahí. Sin el id
        // del servidor no se llama: se dice. Espejo de iOS (Codex r1 #1, 18-oct; mesa y sin fila, 18-oct).
        val encolada = (_state.value as? PaymentFlowState.Success)?.takeIf { it.isQueued }
        if (encolada != null) {
            ligarClienteAVentaEncolada(encolada.colaDelCobro, customerId, customerName)
            return
        }
        val paymentId = lastPaymentId
        val amountCents = currentBaseAmount()
        val tipCents = currentTipCents
        // 🔴 La venta de ESTA liga (auditoría A #4). Si el cajero toca «Venta nueva» con la petición en vuelo, la respuesta
        // llega a una pantalla que ya es de OTRA venta y no puede escribir nada en ella.
        val generacion = paymentGeneration
        _customerAttachSending.value = true
        _customerAttachResult.value = null

        viewModelScope.launch {
            val result = if (!paymentId.isNullOrBlank()) {
                orderRepository.attachCustomerToPayment(paymentId, customerId)
            } else {
                orderRepository.attachCustomerToLatestPayment(
                    customerId = customerId,
                    amountCents = amountCents,
                    tipCents = tipCents,
                    staffId = selectedStaffId(),
                )
            }
            alResponderLaLiga(result, generacion, customerId, customerName)
        }
    }

    /**
     * La venta encolada se liga con el id que el servidor le dio a SU fila (cola de pagos) o a SU intent `PAY_CASH` (mesa
     * abierta sin red); sin él, o sin fila, ni se llama al servidor.
     */
    private fun ligarClienteAVentaEncolada(cola: ColaDelCobro?, customerId: String, customerName: String) {
        val generacion = paymentGeneration
        _customerAttachSending.value = true
        _customerAttachResult.value = null
        viewModelScope.launch {
            val pagoDelServidor = when (cola) {
                is ColaDelCobro.Pagos -> cashPaymentRepository.pagoDelServidor(cola.id)
                is ColaDelCobro.Outbox -> syncOutbox.pagoDelServidor(cola.intentId)
                null -> null
            }
            if (pagoDelServidor == null) {
                if (generacion == paymentGeneration) {
                    _customerAttachResult.value = TextosDelSello.VENTA_SIN_SINCRONIZAR
                    _customerAttachSending.value = false
                }
                return@launch
            }
            alResponderLaLiga(orderRepository.attachCustomerToPayment(pagoDelServidor, customerId), generacion, customerId, customerName)
        }
    }

    private fun alResponderLaLiga(
        result: Result<SaleStampOutcome?>,
        generacion: Int,
        customerId: String,
        customerName: String,
    ) {
        if (generacion != paymentGeneration) {
            // Ni cliente, ni nombre, ni aviso, ni renglón, ni «enviando» (lo reinició `startPaymentFlow` y puede ser ya
            // el de la liga de la venta nueva). Éxito o error: igual. La liga del servidor quedó en la venta anterior.
            Log.d("👤", "Respuesta de la liga de una venta anterior: no toca la venta en pantalla")
            return
        }
        result
            .fold(
                onSuccess = { sello ->
                    attachedCustomerId = customerId
                    _attachedCustomerName.value = customerName
                    _customerAttachResult.value = "Cliente agregado: $customerName"
                    // Sellos tardíos: el servidor ya sumó (o no) el sello al ligar; aquí sólo se dice.
                    consultaDelSello?.cancel()
                    _renglonDelSello.value = RenglonDelSello.desde(sello)
                    // El aviso ya se resolvió: la venta SÍ tiene cliente ahora. Dejarlo
                    // puesto es estado que miente — y era la única divergencia con iOS
                    // en el campo que este cambio introdujo.
                    (_state.value as? PaymentFlowState.Success)?.let { exito ->
                        _state.value = exito.copy(customerLinkWarning = null)
                    }
                },
                onFailure = { error ->
                    // Agregar cliente después de cobrar es online-only A PROPÓSITO (spec §4): sin red se dice en
                    // llano y con el plazo, nunca «Unable to resolve host…».
                    _customerAttachResult.value = if (ServerErrorText.isOffline(error)) {
                        TextosDelSello.SIN_RED_AL_AGREGAR_CLIENTE
                    } else {
                        ServerErrorText.humanize(error, fallback = "No se pudo agregar el cliente. Inténtalo de nuevo.")
                    }
                },
            )
        _customerAttachSending.value = false
    }

    fun reprintReceipt() {
        val receipt = lastReceipt ?: buildReceiptSnapshot()?.also { lastReceipt = it }
        if (receipt == null) {
            if (!selectedTerminalId.isNullOrBlank()) {
                _printResult.value = LOCAL_PRINTER_UNAVAILABLE
            } else {
                _printResult.value = "No hay recibo disponible para reimprimir"
            }
            return
        }
        // 🔴 Codex r14 B5: la reimpresión de A termina, pero su resultado y su bandera sólo se publican si A sigue en pantalla.
        val venta = ventaEnCurso
        viewModelScope.launch {
            _printSending.value = true
            _printResult.value = null
            try {
                // El motivo importa: "pon papel" y "configura una impresora" son
                // acciones distintas, y decir "Recibo impreso" cuando no salió
                // nada deja al cajero sin ticket creyendo que sí se imprimió.
                val resultado = when (val outcome = printerService.manualPrintReceipt(receipt)) {
                    is PrinterService.PrintOutcome.Printed -> "Recibo impreso"
                    is PrinterService.PrintOutcome.OutOfPaper -> "La impresora no tiene papel"
                    is PrinterService.PrintOutcome.Failed ->
                        if (!selectedTerminalId.isNullOrBlank()) LOCAL_PRINTER_UNAVAILABLE
                        else "No se pudo imprimir: ${outcome.reason}"
                    is PrinterService.PrintOutcome.NoPrinter ->
                        if (!selectedTerminalId.isNullOrBlank()) LOCAL_PRINTER_UNAVAILABLE
                        else "No hay impresora de recibos en esta caja. Agrégala en Más › Impresoras y cajón."
                }
                if (!otraVenta(venta)) _printResult.value = resultado
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!otraVenta(venta)) _printResult.value = "Error al imprimir: ${e.message ?: "desconocido"}"
            } finally {
                if (!otraVenta(venta)) _printSending.value = false
            }
        }
    }

    fun printReceiptOnTerminal() {
        val receipt = lastReceipt ?: buildReceiptSnapshot()?.also { lastReceipt = it }
        val terminalId = selectedTerminalId
        if (receipt == null) {
            _printResult.value = "No hay recibo disponible para imprimir"
            return
        }
        if (terminalId.isNullOrBlank()) {
            _printResult.value = "No hay TPV seleccionada para imprimir"
            return
        }

        val venta = ventaEnCurso // Codex r14 B5
        val pago = lastPaymentId
        val llaveDelRecibo = lastReceiptAccessKey
        viewModelScope.launch {
            _printSending.value = true
            _printResult.value = null
            val resultado = terminalPaymentService.printReceiptOnTerminal(
                terminalId = terminalId,
                receipt = receipt,
                paymentId = pago,
                receiptAccessKey = llaveDelRecibo,
            ).fold(
                onSuccess = { "Recibo impreso en TPV" },
                onFailure = { error -> error.message ?: "No se pudo imprimir en TPV" },
            )
            if (otraVenta(venta)) return@launch
            _printResult.value = resultado
            _printSending.value = false
        }
    }

    // Split payment support
    private val _splitType = MutableStateFlow("FULLPAYMENT")
    val splitType: StateFlow<String> = _splitType.asStateFlow()

    fun setSplitType(type: String) {
        _splitType.value = type
        splitSelectedItemIds = emptySet()
        splitNumberOfParts = null
        splitCustomAmountCents = null
        splitBaseAmountOverride = null
    }

    fun setSplitConfig(
        type: String,
        selectedItemIds: List<String> = emptyList(),
        numberOfParts: Int? = null,
        customAmountCents: Int? = null,
    ) {
        _splitType.value = type
        splitSelectedItemIds = selectedItemIds.toSet()
        splitNumberOfParts = numberOfParts
        splitCustomAmountCents = customAmountCents
    }

    /**
     * 🔴 Copia de los ajustes del cobro en curso, tomada al arrancarlo (`startPaymentFlow`). El poll, ON_RESUME y la
     * reconexión refrescan los ajustes en segundo plano: leídos vivos, un refresco a media venta cambiaba los pasos
     * (calificación/propina) o los porcentajes con el cliente ya frente a ellos. Un refresco sólo cuenta para el
     * SIGUIENTE cobro. Es local: sin red funciona igual.
     */
    private var flowSettings: TpvSettings? = null

    val settings: TpvSettings get() = flowSettings ?: tpvSettingsRepository.getCurrentSettings()

    private fun selectedStaffId(): String {
        return cartState?.selectedStaffId?.takeIf { it.isNotBlank() }
            ?: secureStorage.userId.orEmpty()
    }

    /**
     * @param customerId Cliente que el cajero ya eligió en el carrito. Viaja en
     *   `POST /orders` para que la venta quede ligada a él (historial, lealtad,
     *   facturación). Va como parámetro de ESTA función —y no en un setter
     *   aparte— porque aquí mismo se limpia el estado de la venta anterior: un
     *   setter externo se podía llamar antes y quedar borrado en silencio.
     * @param resumeOrderId 🔴 DINERO. La orden que esta venta ya tiene abierta
     *   porque una parte se cobró antes (split de MOSTRADOR, sin mesa). Va como
     *   parámetro por lo mismo que `customerId`: aquí se borra el estado de la
     *   venta anterior, así que un setter externo se perdería en silencio — y
     *   perderlo es exactamente el bug que esto cierra. Lo resuelve el carrito,
     *   que es el dueño de la venta (`CartViewModel.resolvePendingSplitOrderForCharge`).
     * @param origen Quién abrió el cobro ([com.avoqado.pos.payment.domain.OrigenDelCobro]). Con `PEDIDO_EN_LINEA` una mesa
     *   en PAYING no reemplaza a [resumeOrderId]. Default `NORMAL`: todo cobro de hoy queda igual.
     */
    private var ventaVivaDeSaldo: (() -> CartState)? = null
    private var fotoDeSaldo: (() -> com.avoqado.pos.giftcards.domain.FotoDeLaVenta)? = null
    private var vaciarDeSaldo: (() -> Unit)? = null
    private var restaurarDeSaldo: ((com.avoqado.pos.giftcards.domain.FotoDeLaVenta) -> CartState?)? = null
    private var desdeCuentasDeSaldo = false
    private var alRestaurarSnapshotDeSaldo: (CartState) -> Unit = {}
    private var ultimoCobroConSaldo: CobroConSaldo? = null
    val cuentaDelResultadoDeSaldo: String? get() = ultimoCobroConSaldo?.orderId
    val llaveDeLaSolicitudDeSaldo: String? get() = llaveDelSaldo
    fun configurarSaldo(ventaViva: () -> CartState, foto: (() -> com.avoqado.pos.giftcards.domain.FotoDeLaVenta)? = null,
        vaciar: (() -> Unit)? = null, restaurar: ((com.avoqado.pos.giftcards.domain.FotoDeLaVenta) -> CartState?)? = null, desdeCuentas: Boolean = false,
        alRestaurarSnapshot: (CartState) -> Unit = {}) {
        ventaVivaDeSaldo = ventaViva; fotoDeSaldo = foto; vaciarDeSaldo = vaciar; restaurarDeSaldo = restaurar; desdeCuentasDeSaldo = desdeCuentas
        alRestaurarSnapshotDeSaldo = alRestaurarSnapshot
    }
    private var desvincularRecuperacionDeFoto: (() -> Unit)? = null
    private var otrosDuenosPermitenCobrar: () -> Boolean = { true }
    private var restaurandoFotoDeEsteCobro = false
    private var aperturaInvalidadaPorFoto = false

    fun vincularRecuperacionDeFoto(registrar: ((() -> Boolean, () -> Boolean, () -> Unit) -> Pair<() -> Unit, () -> Boolean>)?) {
        desvincularRecuperacionDeFoto?.invoke()
        val dueno = java.lang.ref.WeakReference(this)
        val vinculo = registrar?.invoke({ dueno.get()?.puedeSoltarLaFoto() ?: true },
            { dueno.get()?.puedeEmpezarOtroCobro() ?: true }, { dueno.get()?.invalidarPorFotoRecuperada() })
        desvincularRecuperacionDeFoto = vinculo?.first
        otrosDuenosPermitenCobrar = vinculo?.second ?: { true }
    }

    // Los apartados de efectivo de esta venta cuentan como dinero en curso: si se soltaran, se darían por cobrados (D2 v7).
    private fun dineroEnCurso(): Boolean = isProcessingPayment || cobrandoSaldo || saldoEnVuelo.value ||
        apartadosDeEstaVenta.isNotEmpty() || porDescartar.isNotEmpty() || descartandoApartados || undeterminedRequestId != null

    private fun esDuenoLocalDeFotoT1(): Boolean = viewModelScope.isActive && venueDelSaldo == secureStorage.venueId &&
        createdOrderId == null && cartState?.let(::hasProductItems) == true

    // Tener orden permite recuperar otra foto, pero no iniciar otro cobro sobre los mismos artículos.
    private fun puedeSoltarLaFoto(): Boolean = restaurandoFotoDeEsteCobro || !esDuenoLocalDeFotoT1() || !dineroEnCurso()

    private fun puedeEmpezarOtroCobro(): Boolean = !viewModelScope.isActive || venueDelSaldo != secureStorage.venueId ||
        cartState?.identidadDeVenta != ventaVivaDeSaldo?.invoke()?.identidadDeVenta || !dineroEnCurso()

    private fun invalidarPorFotoRecuperada() {
        if (restaurandoFotoDeEsteCobro || !esDuenoLocalDeFotoT1()) return
        paymentGeneration++
        aperturaEnCurso = null; aperturaInvalidadaPorFoto = true; completionConsumed = true
        cartState = null; createdOrderId = null; createdOrderNumber = null; paymentIdempotencyKey = null
        llaveDelSaldo = null
        _state.value = PaymentFlowState.Error(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR, PaymentErrorSource.SERVER)
    }

    private var regresoDeSaldo: Triple<String, String, String>? = null
    private var regresandoSaldo = false
    private var mesaDelRegreso: Pair<com.avoqado.pos.tables.data.TableSession.Active, Long>? = null
    fun ofreceRegresarSaldo(cobrosConSaldo: Int, pagada: Boolean): Boolean =
        cobrosConSaldo > 0 && !pagada && rolesDeSaldo?.canRedeemGiftCards == true
    fun prepararRegresoDeSaldo(orderId: String, cobrosConSaldo: Int, pagada: Boolean): Boolean {
        if (regresandoSaldo || orderId.isBlank() || !ofreceRegresarSaldo(cobrosConSaldo, pagada)) return false
        val venue = secureStorage.venueId ?: return false
        regresoDeSaldo = Triple(venue, orderId, java.util.UUID.randomUUID().toString())
        mesaDelRegreso = tableSession.current()?.takeIf { it.orderId == orderId }?.let { it to tableSession.contextoActual() }
        _cancelFailure.value = null
        return true
    }
    suspend fun regresarSaldoYCancelar(): Boolean {
        val (venue, orderId, key) = regresoDeSaldo ?: return false
        if (regresandoSaldo || secureStorage.venueId != venue || rolesDeSaldo?.canRedeemGiftCards != true) return false
        mesaDelRegreso?.let { (original, epoch) ->
            val actual = tableSession.current()
            if (tableSession.contextoActual() != epoch || actual?.tableId != original.tableId ||
                actual.orderId != original.orderId) return false
        }
        if (connectivityMonitor?.isConnected?.value != true || connectivityMonitor.isServerReachable.value != true) {
            _cancelFailure.value = TextosDeTarjetas.SIN_RED; return false
        }
        if (cobroConSaldo?.cuentaTrabada(orderId) == true) { _cancelFailure.value = TextosDeTarjetas.COMPROBANDO_COBRO; return false }
        regresandoSaldo = true
        return try {
            when (val r = tarjetasRepository?.regresarSaldoYCancelar(venue, orderId, key)) {
                is Respuesta.Ok -> { regresoDeSaldo = null; secureStorage.venueId == venue }
                is Respuesta.Rechazo -> { _cancelFailure.value = when {
                    r.fallo.http == 404 -> TextosDeTarjetas.CUENTA_NO_ENCONTRADA
                    r.fallo.http == 403 -> TextosDeTarjetas.SIN_PERMISO_COBRAR
                    else -> r.fallo.message ?: TextosDeTarjetas.SIN_RED
                }; false }
                else -> { _cancelFailure.value = TextosDeTarjetas.SIN_RED; false }
            }
        } finally { regresandoSaldo = false }
    }

    private var venueDelSaldo: String? = null
    private var cobrandoSaldo = false
    private var llaveDelSaldo: String? = null
    private var volverAIntentarSaldoEn = 0L
    private val resultadoDeSaldo = MutableStateFlow<DesenlaceDeCobro?>(null)
    val desenlaceDeSaldo = resultadoDeSaldo.asStateFlow()
    fun descartarSaldoInsuficienteAlIdentificar() {
        // El 409 no identifica tarjeta; una identificación fresca no debe heredar ese saldo.
        if (resultadoDeSaldo.value is DesenlaceDeCobro.SaldoInsuficiente) resultadoDeSaldo.value = null
    }
    private val saldoEnVuelo = MutableStateFlow(false)
    val procesandoSaldo = saldoEnVuelo.asStateFlow()
    private val filaDeSaldo = MutableStateFlow(ConductaDeTarjeta(FilaDeLaCaja.T12,false,false,null))
    val conductaDelSaldo = filaDeSaldo.asStateFlow()
    val conexionDelSaldo: StateFlow<Boolean> = connectivityMonitor?.isConnected ?: MutableStateFlow(false)
    val servidorDelSaldo: StateFlow<Boolean> = connectivityMonitor?.isServerReachable ?: MutableStateFlow(false)
    init {
        viewModelScope.launch {
            combine(conexionDelSaldo, servidorDelSaldo,
                tarjetasRepository?.cambiosDeAjustes ?: MutableStateFlow<Map<String, EstadoDeAjustes>>(emptyMap())) { _, _, _ -> Unit }
                .collect { actualizarFilaDeSaldo() }
        }
    }
    private fun ajustesParaSaldo(): EstadoDeAjustes = secureStorage.venueId?.let { secureStorage.leerAjustesDeTarjetas(it) }?.let {
        if (it == "null") EstadoDeAjustes.NoDisponible else runCatching { EstadoDeAjustes.Disponible(GiftCardsApi.parsearAjustes(it)) }.getOrNull()
    } ?: EstadoDeAjustes.Desconocido
    suspend fun conductaDeSaldo(): ConductaDeTarjeta {
        val cart = ventaVivaDeSaldo?.invoke() ?: cartState ?: return ConductaDeTarjeta(FilaDeLaCaja.T12,false,false,null)
        val id = createdOrderId
        val venue = secureStorage.venueId
        val pendiente = if (venue != null && id != null) try { cobrosLocalesDeSaldo?.hayPendiente(venue,id) ?: true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { true } else false
        return estadoDeLaVenta(ajustesParaSaldo(),
            connectivityMonitor?.isConnected?.value == true && connectivityMonitor.isServerReachable.value,
            rolesDeSaldo?.canRedeemGiftCards == true,cuentaDeTarjetas.value,
            cart.items.any { it.areaTicketId != null },cart.items.any { it.type is CartItemType.CreditPack },
            id != null || cart.items.any { it.type is CartItemType.ProductItem },mesaDelCobro?.isProvisional == true,
            cart.creacionIncierta != null,cart.vinoDeCarritoGuardado,cobroConSaldo?.cuentaTrabada(id) == true,
            mesaDelCobro != null,desdeCuentasDeSaldo,id != null,pendiente,
            rolesDeSaldo?.canReadGiftCards == true,rolesDeSaldo?.hasVenuePermission("orders:create") == true)
    }
    suspend fun abrirSaldoDesdeConsultar(tarjeta: TarjetaIdentificada, onAbrir: (TarjetaIdentificada) -> Unit): Boolean {
        val fila = conductaDeSaldo()
        filaDeSaldo.value = fila
        if (!fila.habilitada || !tarjeta.tarjeta.usable || venueDelSaldo != secureStorage.venueId) return false
        onAbrir(tarjeta)
        return true
    }
    suspend fun actualizarFilaDeSaldo(refrescar: Boolean = false) {
        val venue = secureStorage.venueId
        if (refrescar && venue != null && connectivityMonitor?.isConnected?.value == true && connectivityMonitor.isServerReachable.value)
            tarjetasRepository?.refrescarAjustes(venue)
        if (venue == secureStorage.venueId) filaDeSaldo.value = conductaDeSaldo()
    }
    suspend fun cobrarConSaldo(identificada: TarjetaIdentificada, montoCents: Int): DesenlaceDeCobro {
        if (bloqueoDeEntregaDeSaldo()) return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR).also { resultadoDeSaldo.value = it }
        if (!otrosDuenosPermitenCobrar()) return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.COMPROBANDO_COBRO).also { resultadoDeSaldo.value = it }
        if (cobrandoSaldo || llaveDelSaldo != null) return resultadoDeSaldo.value ?: DesenlaceDeCobro.Incierto
        cobrandoSaldo = true; saldoEnVuelo.value = true
        val venue = venueDelSaldo
        val epoch = paymentGeneration
        try {
            val fila = conductaDeSaldo()
            if (!fila.habilitada) return DesenlaceDeCobro.NoSalio(fila.motivo ?: TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO)
                .also { resultadoDeSaldo.value = it }
            if (venue == null || venue != secureStorage.venueId || epoch != paymentGeneration)
                return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO).also {resultadoDeSaldo.value=it}
            val snapshot = cartState ?: return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO)
            if (ventaVivaDeSaldo?.invoke()?.let { it != snapshot } == true)
                return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO).also {resultadoDeSaldo.value=it}
            val monto = montoParaSaldo(identificada)
            if (!identificada.tarjeta.usable || montoCents <= 0 || montoCents > monto.montoCents)
                return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO).also {resultadoDeSaldo.value=it}
            val coordinator = cobroConSaldo ?: return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO)
            val anteriores = SolicitudesDeTarjeta.leer(secureStorage.giftCardRequestsJson).map {it.llave}.toSet()
            val resultado = if (fila.fila == FilaDeLaCaja.T1) {
                val foto = fotoDeSaldo?.invoke() ?: return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO)
                val vaciar = vaciarDeSaldo ?: return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO)
                val payload = Json.parseToJsonElement(OrderRepository.buildCreateOrderPayload(buildOrderRequest(snapshot),selectedStaffId(),attachedCustomerId,
                    orderType=snapshot.orderType,stampRewardId=snapshot.pendingStampReward?.id,
                    stampRewardExpectedDiscount=snapshot.stampRewardCents)).jsonObject
                var identidadEntregada: String? = null
                coordinator.cobrarNuevaCuenta(venue,payload,foto,identificada.prueba,montoCents,monto.propinaCents,vaciar,
                    destinoVigente = { viewModelScope.isActive && identidadEntregada != null && ventaVivaDeSaldo?.invoke()?.identidadDeVenta == identidadEntregada }) { photo ->
                    restaurarEnEsteCobro(photo, venue, epoch)?.also { identidadEntregada = it.identidadDeVenta } != null
                }
            } else {
                val id = createdOrderId ?: return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.CUENTA_NO_ENCONTRADA)
                coordinator.cobrar(venue,id,identificada.prueba,montoCents,monto.propinaCents,
                    vaciarCarrito=if(fila.fila==FilaDeLaCaja.T2) vaciarDeSaldo else null,deMostrador=fila.fila==FilaDeLaCaja.T2)
            }
            if (venue == secureStorage.venueId && epoch == paymentGeneration) {
                llaveDelSaldo = SolicitudesDeTarjeta.leer(secureStorage.giftCardRequestsJson).firstOrNull {
                    it.venueId==venue && it.tipo=="GIFT_CARD_REDEEM" && it.llave !in anteriores && it.mandado && !it.porRecuperar && !it.porTerminar
                }?.llave
                resultadoDeSaldo.value=resultado
                if (resultado is DesenlaceDeCobro.Cobrado) mostrarCobroConSaldo(resultado.cobro)
                if (resultado is DesenlaceDeCobro.Reintentable) volverAIntentarSaldoEn=System.currentTimeMillis()+(resultado.retryAfterSeg ?: 0)*1000L
            }
            return resultado
        } finally { cobrandoSaldo=false;saldoEnVuelo.value=false }
    }

    private fun restaurarEnEsteCobro(photo: com.avoqado.pos.giftcards.domain.FotoDeLaVenta, venue: String, epoch: Int): CartState? {
        if (!viewModelScope.isActive || venue != secureStorage.venueId || epoch != paymentGeneration) return null
        restaurandoFotoDeEsteCobro = true
        val nueva = try { restaurarDeSaldo?.invoke(photo) } finally { restaurandoFotoDeEsteCobro = false } ?: return null
        if (venue != secureStorage.venueId || epoch != paymentGeneration) return null
        alRestaurarSnapshotDeSaldo(nueva)
        cartState=nueva;createdOrderId=null;createdOrderNumber=null;paymentIdempotencyKey=null
        _state.value=PaymentFlowState.SelectingPaymentMethod(nueva.totalCents)
        return nueva
    }

    fun puedeReintentarSaldo(ahora: Long = System.currentTimeMillis()): Boolean =
        !aperturaInvalidadaPorFoto && otrosDuenosPermitenCobrar() && !cobrandoSaldo && llaveDelSaldo != null && ahora >= volverAIntentarSaldoEn
    suspend fun reintentarSaldo(): DesenlaceDeCobro? {
        if (!puedeReintentarSaldo()) return resultadoDeSaldo.value
        val llave = llaveDelSaldo ?: return resultadoDeSaldo.value
        val venue = venueDelSaldo ?: return resultadoDeSaldo.value
        val epoch=paymentGeneration
        if (venue != secureStorage.venueId || connectivityMonitor?.isConnected?.value != true || connectivityMonitor.isServerReachable.value != true)
            return resultadoDeSaldo.value
        cobrandoSaldo=true;saldoEnVuelo.value=true
        try {
            var identidadEntregada: String? = null
            val result=cobroConSaldo?.resolver(llave,
                destinoVigente = { viewModelScope.isActive && identidadEntregada != null && ventaVivaDeSaldo?.invoke()?.identidadDeVenta == identidadEntregada }) { photo ->
                restaurarEnEsteCobro(photo, venue, epoch)?.also { identidadEntregada = it.identidadDeVenta } != null
            } ?: DesenlaceDeCobro.Incierto
            if(venue==secureStorage.venueId && epoch==paymentGeneration) {
                resultadoDeSaldo.value=result
                if (result is DesenlaceDeCobro.Cobrado) mostrarCobroConSaldo(result.cobro)
                if(result is DesenlaceDeCobro.Cobrado || SolicitudesDeTarjeta.leer(secureStorage.giftCardRequestsJson).none { it.llave==llave }) llaveDelSaldo=null
                if(result is DesenlaceDeCobro.Reintentable) volverAIntentarSaldoEn=System.currentTimeMillis()+(result.retryAfterSeg ?: 0)*1000L
            }
            return result
        } finally {cobrandoSaldo=false;saldoEnVuelo.value=false}
    }
    fun montoParaSaldo(identificada: TarjetaIdentificada): MontoOfrecido =
        montoOfrecido(currentBaseAmount(), ((resultadoDeSaldo.value as? DesenlaceDeCobro.SaldoInsuficiente)?.saldoDisponible ?: identificada.tarjeta.balance).toString().moneyToCents(), currentTipCents)
    val cuentaParaContinuarConSaldo: String? get() = createdOrderId?.takeIf {
        mesaDelCobro == null && (resultadoDeSaldo.value is DesenlaceDeCobro.Rechazado || resultadoDeSaldo.value is DesenlaceDeCobro.SaldoInsuficiente)
    }
    val venueParaSaldo: String? get() = venueDelSaldo
    fun ajustesDelSaldo(): AjustesDeTarjetas? = (ajustesParaSaldo() as? EstadoDeAjustes.Disponible)?.ajustes
    private fun mostrarCobroConSaldo(r: CobroConSaldo) {
        ultimoCobroConSaldo = r
        createdOrderId = r.orderId; createdOrderNumber = r.orderNumber; lastPaymentId = r.paymentId
        lastReceiptAccessKey = r.receiptAccessKey; lastReceiptUrl = r.receiptUrl
        currentTipCents = r.tipAmount.toString().moneyToCents()
        if (r.orderRemaining > 0) {
            _splitType.value = "CUSTOMAMOUNT"; splitCustomAmountCents = r.amount.toString().moneyToCents()
        }
        _state.value = PaymentFlowState.Success(totalAmount = (r.amount + r.tipAmount).toString().moneyToCents(),
            method = PaymentMethod.CARD, paymentId = r.paymentId, receiptAccessKey = r.receiptAccessKey, receiptUrl = r.receiptUrl)
    }
    private fun bloqueoDeEntregaDeSaldo(): Boolean = aperturaInvalidadaPorFoto || (!desdeCuentasDeSaldo && mesaDelCobro == null &&
        SolicitudesDeTarjeta.reservas(secureStorage.giftCardRequestsJson).any { it.venueId == secureStorage.venueId })
    private fun bloqueoDelSaldoActual(): Boolean {
        if (bloqueoDeEntregaDeSaldo()) {
            _state.value = PaymentFlowState.Error(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR, PaymentErrorSource.SERVER)
            return true
        }
        if (!otrosDuenosPermitenCobrar()) {
            _state.value = PaymentFlowState.Error(TextosDeTarjetas.COMPROBANDO_COBRO, PaymentErrorSource.SERVER)
            return true
        }
        val rows=SolicitudesDeTarjeta.leer(secureStorage.giftCardRequestsJson)
        if(!cobrandoSaldo && !saldoEnVuelo.value && rows.none {it.venueId==secureStorage.venueId && it.tipo=="GIFT_CARD_REDEEM" && it.mandado && !it.porTerminar && !it.porRecuperar &&
            (it.llave==llaveDelSaldo || createdOrderId!=null && it.orderId==createdOrderId)}) {
            val snapshot = cartState
            if (!desdeCuentasDeSaldo && mesaDelCobro == null && createdOrderId == null && snapshot != null &&
                ventaVivaDeSaldo?.invoke()?.identidadDeVenta?.let { it != snapshot.identidadDeVenta } == true) {
                _state.value = PaymentFlowState.Error(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO, PaymentErrorSource.SERVER)
                return true
            }
            return false
        }
        _state.value=PaymentFlowState.Error(TextosDeTarjetas.COMPROBANDO_COBRO,PaymentErrorSource.SERVER)
        return true
    }

    private var onCreacionIncierta: (String, String) -> Unit = { _, _ -> }
    private var onCreacionResuelta: (String) -> Unit = {}

    fun startPaymentFlow(
        cart: CartState,
        customerId: String? = null,
        customerName: String? = null,
        resumeOrderId: String? = null,
        apertura: String? = null,
        cuentaDeTarjetas: Boolean = false,
        onCreacionIncierta: (String, String) -> Unit = { _, _ -> },
        onCreacionResuelta: (String) -> Unit = {},
        origen: com.avoqado.pos.payment.domain.OrigenDelCobro = com.avoqado.pos.payment.domain.OrigenDelCobro.NORMAL,
    ) {
        // 🔴 La MISMA apertura (un toque de «Cobrar») no reinicia el cobro. La pantalla vuelve a
        // componerse al cambiar de pestaña o girar la tablet y relanza este arranque con lo que
        // haya en el carrito: tras cobrar, vacío. En la D3 (29-sep) eso volvía a pedir la
        // calificación de una venta ya pagada y terminaba en un cobro de $0.00 con «Efectivo $0».
        if (apertura != null && apertura == aperturaEnCurso) {
            Log.d("💰", "Mismo cobro (apertura $apertura): se sigue donde iba")
            return
        }
        aperturaEnCurso = apertura
        aperturaInvalidadaPorFoto = false
        this.onCreacionIncierta = onCreacionIncierta
        this.onCreacionResuelta = onCreacionResuelta
        this.cuentaDeTarjetas.value = cuentaDeTarjetas
        origenDelCobro = origen
        // Después del corte de «misma apertura» (volver de otra pestaña conserva la copia) y antes de `enterInitialState`.
        flowSettings = tpvSettingsRepository.getCurrentSettings()
        paymentGeneration++
        cobroDeValesId = java.util.UUID.randomUUID().toString()
        ventaEnCurso++
        cancelacionFallida = false
        // Una venta nueva arranca sin cobro propio: el pendiente de SU orden se lee vivo de la lista, al elegir tarjeta.
        undeterminedRequestId = null
        cartState = cart
        venueDelSaldo = secureStorage.venueId; llaveDelSaldo = null; resultadoDeSaldo.value = null; volverAIntentarSaldoEn = 0
        // 🔴 DINERO. El cobro de un pedido web (bandeja «Pedidos en línea») NUNCA adopta una mesa: una sesión que quedó en
        // PAYING reemplazaba abajo a `resumeOrderId` y el dinero liquidaba la cuenta de la mesa (Codex plan r1 #1). Sin mesa
        // tampoco hay ticket ni saldo esperado de mesa. NORMAL queda exactamente como antes.
        mesaDelCobro = if (origen.esPedidoEnLinea) {
            null
        } else {
            tableSession.current()?.takeIf { it.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING }
        }
        contextoDeLaMesaDelCobro = tableSession.contextoActual()
        nombreDelNegocioDelCobro = secureStorage.venueName ?: "Avoqado"
        nombreDeLaSesionDelCobro = listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName).joinToString(" ").trim()
            .ifEmpty { secureStorage.userEmail.orEmpty() }.takeIf { it.isNotBlank() }
        completionConsumed = false
        // 🔴 Un `Insistiendo` de la venta anterior SÍ se borra: describe algo que ya terminó,
        // y arrastrarlo culparía a una impresora que en esta venta puede estar perfecta.
        //
        // Un `NoSalio` NO. Es una comanda que de verdad no salió y que nadie ha resuelto: el
        // cajero tiene que poder verla y reimprimirla aunque ya haya empezado la venta
        // siguiente. Borrarla aquí la hacía inalcanzable para siempre — y como el reintento
        // tarda hasta ~1 minuto, el caso normal es que el aviso llegue DESPUÉS de que el cajero
        // tocó «Listo» (P1 #6 de la auditoría de Codex, 2026-09-07). Se va con «Ya la canté»,
        // que es una persona decidiendo, no un efecto secundario de cobrar otra cosa.
        // Un `Insistiendo` de la venta anterior describe algo que ya terminó: se limpia. El
        // fallo final NO vive aquí, así que no hay nada que preservar a mano.
        _avisoEnCurso.value = null
        // D4 (27-oct): una pieza de un combo arrastra a todo su combo. Una sola vez, aquí: el importe, el saldo, la pantalla y
        // el recibo leen la MISMA selección, y el carrito (que quita el combo entero) nunca queda con dinero sin renglón.
        splitSelectedItemIds = conCombosCompletos(cart.items, splitSelectedItemIds)
        // D10 (27-oct): si la parte saldría en $0 o dejaría ≤ 1 ¢, se lleva TODO lo que queda (selección, importe y recibo).
        if (_splitType.value == "BYPRODUCT" && splitSelectedItemIds.isNotEmpty() && parteDeLaSeleccion(cart).todoLoQueFalta) {
            splitSelectedItemIds = cart.items.map { it.id }.toSet()
        }
        splitBaseAmountOverride = resolveSplitBaseAmount(cart)
        // El total autoritativo es de la venta ANTERIOR: arrastrarlo cobraría
        // esta venta al precio de la pasada.
        serverTotalOverrideCents = null
        serverOrderTotalCents = null
        serverRemainingBalanceCents = null
        ultimoCobroConSaldo = null
        _avisoDelPremio.value = null
        premioConfirmadoCents = null
        // 🔴 D2 v7: otra venta (o la pantalla recreada) no decide nada de la anterior: sus apartados se sueltan y se dan por
        // cobrados, nunca se borran. `porDescartar` NO: son borrados ya decididos que esperan su escritura.
        soltarLosApartadosDeEstaVenta()
        // Las correcciones de precio eran de la venta anterior.
        _avisoDePrecio.value = null
        ultimoAvisoDePrecio = null
        cambiosDeEstaVenta = emptyList()
        totalAntesDelRechazoDePrecio = null
        val amount = currentBaseAmount()

        // Reset transient state from any previous session.
        isProcessingPayment = false
        paymentIdempotencyKey = null
        selectedMethod = null
        currentRating = null
        currentTipCents = 0
        createdOrderId = null
        createdOrderNumber = null
        externalIdDeLaVenta = null
        // Una venta nueva no hereda la cancelación de la anterior: ni su observación, ni su aviso,
        // ni su salida. El aviso de «no se pudo guardar» describía la venta pasada y reaparecía
        // encima de ésta.
        dejarDeObservarLaCancelacion()
        ordenCreadaPorEsteFlujo = false
        ultimoIntento = null
        cobroAplicadoTrasCancelar = null
        _debeSalir.value = false
        _cancelFailure.value = null
        // El aviso y la revisión de «otra venta» describían la venta pasada. Se vuelve a pintar desde el disco al elegir
        // tarjeta: un «SÍ pasó» sin «Entendido» sigue ahí (H2), sólo la copia en pantalla se va.
        publicarAviso(emptyList())
        revisionDeOtrasVentas++
        cobroEnRevision = null

        // 🔴 DINERO — MOSTRADOR: partes 2..N de un split.
        //
        // Al quedar saldo, el checkout sustituye el carrito por una línea
        // "Saldo pendiente" (o le quita los artículos ya cobrados). Sin esto, la
        // parte 2 arrancaba sin orden: efectivo caía al cobro rápido y tarjeta
        // creaba un pago suelto —o, con productos reales, una SEGUNDA orden con
        // las líneas duplicadas—. La venta quedaba partida en dos, la orden
        // original PARTIAL para siempre y **el stock nunca se descontaba** (sólo
        // se descuenta al llegar a PAID).
        //
        // Sembrarlo aquí basta: las dos ramas de cobro ya prefieren la orden que
        // existe (`recordCashPaymentForOrder` en efectivo, `orderId` a la
        // terminal en tarjeta), y el importe sale de `currentBaseAmount()`, que
        // ya refleja el resto.
        //
        // Quién garantiza que NO se filtre a la venta siguiente: el carrito. El
        // vínculo muere con él (`CartViewModel.clearCart`), y se revalida contra
        // el carrito real antes de cada cobro.
        resumeOrderId?.takeIf { it.isNotBlank() }?.let { orden ->
            createdOrderId = orden
            createdOrderNumber = ultimoFolio?.takeIf { it.first == orden }?.second
        }

        // TABLE_SERVICE (PRO) seam — a PAYING table session means this payment
        // settles the table's EXISTING order: preset its id so (a) the card path
        // sends orderId to the terminal (the TPV records against the order and
        // marks it PAID, its native table flow) and (b) the cash path records
        // against it via recordCashPaymentForOrder — and NO new order is ever
        // created for this charge. With no session active this is a no-op and
        // every retail/quick flow is byte-identical.
        mesaDelCobro?.let { createdOrderId = it.orderId }
        continuaVentaPartida = mesaDelCobro == null && !resumeOrderId.isNullOrBlank()
        saldoSinConfirmar = false
        // Regla 9 (vales, r7): el apartado que cobra ESTE cobro; terminar, soltar y salir actúan sobre él, nunca sobre el vigente.
        sesionDelCobro = areaTicketRepository.session.current()?.id?.takeIf { esCobroDeVales() }
        selectedTerminalId = null
        _canPrintOnTerminal.value = false
        lastPaymentId = null
        lastCardBrand = null
        lastCardLastFour = null
        lastReceiptAccessKey = null
        lastReceiptUrl = null
        lastAreaDeliveryCode = null
        lastCashTenderedCents = null
        // 🔴 Sin esto, un cobro con TARJETA hereda el método manual/catálogo de la venta
        // anterior (el ViewModel sobrevive a más de una venta — split, o dos ventas seguidas
        // del mismo turno): `manualMethodLabel` seguía devolviendo "Transferencia"/"Uber Eats"
        // y `buildReceiptSnapshot` imprimía ese texto en vez de "Tarjeta", perdiendo además la
        // marca y los últimos 4 que sí trajo la terminal. Antes NO se limpiaban aquí pese a que
        // el comentario de `manualMethod`/`selectedTender` afirmaba lo contrario.
        manualMethod = null
        selectedTender = null
        _onlineTerminals.value = emptyList()

        // Clear receipt sending state from previous payment
        _whatsAppResult.value = null
        _whatsAppSending.value = false
        _emailResult.value = null
        _emailSending.value = false
        _printResult.value = null
        _printSending.value = false
        _customerAttachResult.value = null
        _customerAttachSending.value = false
        _renglonDelSello.value = null
        // La consulta del sello de la venta ANTERIOR no puede pintar sobre ésta.
        consultaDelSello?.cancel()
        consultaDelSello = null
        ventaDelSello = null
        attachedCustomerId = customerId?.takeIf { it.isNotBlank() }
        _attachedCustomerName.value = customerName?.takeIf { it.isNotBlank() }
        lastReceipt = null

        Log.d("💰", "Starting payment flow - amount: $amount")

        // Probe terminal availability up-front so the CARD option can disable
        // itself before we collect tip/rating.
        probeTerminalAvailability()

        // 🔴 Antes de dejar cobrar nada: ¿quedó un cobro con tarjeta sin resolver? La llave
        // vive en disco, así que sobrevive al cambio de pestaña y a la muerte del proceso —
        // que es justo cuando la pantalla de advertencia se evaporaba y el siguiente "Cobrar"
        // arrancaba limpio. Se resuelve ESE antes de ofrecer uno nuevo.
        // 🔴 FOUNDER, 21-sep, viéndolo en la D3: «lo que no quiero es que bloquee las siguientes
        // ventas, o que el cajero se queje porque no puede vender». Esto CORTABA el flujo entero —
        // `return` antes de `enterInitialState`—, así que un cobro con tarjeta sin resolver dejaba
        // al negocio sin poder cobrar NI EN EFECTIVO. Medido en el aparato: al tocar «Cobrar» se
        // saltaba la pantalla de métodos de pago y no había forma de llegar al efectivo.
        //
        // El alcance era demasiado ancho. Un cargo de TARJETA incierto no se puede duplicar
        // cobrando en efectivo ni registrando «ya pagó de otra forma»: son instrumentos distintos.
        // Lo único que de verdad choca es volver a mandar ESTA venta a una terminal, y eso se
        // sigue bloqueando (abajo, al elegir TARJETA).
        //
        // 🔑 Aquí NO se guarda copia de la llave pendiente: la rama de TARJETA la lee VIVA, para
        // que resolverla a media venta («Volver a consultar») desbloquee de inmediato en vez de
        // obligar al cajero a salir y empezar otra. Medido en la D3 el 21-sep.
        enterInitialState(amount)
    }

    /** Primer paso del cobro según la configuración de la TPV (calificación → propina → método). */
    private fun enterInitialState(amount: Int) {
        if (cuentaDeTarjetas.value || !origenDelCobro.pideCalificacionYPropina) {
            // La cuenta de tarjetas de regalo no pide calificación ni propina. Rappi 2b: la propina ya viene en el pedido; pedir otra sumaría una segunda. Directo al método (sólo efectivo).
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        } else if (settings.showReviewScreen) {
            _state.value = PaymentFlowState.CollectingRating(amount)
        } else if (settings.showTipScreen) {
            _state.value = PaymentFlowState.CollectingTip(amount, null)
        } else {
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        }
    }

    fun submitRating(rating: Int?) {
        // 🔴 Con doble pantalla hay DOS dedos sobre el mismo flujo. Si el
        // cliente toca su pantalla un instante después de que el cajero avanzó,
        // esto reescribía el paso ya cerrado. Solo se acepta si seguimos en él.
        if (_state.value !is PaymentFlowState.CollectingRating) return
        currentRating = rating
        val amount = currentBaseAmount()

        if (settings.showTipScreen && origenDelCobro.pideCalificacionYPropina) {
            _state.value = PaymentFlowState.CollectingTip(amount, rating)
        } else {
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        }
    }

    fun submitTip(tipCents: Int) {
        // 🔴 MONEY: un toque tardío del cliente NO puede cambiar la propina con
        // el cajero ya en método de pago / efectivo / terminal — cambiaría el
        // total por debajo de una pantalla que ya mostraba otro.
        if (_state.value !is PaymentFlowState.CollectingTip) return
        currentTipCents = tipCents
        val amount = currentBaseAmount()

        _state.value = PaymentFlowState.SelectingPaymentMethod(amount + tipCents)
    }

    fun currentTipPercentageBaseCents(): Int {
        return computeTipPercentageBaseAmount(currentBaseAmount())
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        if (method == PaymentMethod.CARD && !origenDelCobro.permiteTarjeta) return
        if (bloquearCuentaSinRed()) return
        selectedMethod = method
        val baseAmount = currentBaseAmount()
        val total = baseAmount + currentTipCents

        when (method) {
            PaymentMethod.CASH -> {
                _state.value = PaymentFlowState.CollectingCashAmount(total)
            }
            PaymentMethod.CARD -> {
                // 🔴 Founder, 25-sep: sólo la MISMA venta espera. La duda de otra venta se avisa y NO frena la tarjeta; la
                // protección contra cobrarla dos veces la sigue dando el servidor, que cerca SU orden en toda terminal.
                // SE LEE LA LISTA VIVA, nunca una copia (Sunmi D3, 21-sep: la copia sobrevivía a su propia causa).
                // Una llave en blanco no es un pendiente: no hay a quién preguntarle por ella.
                val deEstaVenta = terminalPaymentService.pendienteDeLaVenta(ordenEnCurso())
                if (!deEstaVenta.isNullOrBlank()) {
                    esperarCobroDeEstaOrden(deEstaVenta, total, CardChargeDecision.UNDETERMINED_MESSAGE)
                    return
                }
                ofrecerTerminales(total)
            }
        }
    }

    /** Cash preset tapped directly from payment method screen (iOS-style direct confirm) */
    /**
     * Cobro registrado a mano: el dinero NO pasó por Avoqado (terminal ajena,
     * transferencia). null = efectivo. Se limpia en `startPaymentFlow()` para
     * que la SIGUIENTE venta no herede el método de la anterior — un tiempo
     * en que NO se limpiaba ahí hizo que un cobro con tarjeta imprimiera el
     * método manual de la venta previa (y perdiera marca/últimos 4).
     */
    private var manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null

    /**
     * Tipo de pago del catálogo del negocio elegido para ESTE cobro ("Uber Eats").
     * Se limpia igual que `manualMethod`, en `startPaymentFlow()`, por la misma razón.
     */
    private var selectedTender: com.avoqado.pos.payment.domain.TenderTypeOption? = null

    /**
     * El mesero declara que ya le pagaron por otro medio. No hay teclado ni
     * cambio: el monto es exactamente el total, así que se cobra de una.
     */
    /** Etiqueta del cobro manual para la pantalla de éxito y el recibo. */
    val manualMethodLabel: String? get() = if (ultimoCobroConSaldo != null) "Tarjeta de regalo" else selectedTender?.name ?: manualMethod?.label

    /**
     * Catálogo del negocio para la hoja de "¿cómo pagó el cliente?".
     *
     * 🔴 Es un StateFlow, NO un getter. Con un getter, Compose no tiene cómo enterarse
     * de que el refresh terminó: al abrir el cobro la caché estaba vacía, la hoja se
     * componía sin tipos y la respuesta llegaba a un valor que nadie volvía a leer —
     * o sea que **la primera venta después de abrir la app nunca veía los tipos del
     * negocio**. Medido en el D3 (2026-08-17). Si lo vuelves a un getter, vuelve el bug.
     */
    val tenderTypes: StateFlow<List<com.avoqado.pos.payment.domain.TenderTypeOption>> =
        tenderTypeRepository.tenderTypes

    fun confirmManualChoice(choice: com.avoqado.pos.payment.domain.ManualPaymentChoice) {
        // Un pedido de plataforma se cobra SÓLO en efectivo: el SERVIDOR rechaza otro método (la pantalla ni lo ofrece).
        if (origenDelCobro.esDePlataforma) return
        if (isProcessingPayment || bloquearCuentaSinRed()) return
        when (choice) {
            is com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed -> {
                manualMethod = choice.method
                selectedTender = null
            }
            is com.avoqado.pos.payment.domain.ManualPaymentChoice.Tender -> {
                manualMethod = null
                selectedTender = choice.option
                // El server RECHAZA tip>0 en un tipo sin propina; no le mandamos
                // una venta que sabemos que va a rebotar.
                if (!choice.option.captureTip) currentTipCents = 0
            }
        }
        selectedMethod = PaymentMethod.CASH
        val total = currentBaseAmount() + currentTipCents
        lastCashTenderedCents = total
        processCashPayment(total)
    }

    fun confirmCashPreset(tenderedCents: Int) {
        manualMethod = null
        selectedTender = null
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    /** Custom cash amount confirmed from bottom sheet keypad */
    fun confirmCashCustom(tenderedCents: Int) {
        manualMethod = null
        selectedTender = null
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    /**
     * Sonda de disponibilidad: pregunta si hay terminales conectadas para poder
     * deshabilitar "Cobrar con terminal" ANTES de que el cajero lo intente.
     *
     * @param background por defecto SÍ, porque el arranque del cobro la dispara
     * solo — nadie la pidió, y su fracaso no impide nada (falla en abierto: la
     * opción se queda habilitada y el error real sale al enviar). Sólo el enlace
     * "Reintentar", que es un toque explícito sobre esta misma pantalla, la corre
     * en primer plano: ahí el "no" tiene que verse.
     */
    fun probeTerminalAvailability(background: Boolean = true) {
        _terminalAvailability.value = TerminalAvailability.CHECKING
        viewModelScope.launch {
            when (val result = terminalPaymentService.fetchOnlineTerminals(background = background)) {
                is TerminalListResult.Success -> {
                    _onlineTerminals.value = result.terminals
                    _terminalAvailability.value =
                        if (result.terminals.isEmpty()) TerminalAvailability.NONE
                        else TerminalAvailability.AVAILABLE
                }
                is TerminalListResult.Error -> {
                    // Fail OPEN: can't verify (network) shouldn't block a working
                    // terminal — keep the option enabled and let the send-step
                    // surface any real error.
                    _terminalAvailability.value = TerminalAvailability.ERROR
                }
            }
        }
    }

    /**
     * Trae la lista para ELEGIR terminal. Nace del toque "Cobrar con terminal" y
     * su fracaso impide exactamente lo que el cajero pidió, así que NO va marcada
     * como de fondo: aquí el 403 debe verse.
     */
    private fun fetchTerminals() {
        viewModelScope.launch {
            when (val result = terminalPaymentService.fetchOnlineTerminals()) {
                is TerminalListResult.Success -> {
                    _onlineTerminals.value = result.terminals
                    _terminalAvailability.value =
                        if (result.terminals.isEmpty()) TerminalAvailability.NONE
                        else TerminalAvailability.AVAILABLE
                    // Paridad con iOS (26-sep): sin terminales tampoco es la pantalla de error, que tapaba las líneas ámbar de
                    // otras ventas: se dice en lugar de la lista, con «Reintentar».
                    if (result.terminals.isEmpty()) {
                        (_state.value as? PaymentFlowState.SelectingTerminal)?.let {
                            _state.value = it.copy(sinLista = "No hay terminales conectadas")
                        }
                    }
                }
                // B7b-5 (QA 26-sep) y N6 (Codex r2): que la lista no cargue NO es «Error en el pago» — esa pantalla tapaba las
                // líneas ámbar de otras ventas. La pantalla se queda, lo dice en lugar de la lista (sin red: que la tarjeta
                // necesita internet) y ofrece «Reintentar».
                is TerminalListResult.Error -> (_state.value as? PaymentFlowState.SelectingTerminal)?.let {
                    _state.value = it.copy(sinLista = if (result.sinRed) TARJETA_SIN_RED else result.message)
                }
            }
        }
    }

    // MARK: - Cobros sin confirmar de OTRAS ventas (founder, 25-sep): se avisan, no frenan

    /** La orden de la venta en curso: exactamente la que viaja a `sendPaymentToTerminal(orderId = …)`. Null en un cobro rápido. */
    private fun ordenEnCurso(): String? = createdOrderId

    /**
     * 🔴 El pendiente de ESTA orden frena la tarjeta (la misma cerca que el servidor). Sólo sigue siendo de ESTE flujo el
     * cobro que este flujo mandó (`undeterminedRequestId`, fijado por su propio POST): se consulta como suyo, como antes de
     * este plan. Cualquier otro que se encuentre en la lista se abre SIEMPRE como REVISIÓN («…de esta venta»): confirmarlo
     * jamás da por pagada esta venta. Controlador (26-sep): los importes no distinguen partes ni intentos (2 × $250; la
     * parte 2 de mostrador llega como pago completo), así que no se comparan. Si sí pasó, se dice («El cobro anterior sí
     * se había realizado») y el servidor rechaza un segundo cobro sobre una orden ya pagada. La pantalla fija el cobro
     * del que habla, leído VIVO al elegir tarjeta.
     */
    private fun esperarCobroDeEstaOrden(requestId: String, total: Int, message: String) {
        // H2: ya consta que ESE cobro de esta orden SÍ pasó (sigue en disco su ventana) y no lo mandó este flujo.
        // No se manda otro ni se pregunta: se dice con el texto de siempre, y su «Entendido» en «Cobro anterior resuelto»
        // lo quita. El PROPIO sigue abajo: «Volver a consultar» lo aplica como su pago (pantalla de éxito).
        if (requestId != undeterminedRequestId && terminalPaymentService.yaCobrado(requestId)) {
            resolverCobroAnterior("El cobro anterior sí se había realizado", cobroQueSiPaso = requestId)
            return
        }
        if (requestId == undeterminedRequestId) {
            _state.value = PaymentFlowState.Undetermined(totalAmount = total, message = message)
            return
        }
        cobroEnRevision = CobroEnRevision(requestId, SAME_SALE_CHARGE_PREFIX)
        _state.value = PaymentFlowState.Undetermined(
            totalAmount = total, message = conEncabezado(SAME_SALE_CHARGE_PREFIX, message), fromPreviousSale = true,
        )
    }

    /**
     * A elegir terminal. TODA entrada a esta pantalla pasa por aquí, para que ninguna llegue sin el aviso de las otras
     * ventas — también la que viene de resolver el cobro de ésta.
     */
    private fun ofrecerTerminales(total: Int) {
        _state.value = PaymentFlowState.SelectingTerminal(total)
        fetchTerminals()
        revisarPendientesDeOtrasVentas()
    }

    /**
     * 1. YA, sin red: el aviso sale de la lista guardada — un cobro que SÍ pasó primero (H2: sigue en disco, marcado,
     *    su ventana de 10 min, en TODA venta y tras reiniciar), y la duda más reciente de menos de [VENTANA_AVISO_MS].
     * 2. Consulta un lote de hasta 3 que elige el servicio (H6: los consultados hace más tiempo primero, para que todos
     *    tengan turno). Lo que ya consta como cobrado no se vuelve a consultar.
     * 3. El definitivo, releyendo el disco: la consulta MARCÓ lo que sí pasó y soltó lo que consta como NO cobrado; un fallo
     *    de red deja la entrada como está, y se sigue nombrando. Una revisión obsoleta no publica, y no pierde nada: lo
     *    probado ya quedó en disco para la siguiente.
     */
    private fun revisarPendientesDeOtrasVentas() {
        val turno = ++revisionDeOtrasVentas
        val otras = terminalPaymentService.pendientesDeOtrasVentas(ordenEnCurso())
        publicarAviso(otras)
        val aConsultar = terminalPaymentService.loteDeRevision(otras.filterNot { it.cobrado })
        if (aConsultar.isEmpty()) return
        viewModelScope.launch {
            val consultas = aConsultar.map { it to async { desenlaceSinTragarseLaCancelacion(it.requestId) } }
            val desenlaces = consultas.associate { (ctx, consulta) -> ctx.requestId to consulta.await() }
            if (turno != revisionDeOtrasVentas) return@launch
            publicarAviso(
                terminalPaymentService.pendientesDeOtrasVentas(ordenEnCurso())
                    // N3 (Codex r2): lo que ya consta como cobrado no lo esconde el negativo de esta consulta.
                    .filter { it.cobrado || desenlaces[it.requestId] !is TerminalPaymentResult.Error }
                    .map { if (desenlaces[it.requestId] is TerminalPaymentResult.Success) it.copy(cobrado = true) else it },
            )
        }
    }

    /**
     * Pinta el aviso de [pendientes] (el más nuevo primero). H7: lo que está en pantalla se calla a los 10 min aunque la
     * pantalla siga abierta — un recálculo LOCAL al vencer, sin red. Founder (26-sep): un «SÍ pasó» vence igual que una duda
     * (el servicio lo purga del disco al releerlo); una duda sólo se calla, sigue en disco frenando su venta.
     */
    private fun publicarAviso(pendientes: List<ContextoDeCobro>) {
        vencimientoDelAviso?.cancel()
        val (primero, segundo) = avisosDe(pendientes)
        _avisoDeOtroCobro.value = primero
        _segundoAviso.value = segundo
        val vence = listOfNotNull(primero, segundo)
            .mapNotNull { aviso -> pendientes.firstOrNull { it.requestId == aviso.requestId }?.desdeMillis }
            .minOrNull()?.plus(VENTANA_AVISO_MS) ?: return
        vencimientoDelAviso = viewModelScope.launch {
            delay((vence - reloj()).coerceAtLeast(0))
            if (reloj() >= vence) publicarAviso(terminalPaymentService.pendientesDeOtrasVentas(ordenEnCurso()))
        }
    }

    /**
     * Las líneas vigentes (menos de [VENTANA_AVISO_MS] desde el cobro, también un «SÍ pasó»): un cobro que SÍ pasó va primero
     * (pudo ser esta misma venta rehecha) y, I-2, la duda más nueva va debajo; sin «SÍ pasó», la duda va sola arriba.
     */
    private fun avisosDe(pendientes: List<ContextoDeCobro>): Pair<AvisoDeOtroCobro?, AvisoDeOtroCobro?> {
        val ahora = reloj()
        val vigentes = pendientes.filter { ahora - (it.desdeMillis ?: ahora) < VENTANA_AVISO_MS }
        val siPaso = vigentes.firstOrNull { it.cobrado }?.let {
            AvisoDeOtroCobro(it.requestId, textoCobroQueSiPaso(totalDe(it)), yaCobrado = true)
        }
        val duda = vigentes.firstOrNull { !it.cobrado }?.let {
            AvisoDeOtroCobro(it.requestId, textoCobroSinConfirmar(totalDe(it), it.desdeMillis, ahora), yaCobrado = false)
        }
        return if (siPaso != null) siPaso to duda else duda to null
    }

    /** Importe + propina del cobro pendiente; `null` si su contexto no lo guardó (nunca un cero inventado). */
    private fun totalDe(ctx: ContextoDeCobro): Long? =
        ctx.amountCents?.let { (it + (ctx.tipCents ?: 0)).toLong() }

    /** Una consulta que falla no tumba a las demás ni se lee como desenlace; la cancelación del scope SÍ se propaga. */
    private suspend fun desenlaceSinTragarseLaCancelacion(requestId: String): TerminalPaymentResult? =
        try {
            terminalPaymentService.resolveOutcome(requestId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PaymentFlow", "⚠️ No se pudo revisar el cobro $requestId de otra venta: ${e.message}")
            null
        }

    /** «Revisar» en el aviso: la pantalla de siempre sobre ESE cobro de otra venta. Confirmarlo nunca paga ésta. */
    fun revisarCobroDeOtraVenta(requestId: String) =
        reconcileThenOffer(requestId, currentBaseAmount() + currentTipCents, encabezado = PREVIOUS_CHARGE_PREFIX)

    /**
     * El cobro sin confirmar del que habla la pantalla: el de otra venta que el cajero abrió con «Revisar», o el de ESTA
     * venta (el que vio esta pantalla o, si el proceso murió, el que la lista durable guarda para su orden).
     */
    private fun cobroSinConfirmarEnPantalla(): String? =
        if ((_state.value as? PaymentFlowState.Undetermined)?.fromPreviousSale == true) cobroEnRevision?.requestId
        else cobroPendienteDeEstaVenta()

    private fun cobroPendienteDeEstaVenta(): String? =
        undeterminedRequestId ?: terminalPaymentService.pendienteDeLaVenta(ordenEnCurso())?.takeIf { it.isNotBlank() }

    fun selectTerminalAndPay(terminalId: String) {
        selectedTerminalId = terminalId
        _canPrintOnTerminal.value = true
        confirmPayment()
    }

    fun confirmPayment() {
        if (isProcessingPayment) return
        if (descartandoApartados) return
        if (bloquearCuentaSinRed()) return
        if (primeroDescartarLosPendientes { confirmPayment() }) return
        // 🔴 Ronda 1 (I3): mandar ESTA venta a la tarjeta es volver a cobrarla por otro método. Antes de la terminal se borran, y se
        // espera, sus apartados de efectivo; si no se pudo, la tarjeta no sale («No se pudo cancelar el cobro…») y la venta sigue
        // con su propiedad. Sin esto, «Salir (queda pendiente)» soltaba el efectivo y se daba por cobrado: venta doble.
        // («Ya pagó de otra forma» pasa por `processCashPayment`: su apartado nuevo ya borra los viejos con `reservarEnLugarDe`.)
        if (selectedMethod == PaymentMethod.CARD && apartadosDeEstaVenta.isNotEmpty()) {
            val generacionAlBorrar = paymentGeneration
            viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                // Ronda final: sólo si sigue siendo ESTA venta y ESTE método (otra venta pudo empezar mientras se borraba).
                if (descartarApartadosDeEstaVenta() && generacionAlBorrar == paymentGeneration && selectedMethod == PaymentMethod.CARD) {
                    confirmPayment()
                }
            }
            return
        }
        val cart = cartState ?: return
        if (detenerSiHayValesSinApartado(cart)) return
        isProcessingPayment = true
        // N2: un cobro nuevo deja atrás el «Cancelar» que falló; si no, «Reintentar» de SU error cancelaría la venta.
        cancelacionFallida = false
        apartadoDelIntento = null // este intento no aparta efectivo
        val venueCapturado = secureStorage.venueId.orEmpty()
        val actorCapturado = secureStorage.userId.orEmpty()
        val nombreActorCapturado = "${secureStorage.userFirstName.orEmpty()} ${secureStorage.userLastName.orEmpty()}"
            .trim().ifEmpty { secureStorage.userEmail ?: "Staff" }
        val metodoCapturado = selectedMethod
        val manualCapturado = manualMethod
        val tenderCapturado = selectedTender
        val tipCapturado = currentTipCents
        val splitCapturado = _splitType.value
        val llaveCapturada = sessionIdempotencyKey()
        val total = currentBaseAmount() + tipCapturado
        // La hora de la venta se captura UNA vez, con el pedido (igual que en `processCashPayment`): la cola la hereda.
        val pedidoCapturado = buildOrderRequest(cart).copy(createdAtLocal = System.currentTimeMillis())
        val ordenCapturada = createdOrderId
        val provisionalCapturada = tableSession.current()?.let {
            it.isProvisional && it.orderId == ordenCapturada
        } == true
        val valesCapturados = valesDelCobro()
        val permiteColaCapturada = valesCapturados == null
        val empleadoCapturado = selectedStaffId()
        val clienteCapturado = attachedCustomerId
        val ratingCapturado = currentRating
        val mesaCapturada = mesaCapturada(venueCapturado)
        // Ronda 1 (M1): el `/pay` legado de «Confirmar» en efectivo también es un POST de efectivo: sin «Cancelar» mientras viaja.
        _state.value = PaymentFlowState.Processing(total, efectivoEnVuelo = metodoCapturado == PaymentMethod.CASH && valesCapturados == null)
        // 🔴 La generación con la que entró este cobro. Crear la orden tarda, y en ese rato el
        // cajero puede tocar «Cancelar»: si cuando vuelve la creación la generación cambió, el
        // cobro NO se envía y lo único que queda es cancelar la orden recién creada.
        val generacion = paymentGeneration
        // Ronda final (A3): y si lo que cambió es la VENTA, nada de ésta se escribe en la nueva.
        val venta = ventaEnCurso

        viewModelScope.launch {
            try {
                val cajaCapturada = cajaAbiertaAlConfirmar()
                // Agregado 4 de la ronda corta, corregido: con TARJETA todavía no hubo dinero; si otra venta empezó mientras se leía la
                // caja, no se crea su orden ni se manda nada. Con EFECTIVO el billete ya está en la mano (R5: lo durable se conserva): la
                // venta sigue con su orden, su cobro y su cola, con su contexto capturado, y no escribe nada en la nueva.
                val conBilleteEnMano = metodoCapturado == PaymentMethod.CASH
                if (otraVenta(venta) && !conBilleteEnMano) return@launch
                if (valesCapturados != null) {
                    val ordenDeLosVales = materializeAreaTicketCheckout(cart, venta, valesCapturados, venueCapturado) ?: return@launch
                    if (otraVenta(venta) && !conBilleteEnMano) return@launch // ronda final (A3): otra venta en pantalla
                    if (!otraVenta(venta) && generacion != paymentGeneration) {
                        cancelarLoCreadoTrasCancelar(orderId = null)
                        return@launch
                    }
                    if (!otraVenta(venta) && conBilleteEnMano) _state.value = PaymentFlowState.Processing(total, efectivoEnVuelo = true)
                    processPaymentMethod(total, generacion, tipCapturado, splitCapturado, llaveCapturada, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, metodoCapturado, pedidoCapturado, ordenDeLosVales, empleadoCapturado, clienteCapturado, ratingCapturado, provisionalCapturada, permiteColaCapturada, cajaCapturada, mesaCapturada)
                    return@launch
                }
                val hasRealProducts = hasProductItems(cart)

                // D2 etapa 2: una venta rápida DIVIDIDA también crea su orden («Otro importe») y las partes le abonan.
                if (hasRealProducts || esVentaRapidaDividida(cart, splitCapturado)) {
                    if (ordenCapturada == null) {
                        // Create order only once per payment session.
                        val orderRequest = requireNotNull(pedidoCapturado)
                        val orderExternalId = llaveCapturada
                        // Familia B (rediseño r9): tras leer la caja (suspende), el folio sólo es de ESTA venta si sigue en pantalla.
                        if (!otraVenta(venta)) externalIdDeLaVenta = orderExternalId
                        onCreacionIncierta(cart.identidadDeVenta, orderExternalId)
                        val orderResult = orderRepository.createOrder(
                            orderRequest,
                            staffId = empleadoCapturado,
                            customerId = clienteCapturado,
                            orderType = cart.orderType,
                            externalId = orderExternalId,
                            // 🔴 El premio viaja EN LA CREACIÓN. El total que devuelva
                            // esta llamada es el que se cobra (`adoptarTotalDelServer`),
                            // así que el descuento tiene que existir antes de que
                            // vuelva. Con una segunda llamada quedaría una ventana con
                            // la cuenta al total completo.
                            stampRewardId = cart.pendingStampRewardId?.takeIf { cart.premioAplica },
                            stampRewardExpectedDiscount = cart.stampRewardCents.takeIf { it > 0 },
                            capturedVenueId = venueCapturado,
                        )

                        orderResult.fold(
                            onSuccess = { response ->
                                if (otraVenta(venta)) {
                                    // Ronda final (A3): otra venta en pantalla; nada de ésta se escribe en ella.
                                    val ordenDeEstaVenta = response.data?.id
                                    if (conBilleteEnMano && !ordenDeEstaVenta.isNullOrBlank()) {
                                        // Corrección del agregado 4: el efectivo ya está en la mano: su orden se cobra con el contexto de esta
                                        // venta (el total que vio la cajera; el del servidor no se adopta: escribiría en la nueva).
                                        processPaymentMethod(total, generacion, tipCapturado, splitCapturado, llaveCapturada, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, metodoCapturado, pedidoCapturado, ordenDeEstaVenta, empleadoCapturado, clienteCapturado, ratingCapturado, provisionalCapturada, permiteColaCapturada, cajaCapturada, mesaCapturada)
                                    } else {
                                        // N1: con tarjeta nadie la va a cobrar: se cancela por la vía durable, sin escribir nada en la nueva.
                                        cancelarLaOrdenDeOtraVenta(ordenDeEstaVenta, response.data?.orderNumber, total, venueCapturado, empleadoCapturado)
                                    }
                                    return@fold
                                }
                                val orderId = response.data?.id
                                if (orderId.isNullOrBlank()) {
                                    _state.value = PaymentFlowState.Error(
                                        message = "No se pudo obtener la orden creada",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                    return@fold
                                }
                                onCreacionResuelta(orderExternalId)
                                createdOrderId = orderId
                                createdOrderNumber = response.data?.orderNumber
                                recordarFolio()
                                ordenCreadaPorEsteFlujo = true
                                if (generacion != paymentGeneration) {
                                    // Se canceló mientras se creaba: ni un cobro más, y la orden
                                    // que acaba de nacer se cancela por la vía durable.
                                    cancelarLoCreadoTrasCancelar(orderId)
                                    return@fold
                                }
                                // 🔴 El servidor cobró otro precio en algunas líneas: el carrito del cobro se
                                // corrige (el descuento de cuenta que viajó queda fijo) y el estimado sale de él.
                                val cambios = response.data?.priceChanges.orEmpty()
                                val estimado = if (corregirPrecios(cambios, congelar = true)) currentBaseAmount() + tipCapturado else total
                                // La orden ya existe y todavía no se ha tomado
                                // dinero: se cobra lo que dice el server, no el
                                // estimado del carrito. Ver `totalACobrarCents`.
                                val adoptado = adoptarTotalDelServer(orderRequest, response, estimado)
                                if (cambios.isNotEmpty() || totalAntesDelRechazoDePrecio != null) publicarAvisoDePrecio(totalNuevoCents = adoptado)
                                processPaymentMethod(adoptado, generacion, tipCapturado, splitCapturado, llaveCapturada, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, metodoCapturado, pedidoCapturado, orderId, empleadoCapturado, clienteCapturado, ratingCapturado, provisionalCapturada, permiteColaCapturada, cajaCapturada, mesaCapturada)
                            },
                            onFailure = { error ->
                                // Ronda final (A3): con otra venta en pantalla nada de ésta se escribe en ella. Agregado 7 de la ronda
                                // corta: pero lo durable se conserva: el efectivo de ESTA venta que no llegó al servidor se encola igual.
                                val otra = otraVenta(venta)
                                if (!otra && generacion != paymentGeneration) {
                                    // Se canceló mientras se creaba y la orden ni siquiera nació:
                                    // no se encola nada ni se pinta un error sobre la cancelación.
                                    cancelarLoCreadoTrasCancelar(orderId = null)
                                    return@fold
                                }
                                if (error is OrderRepository.ServerException && error.code in 400..499) onCreacionResuelta(orderExternalId)
                                // 409 de precio: nada se creó ni se cobró. Antes de mirar si es encolable.
                                val cambios = (error as? OrderRepository.ServerException)?.cambiosDePrecio.orEmpty()
                                if (cambios.isNotEmpty()) {
                                    if (!otra) volverAElegirTrasCambioDePrecio(error, cambios, totalAntes = total, tipCapturado = tipCapturado)
                                    return@fold
                                }
                                // For CASH payments: queue offline if network/server error
                                if (metodoCapturado == PaymentMethod.CASH) {
                                    val isQueueable = OrderRepository.isQueueableError(error) ||
                                        (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))

                                    if (isQueueable && cart.pendingStampRewardId != null && cart.premioAplica) {
                                        // 🔴 El canje de lealtad es online A PROPÓSITO (regla offline §5, como
                                        // Square): no se encola ni se registra. El cajero reintenta (la misma
                                        // llave deduplica si la creación sí llegó) o quita el premio.
                                        if (otra) return@fold
                                        _state.value = PaymentFlowState.Error(
                                            message = MENSAJE_PREMIO_SIN_RED,
                                            source = PaymentErrorSource.NETWORK,
                                        )
                                    } else if (isQueueable) {
                                        val idEnCola = cashPaymentRepository.queueCashPayment(
                                            orderRequest = orderRequest,
                                            amountCents = total - tipCapturado,
                                            tipCents = tipCapturado,
                                            splitType = splitCapturado,
                                            staffId = empleadoCapturado,
                                            customerId = clienteCapturado,
                                            idempotencyKey = llaveCapturada,
                                            cashTenderedCents = null,
                                            changeCents = null,
                                            rating = ratingCapturado,
                                            orderId = null,
                                            orderExternalId = orderExternalId,
                                            manualMethod = manualCapturado,
                                            tenderType = tenderCapturado,
                                            venueId = venueCapturado,
                                            cajaDelCobro = cajaCapturada,
                                        )
                                        // Record cash sale in drawer (defensive: same fix as B4)
                                        recordCashSale(total, null, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = false)
                                        // Codex r9 #5: encolar suspende. Si mientras tanto empezó otra venta, su éxito no se pinta en ella.
                                        if (otraVenta(venta)) return@fold
                                        _state.value = PaymentFlowState.Success(
                                            totalAmount = total,
                                            method = PaymentMethod.CASH,
                                            changeAmount = 0,
                                            isQueued = true,
                                            colaDelCobro = com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos(idEnCola),
                                        )
                                        autoPrintAfterPayment(PaymentMethod.CASH)
                                    } else {
                                        if (otra) return@fold
                                        estrenarLlaveTrasRechazoDeOrden(error)
                                        _state.value = PaymentFlowState.Error(
                                            message = error.message ?: "Error al crear la orden",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                    }
                                } else if (otra) {
                                    return@fold // tarjeta: nada que conservar aquí; la venta nueva no se toca
                                } else if (com.avoqado.pos.core.data.network.ServerErrorText.isOffline(error)) {
                                    // Tarjeta sin red (también si la sesión venció y no hubo red para renovarla,
                                    // Testarudo 15-oct): se dice como en el selector de terminal, nunca con el
                                    // mensaje crudo del servidor. La llave se conserva: si la orden sí aterrizó,
                                    // el reintento la deduplica.
                                    _state.value = PaymentFlowState.Error(
                                        message = TARJETA_SIN_RED,
                                        source = PaymentErrorSource.NETWORK,
                                    )
                                } else {
                                    estrenarLlaveTrasRechazoDeOrden(error)
                                    _state.value = PaymentFlowState.Error(
                                        message = error.message ?: "Error al crear la orden",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                }
                            },
                        )
                    } else {
                        // Ya hay orden: se reusa en vez de crear otra. Entran por
                        // aquí el reintento de tarjeta y —desde el fix del split de
                        // mostrador— la parte 2 de una venta por artículos, donde el
                        // carrito conserva productos reales y crear una segunda orden
                        // duplicaría esas líneas en base.
                        processPaymentMethod(total, generacion, tipCapturado, splitCapturado, llaveCapturada, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, metodoCapturado, pedidoCapturado, ordenCapturada, empleadoCapturado, clienteCapturado, ratingCapturado, provisionalCapturada, permiteColaCapturada, cajaCapturada, mesaCapturada)
                    }
                } else {
                    // Custom amount only — use Fast Payment endpoint
                    Log.d("💰", "Custom amount payment (fast) - total: $total")
                    processPaymentMethod(total, generacion, tipCapturado, splitCapturado, llaveCapturada, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, metodoCapturado, pedidoCapturado, ordenCapturada, empleadoCapturado, clienteCapturado, ratingCapturado, provisionalCapturada, permiteColaCapturada, cajaCapturada, mesaCapturada)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("💰", "Payment error: ${e.message}")
                // Codex r11 #4: lo que esta venta ya dejó durable (servidor, cola) se conserva; su error sólo se pinta si sigue en pantalla.
                if (otraVenta(venta)) return@launch
                _state.value = PaymentFlowState.Error(
                    message = e.message ?: "Error inesperado",
                    source = PaymentErrorSource.UNKNOWN,
                )
            }
        }
    }

    private suspend fun processPaymentMethod(
        total: Int,
        generacion: Int,
        tipCapturado: Int,
        splitCapturado: String,
        llaveCapturada: String,
        manualCapturado: com.avoqado.pos.payment.domain.ManualPaymentMethod?,
        tenderCapturado: com.avoqado.pos.payment.domain.TenderTypeOption?,
        venueCapturado: String,
        actorCapturado: String,
        nombreActorCapturado: String,
        metodoCapturado: PaymentMethod?,
        pedidoCapturado: CreateOrderRequest?,
        ordenCapturada: String?,
        empleadoCapturado: String,
        clienteCapturado: String?,
        ratingCapturado: Int?,
        provisionalCapturada: Boolean,
        permiteColaCapturada: Boolean,
        cajaCapturada: String?,
        /** La mesa de esta venta, fotografiada al empezar el cobro (familia B). */
        mesaCapturada: MesaCapturada?,
    ) {
        when (metodoCapturado) {
            PaymentMethod.CARD -> {
                val terminalId = selectedTerminalId
                if (terminalId == null) {
                    _state.value = PaymentFlowState.Error(
                        message = "No se seleccionó una terminal",
                        source = PaymentErrorSource.TERMINAL,
                    )
                    return
                }

                // 🔴 Última puerta antes de que una tarjeta pueda cobrarse: si el cajero ya
                // canceló, este cobro no sale. Lo que quede por cancelar ya se registró.
                if (generacion != paymentGeneration) {
                    Log.d("💰", "Cancelado antes de enviar: el cobro no se manda a la terminal")
                    return
                }
                // 🔴 Un premio que deja la cuenta en $0 (un café gratis como único producto): no hay nada que
                // cobrarle a una tarjeta, y el servidor rechaza un cobro de terminal ≤ 0. La orden se cierra
                // con un registro de $0, igual que una cortesía total.
                val ordenEnCero = ordenCapturada
                if (total <= 0 && !ordenEnCero.isNullOrBlank() && pedidoCapturado != null) {
                    // Ronda 1 (M1): el cierre en $0 es un `/pay` en efectivo: sin «Cancelar» mientras viaja.
                    _state.value = PaymentFlowState.Processing(0, efectivoEnVuelo = true)
                    recordCashPaymentForOrder(
                        generacion = generacion,
                        orderId = ordenEnCero,
                        total = 0,
                        cashReceivedCents = 0,
                        changeCents = 0,
                        orderRequest = pedidoCapturado,
                        tipCapturado = 0,
                        splitCapturado = splitCapturado,
                        llaveCapturada = llaveCapturada,
                        manualCapturado = manualCapturado,
                        tenderCapturado = tenderCapturado,
                        venueCapturado = venueCapturado,
                        actorCapturado = actorCapturado,
                        nombreActorCapturado = nombreActorCapturado,
                        empleadoCapturado = empleadoCapturado,
                        clienteCapturado = clienteCapturado,
                        ratingCapturado = ratingCapturado,
                        provisionalCapturada = provisionalCapturada,
                        permiteColaCapturada = permiteColaCapturada,
                        cajaCapturada = cajaCapturada,
                        conApartado = false, // confirmPayment no aparta efectivo
                        mesaCapturada = mesaCapturada,
                    )
                    return
                }
                if (bloqueoDelSaldoActual()) { isProcessingPayment = false; return }
                _state.value = PaymentFlowState.SentToTerminal(total)
                val generation = paymentGeneration
                val terminalResult = terminalPaymentService.sendPaymentToTerminal(
                    terminalId = terminalId,
                    amountCents = currentBaseAmount(),
                    tipCents = currentTipCents,
                    rating = currentRating,
                    orderId = createdOrderId,
                    processedByStaffId = selectedStaffId(),
                    // 🔴 EL CLIENTE DE LA VENTA. Es el valor CONGELADO al abrir el cobro
                    // (`clienteDelCobro` de CheckoutScreen → startPaymentFlow), nunca el
                    // flujo vivo del carrito. En el cobro rápido con tarjeta no hay orden
                    // (`createdOrderId` va nulo), así que sin esta línea la venta `FAST-*`
                    // nacía anónima aunque el cajero sí lo hubiera elegido. Espejo exacto
                    // del camino de EFECTIVO (`recordFastCashPayment`).
                    customerId = attachedCustomerId,
                )
                recordarIntentoDeCobro(terminalResult)
                if (terminalResult is TerminalPaymentResult.Success && terminalResult.alreadyRecovered) return
                if (generation != paymentGeneration) {
                    // El cajero canceló mientras el envío seguía en vuelo (hasta 330 s): no se
                    // marca Success/Error ni se imprime sobre una pantalla de la que ya se fue.
                    // Pero el DINERO no se descarta con la navegación — ver handleStaleCardResult.
                    handleStaleCardResult(terminalResult)
                    return
                }
                when (terminalResult) {
                    is TerminalPaymentResult.Success -> applyCardCharged(terminalResult, total)
                    is TerminalPaymentResult.Error -> {
                        // Consta que no se cobró: reintentar vuelve a ser seguro.
                        undeterminedRequestId = null
                        _state.value = PaymentFlowState.Error(
                            message = terminalResult.message,
                            source = PaymentErrorSource.TERMINAL,
                        )
                    }
                    // 🔴 No se sabe si la tarjeta se cobró. Ni Success ni Error: su propia
                    // pantalla, sin Reintentar a ciegas. Ver PaymentFlowState.Undetermined.
                    // `inherited` (25-sep) = el servicio NO lo mandó porque ESTA orden ya tiene un cobro sin confirmar: se
                    // espera; es de este flujo sólo si es el cobro que él mismo mandó (ver `esperarCobroDeEstaOrden`).
                    is TerminalPaymentResult.Undetermined -> if (terminalResult.inherited) {
                        esperarCobroDeEstaOrden(terminalResult.requestId, total, terminalResult.message)
                    } else {
                        undeterminedRequestId = terminalResult.requestId
                        _state.value = PaymentFlowState.Undetermined(
                            totalAmount = total,
                            message = terminalResult.message,
                        )
                    }
                }
            }
            PaymentMethod.CASH -> {
                // Record cash payment on the order
                val orderId = ordenCapturada
                if (orderId != null) {
                    val subtotal = total - tipCapturado
                    val payResult = orderRepository.recordCashPayment(
                        orderId = orderId,
                        amount = subtotal,
                        staffId = empleadoCapturado,
                        tip = tipCapturado,
                        splitType = splitCapturado,
                        idempotencyKey = llaveCapturada,
                        manualMethod = manualCapturado,
                        tenderType = tenderCapturado,
                        capturedVenueId = venueCapturado,
                    )
                    payResult.fold(
                        onSuccess = { result ->
                            // 🔴 Ronda 1b (como I2): otra venta ya está en pantalla. El cobro de ésta SÍ ocurrió: su cajón se registra,
                            // pero su saldo, su recibo y su éxito no se escriben sobre la venta nueva.
                            if (generacion != paymentGeneration) {
                                recordCashSale(total, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = false)
                                liberarLaMesaDeOtraVenta(mesaCapturada, orderId) // como iOS: con SU contexto
                                return@fold
                            }
                            lastPaymentId = result.paymentId
                            // 🔴 El saldo que queda lo dice el server. Ver `buildCompletion`.
                            adoptarSaldoDelServer(result.remainingBalanceCents, result.orderPaymentStatus)
                            // accessKey del recibo → QR en pantalla del cliente y recibo
                            // impreso, igual que en tarjeta. Se setea ANTES de imprimir y
                            // de armar el estado Success para que el QR ya esté disponible.
                            result.receiptAccessKey?.let { lastReceiptAccessKey = it }
                            result.receiptUrl?.let { lastReceiptUrl = it }
                            finishAreaTicketPayment()
                            recordCashSale(total, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = false)
                            _state.value = PaymentFlowState.Success(
                                totalAmount = total,
                                method = PaymentMethod.CASH,
                                paymentId = result.paymentId,
                                receiptAccessKey = result.receiptAccessKey,
                                receiptUrl = result.receiptUrl,
                                inventoryWarningMessage = result.inventoryWarningMessage,
                            )
                            autoPrintAfterPayment(PaymentMethod.CASH)
                        },
                        onFailure = { error ->
                            Log.w("💵", "Cash payment recording failed: ${error.message}")
                            // FIX B2: Don't silently show Success on payment failure.
                            // Queue for offline sync if error is transient, otherwise show Error.
                            // 🔴 D2 v7 punto 7: 2xx con lo local fallido = COBRADO, encolado con su llave; nunca un error cancelable.
                            val registrado = error is OrderRepository.CobroRegistradoSinGuardar
                            val isQueueable = OrderRepository.isQueueableError(error) ||
                                (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                            if (registrado || (isQueueable && pedidoCapturado != null && permiteColaCapturada)) {
                                // Codex r14 A5: con el 2xx comprobado, lo que el servidor registró (cajón y pantalla), como el camino normal.
                                val cobrado = (error as? OrderRepository.CobroRegistradoSinGuardar)?.importeCobradoCents ?: total
                                val idEnCola = if (registrado) llaveCapturada.also { cashPaymentRepository.darPorCobrado(it, cobrado) } else cashPaymentRepository.queueCashPayment(
                                    orderRequest = requireNotNull(pedidoCapturado),
                                    amountCents = total - tipCapturado,
                                    tipCents = tipCapturado,
                                    splitType = splitCapturado,
                                    staffId = empleadoCapturado,
                                    customerId = clienteCapturado,
                                    idempotencyKey = llaveCapturada,
                                    cashTenderedCents = null,
                                    changeCents = null,
                                    rating = ratingCapturado,
                                    orderId = orderId,
                                    manualMethod = manualCapturado,
                                    tenderType = tenderCapturado,
                                    venueId = venueCapturado,
                                    cajaDelCobro = cajaCapturada,
                                )
                                recordCashSale(cobrado, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = false)
                                if (generacion != paymentGeneration) return@fold // ronda 1b: otra venta en pantalla
                                if (registrado) finishAreaTicketPayment()
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = cobrado,
                                    method = PaymentMethod.CASH,
                                    isQueued = true,
                                    colaDelCobro = com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos(idEnCola),
                                )
                                autoPrintAfterPayment(PaymentMethod.CASH)
                            } else {
                                if (generacion != paymentGeneration) return@fold // ronda 1b: otra venta en pantalla
                                _state.value = PaymentFlowState.Error(
                                    message = mensajeDelCobroFallido(error),
                                    source = PaymentErrorSource.SERVER,
                                )
                            }
                        },
                    )
                } else {
                    // No orderId — fast payment path. Let caller handle.
                    recordCashSale(total, null, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = false)
                    if (generacion != paymentGeneration) return // ronda 1b: otra venta en pantalla (leer la caja suspende)
                    _state.value = PaymentFlowState.Success(
                        totalAmount = total,
                        method = PaymentMethod.CASH,
                        paymentId = lastPaymentId,
                    )
                    autoPrintAfterPayment(PaymentMethod.CASH)
                }
            }
            null -> {
                _state.value = PaymentFlowState.Error(
                    message = "Método de pago no seleccionado",
                    source = PaymentErrorSource.UNKNOWN,
                )
            }
        }
    }

    fun processCashPayment(cashReceivedCents: Int) {
        if (isProcessingPayment) return
        if (descartandoApartados) return
        if (bloquearCuentaSinRed()) return
        if (primeroDescartarLosPendientes { processCashPayment(cashReceivedCents) }) return
        if (detenerSiHayValesSinApartado(cartState)) return
        isProcessingPayment = true
        // N2: un cobro nuevo deja atrás el «Cancelar» que falló; si no, tras dos fallos de disco «Reintentar» cancelaría la venta
        // y borraría el apartado nuevo.
        cancelacionFallida = false
        apartadoDelIntento = null // lo fija `reservarEfectivo` si este intento aparta
        lastCashTenderedCents = cashReceivedCents
        val cart = cartState
        val venueCapturado = secureStorage.venueId.orEmpty()
        val actorCapturado = secureStorage.userId.orEmpty()
        val nombreActorCapturado = "${secureStorage.userFirstName.orEmpty()} ${secureStorage.userLastName.orEmpty()}"
            .trim().ifEmpty { secureStorage.userEmail ?: "Staff" }
        val manualCapturado = manualMethod
        val tenderCapturado = selectedTender
        val tipCapturado = currentTipCents
        val splitCapturado = _splitType.value
        val llaveCapturada = sessionIdempotencyKey()
        val total = currentBaseAmount() + tipCapturado
        // La hora de la venta se captura UNA vez, con el pedido: la reserva y la cola la heredan (`createdAtLocal`).
        val pedidoCapturado = cart?.let(::buildOrderRequest)?.copy(createdAtLocal = System.currentTimeMillis())
        val ordenCapturada = createdOrderId
        val provisionalCapturada = tableSession.current()?.let {
            it.isProvisional && it.orderId == ordenCapturada
        } == true
        val valesCapturados = valesDelCobro()
        val permiteColaCapturada = valesCapturados == null
        val empleadoCapturado = selectedStaffId()
        val clienteCapturado = attachedCustomerId
        val ratingCapturado = currentRating
        // Misma puerta que en tarjeta: crear la orden tarda, y si el cajero cancela mientras
        // tanto, el efectivo NO se registra contra una venta que él ya dio por cancelada.
        val generacion = paymentGeneration
        // 🔴 Ronda final: la venta y SUS apartados, fotografiados ANTES de la primera suspensión (A1, A3).
        val venta = ventaEnCurso
        val anterioresDeEstaVenta = apartadosDeEstaVenta.toList()
        val mesaCapturada = mesaCapturada(venueCapturado)

        when (val result = cashPaymentRepository.processCashPayment(total, cashReceivedCents)) {
            is CashPaymentResult.Success -> {
                viewModelScope.launch(alFallarElEfectivo(generacion)) {
                    if (bloqueoDelSaldoActual()) { isProcessingPayment = false; return@launch }
                    // 🔴 D2 v6 punto 5: mientras el efectivo viaja no hay «Cancelar» (como iOS en `.processing`).
                    _state.value = PaymentFlowState.Processing(total, efectivoEnVuelo = valesCapturados == null)
                    val cajaCapturada = cajaAbiertaAlConfirmar()

                    if (cart != null && valesCapturados != null) {
                        // Corrección del agregado 4: con el billete en la mano, la orden de ESTOS vales se cobra aunque ya haya otra venta.
                        val ordenDeLosVales = materializeAreaTicketCheckout(cart, venta, valesCapturados, venueCapturado) ?: return@launch
                        if (!otraVenta(venta) && generacion != paymentGeneration) {
                            cancelarLoCreadoTrasCancelar(orderId = null)
                            return@launch
                        }
                        if (!otraVenta(venta)) _state.value = PaymentFlowState.Processing(total, efectivoEnVuelo = true)
                        recordCashPaymentForOrder(
                            generacion = generacion,
                            orderId = ordenDeLosVales,
                            total = total,
                            cashReceivedCents = cashReceivedCents,
                            changeCents = result.changeCents,
                            orderRequest = requireNotNull(pedidoCapturado),
                            tipCapturado = tipCapturado,
                            splitCapturado = splitCapturado,
                            llaveCapturada = llaveCapturada,
                            manualCapturado = manualCapturado,
                            tenderCapturado = tenderCapturado,
                            venueCapturado = venueCapturado,
                            actorCapturado = actorCapturado,
                            nombreActorCapturado = nombreActorCapturado,
                            empleadoCapturado = empleadoCapturado,
                            clienteCapturado = clienteCapturado,
                            ratingCapturado = ratingCapturado,
                            provisionalCapturada = provisionalCapturada,
                            permiteColaCapturada = permiteColaCapturada,
                            cajaCapturada = cajaCapturada,
                            conApartado = false, // vales: su cobro no se encola ni se aparta
                            mesaCapturada = mesaCapturada,
                        )
                        return@launch
                    }
                    // 🔴 Antes de la primera llamada de red (crear la orden o registrar el cobro).
                    // Rediseño r9: con apartado de efectivo puro, la venta en el cajón la anota su salida de `EN_VUELO`, no este intento.
                    val apartadoDelCobro = try {
                        reservarEfectivo(generacion, anterioresDeEstaVenta, cart, total, cashReceivedCents, result.changeCents, tipCapturado, splitCapturado, llaveCapturada, manualCapturado, tenderCapturado, pedidoCapturado, ordenCapturada, empleadoCapturado, clienteCapturado, ratingCapturado, venueCapturado, provisionalCapturada, permiteColaCapturada, cajaCapturada, actorCapturado, nombreActorCapturado)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Una reserva de otra apertura o identidad no modifica la venta vigente.
                        if (generacion == paymentGeneration && secureStorage.venueId == venueCapturado && secureStorage.userId == actorCapturado) {
                            _state.value = PaymentFlowState.Error(
                                message = e.message ?: "No se pudo guardar el cobro. Intenta de nuevo antes de recibir el pago.",
                                source = PaymentErrorSource.UNKNOWN,
                            )
                        }
                        return@launch
                    }
                    // Con apartado de efectivo puro, su venta la anota la salida de `EN_VUELO`; con CUALQUIER apartado, su orden no se cancela.
                    val apartado = apartadoDelCobro == Apartado.EFECTIVO
                    val reservado = apartadoDelCobro != Apartado.NINGUNO
                    val hasRealProducts = cart?.let(::hasProductItems) ?: false

                    // TABLE_SERVICE: a preset createdOrderId (PAYING table session)
                    // routes cash through the order-based path even though the
                    // seeded cart only carries the "Cuenta Mesa N" amount line.
                    // D2 etapa 2: también la venta rápida DIVIDIDA (su reserva ya es ORDER, `OrderRepository.aceptaCrearOrden`).
                    if (hasRealProducts || ((!ordenCapturada.isNullOrBlank() || esVentaRapidaDividida(cart, splitCapturado)) && cart != null)) {
                        // Order-based cash payment: create order once, then reuse it across retries.
                        val orderRequest = requireNotNull(pedidoCapturado)
                        val existingOrderId = ordenCapturada
                        if (!existingOrderId.isNullOrBlank()) {
                            recordCashPaymentForOrder(
                                generacion = generacion,
                                orderId = existingOrderId,
                                total = total,
                                cashReceivedCents = cashReceivedCents,
                                changeCents = result.changeCents,
                                orderRequest = orderRequest,
                                tipCapturado = tipCapturado,
                                splitCapturado = splitCapturado,
                                llaveCapturada = llaveCapturada,
                                manualCapturado = manualCapturado,
                                tenderCapturado = tenderCapturado,
                                venueCapturado = venueCapturado,
                                actorCapturado = actorCapturado,
                                nombreActorCapturado = nombreActorCapturado,
                                empleadoCapturado = empleadoCapturado,
                                clienteCapturado = clienteCapturado,
                                ratingCapturado = ratingCapturado,
                                provisionalCapturada = provisionalCapturada,
                                permiteColaCapturada = permiteColaCapturada,
                                cajaCapturada = cajaCapturada,
                                conApartado = apartado,
                                mesaCapturada = mesaCapturada,
                            )
                        } else {
                            val orderExternalId = llaveCapturada
                            // Familia B (rediseño r9): tras leer la caja y guardar el apartado (dos suspensiones), el folio de la
                            // comanda sólo es de ESTA venta si sigue en pantalla.
                            if (!otraVenta(venta)) externalIdDeLaVenta = orderExternalId
                            onCreacionIncierta(cart.identidadDeVenta, orderExternalId)
                            val orderResult = if (laCajaEsperaSuApertura(cajaCapturada)) Result.failure(CajaEsperandoSuApertura()) else orderRepository.createOrder(
                                orderRequest,
                                staffId = empleadoCapturado,
                                customerId = clienteCapturado,
                                orderType = cart.orderType,
                                externalId = orderExternalId,
                                // 🔴 El premio viaja también en EFECTIVO. Antes este camino
                                // creaba la orden sin él: el cajero tocaba «Aplicar premio» y
                                // el cliente pagaba completo sin que nadie se enterara.
                                stampRewardId = cart.pendingStampRewardId?.takeIf { cart.premioAplica },
                                stampRewardExpectedDiscount = cart.stampRewardCents.takeIf { it > 0 },
                                capturedVenueId = venueCapturado,
                            )
                            orderResult.fold(
                                onSuccess = { orderResponse ->
                                    // 🔴 Ronda final (A3): la orden es de una venta que ya no está en pantalla. Existe en el servidor
                                    // (lo que ocurrió se conserva), pero nada de ella se escribe en la venta nueva.
                                    // Aquí el billete ya está en la mano: esa orden nunca se cancela (R5, corrección del agregado 4).
                                    // Con apartado, nada más: el de efectivo lo da por cobrado el barrido y el de «Ya pagó de otra forma» lo
                                    // decide una persona (ruling N1). SIN apartado (venta con premio) nada lo haría: se cobra ya, con el
                                    // contexto de esta venta y el total que vio la cajera (el del servidor no se adopta: escribiría en la
                                    // nueva). Si el servidor no aplicó el premio, la orden queda con saldo y la ve el gerente.
                                    if (otraVenta(venta)) {
                                        val ordenDeEstaVenta = orderResponse.data?.id
                                        if (!reservado && !ordenDeEstaVenta.isNullOrBlank()) {
                                            recordCashPaymentForOrder(
                                                generacion = generacion,
                                                orderId = ordenDeEstaVenta,
                                                total = total,
                                                cashReceivedCents = cashReceivedCents,
                                                changeCents = result.changeCents,
                                                orderRequest = orderRequest,
                                                tipCapturado = tipCapturado,
                                                splitCapturado = splitCapturado,
                                                llaveCapturada = llaveCapturada,
                                                manualCapturado = manualCapturado,
                                                tenderCapturado = tenderCapturado,
                                                venueCapturado = venueCapturado,
                                                actorCapturado = actorCapturado,
                                                nombreActorCapturado = nombreActorCapturado,
                                                empleadoCapturado = empleadoCapturado,
                                                clienteCapturado = clienteCapturado,
                                                ratingCapturado = ratingCapturado,
                                                provisionalCapturada = provisionalCapturada,
                                                permiteColaCapturada = permiteColaCapturada,
                                                cajaCapturada = cajaCapturada,
                                                conApartado = false,
                                                mesaCapturada = mesaCapturada,
                                            )
                                        }
                                        return@fold
                                    }
                                    val orderId = orderResponse.data?.id
                                    if (orderId.isNullOrBlank()) {
                                        _state.value = PaymentFlowState.Error(
                                            message = "No se pudo obtener la orden creada",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                        return@fold
                                    }
                                    onCreacionResuelta(orderExternalId)
                                    createdOrderId = orderId
                                    // Como en la tarjeta: el folio real va en el ticket de esta parte y de las siguientes.
                                    createdOrderNumber = orderResponse.data?.orderNumber
                                    recordarFolio()
                                    ordenCreadaPorEsteFlujo = true
                                    if (generacion != paymentGeneration) {
                                        cancelarLoCreadoTrasCancelar(orderId)
                                        return@fold
                                    }
                                    // El efectivo ya está en la mano, así que el
                                    // total del server sólo se adopta si el
                                    // dinero recibido alcanza; si no, se cobra el
                                    // estimado (como hasta hoy) en vez de dejar
                                    // al cajero pidiendo centavos de vuelta.
                                    // 🔴 Antes, el precio que decidió el servidor: el estimado sale del carrito corregido.
                                    val cambios = orderResponse.data?.priceChanges.orEmpty()
                                    val estimado = if (corregirPrecios(cambios, congelar = true)) currentBaseAmount() + tipCapturado else total
                                    val hayAvisoDePrecio = cambios.isNotEmpty() || totalAntesDelRechazoDePrecio != null
                                    val adoptado = adoptarTotalDelServer(orderRequest, orderResponse, estimado)
                                    // 🔴 Con premio no son centavos: el premio no se aplicó (o por menos) y el
                                    // efectivo recibido no cubre el total real. NO se registra un cobro corto
                                    // —dejaría la cuenta debiendo y la caja la daría por liquidada—: se vuelve
                                    // a elegir cómo paga, con el total real y el motivo a la vista. La orden ya
                                    // existe, así que el siguiente intento cobra contra ELLA, sin crear otra.
                                    if (cart.pendingStampRewardId != null && adoptado > cashReceivedCents) {
                                        if (hayAvisoDePrecio) publicarAvisoDePrecio(totalNuevoCents = adoptado)
                                        _avisoDelPremio.value = avisoDeEfectivoCortoPorPremio(
                                            orderResponse.data?.stampReward,
                                            totalRealCents = adoptado,
                                            recibidoCents = cashReceivedCents,
                                        )
                                        isProcessingPayment = false
                                        _state.value = PaymentFlowState.SelectingPaymentMethod(adoptado)
                                        return@fold
                                    }
                                    val totalFinal = adoptado
                                        .takeIf { it <= cashReceivedCents }
                                        ?: estimado.also { serverTotalOverrideCents = null }
                                    // Con el efectivo ya en la mano, lo que bajó se devuelve: se dice cuánto más de cambio.
                                    if (hayAvisoDePrecio) {
                                        publicarAvisoDePrecio(totalNuevoCents = totalFinal, cambioDeMasCents = (total - totalFinal).coerceAtLeast(0))
                                    }
                                    // 🔴 Codex r10 #1 + agregado 1: el apartado lleva el importe que de verdad se cobra y su orden ANTES de
                                    // mandarlo. Si la app muere o la venta se suelta, el barrido lo da por cobrado (y la cola lo sube) con
                                    // ESTE importe, y su venta en el cajón lleva su orden.
                                    if (reservado) {
                                        try {
                                            cashPaymentRepository.actualizarApartado(llaveCapturada, venueCapturado, totalFinal - tipCapturado, orderId)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            // Como el apartado mismo: sin el importe en disco no sale el POST. El reintento lo vuelve a escribir.
                                            Log.e("💵", "No se pudo guardar el importe nuevo del apartado $llaveCapturada: ${e.message}")
                                            if (!otraVenta(venta)) {
                                                _state.value = PaymentFlowState.Error(
                                                    message = "No se pudo guardar el cobro. Intenta de nuevo antes de recibir el pago.",
                                                    source = PaymentErrorSource.UNKNOWN,
                                                )
                                            }
                                            return@fold
                                        }
                                    }
                                    recordCashPaymentForOrder(
                                        generacion = generacion,
                                        orderId = orderId,
                                        total = totalFinal,
                                        cashReceivedCents = cashReceivedCents,
                                        changeCents = if (totalFinal == total) {
                                            result.changeCents
                                        } else {
                                            (cashReceivedCents - totalFinal).coerceAtLeast(0)
                                        },
                                        orderRequest = orderRequest,
                                        tipCapturado = tipCapturado,
                                        splitCapturado = splitCapturado,
                                        llaveCapturada = llaveCapturada,
                                        manualCapturado = manualCapturado,
                                        tenderCapturado = tenderCapturado,
                                        venueCapturado = venueCapturado,
                                        actorCapturado = actorCapturado,
                                        nombreActorCapturado = nombreActorCapturado,
                                        empleadoCapturado = empleadoCapturado,
                                        clienteCapturado = clienteCapturado,
                                        ratingCapturado = ratingCapturado,
                                        provisionalCapturada = provisionalCapturada,
                                        permiteColaCapturada = permiteColaCapturada,
                                        cajaCapturada = cajaCapturada,
                                        conApartado = apartado,
                                        mesaCapturada = mesaCapturada,
                                        // Founder 18-oct («A»): con red, el aviso ya se lo dijo al cajero; sin red sí es alerta.
                                        precioAceptadoEnPantalla = cambios.isNotEmpty() && totalFinal != total,
                                    )
                                },
                                onFailure = { error ->
                                    if (otraVenta(venta)) return@fold // ronda final (A3): otra venta en pantalla
                                    if (generacion != paymentGeneration) {
                                        cancelarLoCreadoTrasCancelar(orderId = null)
                                        return@fold
                                    }
                                    if (error is OrderRepository.ServerException && error.code in 400..499) onCreacionResuelta(orderExternalId)
                                // 409 de precio: nada se creó ni se cobró. Antes de mirar si es encolable.
                                    val cambios = (error as? OrderRepository.ServerException)?.cambiosDePrecio.orEmpty()
                                    if (cambios.isNotEmpty()) {
                                        volverAElegirTrasCambioDePrecio(error, cambios, totalAntes = total, tipCapturado = tipCapturado)
                                        return@fold
                                    }
                                    val isQueueable = OrderRepository.isQueueableError(error) ||
                                        (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                                    if (isQueueable && cart.pendingStampRewardId != null && cart.premioAplica) {
                                        // 🔴 El canje de lealtad es online A PROPÓSITO (regla offline §5, como
                                        // Square): no se encola ni se registra. El cajero reintenta (la misma
                                        // llave deduplica si la creación sí llegó) o quita el premio.
                                        _state.value = PaymentFlowState.Error(
                                            message = MENSAJE_PREMIO_SIN_RED,
                                            source = PaymentErrorSource.NETWORK,
                                        )
                                    } else if (isQueueable) {
                                        val idEnCola = cashPaymentRepository.queueCashPayment(
                                            orderRequest = orderRequest,
                                            amountCents = total - tipCapturado,
                                            tipCents = tipCapturado,
                                            splitType = splitCapturado,
                                            staffId = empleadoCapturado,
                                            customerId = clienteCapturado,
                                            idempotencyKey = llaveCapturada,
                                            cashTenderedCents = cashReceivedCents,
                                            changeCents = result.changeCents,
                                            rating = ratingCapturado,
                                            orderId = null,
                                            orderExternalId = orderExternalId,
                                            manualMethod = manualCapturado,
                                            tenderType = tenderCapturado,
                                            venueId = venueCapturado,
                                            cajaDelCobro = cajaCapturada,
                                        )
                                        // FIX B4: Record cash sale in drawer even on offline queue
                                        recordCashSale(total, null, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = apartado)
                                        // Codex r9 #5: encolar suspende. Si mientras tanto empezó otra venta, su éxito no se pinta en ella.
                                        if (otraVenta(venta)) return@fold
                                        _state.value = PaymentFlowState.Success(
                                            totalAmount = total,
                                            method = PaymentMethod.CASH,
                                            changeAmount = result.changeCents,
                                            isQueued = true,
                                            colaDelCobro = com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos(idEnCola),
                                        )
                                        autoPrintAfterPayment(PaymentMethod.CASH, result.changeCents)
                                    } else {
                                        // Non-queueable error (validation, auth, etc.)
                                        estrenarLlaveTrasRechazoDeOrden(error)
                                        _state.value = PaymentFlowState.Error(
                                            message = error.message ?: "Error al crear la orden",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        // Fast payment (custom amount, no products)
                        // Send amount WITHOUT tip, tip separately
                        val subtotal = total - tipCapturado
                        // Sólo con carrito: sin él, este camino no encola (lo perdería) y sigue en línea, como hoy.
                        val fastResult = if (cart != null && laCajaEsperaSuApertura(cajaCapturada)) Result.failure(CajaEsperandoSuApertura()) else orderRepository.recordFastCashPayment(
                            amount = subtotal,
                            staffId = empleadoCapturado,
                            tip = tipCapturado,
                            splitType = splitCapturado,
                            idempotencyKey = llaveCapturada,
                            manualMethod = manualCapturado,
                            tenderType = tenderCapturado,
                            // 🔴 EL CLIENTE DE LA VENTA. Es el valor CONGELADO al abrir el
                            // cobro (`clienteDelCobro` de CheckoutScreen → startPaymentFlow),
                            // nunca el flujo vivo del carrito. Sin esta línea la venta rápida
                            // nacía anónima aunque el cajero sí lo hubiera elegido.
                            customerId = clienteCapturado,
                            capturedVenueId = venueCapturado,
                        )
                        fastResult.fold(
                            onSuccess = { fast ->
                                // 🔴 Ronda 1 (I2): otra venta ya está en pantalla. El cobro de ésta SÍ ocurrió: su cajón se registra,
                                // pero su recibo, su cliente y su éxito no se escriben sobre la venta nueva.
                                if (generacion != paymentGeneration) {
                                    recordCashSale(total, null, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = apartado)
                                    return@fold
                                }
                                lastPaymentId = fast.paymentId
                                // Recibo → QR en pantalla del cliente y recibo impreso.
                                fast.receiptAccessKey?.let { lastReceiptAccessKey = it }
                                fast.receiptUrl?.let { lastReceiptUrl = it }
                                // 🔴 La pantalla NO puede mentir: si el server no vinculó al
                                // cliente, el recibo no puede seguir enseñándolo puesto. Se
                                // suelta para que vuelva a ofrecer "Agregar cliente" — que es
                                // justo la reasignación, sin volver a cobrar nada.
                                if (fast.customerLinkWarning != null) {
                                    attachedCustomerId = null
                                    _attachedCustomerName.value = null
                                }
                                recordCashSale(total, null, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = apartado)
                                // 🔴 D16: este camino (cobro rápido en efectivo) NUNCA pasa por
                                // `autoPrintAfterPayment`, así que sin
                                // congelar `lastReceipt` AQUÍ, el primer toque de «Imprimir» en la
                                // pantalla de éxito arma el recibo con la hora de ESE toque — no la
                                // de la venta (revisión de conjunto, I2).
                                lastReceipt = buildReceiptSnapshot(PaymentMethod.CASH, result.changeCents)
                                abrirCajonSiCorresponde(PaymentMethod.CASH, total)
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = total,
                                    method = PaymentMethod.CASH,
                                    changeAmount = result.changeCents,
                                    paymentId = fast.paymentId,
                                    receiptAccessKey = fast.receiptAccessKey,
                                    receiptUrl = fast.receiptUrl,
                                    // El server no pudo vincular al cliente: se AVISA (ámbar)
                                    // y el cobro queda como está. Jamás se vuelve a cobrar.
                                    customerLinkWarning = fast.customerLinkWarning,
                                )
                            },
                            onFailure = { error ->
                                // 🔴 D2 v7 punto 7: 2xx con lo local fallido = COBRADO, encolado con su llave; nunca un error cancelable.
                                val registrado = error is OrderRepository.CobroRegistradoSinGuardar
                                val isQueueable = registrado || OrderRepository.isQueueableError(error) ||
                                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                                if (isQueueable) {
                                    // FIX B1: Actually queue the fast-cash payment for offline sync
                                    // FIX B4: Also record cash sale in drawer
                                    val idEnCola = if (registrado) {
                                        llaveCapturada.also { cashPaymentRepository.darPorCobrado(it) }
                                    } else if (cart != null) {
                                        cashPaymentRepository.queueCashPayment(
                                            orderRequest = requireNotNull(pedidoCapturado),
                                            amountCents = total - tipCapturado,
                                            tipCents = tipCapturado,
                                            splitType = splitCapturado,
                                            staffId = empleadoCapturado,
                                            customerId = clienteCapturado,
                                            idempotencyKey = llaveCapturada,
                                            cashTenderedCents = cashReceivedCents,
                                            changeCents = result.changeCents,
                                            rating = ratingCapturado,
                                            orderId = null,
                                            manualMethod = manualCapturado,
                                            tenderType = tenderCapturado,
                                            venueId = venueCapturado,
                                            cajaDelCobro = cajaCapturada,
                                        )
                                    } else {
                                        null
                                    }
                                    recordCashSale(total, null, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = apartado)
                                    if (generacion != paymentGeneration) return@fold // ronda 1 (I2): otra venta en pantalla
                                    // 🔴 Mismo hueco de D16 que arriba, camino encolado: sin esto
                                    // el ticket de una venta rápida que se guardó offline también
                                    // toma la hora de cuando alguien la imprima, no la de la venta.
                                    lastReceipt = buildReceiptSnapshot(PaymentMethod.CASH, result.changeCents)
                                    abrirCajonSiCorresponde(PaymentMethod.CASH, total)
                                    _state.value = PaymentFlowState.Success(
                                        totalAmount = total,
                                        method = PaymentMethod.CASH,
                                        changeAmount = result.changeCents,
                                        isQueued = true,
                                        colaDelCobro = idEnCola?.let { com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos(it) },
                                    )
                                } else {
                                    if (generacion != paymentGeneration) return@fold // ronda 1 (I2): otra venta en pantalla
                                    _state.value = PaymentFlowState.Error(
                                        message = error.message ?: "Error al registrar pago",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            is CashPaymentResult.InsufficientFunds -> {
                // Se queda en el cobro para volver a capturar lo recibido. Hay que soltarlo: si no, el siguiente
                // intento de efectivo se ignoraba en silencio y el cobro quedaba atorado (pendiente del 19-oct).
                // iOS hace lo mismo: regresa a pedir el efectivo (`confirmCashPayment`).
                isProcessingPayment = false
            }
        }
    }

    /**
     * B3 (IVA B2b): un rechazo de NEGOCIO del servidor (4xx) se dice tal cual, sin prefijo — p. ej. «Esta cuenta está
     * cancelada, abre una nueva.» —, igual que en iOS. Lo demás conserva el texto de siempre.
     */
    private fun mensajeDelCobroFallido(error: Throwable): String {
        val delServidor = (error as? OrderRepository.ServerException)?.takeIf { it.code in 400..499 }?.message
        return if (!delServidor.isNullOrBlank()) delServidor else "No se pudo registrar el pago: ${error.message ?: "error desconocido"}"
    }

    private suspend fun recordCashPaymentForOrder(
        /**
         * 🔴 Ronda 1 (I2): la venta (generación) de este cobro. Si cuando contesta el servidor ya hay OTRA venta en pantalla, el
         * cobro sí ocurrió (su cajón se registra y su llave ya quedó en disco), pero no pinta nada ni toca la venta nueva.
         */
        generacion: Int,
        orderId: String,
        total: Int,
        cashReceivedCents: Int,
        changeCents: Int,
        orderRequest: CreateOrderRequest,
        tipCapturado: Int,
        splitCapturado: String,
        llaveCapturada: String,
        manualCapturado: com.avoqado.pos.payment.domain.ManualPaymentMethod?,
        tenderCapturado: com.avoqado.pos.payment.domain.TenderTypeOption?,
        venueCapturado: String,
        actorCapturado: String,
        nombreActorCapturado: String,
        empleadoCapturado: String,
        clienteCapturado: String?,
        ratingCapturado: Int?,
        provisionalCapturada: Boolean,
        permiteColaCapturada: Boolean,
        /** La caja abierta al confirmar ([cajaAbiertaAlConfirmar]). */
        cajaCapturada: String?,
        /** Rediseño r9: este cobro dejó un apartado de efectivo puro; su venta en el cajón la anota su salida de `EN_VUELO`. */
        conApartado: Boolean,
        /** La mesa de esta venta, fotografiada al empezar el cobro (familia B). */
        mesaCapturada: MesaCapturada?,
        /** El servidor cambió el precio EN LÍNEA y el cajero ya vio el aviso: ese importe no es alerta para el gerente. */
        precioAceptadoEnPantalla: Boolean = false,
    ) {
        // Send amount WITHOUT tip, tip separately
        val subtotal = total - tipCapturado

        // Offline-first Corte C: mesa abierta SIN red (sesión provisional) —
        // la orden aún no existe en el server, así que el cobro va como intent
        // PAY_CASH al outbox con el UUID local. El reducer lo aplicará DESPUÉS
        // del OPEN_TABLE/ADD_ITEMS del mismo dispositivo (FIFO). Regla
        // "Backgrounded": si el replay lo rechaza, la cuenta queda visible en
        // cuarentena — jamás se pierde una venta en silencio.
        if (provisionalCapturada) {
            val vId = venueCapturado
            if (vId.isNotBlank()) {
                val idEnCola = syncOutbox.enqueue(
                    vId,
                    com.avoqado.pos.core.data.sync.SyncIntentTypes.PAY_CASH,
                    kotlinx.serialization.json.buildJsonObject {
                        put("localOrderId", kotlinx.serialization.json.JsonPrimitive(orderId))
                        put("amountCents", kotlinx.serialization.json.JsonPrimitive(subtotal))
                        put("tipCents", kotlinx.serialization.json.JsonPrimitive(tipCapturado))
                        // Sin esto, un cobro con terminal ajena hecho sin red
                        // se reproduciría como EFECTIVO y descuadraría el corte.
                        if (tenderCapturado != null) {
                            put("tenderTypeId", kotlinx.serialization.json.JsonPrimitive(tenderCapturado.id))
                            put("tenderRevision", kotlinx.serialization.json.JsonPrimitive(tenderCapturado.revision))
                        } else manualCapturado?.let { m ->
                            put("method", kotlinx.serialization.json.JsonPrimitive(m.serverMethod))
                            m.externalSource?.let { put("externalSource", kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                        // 🔴 La caja de la venta (efectivo sin red en el turno correcto): el servidor saca de ahí el turno.
                        cajaCapturada?.let { put("cashDrawerSessionId", kotlinx.serialization.json.JsonPrimitive(it)) }
                    },
                    cashTenderedCents = cashReceivedCents, changeCents = changeCents, splitType = splitCapturado,
                    // 19-oct (mesa sin red retenida): lo que la mesa debía al empezar el cobro, menos lo cobrado. Al confirmarse, si
                    // el servidor (que valora con el precio de HOY) deja otro saldo, el gerente ve DIFERENCIA.
                    restanteEsperadoCents = mesaDelCobro?.takeIf { it.orderId == orderId }?.let { (it.totalCents - subtotal).coerceAtLeast(0) },
                )
                recordCashSale(total, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = conApartado)
                if (generacion != paymentGeneration) return // ronda 1 (I2): otra venta en pantalla
                _state.value = PaymentFlowState.Success(
                    totalAmount = total,
                    method = PaymentMethod.CASH,
                    changeAmount = changeCents,
                    isQueued = true,
                    colaDelCobro = com.avoqado.pos.payment.data.model.ColaDelCobro.Outbox(idEnCola),
                )
                autoPrintAfterPayment(PaymentMethod.CASH, changeCents)
                return
            }
        }
        // Sólo si se puede encolar: un cobro que no admite cola (vales por área) sigue en línea, como hoy.
        val payResult = if (permiteColaCapturada && laCajaEsperaSuApertura(cajaCapturada)) Result.failure(CajaEsperandoSuApertura()) else orderRepository.recordCashPayment(
            orderId = orderId,
            amount = subtotal,
            staffId = empleadoCapturado,
            tip = tipCapturado,
            splitType = splitCapturado,
            idempotencyKey = llaveCapturada,
            manualMethod = manualCapturado,
            tenderType = tenderCapturado,
            capturedVenueId = venueCapturado,
            importeAceptadoCents = subtotal.takeIf { precioAceptadoEnPantalla },
            capturaPrevia = origenDelCobro.esCapturaPrevia,
        )
        payResult.fold(
            onSuccess = { result ->
                // Una segunda caja pudo mover la orden mientras cobrábamos. En
                // ese caso el server recorta SÓLO efectivo de cajón al saldo
                // fresco y devuelve el importe/cambio que de verdad registró.
                // Pantalla, ticket y arqueo deben contar ese resultado; los
                // campos opcionales conservan el fallback para servers viejos.
                // Un payload imposible no puede desbordar Int y convertir una
                // venta positiva en un movimiento negativo. Igual que iOS,
                // ante cualquier inconsistencia conservamos el total local.
                // 🔴 Codex r10 #1: la MISMA función con la que la salida de `EN_VUELO` anota el cajón (no existe dos veces).
                val retenido = OrderRepository.importeCobradoEnEfectivo(
                    result.recordedAmountCents, result.recordedTipCents, total, cashRetainedCents = result.cashRetainedCents,
                )
                // 🔴 Ronda 1 (I2): otra venta ya está en pantalla. El cobro de ésta SÍ ocurrió (el servidor lo tiene y su recibo
                // también): se registra su cajón, pero su saldo, su recibo y su éxito no se escriben sobre la venta nueva.
                if (generacion != paymentGeneration) {
                    recordCashSale(retenido, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = conApartado)
                    liberarLaMesaDeOtraVenta(mesaCapturada, orderId) // como iOS: con SU contexto
                    return@fold
                }
                lastPaymentId = result.paymentId
                // 🔴 El saldo que queda lo dice el server. Ver `buildCompletion`.
                adoptarSaldoDelServer(result.remainingBalanceCents, result.orderPaymentStatus)
                // 🔴 EL CAMBIO ES LO QUE SALE DEL CAJÓN: recibido − lo que de
                // verdad se cobró. NUNCA `result.authoritativeChangeCents`.
                //
                // Son dos cosas distintas que se llamaban igual, y confundirlas
                // borró el cambio de TODA venta en efectivo con productos (ticket
                // de Testarudo, 10-sep-2026: el cliente dio $550 sobre $544.50 y
                // el recibo imprimió «Recibido: $544.50» sin renglón de cambio):
                //
                //   · aquí        cambio = recibido − cobrado  → lo que se le regresa al cliente
                //   · en el server `changeCents` = lo que sobró del importe ENVIADO
                //     sobre el saldo de la orden. Como le mandamos exactamente el
                //     saldo, en un cobro normal vale SIEMPRE 0.
                //
                // Esta resta subsume el caso que motivó el campo del server (otra
                // caja movió la orden y el server recortó el importe al saldo
                // fresco): al restar sobre el total REALMENTE cobrado, el sobrante
                // recortado ya queda dentro del cambio, sin sumar dos fuentes.
                //
                // Contrato con Rappi 2b (21-oct, Task S2 del SERVIDOR): si la respuesta trae `cashRetainedCents` (lo que se
                // queda en el cajón por este cobro: hoy importe + propina registrados; Rappi le sumará su PAY_IN), el cambio
                // es recibido − eso. Sin él (servidor viejo), la resta de arriba. Sigue sin usarse `changeCents`.
                // Rappi 2b (Codex plan r1 #3): UN total para caja, pantalla de éxito y ticket: lo que se quedó en el cajón
                // (incluye lo cobrado por cuenta de Rappi). En un cobro normal `cashRetainedCents` = importe + propina: igual.
                val finalChange = (cashReceivedCents - retenido).coerceAtLeast(0)
                // Recibo → QR en pantalla del cliente y recibo impreso.
                result.receiptAccessKey?.let { lastReceiptAccessKey = it }
                result.receiptUrl?.let { lastReceiptUrl = it }
                finishAreaTicketPayment()
                recordCashSale(retenido, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = conApartado)
                _state.value = PaymentFlowState.Success(
                    totalAmount = retenido,
                    method = PaymentMethod.CASH,
                    changeAmount = finalChange,
                    paymentId = result.paymentId,
                    receiptAccessKey = result.receiptAccessKey,
                    receiptUrl = result.receiptUrl,
                    inventoryWarningMessage = result.inventoryWarningMessage,
                )
                autoPrintAfterPayment(PaymentMethod.CASH, finalChange)
            },
            onFailure = { error ->
                // 🔴 D2 v7 punto 7: 2xx con lo local fallido = COBRADO, encolado con su llave; nunca un error cancelable.
                val registrado = error is OrderRepository.CobroRegistradoSinGuardar
                val isQueueable = OrderRepository.isQueueableError(error) ||
                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                if (registrado || (isQueueable && permiteColaCapturada)) {
                    // Codex r14 A5: con el 2xx comprobado, lo que el servidor registró: cajón, pantalla y cambio, como el camino normal.
                    val cobrado = (error as? OrderRepository.CobroRegistradoSinGuardar)?.importeCobradoCents ?: total
                    val cambio = if (registrado) (cashReceivedCents - cobrado).coerceAtLeast(0) else changeCents
                    val idEnCola = if (registrado) llaveCapturada.also { cashPaymentRepository.darPorCobrado(it, cobrado) } else cashPaymentRepository.queueCashPayment(
                        // El pago capturado y el pedido completo son datos distintos.
                        orderRequest = orderRequest,
                        amountCents = total - tipCapturado,
                        tipCents = tipCapturado,
                        splitType = splitCapturado,
                        staffId = empleadoCapturado,
                        customerId = clienteCapturado,
                        idempotencyKey = llaveCapturada,
                        cashTenderedCents = cashReceivedCents,
                        changeCents = changeCents,
                        rating = ratingCapturado,
                        orderId = orderId,
                        manualMethod = manualCapturado,
                        tenderType = tenderCapturado,
                        venueId = venueCapturado,
                        cajaDelCobro = cajaCapturada,
                    )
                    recordCashSale(cobrado, orderId, manualCapturado, tenderCapturado, venueCapturado, actorCapturado, nombreActorCapturado, llaveCapturada, conApartado = conApartado)
                    if (generacion != paymentGeneration) return@fold // ronda 1 (I2): otra venta en pantalla
                    if (registrado) finishAreaTicketPayment()
                    _state.value = PaymentFlowState.Success(
                        totalAmount = cobrado,
                        method = PaymentMethod.CASH,
                        changeAmount = cambio,
                        isQueued = true,
                        colaDelCobro = com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos(idEnCola),
                    )
                    autoPrintAfterPayment(PaymentMethod.CASH, cambio)
                } else {
                    if (generacion != paymentGeneration) return@fold // ronda 1 (I2): otra venta en pantalla
                    _state.value = PaymentFlowState.Error(
                        message = mensajeDelCobroFallido(error),
                        source = PaymentErrorSource.SERVER,
                    )
                }
            },
        )
    }

    fun sendReceiptWhatsApp(phone: String) {
        val paymentId = lastPaymentId
        val receiptAccessKey = lastReceiptAccessKey
        if (paymentId.isNullOrBlank() && receiptAccessKey.isNullOrBlank()) {
            _whatsAppResult.value = "No se encontró identificador del recibo"
            return
        }
        // 🔴 Codex r14 B5: el envío de A termina; su resultado y su bandera sólo se publican si A sigue en pantalla.
        val venta = ventaEnCurso
        viewModelScope.launch {
            _whatsAppSending.value = true
            _whatsAppResult.value = null
            val resultado = orderRepository.sendReceiptWhatsApp(
                paymentId = paymentId,
                phone = phone,
                receiptAccessKey = receiptAccessKey,
            ).fold(
                onSuccess = { "Recibo enviado por WhatsApp" },
                onFailure = { e ->
                    when (e) {
                        is OrderRepository.ServerException -> "Error del servidor (${e.code}). Intenta de nuevo."
                        is java.net.UnknownHostException -> "Sin conexión a internet"
                        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
                        else -> e.message ?: "Error al enviar recibo"
                    }
                },
            )
            if (otraVenta(venta)) return@launch
            _whatsAppResult.value = resultado
            _whatsAppSending.value = false
        }
    }

    fun sendReceiptEmail(email: String) {
        val paymentId = lastPaymentId
        val receiptAccessKey = lastReceiptAccessKey
        if (paymentId.isNullOrBlank() && receiptAccessKey.isNullOrBlank()) {
            _emailResult.value = "No se encontró identificador del recibo"
            return
        }
        // 🔴 Codex r14 B5: el envío de A termina; su resultado y su bandera sólo se publican si A sigue en pantalla.
        val venta = ventaEnCurso
        viewModelScope.launch {
            _emailSending.value = true
            _emailResult.value = null
            val resultado = orderRepository.sendReceiptEmail(
                paymentId = paymentId,
                email = email,
                receiptAccessKey = receiptAccessKey,
            ).fold(
                onSuccess = { "Recibo enviado por correo" },
                onFailure = { e ->
                    when (e) {
                        is OrderRepository.ServerException -> "Error del servidor (${e.code}). Intenta de nuevo."
                        is java.net.UnknownHostException -> "Sin conexión a internet"
                        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
                        else -> e.message ?: "Error al enviar recibo"
                    }
                },
            )
            if (otraVenta(venta)) return@launch
            _emailResult.value = resultado
            _emailSending.value = false
        }
    }

    fun retry() {
        // Ronda final (M-1, espejo de iOS): tras «No se pudo cancelar el cobro», «Reintentar» reintenta la CANCELACIÓN.
        if (cancelacionFallida) {
            cancelarVenta()
            return
        }
        when (selectedMethod) {
            PaymentMethod.CARD -> {
                val total = currentBaseAmount() + currentTipCents
                // 🔴 NUNCA cobrar de nuevo sin preguntar antes cómo quedó el intento anterior.
                // Este `retry()` mandaba directo a "Seleccionar terminal" y cobró una tarjeta
                // dos veces (2026-08-10). Si queda un cobro sin resolver, primero se consulta;
                // sólo si CONSTA que no hubo cargo se ofrece cobrar.
                val pending = undeterminedRequestId
                if (pending != null) {
                    reconcileThenOffer(pending, total)
                    return
                }
                ofrecerTerminales(total)
            }
            PaymentMethod.CASH -> {
                val tendered = lastCashTenderedCents ?: (currentBaseAmount() + currentTipCents)
                processCashPayment(tendered)
            }
            null -> {
                val total = currentBaseAmount() + currentTipCents
                _state.value = PaymentFlowState.SelectingPaymentMethod(total)
            }
        }
    }

    /**
     * El cobro con tarjeta consta como exitoso: se cierra el flujo como cualquier venta buena.
     *
     * Se usa igual cuando el éxito llega por la respuesta directa de la terminal que cuando se
     * descubre TARDE, re-consultando el estado durable. En ese segundo caso el cajero no ve
     * ningún error: el cobro salió bien, la app sólo se enteró después.
     */
    private suspend fun applyCardCharged(
        charged: TerminalPaymentResult.Success,
        total: Int,
        /** Se descubrió al cancelar: la pantalla lo DICE, porque el cajero creía cancelada la venta. */
        trasCancelar: Boolean = false,
    ) {
        // D7 (27-oct): la terminal no dice cuánto queda de una venta partida. Se le pregunta al SERVIDOR ANTES de soltar la
        // protección durable del cobro (`reconocerCobro`) y antes de publicar el éxito, que es lo que entrega el resultado
        // al carrito. Si el cajero se fue mientras tanto, el dinero no se pierde: va por el camino del desenlace tardío.
        var avisoDeSaldo: String? = null
        val ordenALeer = createdOrderId
        if (ordenALeer != null && mesaDelCobro == null && !esCobroDeVales() &&
            (continuaVentaPartida || _splitType.value != "FULLPAYMENT")
        ) {
            val generacion = paymentGeneration
            val saldo = leerSaldoTrasTarjeta(ordenALeer)
            if (generacion != paymentGeneration) {
                handleStaleCardResult(charged)
                return
            }
            if (saldo != null) {
                adoptarSaldoDelServer(saldo.remainingCents, saldo.paymentStatus)
            } else {
                saldoSinConfirmar = true
                avisoDeSaldo = AVISO_SALDO_SIN_CONFIRMAR
            }
        }
        undeterminedRequestId = null // el desenlace ya consta
        // H2: ESTE flujo lo aplica como SU pago: la entrada (marcada por la consulta que lo probó) sale de la lista, sin
        // dejar ni pendiente ni «SÍ pasó» sobre su propio cobro.
        charged.requestId?.takeIf { it.isNotBlank() }?.let { terminalPaymentService.reconocerCobro(it) }
        lastPaymentId = charged.paymentId
        lastReceiptAccessKey = charged.receiptAccessKey
        lastReceiptUrl = charged.receiptUrl
        lastCardBrand = charged.cardBrand
        lastCardLastFour = charged.cardLastFour
        finishAreaTicketPayment()
        _state.value = PaymentFlowState.Success(
            totalAmount = total,
            method = PaymentMethod.CARD,
            paymentId = charged.paymentId,
            receiptAccessKey = charged.receiptAccessKey,
            receiptUrl = charged.receiptUrl,
            cobroTrasCancelar = trasCancelar,
            avisoDeSaldo = avisoDeSaldo,
        )
        autoPrintAfterPayment(PaymentMethod.CARD)
    }

    /**
     * D7: dos intentos (un tropiezo de red no basta para rendirse) y 5 s en TOTAL como máximo: corre entre «la tarjeta
     * cobró» y «Cobro exitoso» (Codex fase 1 código r1 #2). null = no se pudo confirmar.
     */
    private suspend fun leerSaldoTrasTarjeta(orderId: String): OrderRepository.SaldoDeLaOrden? =
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            var saldo: OrderRepository.SaldoDeLaOrden? = null
            for (intento in 0 until 2) {
                saldo = orderRepository.saldoDeLaOrden(orderId)
                if (saldo != null) break
                if (intento == 0) kotlinx.coroutines.delay(500)
            }
            saldo
        }

    /**
     * El desenlace de la terminal llegó TARDE, después de que el cajero canceló y la pantalla
     * ya avanzó a otra cosa. Descartarlo es correcto para la NAVEGACIÓN; para el DINERO, no.
     *
     * 🔴 **Cancelar es una PETICIÓN, no una garantía.** Si la tarjeta ya se pasó, la terminal
     * cobra igual y el server reconcilia la fila a COMPLETED. El guard anterior tiraba ese
     * desenlace ENTERO —incluido el cobro exitoso—: el dinero salía y la venta quedaba marcada
     * como impaga. Nadie sabía que ese pago existía, y el cajero cobraba otra vez.
     *
     * Ahora la referencia del cobro se re-arma como pendiente en la lista DURABLE (disco), que
     * es la misma que sobrevive al cambio de pestaña y a la muerte del proceso. Esa misma venta la
     * espera al elegir tarjeta; cualquier otra la ve en el aviso de la selección de terminal, y
     * «Revisar» la abre por la ruta `fromPreviousSale`: informa del cargo viejo SIN pagar la venta nueva.
     */
    private fun handleStaleCardResult(result: TerminalPaymentResult) {
        val (outcomeDelResultado, requestId) = when (result) {
            is TerminalPaymentResult.Success ->
                CardChargeOutcome.Charged(result.paymentId) to result.requestId
            is TerminalPaymentResult.Error ->
                CardChargeOutcome.NotCharged(result.message) to null
            is TerminalPaymentResult.Undetermined ->
                CardChargeOutcome.Undetermined(result.message) to result.requestId
        }
        // 🔴 La cancelación durable pudo resolver ESTE cobro mientras su POST seguía en vuelo. Si
        // ya consta que no se cobró —o si esta pantalla ya aplicó el cobro que sí ocurrió—, el
        // resultado tardío no puede volver a armar la llave: dejaría a la venta siguiente pidiendo
        // resolver un cobro que ya está resuelto. La excepción, y es de dinero: un resultado que
        // trae paymentId ACREDITADO manda sobre el veredicto — ver `staleOutcome`.
        val conocido = requestId?.let { cancelacionDeCobro.desenlaceConocido(it) }
        val outcome = CardChargeDecision.staleOutcome(
            resultado = outcomeDelResultado,
            verdictoNoSeCobro = conocido is com.avoqado.pos.payment.domain.DesenlaceDeCancelacion.NoSeCobro,
            cobroYaAplicadoAEstaVenta =
                conocido is com.avoqado.pos.payment.domain.DesenlaceDeCancelacion.SeCobro &&
                    requestId == cobroAplicadoTrasCancelar,
        )
        // 🔴 Desde el 25-sep hay VARIOS pendientes: el desenlace tardío decide sólo sobre SU cobro y nunca toca la
        // entrada de otro (antes la ranura era una sola y el rezagado se quedaba sin lugar). Sin `requestId` —o en
        // blanco— no hay referencia que consultar: no se arma nada.
        //
        // 🔴 P2 de Codex (20-sep): un ÉXITO tardío de ESTE cobro manda sobre una declaración ya aceptada — si
        // el cobro sí pasó, la afirmación del cajero no puede silenciarlo. Cualquier otro desenlace obsoleto NO
        // repone lo que la declaración soltó.
        val pending = requestId?.takeIf { it.isNotBlank() }
        val enDisco = pending?.let {
            terminalPaymentService.aplicarDesenlaceTardio(it, outcome, aunSiFueDeclarado = outcome is CardChargeOutcome.Charged)
        } ?: true
        if (!enDisco) {
            // Nada se descarta en silencio: si hubo una cancelación registrada, su intención lo vuelve a entregar.
            Log.w("PaymentFlow", "⚠️ El desenlace tardío del cobro $pending no quedó en disco")
        }
        // Esta pantalla ya no gobierna ese cobro: la lista durable manda. Su venta lo espera; las demás lo ven
        // en el aviso (no paga la venta nueva).
        if (pending != null && CardChargeDecision.quedaPendienteTrasDesenlaceTardio(outcome)) {
            Log.w("PaymentFlow", "⚠️ Se canceló, pero el cobro no consta como no cobrado (requestId: $pending)")
        } else {
            Log.d("PaymentFlow", "⏭️ Resultado obsoleto tras cancelar: consta que no se cobró")
        }
    }

    /**
     * Vuelve a preguntarle al server cómo quedó un cobro sin resolver y actúa según el desenlace:
     * cobró → flujo normal; no cobró → recién ahí se ofrece cobrar; sigue sin saberse → la
     * pantalla honesta. **Nunca dispara un cargo.**
     */
    private fun reconcileThenOffer(requestId: String, total: Int, encabezado: String? = null, automatica: Boolean = false) {
        val enVuelo = consultaEnVuelo
        if (enVuelo != null && enVuelo.generacion == paymentGeneration) {
            // D2 (relevo, 15-oct): el cajero tocó «Volver a consultar» con la automática en vuelo (con WiFi malo dura hasta
            // 35 s). No sale otra consulta: la que va en vuelo pasa a ser SUYA — ve «Consultando…» y su desenlace, también
            // «no se sabe». Si no, el botón parecía muerto.
            if (!automatica && enVuelo.automatica && enVuelo.requestId == requestId) {
                enVuelo.automatica = false
                (_state.value as? PaymentFlowState.Undetermined)?.let { _state.value = it.copy(checking = true) }
            }
            return
        }
        val propia = ConsultaEnVuelo(requestId, paymentGeneration, automatica)
        consultaEnVuelo = propia
        // `encabezado` = se REVISA sin adoptar (`fromPreviousSale`). La pantalla tiene que seguir hablando de ESE cobro:
        // «Volver a consultar» y la declaración lo buscan aquí, porque no es el cobro de esta pantalla.
        val fromPreviousSale = encabezado != null
        if (encabezado != null) cobroEnRevision = CobroEnRevision(requestId, encabezado)
        val pendingMessage = conEncabezado(encabezado, CardChargeDecision.UNDETERMINED_MESSAGE)
        // D2: la consulta automática no pinta «Consultando…»: el cajero no tocó nada.
        if (!automatica) {
            _state.value = PaymentFlowState.Undetermined(
                totalAmount = total,
                message = pendingMessage,
                checking = true,
                fromPreviousSale = fromPreviousSale,
            )
        }
        viewModelScope.launch {
            val outcome = try {
                terminalPaymentService.resolveOutcome(requestId, enSegundoPlano = automatica)
            } finally { if (consultaEnVuelo === propia) consultaEnVuelo = null }
            // Si el cajero la hizo suya a medio camino, se aplica como la del botón.
            val sigueAutomatica = propia.automatica
            if (propia.generacion != paymentGeneration) {
                handleStaleCardResult(if (outcome is TerminalPaymentResult.Success) outcome.copy(requestId = requestId) else outcome)
                return@launch
            }
            // D2: la automática sólo gobierna el «Cobro sin confirmar» QUIETO que la lanzó — una declaración en vuelo
            // (`checking`) manda sobre ella — y «no se sabe» (sin red, 403, 404) no toca la pantalla.
            //
            // Ronda 2: y sólo si la pantalla sigue hablando de ESE cobro. Una declaración liberada y un cobro nuevo de la
            // venta (otra solicitud) no los gobierna el desenlace tardío de la anterior.
            if (sigueAutomatica) {
                val actual = _state.value
                if (actual !is PaymentFlowState.Undetermined || actual.checking || outcome is TerminalPaymentResult.Undetermined ||
                    cobroSinConfirmarEnPantalla() != requestId
                ) {
                    return@launch
                }
            }
            when (outcome) {
                is TerminalPaymentResult.Success -> {
                    undeterminedRequestId = null
                    if (fromPreviousSale) {
                        // 🔴 Ese cobro NO se adoptó (de otra venta, u otra parte de ésta): confirmarlo NO paga ésta.
                        // Y tampoco arranca ésta: el cajero vino a resolver un pendiente,
                        // no a cobrar. Soltarlo en el primer paso de la venta nueva —con el
                        // aviso desvaneciéndose encima— hacía que el desenlace del cobro
                        // viejo pasara volando mientras la pantalla ya le pedía otra cosa.
                        // Vuelve a donde estaba, con el carrito intacto, y él decide. H2: la entrada queda marcada en
                        // disco hasta que se cierre «Cobro anterior resuelto» (o venza su ventana).
                        resolverCobroAnterior("El cobro anterior sí se había realizado", cobroQueSiPaso = requestId)
                    } else {
                        applyCardCharged(outcome.copy(requestId = outcome.requestId ?: requestId), total)
                    }
                }
                is TerminalPaymentResult.Error -> {
                    // Consta que NO se cobró: aquí sí es seguro ofrecer cobrar de nuevo.
                    undeterminedRequestId = null
                    if (fromPreviousSale) {
                        resolverCobroAnterior("El cobro anterior no se realizó")
                    } else {
                        ofrecerTerminales(total)
                    }
                }
                is TerminalPaymentResult.Undetermined -> {
                    if (!fromPreviousSale) undeterminedRequestId = outcome.requestId
                    _state.value = PaymentFlowState.Undetermined(
                        totalAmount = total,
                        message = conEncabezado(encabezado, outcome.message),
                        checking = false,
                        fromPreviousSale = fromPreviousSale,
                    )
                }
            }
        }
    }

    /**
     * "Volver a consultar" desde la pantalla de cobro no confirmado. Es la acción SEGURA:
     * sólo pregunta, jamás cobra.
     *
     * @param automatica D2 (mesas40): la pantalla lo pregunta sola cada 5 s — el servidor resuelve hasta ~30 s después y
     *   Android no tiene socket —, por ESTE mismo camino: misma solicitud, mismo desenlace (también `fromPreviousSale`).
     *   Sólo cambia que no pinta «Consultando…» y que consulta en segundo plano; corre sobre «Cobro sin confirmar» quieto
     *   y nunca mueve la pantalla sin una respuesta del servidor que lo acredite.
     */
    fun recheckCardCharge(automatica: Boolean = false) {
        val enPantalla = _state.value as? PaymentFlowState.Undetermined
        if (automatica && (enPantalla == null || enPantalla.checking)) return
        val total = currentBaseAmount() + currentTipCents
        val fromPreviousSale = enPantalla?.fromPreviousSale == true
        // La lista durable manda: si el proceso murió, `undeterminedRequestId` viene vacío
        // pero el cobro de esta venta sigue sin resolverse en disco.
        val pending = cobroSinConfirmarEnPantalla()
        if (pending == null) {
            // Sin solicitud no hay a quién preguntar: la automática no mueve la pantalla por su cuenta.
            if (automatica) return
            // Ya no hay nada pendiente que consultar: consta que no hay cargo vivo.
            if (fromPreviousSale) enterInitialState(total) else ofrecerTerminales(total)
            return
        }
        // Una revisión conserva su encabezado. Un pendiente de esta orden que ninguna pantalla fijó (lo encontró la lista)
        // no lo mandó este flujo: se revisa, igual que en la puerta de la tarjeta.
        val encabezado = when {
            fromPreviousSale -> cobroEnRevision?.encabezado ?: PREVIOUS_CHARGE_PREFIX
            pending != undeterminedRequestId -> SAME_SALE_CHARGE_PREFIX
            else -> null
        }
        reconcileThenOffer(pending, total, encabezado, automatica)
    }

    /**
     * El cajero revisó la terminal, vio la advertencia del riesgo de doble cobro y aun así
     * decide cobrar otra vez. Es una decisión HUMANA y explícita — nunca un camino automático.
     */
    fun chargeAgainDespiteUndetermined() { /* Only authoritative recovery permits another charge. */ }

    /**
     * «Ya revisé la terminal: no se cobró». El cajero MIRÓ la pantalla del aparato y lo declara;
     * el servidor libera la venta y la ranura de una sola vez.
     *
     * 🔴 Es la salida que faltaba el 18-sep: Testarudo estuvo 26 minutos sin poder cobrar y sólo
     * se destrabó escribiendo SQL en el Postgres de producción. La protección hizo su trabajo
     * —cero cobros dobles—; lo que no existía era una salida para el cajero.
     *
     * 🔴 Y NO cobra: libera. Quien decide si se vuelve a cobrar es el cajero, en la pantalla
     * siguiente, como hasta hoy.
     */
    /**
     * Lo que se va a declarar, CONGELADO desde el contexto durable del cobro pendiente — nunca del
     * carrito que el cajero tiene enfrente.
     *
     * 🔴 P1 de la auditoría de Codex (19-sep): con un pendiente de $100 y una venta nueva de $500,
     * el diálogo afirmaba «el cobro de $500 no pasó» mientras la declaración iba sobre el de $100.
     * El cajero FIRMA CON SU NOMBRE esa afirmación; el importe tiene que ser el del cobro que
     * libera. Y si no consta, `montoCentavos` es `null`: el diálogo dice «ese cobro», no uno falso.
     */
    fun objetivoDeLaDeclaracion(): ObjetivoDeLaDeclaracion? {
        val requestId = cobroSinConfirmarEnPantalla() ?: return null
        val contexto = terminalPaymentService.contextoDe(requestId)
        val monto = contexto?.amountCents?.let { it + (contexto.tipCents ?: 0) }
        return ObjetivoDeLaDeclaracion(requestId = requestId, venueId = contexto?.venueId, montoCentavos = monto)
    }

    fun declararNoCobrado() {
        // El objetivo se congela ANTES de tocar nada: es el mismo que vio el cajero en el diálogo.
        val objetivo = objetivoDeLaDeclaracion() ?: return
        val requestId = objetivo.requestId
        val total = currentBaseAmount() + currentTipCents
        val fromPreviousSale = (_state.value as? PaymentFlowState.Undetermined)?.fromPreviousSale == true
        // 🔴 P2 de Codex: si el flujo se reinicia (rotación) mientras la declaración va en vuelo, la
        // respuesta vieja NO puede gobernar la pantalla nueva — ponía `SelectingTerminal` sobre un
        // flujo sin método de pago y el cajero acababa en «Método de pago no seleccionado». Misma
        // guarda que ya usa `reconcileThenOffer`.
        val generacion = paymentGeneration

        // Se conserva el mensaje que ya estaba: `checking` es lo que indica que hay algo en vuelo, y
        // escribir «Cancelando el cobro…» aquí sería falso — no se está cancelando nada.
        val mensajeActual = (_state.value as? PaymentFlowState.Undetermined)?.message ?: CardChargeDecision.UNDETERMINED_MESSAGE
        _state.value = PaymentFlowState.Undetermined(
            totalAmount = total,
            message = mensajeActual,
            checking = true,
            fromPreviousSale = fromPreviousSale,
        )

        viewModelScope.launch {
            val r = terminalPaymentService.declararNoCobrado(requestId, objetivo.venueId)
            if (generacion != paymentGeneration) return@launch
            when (r) {
                is ResultadoDeDeclaracion.Liberada -> {
                    undeterminedRequestId = null
                    // El MISMO desenlace que «consta que no se cobró»: el cajero decide si cobra.
                    if (fromPreviousSale) {
                        resolverCobroAnterior(CancelacionDeCobro.DECLARACION_LISTO)
                    } else {
                        ofrecerTerminales(total)
                    }
                }
                // El pendiente SE CONSERVA en los dos casos que siguen: nada se resolvió.
                is ResultadoDeDeclaracion.Rechazada ->
                    _state.value = PaymentFlowState.Undetermined(
                        totalAmount = total,
                        message = r.mensaje,
                        checking = false,
                        fromPreviousSale = fromPreviousSale,
                        declaracionRechazada = true,
                    )
                is ResultadoDeDeclaracion.SesionRenovada ->
                    _state.value = PaymentFlowState.Undetermined(
                        totalAmount = total,
                        message = CancelacionDeCobro.DECLARACION_SESION_RENOVADA,
                        checking = false,
                        fromPreviousSale = fromPreviousSale,
                    )
                is ResultadoDeDeclaracion.SinConexion ->
                    _state.value = PaymentFlowState.Undetermined(
                        totalAmount = total,
                        message = CancelacionDeCobro.DECLARACION_SIN_RED,
                        checking = false,
                        fromPreviousSale = fromPreviousSale,
                    )
            }
        }
    }


    // MARK: - Cancelar la venta (§C.4): el cobro primero, la orden sólo cuando conste

    /**
     * «Cancelar» en el cobro con terminal: **se cancela el COBRO y, sólo cuando conste que no se
     * cobró, la ORDEN — si la creó este mismo flujo**.
     *
     * 🔴 Antes esto mandaba el cancel a la terminal y el DELETE de la orden EN PARALELO. Cuando el
     * DELETE llegaba primero, el servidor contestaba 409 («hay un cobro en curso»), el aviso se
     * perdía en un toast sobre una pantalla que ya se había ido, y la orden quedaba huérfana —
     * medido en producción el 11-sep-2026. Ahora la intención se guarda en DISCO antes de tocar la
     * red y la reproduce [cancelacionDeCobro] hasta que consta el desenlace.
     */
    fun cancelarVenta() {
        val estado = _state.value
        // Una cancelación ya en curso no se relanza: un doble toque mandaría dos cancel y, peor,
        // dos borrados.
        if (estado is PaymentFlowState.CancelandoCobro || estado is PaymentFlowState.CancelacionPendiente) return
        // 🔴 D2 v6 punto 5: con el POST del efectivo en vuelo no hay «Cancelar» (ni el botón, ni aquí). Al terminar, la cajera
        // decide con el desenlace a la vista.
        if (efectivoEnVuelo() || descartandoApartados) return
        // 🔴 D2 v7: «Cancelar» es la decisión de la cajera sobre ESTA venta. Primero se borran sus apartados de efectivo y se
        // ESPERA a que quede escrito; si no se pudo, la pantalla lo dice y no se sale.
        if (apartadosDeEstaVenta.isNotEmpty()) {
            val venta = ventaEnCurso
            viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val borrados = descartarApartadosDeEstaVenta()
                // 🔴 Codex r14 B1: el borrado de A terminó; si mientras tanto empezó otra venta, no se cancela ni se marca ésa.
                if (otraVenta(venta)) return@launch
                if (borrados) cancelarVentaSinApartados()
                // Ronda final (M-1): «Reintentar» de esta pantalla reintenta la cancelación, nunca el cobro.
                else if (!efectivoEnVuelo()) cancelacionFallida = true
            }
            return
        }
        cancelarVentaSinApartados()
    }

    private fun cancelarVentaSinApartados() {
        val estado = _state.value
        if (estado is PaymentFlowState.CancelandoCobro || estado is PaymentFlowState.CancelacionPendiente) return

        val esDeVales = esCobroDeVales()
        val cobro = objetivoDeCancelacion(estado)
        val orderId = createdOrderId
        // La cuenta de una MESA o la orden de un split que ya existía no las creó este flujo:
        // cancelar su cobro jamás puede borrarlas. Los vales tienen su propia sesión.
        val puedeBorrarLaOrden = ordenCreadaPorEsteFlujo && orderId != null && !esDeVales

        when {
            cobro != null -> {
                val intencion = com.avoqado.pos.payment.data.IntencionDeCancelarCobro(
                    id = cobro.requestId,
                    requestId = cobro.requestId,
                    venueId = cobro.venueId,
                    terminalId = cobro.terminalId,
                    orderId = orderId ?: cobro.orderId,
                    borrarOrden = puedeBorrarLaOrden,
                    actorStaffId = selectedStaffId().takeIf { it.isNotBlank() },
                    montoCents = currentBaseAmount() + currentTipCents,
                    orderNumber = createdOrderNumber,
                    creadaEn = System.currentTimeMillis(),
                    fase = com.avoqado.pos.payment.data.FaseDeCancelacion.PEDIR_CANCEL.name,
                )
                if (!cancelacionDeCobro.registrar(intencion)) return avisarQueNoSeGuardo()
                // Con la intención ya en disco: el cobro en vuelo NO puede concluir nada por su
                // cuenta (un 409/504 después de esto no es «no se cobró»).
                terminalPaymentService.marcarCancelacionPedida(cobro.requestId)
                empezarACancelar(intencion.id)
            }

            estado is PaymentFlowState.Processing && orderId == null && isProcessingPayment -> {
                // La orden se está creando: al volver, `confirmPayment` ve la generación cambiada,
                // NO manda el cobro y registra la cancelación de esa orden recién nacida.
                paymentGeneration++
                isProcessingPayment = false
                _state.value = PaymentFlowState.CancelandoCobro(currentBaseAmount() + currentTipCents)
            }

            puedeBorrarLaOrden -> {
                val intencion = intencionSoloDeOrden(orderId!!)
                if (!cancelacionDeCobro.registrar(intencion)) return avisarQueNoSeGuardo()
                empezarACancelar(intencion.id)
            }

            else -> {
                // Nada que cancelar en el servidor: se sale como siempre. Con vales se suelta su apartado (D1, 20-oct).
                paymentGeneration++
                isProcessingPayment = false
                soltarApartadoDeVales()
                salirDelFlujo()
            }
        }
    }

    /** Nombre histórico; hoy TODA cancelación es durable. */
    fun cancel() = cancelarVenta()

    /**
     * «Salir (queda pendiente)»: el cajero se va y NO se borra nada.
     *
     * La llave durable del cobro sigue armada, así que la próxima venta vuelve a preguntar por él;
     * y si había una cancelación registrada, el coordinador la sigue reproduciendo solo.
     */
    fun salirDejandoPendiente() {
        dejarDeObservarLaCancelacion()
        // Un desenlace que llegue tarde ya no gobierna esta pantalla, pero SÍ la llave durable.
        paymentGeneration++
        isProcessingPayment = false
        salirDelFlujo()
    }

    /** «Volver a consultar» desde «Cancelación pendiente»: sólo pregunta, nunca cobra ni borra. */
    fun volverAConsultarCancelacion() {
        val id = cancelacionObservada ?: return
        (_state.value as? PaymentFlowState.CancelacionPendiente)?.let { _state.value = it.copy(checking = true) }
        cancelacionDeCobro.procesarAhora(id)
    }

    /** El cobro que esta pantalla puede pedir cancelar, con el contexto para poder hacerlo. */
    private data class CobroACancelar(
        val requestId: String,
        val terminalId: String?,
        val venueId: String,
        val orderId: String?,
    )

    private fun objetivoDeCancelacion(estado: PaymentFlowState): CobroACancelar? {
        // 1) El cobro que sigue vivo en este aparato (POST o re-consulta).
        if (estado is PaymentFlowState.SentToTerminal || estado is PaymentFlowState.Processing) {
            terminalPaymentService.intentoEnVuelo()?.let { enVuelo ->
                val ctx = terminalPaymentService.contextoDe(enVuelo.requestId)
                return CobroACancelar(enVuelo.requestId, enVuelo.terminalId, enVuelo.venueId, ctx?.orderId)
            }
        }
        // 2) Un cobro de ESTE flujo cuyo desenlace no consta, o el último intento que sí llegó a
        //    crearse. Un cobro de una venta ANTERIOR no es de este flujo: no se cancela desde aquí.
        val requestId = when {
            estado is PaymentFlowState.Undetermined && estado.fromPreviousSale -> null
            estado is PaymentFlowState.Undetermined || estado is PaymentFlowState.SentToTerminal -> cobroPendienteDeEstaVenta()
            // Un rechazo de admisión correlacionado ya probó que ese cobro no se creó: no hay a
            // quién preguntarle, y esperar a la terminal dejaría la orden abierta para siempre.
            else -> ultimoIntento?.takeIf { !it.noSeCreo }?.requestId
        } ?: return null
        val ctx = terminalPaymentService.contextoDe(requestId)
        val venueId = ctx?.venueId ?: secureStorage.venueId ?: return null
        return CobroACancelar(requestId, ctx?.terminalId ?: selectedTerminalId, venueId, ctx?.orderId)
    }

    private fun intencionSoloDeOrden(orderId: String) = com.avoqado.pos.payment.data.IntencionDeCancelarCobro(
        id = "orden:$orderId",
        requestId = null,
        venueId = secureStorage.venueId.orEmpty(),
        terminalId = null,
        orderId = orderId,
        borrarOrden = true,
        actorStaffId = selectedStaffId().takeIf { it.isNotBlank() },
        montoCents = currentBaseAmount() + currentTipCents,
        orderNumber = createdOrderNumber,
        creadaEn = System.currentTimeMillis(),
        fase = com.avoqado.pos.payment.data.FaseDeCancelacion.BORRAR_ORDEN.name,
    )

    /**
     * La orden nació DESPUÉS de que el cajero canceló: no se cobra, se cancela.
     *
     * @param orderId `null` = la creación falló, así que no hay nada que cancelar: sólo se sale.
     */
    private fun cancelarLoCreadoTrasCancelar(orderId: String?) {
        if (esCobroDeVales()) {
            soltarApartadoDeVales()
            salirDelFlujo()
            return
        }
        if (orderId == null) {
            salirDelFlujo()
            return
        }
        val intencion = intencionSoloDeOrden(orderId)
        if (!cancelacionDeCobro.registrar(intencion)) return avisarQueNoSeGuardo()
        empezarACancelar(intencion.id)
    }

    /**
     * 🔴 N1 (re-revisión de la ronda final): la orden de una venta que ya NO está en pantalla nació después de que empezó otra.
     * Nadie la va a cobrar, así que se cancela por la vía durable (el incidente del 11-sep: la orden huérfana), con SU importe y
     * SU número. Sólo se registra la intención y se pide al coordinador que la procese: no se llama [empezarACancelar] ni se
     * escribe nada en la venta nueva (ni su estado, ni su aviso). Con premio, cancelarla también suelta el premio.
     */
    private fun cancelarLaOrdenDeOtraVenta(orderId: String?, orderNumber: String?, montoCents: Int, venueId: String, actor: String) {
        val id = orderId?.takeIf { it.isNotBlank() } ?: return
        val intencion = com.avoqado.pos.payment.data.IntencionDeCancelarCobro(
            id = "orden:$id",
            requestId = null,
            venueId = venueId,
            terminalId = null,
            orderId = id,
            borrarOrden = true,
            actorStaffId = actor.takeIf { it.isNotBlank() },
            montoCents = montoCents,
            orderNumber = orderNumber,
            creadaEn = System.currentTimeMillis(),
            fase = com.avoqado.pos.payment.data.FaseDeCancelacion.BORRAR_ORDEN.name,
        )
        if (!cancelacionDeCobro.registrar(intencion)) {
            Log.e("PaymentFlow", "⚠️ No se pudo guardar la cancelación de la orden $id de una venta anterior")
            return
        }
        cancelacionDeCobro.procesarAhora(intencion.id)
    }

    /**
     * 🔴 Familia B (rediseño r9, paridad con iOS): el cobro de la mesa de una venta que ya NO está en pantalla sí ocurrió. Su mesa
     * se libera con el contexto y la época que se capturaron al abrir ESE cobro, revalidados antes y después de la red, sin leer la
     * venta nueva: `clearTable` sólo pasa si el servidor la tiene PAGADA (un abono no la libera) y `finalizar` sólo toca la sesión
     * de ESA orden. Antes la mesa vieja se quedaba sin liberar (la liberación la hacía la pantalla, que ya es de otra venta).
     */
    private fun liberarLaMesaDeOtraVenta(mesa: MesaCapturada?, orderId: String) {
        if (mesa == null || orderId !in mesa.mesa.idsDeLaOrden) return
        val repo = tableServiceRepository ?: return
        fun vigente() = secureStorage.venueId == mesa.venueId && tableSession.contextoActual() == mesa.contexto
        if (!vigente()) return
        viewModelScope.launch {
            if (repo.clearTable(mesa.venueId, mesa.mesa.tableId).isFailure) {
                Log.d("🍽", "La orden de la mesa ${mesa.mesa.tableNumber} sigue con saldo: su sesión sigue viva")
                return@launch
            }
            if (!vigente()) return@launch
            tableSession.finalizar(mesa.venueId, mesa.mesa.idsDeLaOrden, mesa.contexto)
            runCatching { lanHub?.release(mesa.mesa.tableId) }
            runCatching { repo.refresh(mesa.venueId) }
        }
    }

    /**
     * 🔴 Familia B (rediseño r9): LA guarda de la venta. Cada flujo fotografía `ventaEnCurso` antes de su primera suspensión y la
     * compara con ésta después de cada una: si cambió, no escribe nada en la venta que muestra la pantalla (estado, identidad,
     * apartados, recibo, saldo). Lo que de verdad ocurrió se conserva por su cuenta (servidor, cola, cajón, mesa vieja).
     * `paymentGeneration` sigue siendo otra cosa: «la cajera canceló este intento» (no se manda el cobro).
     */
    private fun otraVenta(venta: Int): Boolean = venta != ventaEnCurso

    private fun empezarACancelar(id: String) {
        paymentGeneration++
        isProcessingPayment = false
        paymentIdempotencyKey = null
        _state.value = PaymentFlowState.CancelandoCobro(currentBaseAmount() + currentTipCents)
        observarLaCancelacion(id)
        cancelacionDeCobro.procesarAhora(id)
    }

    /**
     * 🔴 Si la intención no quedó en disco NO sale una sola petición: una cancelación que sólo vive
     * en RAM desaparece con la app y deja la orden abierta con la terminal quizá cobrando. El
     * cajero lo ve y puede volver a intentarlo.
     */
    private fun avisarQueNoSeGuardo() {
        _cancelFailure.value = com.avoqado.pos.payment.domain.CancelacionDeCobro.NO_SE_PUDO_GUARDAR
        Log.e("PaymentFlow", "⚠️ No se pudo guardar la cancelación: no se envía nada")
    }

    private fun observarLaCancelacion(id: String) {
        observadorDeCancelacion?.cancel()
        cancelacionObservada = id
        observadorDeCancelacion = viewModelScope.launch {
            cancelacionDeCobro.estado(id).collect { estado -> alCambiarLaCancelacion(id, estado) }
        }
    }

    private fun dejarDeObservarLaCancelacion() {
        observadorDeCancelacion?.cancel()
        observadorDeCancelacion = null
        cancelacionObservada = null
    }

    private fun alCambiarLaCancelacion(id: String, estado: com.avoqado.pos.payment.data.EstadoDeCancelacion?) {
        val actual = _state.value
        // Sólo gobierna mientras la pantalla esté en la cancelación. Si el cobro en efectivo
        // aterrizó y la venta quedó pagada, ese hecho manda sobre cualquier aviso posterior.
        if (actual !is PaymentFlowState.CancelandoCobro && actual !is PaymentFlowState.CancelacionPendiente) return
        val total = currentBaseAmount() + currentTipCents
        when (estado) {
            null -> Unit
            is com.avoqado.pos.payment.data.EstadoDeCancelacion.EnCurso ->
                if (actual is PaymentFlowState.CancelacionPendiente) _state.value = actual.copy(checking = true)
            is com.avoqado.pos.payment.data.EstadoDeCancelacion.Pendiente ->
                _state.value = PaymentFlowState.CancelacionPendiente(totalAmount = total, sinRed = estado.sinRed)
            // Consta que no se cobró: el dinero ya está resuelto. Lo que quede de la orden lo
            // termina el coordinador solo, y el cajero puede seguir vendiendo.
            is com.avoqado.pos.payment.data.EstadoDeCancelacion.NoSeCobroOrdenPendiente -> terminarLaCancelacion()
            is com.avoqado.pos.payment.data.EstadoDeCancelacion.Cerrada -> terminarLaCancelacion()
            is com.avoqado.pos.payment.data.EstadoDeCancelacion.SeCobro -> aplicarElCobroQueSiOcurrio(id)
        }
    }

    private fun terminarLaCancelacion() {
        dejarDeObservarLaCancelacion()
        soltarApartadoDeVales()
        salirDelFlujo()
    }

    /**
     * La terminal SÍ cobró el cobro que se pidió cancelar: la venta queda pagada.
     *
     * Se resuelve por el camino de siempre (`resolveOutcome`), que es el que suelta la llave
     * durable y trae el recibo; la pantalla lo DICE, porque el cajero creía cancelada esta venta.
     */
    private fun aplicarElCobroQueSiOcurrio(id: String) {
        val requestId = cancelacionObservada?.takeIf { it == id } ?: id
        dejarDeObservarLaCancelacion()
        val total = currentBaseAmount() + currentTipCents
        viewModelScope.launch {
            when (val outcome = terminalPaymentService.resolveOutcome(requestId)) {
                is TerminalPaymentResult.Success -> {
                    cobroAplicadoTrasCancelar = requestId
                    cancelacionDeCobro.cobroAplicado(id)
                    applyCardCharged(outcome.copy(requestId = requestId), total, trasCancelar = true)
                }
                is TerminalPaymentResult.Undetermined -> {
                    // El cobro consta en el coordinador pero la consulta no pudo confirmarlo:
                    // pantalla honesta, con la llave armada y su "Volver a consultar".
                    undeterminedRequestId = requestId
                    _state.value = PaymentFlowState.Undetermined(totalAmount = total, message = outcome.message)
                }
                is TerminalPaymentResult.Error -> terminarLaCancelacion()
            }
        }
    }

    /**
     * Suelta el apartado de los vales en el servidor, con su orden armada y sin cobrar. Cuando el servidor lo confirma, la
     * sesión local se borra y el carrito saca sus vales (`CartViewModel` observa la sesión): se vuelven a escanear. Sin red no
     * se suelta nada y los vales siguen en el carrito para reintentar. Conservar el apartado con la orden ya armada la dejaba
     * congelada: lo que el cajero agregaba después no entraba al cobro (Codex r1 #1, 20-oct).
     */
    private fun soltarApartadoDeVales() {
        // Regla 9: sólo el apartado de este cobro (las tareas de salida soltaban «el vigente», que pudo ser ya otra venta).
        val id = sesionDelCobro ?: return
        viewModelScope.launch {
            runCatching { areaTicketRepository.cancel(soloSi = id) }
                .onFailure { Log.e("PaymentFlow", "⚠️ No se pudo soltar el apartado de vales: ${it.message}") }
        }
    }

    /**
     * 🔴 DINERO (D1, 20-oct). Un renglón de vale cuyo apartado ya no existe (venció o se soltó) no se cobra: por la ruta de
     * venta normal entraba como importe libre, el dinero entraba y el vale quedaba sin pagar, así que el área no lo entregaba.
     * La ruta se decide por lo que trae el carrito, como iOS (`hasAreaTicketLines`), no sólo por el apartado.
     */
    /** El cajero salió sin cobrar sin pasar por «Cancelar»: una cuenta de vales que armó ESTE cobro se suelta (Codex r4 #6). */
    fun alSalirSinCobrar() {
        if (areaTicketRepository.session.cobroQueLaArmo == cobroDeValesId) soltarApartadoDeVales()
    }

    /**
     * 🔴 DINERO (D5, 20-oct). Este cobro es de vales si su carrito trae vales o si continúa la orden de la sesión de vales (su
     * saldo pendiente). Antes bastaba con que hubiera una sesión viva: tras dejar una venta de vales con abono, la venta
     * siguiente de productos normales se cobraba contra la orden vieja, y al terminar olvidaba esa sesión.
     */
    private fun esCobroDeVales(cart: CartState? = cartState): Boolean {
        // 🔴 Pedidos en línea (Ruling 21 #1): la sesión de vales es global (un `@Singleton` en disco) y el cajero puede dejar
        // vales a medias en «Cobrar» e irse a «Más → Pedidos en línea». El cobro de un pedido web nunca es de vales: ni los
        // arma, ni cobra contra su orden, ni los termina, ni los suelta (`sesionDelCobro` queda null). Con NORMAL no cambia nada.
        if (origenDelCobro.esPedidoEnLinea) return false
        val sesion = areaTicketRepository.session.current() ?: return false
        if (cart?.items?.any { it.areaTicketLineId != null } == true) return true
        return sesion.order?.id?.let { it == createdOrderId } == true
    }

    private fun detenerSiHayValesSinApartado(cart: CartState?): Boolean {
        if (cart?.items?.any { it.areaTicketLineId != null } != true) return false
        if (areaTicketRepository.session.current() != null) return false
        _state.value = PaymentFlowState.Error(message = VALES_SIN_APARTADO, source = PaymentErrorSource.SERVER)
        return true
    }

    private fun salirDelFlujo() {
        _state.value = PaymentFlowState.Loading
        _debeSalir.value = true
    }

    private fun recordarIntentoDeCobro(resultado: TerminalPaymentResult) {
        ultimoIntento = when (resultado) {
            is TerminalPaymentResult.Success -> UltimoIntentoDeCobro(resultado.requestId, noSeCreo = false)
            is TerminalPaymentResult.Undetermined -> UltimoIntentoDeCobro(resultado.requestId, noSeCreo = false)
            is TerminalPaymentResult.Error -> UltimoIntentoDeCobro(resultado.requestId, noSeCreo = resultado.noSeCreo)
        }
    }

    /**
     * 🔴 CINCO de los diez sitios que llaman aquí registran la venta SIN orden
     * (`orderId = null`): es el cobro de MOSTRADOR, que se cobra antes de que exista
     * orden alguna. Son, por flujo:
     *
     *  1. la orden con productos que no se pudo crear y se encoló;
     *  2. el camino de cobro rápido de `processPaymentMethod` ("No orderId — fast
     *     payment path");
     *  3. la orden que falló al crearse dentro de `processCashPayment` y se encoló;
     *  4. el cobro rápido que SÍ pasó en línea;
     *  5. el cobro rápido que falló y se encoló.
     *
     * Se cuentan por FIRMA, no por número de línea (que se mueve con cada edición). La
     * expresión se ancla al principio de la línea a propósito: sin eso el propio
     * comentario que estás leyendo entra en la cuenta y da 5 por el motivo equivocado.
     *
     * ```
     * grep -cE '^ +recordCashSale\(total(, null)?\)$' PaymentFlowViewModel.kt   # → 5 (de 10 sitios)
     * ```
     *
     * Importa porque una fila sin orden no se puede parear por identidad con su cobro
     * encolado: es la que `PendingCashSales` tiene que salvar por MONTO.
     *
     * @param amountCents el total CON propina — el dinero que quedó en el cajón.
     */
    private fun recordCashSale(
        amountCents: Int,
        orderId: String?,
        manualCapturado: com.avoqado.pos.payment.domain.ManualPaymentMethod?,
        tenderCapturado: com.avoqado.pos.payment.domain.TenderTypeOption?,
        venueCapturado: String,
        actorCapturado: String,
        nombreActorCapturado: String,
        /**
         * 🔴 Ronda final (B): la llave del cobro. El movimiento del cajón toma de ella un id DETERMINISTA (nunca dos por una llave).
         */
        llave: String?,
        /**
         * 🔴 Rediseño r9: este cobro dejó un apartado de efectivo PURO. Su venta la anota su salida de `EN_VUELO`, en la misma
         * escritura (`SalidaDelApartado`), sea cual sea la venta en pantalla: aquí no se anota. Sin apartado (canales sin reserva,
         * «Ya pagó de otra forma», tipos del catálogo) el único escritor sigue siendo éste, como hoy.
         */
        conApartado: Boolean,
    ) {
        if (conApartado) return
        // Todos los callers son registros de efectivo: conservar el medio de ESA captura,
        // antes de cualquier await, y aplicar la misma política del pulso al arqueo.
        if (!CajonDeDinero.debeAbrirse(PaymentMethod.CASH, manualCapturado, tenderCapturado, amountCents)) return
        viewModelScope.launch {
            try {
                // addCashSale returns null gracefully when no drawer is open
                // (normal — not every venue runs one). A thrown exception is a
                // REAL insert failure: the drawer total will drift from recorded
                // sales, so log at error level instead of a silent Log.d.
                cashDrawerRepository.addCashSale(amountCents, orderId, venueCapturado, actorCapturado, nombreActorCapturado, llaveDelCobro = llave)
            } catch (e: Exception) {
                Log.e("💰", "Cash drawer insert FAILED (drawer total will drift): ${e.message}", e)
            }
        }
    }

    /**
     * Cajón de dinero al cobrar en EFECTIVO (conducta estándar de POS), por la impresora de
     * recibos y sólo si tiene «abrir cajón al cobrar» activado. La regla de cuándo vive en
     * [CajonDeDinero]. Nunca rompe el cobro: el dinero ya se registró, un fallo del cajón
     * sólo se loguea. Funciona igual sin red: el pulso sale del aparato.
     */
    private suspend fun abrirCajonDeDinero() {
        runCatching {
            val receiptPrinter = printerService.getDefaultPrinter(
                com.avoqado.pos.printing.data.model.PrinterRole.RECEIPT,
            )
            if (receiptPrinter != null && receiptPrinter.autoOpenCashDrawer) {
                printerService.openCashDrawer(receiptPrinter)
            }
        }.onFailure { Log.w("💰", "No se pudo abrir el cajón en venta de efectivo: ${it.message}") }
    }

    /**
     * El cobro rápido en efectivo (importe tecleado, sin productos) NO pasa por
     * [autoPrintAfterPayment]; sin esta llamada el cajón nunca se abría (Sunmi D3, 2026-09-17).
     */
    private fun abrirCajonSiCorresponde(method: PaymentMethod, montoCents: Int) {
        if (!CajonDeDinero.debeAbrirse(method, manualMethod, selectedTender, montoCents)) return
        viewModelScope.launch { abrirCajonDeDinero() }
    }

    // MARK: - Auto Print

    private fun autoPrintAfterPayment(method: PaymentMethod, changeCents: Int? = null) {
        val cart = cartState ?: return
        // Se decide AQUÍ, no dentro de la corrutina: `startPaymentFlow` de la venta
        // siguiente limpia `manualMethod`/`selectedTender` y la corrutina podría leerlos ya
        // vacíos — una transferencia abriría el cajón.
        val cobrado = (_state.value as? PaymentFlowState.Success)?.totalAmount ?: (currentBaseAmount() + currentTipCents)
        val abrirCajon = CajonDeDinero.debeAbrirse(method, manualMethod, selectedTender, cobrado)
        // Etapa 3 del KDS (3.4): se decide AQUÍ, como el cajón — la venta siguiente limpia estos campos. Una venta
        // ENCOLADA todavía no llega al servidor, que es quien arma la comanda de pantalla: sus estaciones «sólo
        // pantalla» salen en papel de respaldo.
        val servidorLaTiene = (_state.value as? PaymentFlowState.Success)?.isQueued != true
        val origenDelFolio = externalIdDeLaVenta?.let { "sale:$it" }
        // 🔴 Codex r10 #3 (familia B): la FOTO de esta venta antes de lanzar el trabajo. La impresión y la comanda esperan
        // (hasta ~1 min con reintentos); si mientras tanto empieza otra venta, siguen con SU recibo, SU orden y SU negocio, nunca
        // con los de la venta nueva, y sus avisos no la pisan.
        val recibo = buildReceiptSnapshot(method, changeCents)?.also { lastReceipt = it }
        val ordenDeLaComanda = createdOrderId
        val negocioDeLaComanda = secureStorage.venueId
        val venta = ventaEnCurso

        viewModelScope.launch {
            recibo?.let { printerService.autoPrintReceipt(it) }

            // El cajón va DESPUÉS del ticket: los dos salen por la misma impresora.
            if (abrirCajon) abrirCajonDeDinero()

            // 🔴 Sigue dentro de ESTE `viewModelScope.launch` — desligado del camino del cobro,
            // que a esta altura ya resolvió `_state` (ver `autoPrintAfterPayment`, llamado
            // DESPUÉS de `_state.value = Success`). Con el reintento esto puede tardar hasta
            // ~1 minuto: si viviera en la coroutine del pago, congelaría la caja con el
            // cliente enfrente. `PaymentFlowViewModelTest` fija que `state` llega a `Success`
            // aunque la comanda siga reintentando.
            despacharComanda(cart, servidorLaTiene, origenDelFolio, ordenDeLaComanda, negocioDeLaComanda, venta)
        }
    }

    /**
     * Arma las líneas de la comanda automática post-cobro y llama a [ComandaDispatcher.dispatch].
     * La comparten el disparo automático ([autoPrintAfterPayment]) y el botón manual del aviso
     * ([reintentarComanda]) — así un reintento manda EXACTAMENTE la misma comanda, en vez de una
     * reconstrucción aparte que se pueda desviar.
     *
     * SUSPEND a propósito y SIN lanzar su propio `viewModelScope.launch`: quien llama decide en
     * qué coroutine corre. [autoPrintAfterPayment] ya la corre dentro de la suya; [reintentarComanda]
     * abre una nueva. Nunca se llama desde el camino del cobro.
     */
    private suspend fun despacharComanda(
        cart: CartState,
        servidorLaTiene: Boolean,
        origenDelFolio: String?,
        /** La orden, el negocio y la venta de ESTA comanda, fotografiados antes de lanzarla (Codex r10 #3). */
        orderId: String?,
        venueId: String?,
        venta: Int,
    ) {
        val realItems = cart.items.filter {
            it.type is CartItemType.ProductItem && !it.locked
        }
        // Sin productos reales (venta de importe libre) no hay comanda que mandar.
        if (realItems.isEmpty()) return
        val orderNumber = orderId?.takeLast(4) ?: "Q-${(1000..9999).random()}"

        // PRINT_STATIONS — el disparo POST-PAGO del mostrador. La secuencia (refrescar config →
        // rutear → imprimir CON REINTENTO, o el ticket legado si el venue no tiene estaciones)
        // vive en [ComandaDispatcher], que es la MISMA pieza que usa el disparo PRE-PAGO del
        // vale de área (§5.6). Mover el mecanismo no cambió ni una llamada de este camino:
        // mismos argumentos, mismo orden, mismo ticket legado (con su `category`) — lo fijan
        // PaymentFlowViewModelTest y ComandaDispatcherTest.
        // 🔴 Esto corre en un `viewModelScope.launch` suelto, DESPUÉS de cobrar: una excepción aquí
        // (lectura de disco, bind de la impresora) no tenía quién la atrapara y cerraba la app con el
        // cliente enfrente. Se dice como «No salió la comanda» —sin botón: no hay trabajo que reenviar—
        // y sólo la cancelación se propaga.
        try {
            val coursePedidos = com.avoqado.pos.payment.domain.buildCounterCoursePedidos(realItems)
            if (coursePedidos != null) {
                comandaDispatcher.despacharEnFondo(
                    venueId = venueId, orderNumber = orderNumber, pedidos = coursePedidos,
                    orderId = orderId, servidorLaTiene = servidorLaTiene, origenDelFolio = origenDelFolio,
                    alCambiarEstado = { estado -> aplicarEstadoDeComanda(estado, orderNumber, venta) },
                ).join()
                return
            }
            comandaDispatcher.dispatch(
                venueId = venueId,
                lines = realItems.map { item ->
                    RoutableItem(
                        orderItemId = item.id,
                        productId = (item.type as? CartItemType.ProductItem)?.productId,
                        categoryId = item.categoryId,
                        productName = item.nombreEnCocina,
                        quantity = item.quantity,
                        modifiers = item.selectedModifiers.map { it.modifierName },
                        notes = item.itemNote,
                        // COMBOS — el nombre viaja con la línea para que cada estación
                        // pueda encabezar SUS productos con el combo al que pertenecen.
                        comboName = item.promotionInstanceId?.let { item.promotionName ?: "Combo" },
                    )
                },
                orderNumber = orderNumber,
                orderType = "En tienda",
                // «La libreta» (Task 16) — el id REAL de la orden, para que el reporte al servidor
                // no dependa del `orderNumber` truncado/aleatorio de arriba.
                orderId = orderId,
                // Sin estaciones configuradas: EXACTAMENTE lo de antes — un solo ticket de cocina
                // abanicado a todas las impresoras con rol KITCHEN.
                noStationsFallback = NoStationsFallback.LegacySingleTicket(
                    // COMBOS — en la comanda la llave es el NOMBRE (ver ComboPrintLines):
                    // los productos del mismo combo van juntos bajo un encabezado.
                    ComboPrintLines.kitchen(
                        realItems.map { item ->
                            val comboName = item.promotionInstanceId?.let { item.promotionName ?: "Combo" }
                            val tag = comboName?.let { ComboPrintLines.Tag(key = it, name = it) }
                            tag to KitchenItem(
                                name = item.nombreEnCocina,
                                quantity = item.quantity,
                                modifiers = item.selectedModifiers.map { it.modifierName }.ifEmpty { null },
                                note = item.itemNote,
                                category = item.subtitle,
                            )
                        },
                    ),
                ),
                // Una comanda que sigue reintentando o que se rindió se DICE, con la estación por
                // nombre y la CAUSA REAL que reportó la impresora — nunca un texto genérico.
                // Una comanda que sigue reintentando o que se rindió se DICE, con la estación por
                // nombre y la CAUSA REAL que reportó la impresora — nunca un texto genérico. La regla
                // de "de qué venta habla este aviso" vive en UN solo sitio: [aplicarEstadoDeComanda].
                // Etapa 3 del KDS (3.4): la caja decide por estación — ver `KitchenDeliveryPolicy`.
                servidorLaTiene = servidorLaTiene,
                origenDelFolio = origenDelFolio,
                alCambiarEstado = { estado -> aplicarEstadoDeComanda(estado, orderNumber, venta) },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("🍳", "❌ La comanda del pedido $orderNumber reventó al despacharse: ${e.message}", e)
            aplicarEstadoDeComanda(
                EstadoDeComanda.NoSalio(listOf("Cocina"), e.message, orderNumber, trabajo = null),
                orderNumber,
                venta,
            )
        }
    }

    private val _reintentandoComandaManualmente = MutableStateFlow(false)

    /**
     * El botón "Volver a imprimir" tiene un reintento manual EN VUELO. La pantalla lo usa para
     * deshabilitarlo/mostrarlo cargando — un doble toque no puede disparar DOS ciclos de
     * reintento en paralelo: los dos imprimirían la MISMA comanda y duplicarían el ticket de
     * cocina, que es exactamente lo que este trabajo existe para evitar.
     */
    val reintentandoComandaManualmente: StateFlow<Boolean> = _reintentandoComandaManualmente.asStateFlow()

    /**
     * El cajero tocó "Volver a imprimir" en el aviso de comanda. Repite EXACTAMENTE la misma
     * comanda (mismas líneas, mismo `orderNumber`) — nunca a ciegas: si ya no hay [cartState]
     * de esta venta (el cajero salió y empezó otra), no hay qué reintentar y no se manda nada.
     *
     * 🔴 Con un reintento YA en vuelo, un segundo toque es un NO-OP — el chequeo y el `set` de
     * [_reintentandoComandaManualmente] corren SÍNCRONOS en el hilo de UI (el `onClick` de
     * Compose), así que dos toques seguidos jamás se cruzan a medio camino.
     */
    fun reintentarComanda() {
        if (_reintentandoComandaManualmente.value) return
        // Se comprueba que HAYA algo pendiente y vigente antes de marcar «en vuelo»: el almacén
        // aplica la vigencia (8 h) en el mismo sitio, en vez de copiar la regla aquí.
        val pendiente = comandasPendientesStore.pendiente.value ?: return
        if (pendiente.trabajo == null) return
        _ocultoSinResolver.value = null
        _reintentandoComandaManualmente.value = true
        viewModelScope.launch {
            try {
                // 🔴 Por el ejecutor compartido — ver el KDoc de [replayDeComandas]. Él limpia el
                // pendiente si sale, así que la pantalla se actualiza sola al observarlo.
                replayDeComandas.intentarAhora()
            } finally {
                _reintentandoComandaManualmente.value = false
            }
        }
    }

    /**
     * Publica un [EstadoDeComanda] en el aviso, respetando de QUÉ venta habla.
     *
     * 🔴 Un `Salio` tardío de la venta anterior NO puede borrar el aviso —todavía sin
     * resolver— de la venta que el cajero tiene enfrente. Por eso se compara el folio.
     */
    private fun aplicarEstadoDeComanda(
        estado: EstadoDeComanda,
        orderNumber: String,
        /** La venta de la comanda (Codex r10 #3). null = no es de una venta en particular. */
        venta: Int? = null,
    ) {
        // 🔴 Familia B: un «Insistiendo» de la comanda de una venta que ya no está en pantalla no se pinta sobre la nueva (describe
        // algo en curso de otra venta). El veredicto final SÍ se dice, con su folio: una comanda que no salió nunca se esconde
        // (P1 #6 de Codex, 7-sep; ver `startPaymentFlow`).
        if (estado is EstadoDeComanda.Insistiendo && venta != null && otraVenta(venta)) return
        if (estado is EstadoDeComanda.Salio) {
            // 🔴 Se mira lo que el cajero TIENE ENFRENTE (`comandaWarning`, que ya combina el
            // aviso en curso con lo pendiente del almacén). Un `Salio` tardío de la venta
            // anterior no puede borrar el aviso —todavía sin resolver— de la venta de ahora.
            val avisoActual = comandaWarning.value
            val esDeEstaVenta = when (avisoActual) {
                null, EstadoDeComanda.Salio -> true
                is EstadoDeComanda.Insistiendo -> avisoActual.orderNumber == orderNumber
                is EstadoDeComanda.NoSalio -> avisoActual.orderNumber == orderNumber
            }
            if (esDeEstaVenta) {
                _avisoEnCurso.value = null
                comandasPendientesStore.limpiar()
            }
        } else if (estado is EstadoDeComanda.NoSalio && estado.trabajo != null) {
            // El veredicto final CON algo que reenviar va al almacén, que es lo que ven TODAS
            // las pantallas y lo que el reloj reintenta solo.
            _avisoEnCurso.value = null
            _ocultoSinResolver.value = null
            comandasPendientesStore.guardar(estado)
        } else {
            // 🔴 Todo lo demás SE VE pero no se guarda: un `Insistiendo` describe algo en curso,
            // y un `NoSalio` SIN trabajo (el camino legado, o todas las estaciones saltadas) no
            // se puede reimprimir. Ver y poder reimprimir son cosas distintas: el cajero TIENE
            // que enterarse de que la comanda no salió aunque nadie pueda reenviarla, y
            // guardarlo en disco sólo serviría para PISAR a uno que sí era recuperable.
            _avisoEnCurso.value = estado
        }
    }

    private fun buildReceiptSnapshot(method: PaymentMethod? = null, changeCents: Int? = null): ReceiptData? {
        val cart = cartState
        val successState = _state.value as? PaymentFlowState.Success
        val resolvedMethod = method ?: successState?.method ?: selectedMethod
        val baseAmount = currentBaseAmount()
        val receiptTotal = successState?.totalAmount ?: (baseAmount + currentTipCents)

        fun com.avoqado.pos.pos.data.model.CartItem.toReceiptItem(): ReceiptItem {
            val modifierUnitTotal = selectedModifiers.sumOf { it.priceInCents }
            val effectiveUnitWithModifiers = effectiveUnitPrice + modifierUnitTotal
            return ReceiptItem(
                name = name,
                quantity = quantity,
                unitPrice = effectiveUnitWithModifiers,
                // 🔴 BRUTO, sin el descuento de la línea. El descuento se imprime
                // UNA vez, en su propio renglón junto al subtotal: si además se
                // rebajara aquí, las líneas sumarían menos que el subtotal y el
                // ticket que se lleva el cliente no cuadraría consigo mismo.
                totalPrice = grossPrice,
                modifiers = selectedModifiers.map { it.modifierName }.ifEmpty { null },
                note = itemNote,
                isCortesia = isCortesia,
                weightSummary = weightSummary,
                areaSourceLabel = subtitle.takeIf { locked },
            )
        }

        /**
         * COMBOS — el nombre del combo como renglón y debajo sus componentes sin
         * precio (founder 2026-08-18, patrón Fudo/Square/Toast). La llave es la
         * INSTANCIA: cada combo vendido es su propio renglón con su propio precio.
         * Sin combos en el carrito devuelve exactamente la lista de siempre.
         */
        fun List<com.avoqado.pos.pos.data.model.CartItem>.toReceiptItemsConCombos(): List<ReceiptItem> =
            ComboPrintLines.receipt(
                map { item ->
                    val tag = item.promotionInstanceId?.let { instanceId ->
                        ComboPrintLines.Tag(
                            key = instanceId,
                            name = item.promotionName ?: "Combo",
                        )
                    }
                    tag to item.toReceiptItem()
                },
            )

        val splitTypeValue = _splitType.value
        val receiptItems = when {
            cart == null || cart.items.isEmpty() -> listOf(
                ReceiptItem(
                    name = "Venta",
                    quantity = 1,
                    unitPrice = baseAmount,
                    totalPrice = baseAmount,
                ),
            )
            splitTypeValue == "BYPRODUCT" -> {
                val selected = cart.items.filter { splitSelectedItemIds.contains(it.id) }
                (if (selected.isEmpty()) cart.items else selected).toReceiptItemsConCombos()
            }
            splitTypeValue == "EQUALPARTS" || splitTypeValue == "CUSTOMAMOUNT" -> listOf(
                ReceiptItem(
                    name = "Pago parcial",
                    quantity = 1,
                    unitPrice = baseAmount,
                    totalPrice = baseAmount,
                ),
            )
            else -> cart.items.toReceiptItemsConCombos()
        }

        val isFullPayment = splitTypeValue == "FULLPAYMENT"
        // 🔴 Lo cobrado manda (IVA por producto, plan 7a; Codex r1 #3): antes el subtotal y el descuento salían del carrito y
        // el TOTAL del servidor, así que una promoción que el servidor redondeó distinto ($99 contra $98.99) imprimía un papel
        // que no cuadraba. Ahora el descuento es lo que falta del bruto a lo cobrado: absorbe promociones y el premio de
        // cartilla que confirmó el servidor. Un pago parcial imprime sólo lo cobrado (nunca prorratea descuento ni IVA).
        // Sólo la captura de la cuenta garantizada, sin abonos y en cobro completo, separa el cargo.
        // El carrito de mesa trae una línea de importe con el cargo dentro; ningún split lo prorratea.
        //
        // D4 (mesas40, ronda 2 de Codex): los PLATILLOS de la cuenta no se imprimen aquí. Otra caja pudo cambiar la cuenta
        // con el mismo importe (cortesía de un platillo + otro nuevo) y el cobro no devuelve la versión de la cuenta: nada en
        // el aparato prueba que una foto tomada al tocar «Pagar» siga vigente. Sin esa evidencia, «Cuenta Mesa N».
        val cargoDeLaMesa = mesaDelCobro
            ?.takeIf { !it.isProvisional && it.orderId == createdOrderId && isFullPayment && splitBaseAmountOverride == null && cart != null &&
                cart.items.singleOrNull()?.let { item -> item.type == com.avoqado.pos.pos.data.model.CartItemType.CustomAmount && item.quantity == 1 } == true &&
                cart.taxCents == 0 && cart.subtotalCents == it.totalCents }
            ?.serviceChargeCents?.takeIf { it > 0 && it <= (cart?.subtotalCents ?: 0) && it <= receiptTotal - currentTipCents } ?: 0
        val papel = com.avoqado.pos.printing.data.model.TotalesDelTicket.desdeLoCobrado(
            cobrado = receiptTotal - currentTipCents,
            propina = currentTipCents,
            subtotalBruto = when {
                cart != null && isFullPayment -> cart.subtotalCents
                // D8 (27-oct): lo elegido a precio de lista; el papel imprime su descuento (bruto − cobrado) y cuadra.
                cart != null && splitTypeValue == "BYPRODUCT" -> maxOf(brutoDeLaSeleccion(cart), baseAmount)
                else -> baseAmount
            } - cargoDeLaMesa,
            cargo = cargoDeLaMesa,
            impuestoAgregado = if (cart != null && isFullPayment) cart.taxCents else 0,
        )
        val renglones = if (cargoDeLaMesa > 0) receiptItems.map {
            it.copy(unitPrice = it.unitPrice - cargoDeLaMesa, totalPrice = it.totalPrice - cargoDeLaMesa)
        } else receiptItems
        val resolvedChange = changeCents ?: successState?.changeAmount

        // Folio real del backend; solo si falta cae a los últimos 4 del id (mejor
        // algo que "---", pero lo normal es el folio).
        val folio = createdOrderNumber?.takeIf { it.isNotBlank() }
            ?: createdOrderId?.takeLast(4)
            ?: "---"
        // URL del recibo digital para el QR (misma que la pantalla del cliente).
        // La del backend si vino; si no, se arma contra el DASHBOARD — nunca contra
        // la base del API, que es lo que mandaba a la página vieja sin facturación.
        val receiptUrl = com.avoqado.pos.core.data.network.resolveReceiptUrl(
            lastReceiptUrl,
            lastReceiptAccessKey,
        )
        return ReceiptData(
            orderNumber = folio,
            // D4: «Mesa N» en el cobro de una mesa; si no, el cumplimiento del carrito con las etiquetas de la cuenta.
            orderType = com.avoqado.pos.tables.presentation.etiquetaDelTipoDeOrden(mesaDelCobro?.tableNumber, cart?.orderType),
            items = renglones,
            subtotal = papel.subtotal,
            taxAmount = papel.impuesto,
            tipAmount = if (currentTipCents > 0) currentTipCents else null,
            discountAmount = papel.descuento.takeIf { it > 0 },
            total = papel.total,
            serviceChargeAmount = papel.cargo.takeIf { it > 0 },
            paymentMethod = manualMethodLabel ?: when (resolvedMethod) {
                PaymentMethod.CASH -> "Efectivo"
                PaymentMethod.CARD -> "Tarjeta"
                null -> null
            },
            cardLastFour = if (resolvedMethod == PaymentMethod.CARD && manualMethodLabel == null) lastCardLastFour else null,
            cardBrand = if (resolvedMethod == PaymentMethod.CARD && manualMethodLabel == null) lastCardBrand else null,
            venueName = nombreDelNegocioDelCobro,
            // 🔴 «Atendió: X» = el vendedor elegido en «Vendiendo» cuando lo hay, con
            // respaldo a quien inició sesión — `cart.selectedStaffName` YA resuelve esa
            // precedencia (`CartViewModel.defaultCartState`). Sin esto, el bloque `staff`
            // de la receta imprimía vacío en Android aunque el diseñador lo ofreciera y el
            // iPad SÍ lo mostrara para la misma venta (revisión de conjunto, I1).
            //
            // D4 (mesas40, founder 14-oct, precedente Toast/Square): en el cobro de una MESA, «Atendió» = el mesero dueño
            // de la cuenta aunque cobre otra persona (el pago sigue a nombre de quien cobra, `selectedStaffId`). Sin mesero,
            // o en mostrador sin vendedor elegido, la sesión. Nunca el literal «Staff» del carrito sin vendedor.
            cashierName = if (mesaDelCobro != null) {
                mesaDelCobro?.meseroNombre ?: nombreDeLaSesionDelCobro
            } else {
                cart?.selectedStaffName?.takeIf { cart.selectedStaffId.isNotBlank() } ?: nombreDeLaSesionDelCobro
            },
            customerName = _attachedCustomerName.value,
            // 🔴 EL BILLETE REAL QUE ENTREGÓ EL CLIENTE, no una resta al revés.
            //
            // Esto deducía el recibido como `total + cambio`, así que con el cambio
            // en 0 imprimía el TOTAL como si fuera lo que el cliente puso sobre el
            // mostrador — el defecto que se vio en el ticket de Testarudo. El dato
            // verdadero ya lo tenía la app desde que el cajero tocó el monto
            // (`processCashPayment`), sólo que nadie lo leía al armar el recibo.
            //
            // Invariante que hace que el ticket cuadre consigo mismo:
            //     Recibido − Cambio = TOTAL
            // porque `finalChange` se calcula como `recibido − total cobrado`, y ese
            // mismo total cobrado es el que viaja en `Success.totalAmount`.
            cashTendered = if (resolvedMethod == PaymentMethod.CASH && manualMethodLabel == null) lastCashTenderedCents else null,
            changeAmount = resolvedChange,
            transactionId = lastPaymentId,
            receiptUrl = receiptUrl,
            areaDeliveryCode = lastAreaDeliveryCode,
        )
    }

    private fun buildOrderRequest(cart: CartState): CreateOrderRequest {
        // Keep full cart context here. OrderRepository strips non-product lines
        // when building the backend /orders payload, but mixed carts still need
        // full totals locally for payment and offline queue handling.
        //
        // El mapeo vive en `buildOrderItemRequests` porque "pagar después"
        // (CartViewModel.createPayLaterOrder) tiene que producir EXACTAMENTE lo
        // mismo. Ahí es donde el combo se colapsa en una línea con promotionRef.
        val items = buildOrderItemRequests(cart.items)

        return CreateOrderRequest(
            items = items,
            subtotal = cart.subtotalCents,
            // 🔴 SÓLO el descuento de ORDEN, nunca `discountCents` (que ya incluye
            // los de línea). El server calcula los de línea POR SU CUENTA desde el
            // `discountId` de cada item y los suma a éste:
            // `discountDecimal = itemDiscountTotal + orderLevelDiscount`
            // (`order.mobile.service.ts`). Mandar el combinado los restaría DOS
            // veces y la orden saldría más barata de lo que se cobró.
            discount = cart.orderDiscountCents,
            tip = currentTipCents,
            total = cart.totalCents + currentTipCents,
            paymentMethod = selectedMethod?.value ?: "CARD",
            rating = currentRating,
            note = cart.orderNote,
            splitType = _splitType.value,
            reservationId = cart.reservationId,
        )
    }

    /**
     * 🔴 DINERO. Guarda el saldo que el server le puso a la orden tras ESTE
     * cobro. Sólo lo pisa cuando de verdad vino un número: un `null` (server
     * viejo, camino de tarjeta, cobro encolado sin red) deja lo que hubiera y
     * el cliente se queda con su aritmética local.
     */
    private fun adoptarSaldoDelServer(remainingBalanceCents: Int?, orderPaymentStatus: String?) {
        // La orden ya está cerrada: no queda nada por cobrar, diga lo que diga
        // el número. Cobrarle más la rechazaría y el cajero quedaría con una
        // venta que no cierra.
        //
        // 🔴 `uppercase()` no es adorno: `orderPaymentStatus` es un String libre y
        // la normalización vive en el extractor, así que un `"paid"` armado por
        // otro camino (cola offline, un mock) haría fallar el guard EN SILENCIO.
        // Esta clase de bug ya se vio 3 veces en el workspace — ver la memoria
        // `serial-case-sensitivity-bug-class`.
        if (orderPaymentStatus?.uppercase() == "PAID") {
            serverRemainingBalanceCents = 0
            return
        }
        val saldo = (remainingBalanceCents ?: return).coerceAtLeast(0)

        // 🔴 EL MONTO NO DISTINGUE; SÓLO EL ESTADO. El mismo "1 centavo" son dos
        // cosas opuestas, y la aritmética del server lo demuestra:
        //
        //   50.00 − 49.99 = 0.00999999999999801  → PAID     → redondea a 1¢
        //   35.70 − 35.69 = 0.010000000000005116 → PARTIAL  → redondea a 1¢
        //
        // Por eso el perdón del residuo SÓLO aplica con un server VIEJO, que no
        // manda estado y donde no hay forma de distinguirlos. Si el server dijo
        // PARTIAL, ese centavo es deuda REAL: perdonarlo cerraría el carrito
        // dejando la orden abierta, **con el stock sin descontar** — el mismo bug
        // que esta tarea existe para cerrar, a escala de un centavo. Y sería
        // regresión: hoy la aritmética local deja "Saldo pendiente $0.01" y el
        // cajero lo cobra, que es justo lo que cierra la orden.
        val serverViejoSinEstado = orderPaymentStatus == null
        serverRemainingBalanceCents = if (serverViejoSinEstado && saldo <= 1) 0 else saldo
        Log.d("💵", "Saldo del server tras el cobro: $saldo centavos (estado: $orderPaymentStatus)")
    }

    fun buildCompletion(): PaymentCompletion {
        ultimoCobroConSaldo?.let { r -> return PaymentCompletion(splitType = "FULLPAYMENT",
            remainingBalanceCents = r.orderRemaining.toString().moneyToCents(), orderId = r.orderId, conSaldo = true) }
        val cart = cartState
        val splitTypeValue = _splitType.value
        if (cart == null) {
            return PaymentCompletion(
                splitType = splitTypeValue,
                remainingBalanceCents = 0,
            )
        }

        return when (splitTypeValue) {
            "BYPRODUCT" -> {
                val validPaidIds = splitSelectedItemIds.intersect(cart.items.map { it.id }.toSet())
                // D10: sin el saldo del SERVIDOR, lo que falta es el saldo local menos esta parte (no los precios de lista).
                val remaining = (cart.totalCents - currentBaseAmount()).coerceAtLeast(0)
                PaymentCompletion(
                    splitType = splitTypeValue,
                    // 🔴 El saldo del server gana; los artículos pagados NO son
                    // suyos —eso lo eligió el cajero— y siguen saliendo de aquí.
                    remainingBalanceCents = serverRemainingBalanceCents ?: remaining,
                    paidItemIds = validPaidIds,
                    orderId = createdOrderId,
                    premioConfirmadoCents = premioConfirmadoCents,
                )
            }
            "EQUALPARTS", "CUSTOMAMOUNT" -> {
                // 🔴 DINERO. Con promoción, lo que vale la venta lo dice el
                // server, no el estimado del carrito. Si el resto se calculara
                // del estimado, la suma de las partes quedaría hasta ±11¢ del
                // total de la orden: el cliente paga otra cosa y la cuenta no
                // cierra. La ÚLTIMA parte absorbe la diferencia.
                //
                // El total del server viene CON la propina de la parte que ya se
                // cobró; el resto se mide sin ella, igual que `currentBaseAmount`.
                val totalDeLaVenta = serverOrderTotalCents
                    ?.let { (it - currentTipCents).coerceAtLeast(0) }
                    ?: cart.totalCents
                val remaining = (totalDeLaVenta - currentBaseAmount()).coerceAtLeast(0)
                PaymentCompletion(
                    splitType = splitTypeValue,
                    // 🔴 Cuando el server dice cuánto queda, ÉSE gana: es el
                    // único que ve los pagos que este aparato no vio (otra caja,
                    // un link, un abono anterior). La resta local es el respaldo.
                    remainingBalanceCents = serverRemainingBalanceCents ?: remaining,
                    orderId = createdOrderId,
                    premioConfirmadoCents = premioConfirmadoCents,
                )
            }
            else -> {
                // 🔴 PAGO COMPLETO: el saldo del server NO manda aquí, A PROPÓSITO.
                // Es el camino de más tráfico del mostrador y preferirlo rompía dos
                // cosas: (1) revierte la decisión deliberada de cobrar el estimado
                // local cuando el efectivo no alcanza para el total del server
                // ("en vez de dejar al cajero pidiendo centavos de vuelta", ver
                // `processCashPayment`), y (2) un residuo desviaría el commit a la
                // rama de saldo pendiente, donde `pendingPackGrant` NO se otorga —
                // un cliente pagaría $500 de membresía y no recibiría un solo
                // crédito, sin forma de recuperarlos.
                //
                // Queda vivo el caso preexistente "pago completo con centavos de
                // diferencia ⇒ orden PARTIAL": no lo introdujo este fix y cambiar
                // lo que el cajero hace todos los días es decisión del founder.
                PaymentCompletion(
                    splitType = splitTypeValue,
                    // D7 (27-oct): la ÚNICA excepción es el resto de una venta YA partida en tienda: ahí no hay paquetes ni
                    // efectivo corto que proteger, y si el SERVIDOR dice que aún se debe (otra caja abonó, la terminal quitó
                    // un descuento), cerrar la venta escondía esa deuda. `adoptarSaldoDelServer` ya pone 0 si quedó PAGADA.
                    remainingBalanceCents = if (continuaVentaPartida) serverRemainingBalanceCents?.coerceAtLeast(0) ?: 0 else 0,
                    orderId = createdOrderId,
                    premioConfirmadoCents = premioConfirmadoCents,
                )
            }
        }.copy(
            carritoGuardadoDelCobro = cart.carritoGuardadoId,
            identidadDeVenta = cart.identidadDeVenta,
            parteEnCola = ((_state.value as? PaymentFlowState.Success)?.colaDelCobro as? com.avoqado.pos.payment.data.model.ColaDelCobro.Pagos)?.id,
            saldoDelServidor = serverRemainingBalanceCents != null,
            cambiosDePrecio = cambiosDeEstaVenta,
            descuentoDeCuentaCongelado = cart.descuentoDeCuentaCongelado,
        )
    }

    /**
     * Entrega el resultado económico una sola vez, en cuanto el pago quedó confirmado.
     * La pantalla de recibo puede permanecer abierta para imprimir o enviar el comprobante,
     * pero cerrar esa pantalla ya no decide si el carrito sigue siendo cobrable.
     */
    fun consumeCompletion(): PaymentCompletion? {
        if (_state.value !is PaymentFlowState.Success || completionConsumed) return null
        completionConsumed = true
        return buildCompletion()
    }

    fun buildPaymentContext(): PaymentContext {
        val cart = cartState ?: return PaymentContext(
            subtotalCents = currentBaseAmount(),
            tipCents = currentTipCents,
            totalCents = currentBaseAmount() + currentTipCents,
            rating = currentRating,
            splitType = _splitType.value,
        )

        val splitTypeValue = _splitType.value
        val baseAmount = currentBaseAmount()
        val visibleItems = visiblePaymentItems(cart)

        return when (splitTypeValue) {
            "FULLPAYMENT" -> PaymentContext(
                subtotalCents = cart.subtotalCents,
                discountCents = cart.discountCents + premioEnLaCuenta(cart),
                taxCents = cart.taxCents,
                tipCents = currentTipCents,
                totalCents = cart.totalCents + cart.stampRewardCents - premioEnLaCuenta(cart) + currentTipCents,
                rating = currentRating,
                items = visibleItems,
                splitType = splitTypeValue,
            )
            // D8 (27-oct): renglones a su precio, la línea «Descuento» y lo cobrado cuadran (bruto − cobrado = descuento).
            "BYPRODUCT" -> {
                val bruto = maxOf(brutoDeLaSeleccion(cart), baseAmount)
                PaymentContext(
                    subtotalCents = bruto,
                    discountCents = bruto - baseAmount,
                    taxCents = 0,
                    tipCents = currentTipCents,
                    totalCents = baseAmount + currentTipCents,
                    rating = currentRating,
                    items = visibleItems,
                    splitType = splitTypeValue,
                )
            }
            else -> PaymentContext(
                subtotalCents = baseAmount,
                discountCents = 0,
                taxCents = 0,
                tipCents = currentTipCents,
                totalCents = baseAmount + currentTipCents,
                rating = currentRating,
                items = visibleItems,
                splitType = splitTypeValue,
            )
        }
    }

    private fun currentBaseAmount(): Int {
        // El total del server llega CON propina (se la mandamos al crear la
        // orden); la base es lo que queda al quitársela.
        serverTotalOverrideCents?.let { return (it - currentTipCents).coerceAtLeast(0) }
        return splitBaseAmountOverride ?: (cartState?.totalCents ?: 0)
    }

    /** Se cobra la cuenta ENTERA, sin dividir: sólo entonces se adopta el total del servidor. */
    private fun esPagoCompleto(): Boolean = _splitType.value == "FULLPAYMENT" && splitBaseAmountOverride == null

    /**
     * Fija el total que se va a cobrar cuando el server acaba de crear la orden
     * y devuelve ese total. Ver `totalACobrarCents` para el porqué y para las
     * cuatro condiciones que tiene que cumplir para adoptarse.
     */
    private fun adoptarTotalDelServer(
        orderRequest: CreateOrderRequest,
        response: CreateOrderResponse,
        estimadoLocal: Int,
    ): Int {
        // 🔴 Premio de cartilla: el carrito ya restó un ESTIMADO; aquí manda lo que el servidor
        // CONFIRMÓ. Va antes que la promoción porque el total del servidor tras el canje no es
        // de fiar todavía (pierde descuento de cuenta y propina). Ver `cobroConPremio`.
        val carrito = cartState
        if (carrito?.pendingStampRewardId != null) {
            val cobro = cobroConPremio(estimadoLocal, carrito.stampRewardCents, response.data?.stampReward)
            _avisoDelPremio.value = cobro.aviso
            val confirmado = response.data?.stampReward?.discountCents ?: 0
            premioConfirmadoCents = confirmado
            // Lo que vale la venta ENTERA (con propina, como el total del servidor): contra esto se mide el
            // resto de un pago dividido. `estimadoLocal` es sólo la parte que se cobra ahora.
            serverOrderTotalCents = (carrito.totalCents + carrito.stampRewardCents - confirmado + currentTipCents).coerceAtLeast(0)
            // En pago dividido la parte la eligió el cajero y no se toca: el resto sale del saldo
            // que devuelve el servidor, que ya trae el premio.
            if (!esPagoCompleto()) return estimadoLocal
            if (cobro.totalCents != estimadoLocal) {
                serverTotalOverrideCents = cobro.totalCents
                Log.d("🎁", "Premio confirmado: se cobra ${cobro.totalCents} (el carrito estimaba $estimadoLocal)")
            }
            return cobro.totalCents
        }
        val laVentaLlevaPromocion = orderRequest.items.any { it.promotionRef != null }
        // Se GUARDA aunque no se adopte. En pago dividido el importe de ESTA
        // parte lo eligió el cajero y no se toca, pero el RESTO tiene que salir
        // del total real o la suma de las partes no es lo que vale la venta.
        if (laVentaLlevaPromocion) {
            serverOrderTotalCents = response.data?.totalCents?.takeIf { it >= 0 }
        }
        val total = totalACobrarCents(
            estimadoLocalCents = estimadoLocal,
            orden = response.data,
            esPagoCompleto = esPagoCompleto(),
            laVentaLlevaPromocion = laVentaLlevaPromocion,
        )
        if (total != estimadoLocal) {
            serverTotalOverrideCents = total
            Log.d("🎁", "Total del server $total ≠ estimado del carrito $estimadoLocal — se cobra el del server")
        }
        return total
    }

    /**
     * La base del porcentaje de propina es el TOTAL de la cuenta, con el IVA que ya trae el precio (founder, 18-oct-2026,
     * tras comparar con el mercado: en México la propina se calcula sobre el total, y las terminales de cobro —Mercado
     * Pago, Netpay— sólo ven el total). El 18-sep se había decidido «sin IVA», pero esta función sólo resta el impuesto
     * cobrado APARTE (`cart.taxCents` = `orderTaxPercent`, que hoy nadie fija): con el precio con IVA incluido nunca lo
     * quitó. Se conserva esa resta para el impuesto aparte; no extraer el IVA incluido sin una decisión nueva.
     *
     * Antes esto lo decidia el ajuste `includeTaxInTipBase`, que se retiro por dos razones: era
     * una convencion fiscal de EE.UU. —alla el impuesto se suma aparte y el cliente lo ve; aca el
     * precio en pantalla ya lo incluye, y Fudo, el POS nativo de LatAm, ni siquiera ofrece esa
     * opcion— y se guardaba solo en la memoria del aparato, asi que dos cajas del mismo negocio
     * podian sugerir propinas distintas sin que el dashboard lo viera.
     *
     * 🔴 No volver a ramificar esto por un ajuste sin decidir antes DONDE vive: es dinero del
     * personal, y un ajuste por aparato no puede gobernarlo. Candado en `PaymentFlowViewModelTest`.
     */
    private fun computeTipPercentageBaseAmount(baseAmount: Int): Int {
        if (baseAmount <= 0) return 0
        val cart = cartState ?: return baseAmount
        val taxComponent = estimateTaxComponentForTipBase(cart, baseAmount)
        return (baseAmount - taxComponent).coerceAtLeast(0)
    }

    private fun estimateTaxComponentForTipBase(cart: CartState, baseAmount: Int): Int {
        if (cart.taxCents <= 0 || baseAmount <= 0) return 0
        return when (_splitType.value) {
            "FULLPAYMENT" -> {
                cart.taxCents.coerceAtMost(baseAmount)
            }
            "BYPRODUCT" -> {
                // BYPRODUCT split amount currently comes from selected item totals,
                // which are pre-tax values; avoid subtracting tax twice.
                0
            }
            else -> {
                val cartTotal = cart.totalCents
                if (cartTotal <= 0) return 0
                ((cart.taxCents.toLong() * baseAmount.toLong()) / cartTotal.toLong())
                    .toInt()
                    .coerceAtMost(baseAmount)
            }
        }
    }

    private fun hasProductItems(cart: CartState): Boolean {
        return cart.items.any { it.type is CartItemType.ProductItem }
    }

    /** Importe sin productos cobrado EN PARTES: la parte 1 crea su orden (misma regla que `OrderRepository.aceptaCrearOrden`). */
    private fun esVentaRapidaDividida(cart: CartState?, split: String): Boolean =
        cart != null && cart.items.isNotEmpty() && split != "FULLPAYMENT"

    /** Esta apertura del cobro: una orden de vales armada sólo se reusa dentro de ella (regla 4, `AreaTicketSession.cobroQueLaArmo`). */
    private var cobroDeValesId: String = java.util.UUID.randomUUID().toString()

    /** Regla 9: el apartado de vales de ESTE cobro (se fija al abrir y al armar). */
    private var sesionDelCobro: String? = null

    /** 🔴 Codex r14 B2: la sesión de vales de ESTE cobro y su llave de materializar, fotografiadas antes de la primera espera. */
    private class ValesDelCobro(val checkout: AreaTicketCheckout, val llave: String, val cobroId: String,
        val ordenQueContinua: String?, val cobroQueLaArmo: String?)

    private fun valesDelCobro(): ValesDelCobro? = areaTicketRepository.session.current()?.takeIf { esCobroDeVales() }
        ?.let { ValesDelCobro(it, areaTicketRepository.session.materializeIdempotencyKey(), cobroDeValesId,
            createdOrderId, areaTicketRepository.session.cobroQueLaArmo) }

    private suspend fun materializeAreaTicketCheckout(
        cart: CartState,
        /**
         * 🔴 Codex r9 #5 (familia B): la venta de este cobro, fotografiada antes de la primera suspensión. Si otra venta empezó
         * mientras se materializaba, la orden, el folio y el error de los vales no se escriben en ella.
         */
        venta: Int,
        /** Codex r14 B2: se materializa ESTE checkout, nunca la sesión vigente al volver de esperar (puede ser la de otra venta). */
        vales: ValesDelCobro,
        venue: String,
    ): String? /* la orden de ESTOS vales (null = no se cobra); corrección del agregado 4: la devuelve aunque ya haya otra venta */ {
        // La orden que este cobro continúa (ya se cobró una parte). Si es la MISMA que arman los vales, lo que falta lo dice el
        // servidor y el carrito ya no lleva todos los vales; si es otra, se compara como cualquier cobro (Codex r7).
        val ordenQueContinua = vales.ordenQueContinua
        // Cancelar conserva esta apertura; abrir otra la cambia: una respuesta de A nunca limpia ni pinta sobre B.
        val cobroCapturado = vales.cobroId
        val generacion = paymentGeneration
        return try {
            val normalItems = cart.items
                .filter { !it.locked }
                .mapNotNull { item ->
                    // 🔴 DINERO (Codex r4 #7, founder «arréglalo de una vez»): un importe tecleado junto con los vales se quedaba
                    // fuera de la orden y se perdía al cobrar. Viaja como en una venta normal (nombre + precio en centavos); iOS
                    // ya lo mandaba (`areaTicketNormalItemsPayload`).
                    if (item.type is CartItemType.CustomAmount && item.areaTicketLineId == null) {
                        return@mapNotNull NormalCheckoutItem(
                            productId = null,
                            name = item.name,
                            // El mismo precio que la venta normal (`toOrderItemRequest`), y una cortesía vale 0 (Codex r5 #1).
                            unitPrice = if (item.isCortesia) 0 else item.effectiveUnitPrice,
                            quantity = item.quantity,
                            notes = item.itemNote,
                            discountId = item.itemDiscountId,
                        )
                    }
                    val productId = (item.type as? CartItemType.ProductItem)?.productId ?: return@mapNotNull null
                    NormalCheckoutItem(
                        productId = productId,
                        quantity = item.quantity,
                        notes = item.itemNote,
                        modifierIds = item.selectedModifiers.map { it.modifierId },
                        discountId = item.itemDiscountId,
                        weightQuantity = item.weightKg,
                    )
                }
            val checkout = areaTicketRepository.materialize(
                normalItems = normalItems,
                customerName = null,
                note = cart.orderNote,
                cobroId = cobroCapturado,
                objetivo = vales.checkout,
                llave = vales.llave,
                negocio = venue.takeIf { it.isNotBlank() },
                cobroQueLaArmo = vales.cobroQueLaArmo,
            )
            if (!otraVenta(venta)) sesionDelCobro = checkout.id
            if (checkout.status in setOf("PAYMENT_PENDING", "RECONCILIATION_REQUIRED")) {
                if (!otraVenta(venta) && generacion == paymentGeneration) {
                    _state.value = PaymentFlowState.Error(
                        message = "Este cobro sigue en confirmación. No vuelvas a cobrar; revisa el estado del pago.",
                        source = PaymentErrorSource.SERVER,
                    )
                }
                return null
            }
            val order = checkout.order
            if (order == null) {
                if (!otraVenta(venta) && generacion == paymentGeneration) {
                    _state.value = PaymentFlowState.Error(
                        message = "No se pudo materializar la orden de los vales.",
                        source = PaymentErrorSource.SERVER,
                    )
                }
                null
            } else if (order.id != ordenQueContinua && checkout.tieneAbono()) {
                if (!otraVenta(venta) && generacion == paymentGeneration) {
                    _state.value = PaymentFlowState.Error(message = VALES_CON_ABONO, source = PaymentErrorSource.SERVER)
                }
                null
            } else if (order.id != ordenQueContinua && valesNoCoinciden(cart, checkout, venue)) {
                // No traen los mismos vales: no se cobra (la sesión sigue viva). Releer la sesión suspende: sólo la venta en pantalla sale.
                if (!otraVenta(venta) && generacion == paymentGeneration) {
                    // Nada que cancelar: la sesión sigue viva con los vales del servidor y el carrito los toma al volver.
                    paymentGeneration++
                    isProcessingPayment = false
                    _valesNoCoincidian.value = true
                    salirDelFlujo()
                }
                null
            } else {
                // La orden sólo es de la venta en pantalla si sigue siendo ésta; el cobro de ESTOS vales sigue con su orden igual.
                if (!otraVenta(venta)) {
                    createdOrderId = order.id
                    createdOrderNumber = order.orderNumber
                    lastAreaDeliveryCode = order.areaDeliveryCode
                }
                order.id
            }
        } catch (error: Exception) {
            // Una sesión vencida no es un cobro fallido: no se cobró nada y los vales
            // siguen vivos. Tirar aquí la sesión muerta es lo que permite que el
            // siguiente escaneo abra una nueva; si la dejáramos, la caja se quedaría
            // pulsando Cobrar contra algo que el server ya no acepta.
            if (!otraVenta(venta) && generacion != paymentGeneration) {
                cancelarLoCreadoTrasCancelar(orderId = null)
                return null
            }
            // STALE también puede ser una revisión vieja: se relee antes de olvidar un apartado con dinero.
            if ((error as? com.avoqado.pos.areatickets.data.AreaTicketException)?.code == "CHECKOUT_SESSION_STALE" &&
                areaTicketRepository.session.current()?.id == vales.checkout.id
            ) {
                runCatching { areaTicketRepository.refresh(objetivo = vales.checkout, negocio = venue) }
                if (!otraVenta(venta) && generacion == paymentGeneration) runCatching { areaTicketRepository.restore() }
            }
            if (!otraVenta(venta) && generacion == paymentGeneration) {
                _state.value = PaymentFlowState.Error(
                    message = error.message ?: "No se pudo preparar la venta de vales.",
                    source = PaymentErrorSource.SERVER,
                )
            }
            null
        }
    }

    /**
     * 🔴 DINERO. El importe de este cobro salió del carrito; la orden, de la sesión de vales del servidor. Si no traen los
     * mismos vales (una respuesta perdida al escanear, o la pantalla que se fue a media consulta), se cobraría otra cosa, y en
     * pago completo el resto ni se pediría (pendientes del 19-oct, Codex r5 y r6). iOS no lo necesita: allá el importe sale de
     * la orden materializada. Antes de detener se relee la sesión: la copia del aparato puede ser vieja, y con un abono ya
     * hecho la venta sigue por el saldo del servidor, como siempre (el carrito ya no lleva todos los vales).
     */
    private suspend fun valesNoCoinciden(cart: CartState, checkout: AreaTicketCheckout, venue: String): Boolean {
        if (cobraLosValesDeLaSesion(cart.items, checkout)) return false
        // Codex r14 B2: se relee ESE checkout, no la sesión vigente.
        val alDia = areaTicketRepository.refresh(objetivo = checkout, negocio = venue.takeIf { it.isNotBlank() }) ?: return true
        if ((alDia.order?.paidAmount?.moneyToCents() ?: 0) > 0) return false
        return !cobraLosValesDeLaSesion(cart.items, alDia)
    }

    /**
     * Regla 9 (Codex r6 #5): termina el apartado de ESTE cobro, nunca el vigente (si un latido ya cerró este y el cajero abrió
     * otra venta antes de que llegara la respuesta del pago, borraba la otra). Un pago completo con saldo según el servidor
     * no se da por terminado: se relee y la venta sigue en el aviso «Cobrar saldo».
     */
    private fun finishAreaTicketPayment() {
        val id = sesionDelCobro ?: return
        if (_splitType.value == "FULLPAYMENT" && (serverRemainingBalanceCents ?: 0) <= 0) {
            areaTicketRepository.session.clearIf(id)
        } else {
            // Quedó saldo: la copia del apartado lo sabe desde AQUÍ, aunque el carrito ya sea otra venta (la pantalla no lo
            // marca entonces) y aunque falle la relectura: así el aviso «Cobrar saldo» sale con el saldo real (Codex r7 #1).
            val saldo = serverRemainingBalanceCents
            val orden = areaTicketRepository.session.current()?.takeIf { it.id == id }?.order?.id
            if (orden != null && saldo != null && saldo > 0) areaTicketRepository.session.marcarConAbono(orden, saldo)
            viewModelScope.launch {
                if (areaTicketRepository.session.current()?.id != id) return@launch
                runCatching { areaTicketRepository.refresh() }
                    .onFailure { Log.e("🎟️", "No se pudo refrescar el saldo de la sesión: ${it.message}") }
            }
        }
    }

    private fun visiblePaymentItems(cart: CartState): List<PaymentItem> {
        val sourceItems = when (_splitType.value) {
            "BYPRODUCT" -> {
                val selected = cart.items.filter { splitSelectedItemIds.contains(it.id) }
                if (selected.isEmpty()) cart.items else selected
            }
            else -> cart.items
        }

        return sourceItems.map { item ->
            val modifierNames = item.selectedModifiers.map { it.modifierName }
            PaymentItem(
                name = item.name,
                quantity = item.quantity,
                unitPrice = item.effectiveUnitPrice + item.selectedModifiers.sumOf { it.priceInCents },
                // BRUTO, igual que en el ticket: esta lista se pinta JUNTO al
                // desglose (Subtotal / Descuento) en la pantalla de cobro. Con la
                // línea rebajada, los renglones sumaban menos que el subtotal y
                // parecía que el descuento se aplicaba dos veces.
                lineTotal = item.grossPrice,
                modifiers = modifierNames,
                note = item.itemNote,
                isCortesia = item.isCortesia,
            )
        }
    }

    /** D4: lo elegido más todas las piezas de sus combos (`promotionInstanceId`): un combo se paga completo o no se paga. */
    private fun conCombosCompletos(items: List<CartItem>, elegidos: Set<String>): Set<String> {
        if (elegidos.isEmpty()) return elegidos
        val combos = items.filter { it.id in elegidos }.mapNotNull { it.promotionInstanceId }.toSet()
        if (combos.isEmpty()) return elegidos
        return elegidos + items.filter { it.promotionInstanceId in combos }.map { it.id }
    }

    /**
     * D10: lo que vale cada renglón para repartir: su neto (precio − su descuento propio) y, si es el producto gratis del
     * premio (el más caro, como el SERVIDOR), menos el premio entero — el SERVIDOR lo pone sólo en ese renglón.
     */
    private fun pesosPorRenglon(cart: CartState): Map<String, Long> {
        val premioDirigido = cart.pendingStampReward?.takeIf { cart.premioAplica && it.tipo?.uppercase() == "FREE_PRODUCT" }
            ?.let { cart.stampRewardCents } ?: 0
        val premiado = if (premioDirigido > 0) com.avoqado.pos.loyalty.data.renglonDelProductoGratis(cart.items)?.first else null
        return cart.items.associate { it.id to (it.totalPrice - if (it.id == premiado) premioDirigido else 0).coerceAtLeast(0).toLong() }
    }

    private fun parteDeLaSeleccion(cart: CartState): com.avoqado.pos.payment.domain.ParteProporcional {
        val pesos = pesosPorRenglon(cart)
        return com.avoqado.pos.payment.domain.parteProporcional(
            pesosElegidos = pesos.filterKeys { it in splitSelectedItemIds }.values.sum(),
            pesosRestantes = pesos.values.sum(),
            saldoCents = cart.totalCents,
        )
    }

    /** D8: el precio de lista (sin ningún descuento) de lo elegido en una parte «por producto». */
    private fun brutoDeLaSeleccion(cart: CartState): Int =
        cart.items.filter { splitSelectedItemIds.contains(it.id) }.sumOf { it.grossPrice }

    private fun resolveSplitBaseAmount(cart: CartState): Int? {
        return when (_splitType.value) {
            // D10 (27-oct, founder «A»): su parte proporcional del saldo, no el precio de lista de sus renglones.
            "BYPRODUCT" -> parteDeLaSeleccion(cart).cents
            "EQUALPARTS" -> {
                val parts = (splitNumberOfParts ?: 1).coerceAtLeast(1)
                (cart.totalCents + parts - 1) / parts
            }
            "CUSTOMAMOUNT" -> {
                (splitCustomAmountCents ?: cart.totalCents).coerceIn(0, cart.totalCents)
            }
            else -> null
        }
    }

    // MARK: - Sellos tardíos: el colector de «Cobro exitoso» (va AL FINAL a propósito)

    /**
     * Sellos tardíos: al llegar a «Cobro exitoso» con el cliente que venía del CARRITO se pregunta si la venta sumó
     * sello. Un solo punto de entrada para los ~10 caminos que terminan en Success.
     * 🔴 Este `init` va DESPUÉS de todas las propiedades de la clase (revisión final, Minor #4): Kotlin inicializa en el
     * orden en que se declara y `viewModelScope` colecta de inmediato (`Main.immediate`); [alLlegarAExito] lee
     * propiedades que antes se declaraban más abajo (`_splitType`, vía `buildCompletion()`). No lo subas.
     */
    init {
        viewModelScope.launch {
            _state.collect { estado -> if (estado is PaymentFlowState.Success) alLlegarAExito(estado) }
        }
    }

    companion object {
        /** Sobrevive a la muerte del proceso junto con el resto del SavedStateHandle. */
        private const val KEY_UNDETERMINED_REQUEST = "undeterminedChargeRequestId"

        /** Sellos tardíos: si la lealtad de la venta aún se procesa (PENDING), se vuelve a preguntar UNA vez, a los 2 s. */
        internal const val REINTENTO_DEL_SELLO_MS = 2_000L

        /**
         * Encabezado de la pantalla de un cobro sin confirmar de OTRA venta, la que abre «Revisar» desde el aviso (25-sep:
         * ese cobro ya no frena la tarjeta). «Otra venta», el mismo vocabulario que el aviso que lleva hasta ahí.
         */
        private const val PREVIOUS_CHARGE_PREFIX = "Quedó un cobro sin confirmar de otra venta."

        /**
         * Encabezado de la misma pantalla cuando el cobro es de ESTA orden pero no se adoptó (otra parte de una cuenta
         * dividida, o sin importe registrado). Decir «de otra venta» ahí sería falso.
         */
        private const val SAME_SALE_CHARGE_PREFIX = "Quedó un cobro sin confirmar de esta venta."

        /**
         * El texto de una revisión se COMPONE del encabezado y del mensaje del momento: antes era una copia literal
         * y, al anteponerse a un desenlace que ya traía esa misma instrucción, el cajero la leía dos veces seguidas.
         */
        private fun conEncabezado(encabezado: String?, mensaje: String): String =
            encabezado?.let { "$it $mensaje" } ?: mensaje

        /** El aviso de un cobro de otra venta se calla a los 10 min desde el cobro: una duda y, desde el 26-sep, un «SÍ pasó». */
        const val VENTANA_AVISO_MS = 10L * 60 * 1000

        const val TARJETA_SIN_RED =
            "Sin conexión: el cobro con tarjeta necesita internet. Cobra en efectivo o espera a que vuelva la red."

        private const val SI_ES_ESTA_VENTA = "Si es esta misma venta, no la cobres otra vez."

        /**
         * «Quedó un cobro de $X sin confirmar hace N min en otra venta. Si es esta misma venta, no la cobres otra vez.»
         * iOS usa el MISMO texto. Sin importe o sin antigüedad conocidos no se inventa ninguno: nunca «$0.00».
         */
        fun textoCobroSinConfirmar(totalCentavos: Long?, desdeMillis: Long?, ahoraMillis: Long): String {
            val cobro = importe(totalCentavos)?.let { "un cobro de $it" } ?: "un cobro"
            val hace = desdeMillis?.let { " ${antiguedad(ahoraMillis - it)}" }.orEmpty()
            return "Quedó $cobro sin confirmar$hace en otra venta. $SI_ES_ESTA_VENTA"
        }

        /** «El cobro de $X de otra venta SÍ pasó. Si es esta misma venta, no la cobres otra vez.» — mismo texto en iOS. */
        fun textoCobroQueSiPaso(totalCentavos: Long?): String {
            val cobro = importe(totalCentavos)?.let { "El cobro de $it" } ?: "El cobro"
            return "$cobro de otra venta SÍ pasó. $SI_ES_ESTA_VENTA"
        }

        /** Un importe que no consta —o un cero, que un cobro con tarjeta no puede tener— no se imprime. */
        private fun importe(centavos: Long?): String? = centavos?.takeIf { it > 0 }?.let { formatMoney(it / 100.0) }

        /** Como en la TPV: «hace unos segundos» (< 1 min), «hace N min» (< 60) y «hace N h». Nunca negativa. */
        private fun antiguedad(millis: Long): String {
            val minutos = millis.coerceAtLeast(0) / 60_000
            return when {
                minutos < 1 -> "hace unos segundos"
                minutos < 60 -> "hace $minutos min"
                else -> "hace ${minutos / 60} h"
            }
        }
    }
}
