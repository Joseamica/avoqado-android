package com.avoqado.pos.customerdisplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que ve el CLIENTE en la pantalla secundaria del POS de mostrador
 * (Sunmi T3 Pro y cualquier equipo de doble pantalla).
 *
 * 🔴 Es un ESPEJO, no una fuente de verdad: refleja el carrito y el flujo de
 * pago que ya existen. Cero matemática de dinero aquí — los montos llegan ya
 * calculados por CartState/PaymentFlowState. Si alguna vez hay que "calcular"
 * algo en este archivo, es señal de que el espejo se torció.
 */
sealed interface CustomerContent {
    /** Sin venta activa: marca del negocio. */
    data object Idle : CustomerContent

    /** Carrito en vivo mientras el cajero teclea. Ver [renglonesParaElCliente]. */
    data class Cart(
        val renglones: List<RenglonCliente>,
        val subtotalCents: Int,
        val descuentos: List<DescuentoCliente>,
        val taxCents: Int,
        val totalCents: Int,
    ) : CustomerContent

    /**
     * "¿Algo más?" — el momento de upsell, ANTES de que se congele el total.
     *
     * 🔴 `selectedRuleIds` marca, NO cobra. Tocar una tarjeta la resalta y actualiza
     * la vista previa del total; sólo el botón Agregar la mete al carrito. Un toque
     * directo que agregara al instante suena mejor hasta que alguien roza la
     * pantalla con la manga y le cobran algo que no pidió.
     *
     * En pantallas NO táctiles (T3 Pro) esto se pinta igual pero sin interacción:
     * es señalización para que el cajero la señale mientras lo ofrece de viva voz.
     */
    data class Upsell(
        val cards: List<com.avoqado.pos.pos.data.model.UpsellCard>,
        /** Total actual del carrito, SIN lo marcado. Nunca se tapa. */
        val cartTotalCents: Int,
        val selectedRuleIds: Set<String> = emptySet(),
    ) : CustomerContent {
        /** Vista previa de lo que sumaría lo marcado. */
        val selectedDeltaCents: Int
            get() = cards.filter { it.ruleId in selectedRuleIds }.sumOf { it.displayPriceCents }
    }

    /** Las estrellas: el cliente califica en SU pantalla. */
    data class Rating(val amountCents: Int) : CustomerContent

    /**
     * Propina: el cliente elige en SU pantalla.
     *
     * 🔴 `options` lleva el monto YA calculado por el ViewModel (la misma base del cajero,
     * `currentTipPercentageBaseCents`): la pantalla sólo pinta el porcentaje y entrega `tipCents`. Antes calculaba
     * aquí `amountCents * percent / 100` con su propia base: dos fórmulas para el mismo dinero, que se separaban
     * cuando el impuesto se cobraba aparte.
     */
    data class Tip(
        val amountCents: Int,
        val options: List<TipOption>,
        val selectedTipCents: Int?,
    ) : CustomerContent

    /** Total final confirmado; el cobro ocurre en la terminal. */
    data class Charging(val totalCents: Int, val message: String) : CustomerContent

    /**
     * Le toca al CAJERO (elegir método, capturar efectivo, escoger terminal).
     * El cliente ve el DESGLOSE tipo recibo (productos + subtotal + descuento +
     * propina + total) y NADA que se pueda tocar: dejar aquí la pantalla
     * anterior significaba dejarle vivos los botones de propina — un segundo
     * toque re-abría la propina con el cajero ya en otra pantalla.
     *
     * `renglones` vacío ⇒ se cae al modo "solo total" en grande (p. ej. montos
     * personalizados sin productos, o una PARTE de una cuenta dividida, donde
     * [etiqueta] es «Tu parte»). La propina se muestra solo si `tipCents>0`.
     */
    data class Total(
        val totalCents: Int,
        val renglones: List<RenglonCliente> = emptyList(),
        val subtotalCents: Int = 0,
        val descuentos: List<DescuentoCliente> = emptyList(),
        val taxCents: Int = 0,
        val tipCents: Int = 0,
        val etiqueta: String = "Total",
    ) : CustomerContent

    /** Gracias + QR del recibo digital. */
    data class Done(val totalCents: Int, val receiptUrl: String?) : CustomerContent
}

/** Dónde se dibuja la pantalla del cliente; viaja en el acuse (`surface`) con estos mismos valores que acepta el server. */
const val SURFACE_PRESENTATION = "presentation"
/** Pantallas invertidas: la ventana del cliente es una Activity en la otra pantalla. */
const val SURFACE_ACTIVITY = "activity"
/** El segundo monitor del POS de Windows. */
const val SURFACE_WINDOW = "window"
/** La cara del cliente es el kiosco: la ficha no se dibuja (el acuse va sin `shownAt`). */
const val SURFACE_KIOSK = "kiosk"

/** Una sugerencia de propina: el porcentaje a mostrar y los centavos que cobra (ya calculados). */
data class TipOption(val percent: Int, val tipCents: Int)

/**
 * El diseño de la pantalla del cliente ya listo para pintar: la ficha y, si trae logo, el archivo local (ya verificado).
 * `logoStamp` = `lastModified` del archivo: un logo legado revalidado con 200 se reescribe en la MISMA ruta, y sin la marca el
 * diseño nuevo sería igual al viejo (el StateFlow no emitiría y se seguiría pintando el bitmap anterior). Un 304 no toca el
 * archivo: misma marca, sin emisión de sobra.
 */
data class AppliedDesign(val ficha: Ficha, val logoFile: File?, val logoStamp: Long = 0L)

/**
 * Canal único hacia la pantalla del cliente. Los ViewModels de pantalla
 * (carrito, flujo de pago) EMPUJAN aquí; la Presentation solo observa.
 * Singleton porque la pantalla secundaria vive fuera del ciclo de vida de
 * cualquier pantalla en particular.
 */
@Singleton
class CustomerDisplayState @Inject constructor() {

    /** Marca del NEGOCIO en reposo: logo si lo hay, si no el nombre. No la de Avoqado. */
    private val _venueName = MutableStateFlow<String?>(null)
    val venueName: StateFlow<String?> = _venueName.asStateFlow()

    private val _venueLogoUrl = MutableStateFlow<String?>(null)
    val venueLogoUrl: StateFlow<String?> = _venueLogoUrl.asStateFlow()

    fun setVenueBranding(name: String?, logoUrl: String?) {
        _venueName.value = name
        _venueLogoUrl.value = logoUrl
    }

    // Auto-regreso del "Gracias" al reposo (screensaver). Sin esto la pantalla
    // se quedaba PEGADA en "Gracias por tu compra" hasta la siguiente venta —
    // el StateFlow del carrito deduplica, así que si ya estaba vacío al pagar,
    // nunca re-emite el Idle. El timeout es la red que lo cierra siempre.
    private val timerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private var revertJob: kotlinx.coroutines.Job? = null

    private val _content = MutableStateFlow<CustomerContent>(CustomerContent.Idle)
    val content: StateFlow<CustomerContent> = _content.asStateFlow()

    /**
     * true cuando hay una pantalla de cliente montada AHORA. Sirve para la UI
     * de ajustes ("¿la detectamos?"); NO basta por sí sola para mover la
     * captura de propina/calificación al cliente — ver customerCapturesInput.
     */
    private val _isPresenting = MutableStateFlow(false)
    val isPresenting: StateFlow<Boolean> = _isPresenting.asStateFlow()

    /**
     * Quién captura propina y calificación. Solo el CLIENTE cuando hay pantalla
     * montada Y el negocio activó la opción en Ajustes.
     *
     * 🔴 Tener doble pantalla NO implica que el cliente esté enfrente: el mismo
     * equipo se usa en mostradores donde la segunda pantalla mira a la pared o
     * al propio cajero. Si diéramos por hecho que sí, el cobro se quedaría
     * esperando un toque que nadie va a dar. Por eso apagado por defecto: se
     * prende cuando el negocio confirma que el cliente alcanza la pantalla.
     */
    private var presenting = false
    private var enabledByUser = false
    private var touchCapable = false

    private val _customerCapturesInput = MutableStateFlow(false)
    val customerCapturesInput: StateFlow<Boolean> = _customerCapturesInput.asStateFlow()

    /**
     * La superficie montada AHORA ([SURFACE_PRESENTATION], [SURFACE_ACTIVITY] o [SURFACE_WINDOW]); `null` = ninguna. La
     * observa el acuse «mostrado» de [CustomerDisplaySync]: dice en qué superficie se dibujó el diseño.
     */
    private val _presentingSurface = MutableStateFlow<String?>(null)
    val presentingSurface: StateFlow<String?> = _presentingSurface.asStateFlow()

    fun setPresenting(value: Boolean, surface: String = SURFACE_PRESENTATION) {
        presenting = value
        _isPresenting.value = value
        _presentingSurface.value = if (value) surface else null
        recompute()
    }

    /** Lo prende/apaga el negocio desde Ajustes → Pantalla del cliente. */
    fun setCustomerCaptureEnabled(value: Boolean) {
        enabledByUser = value
        recompute()
    }

    /**
     * ¿La pantalla del cliente puede RECIBIR toques que lleguen a la app?
     * Detección automática por hardware (la pone el manager). Dos formas de que
     * sea cierto:
     *
     * 1. Pantalla FÍSICA (HDMI del D3, Elo…): recibe sus propios toques.
     * 2. Pantalla virtual **con puente táctil** (T3 Pro y su panel `SUNMI
     *    NP511`): el digitalizador SÍ existe —está medido, es multitáctil— pero
     *    Android no lo asocia a esa pantalla y sus toques caen en la caja. El
     *    puente los identifica por dispositivo, los saca del camino del cajero y
     *    los reenvía traducidos. Ver [CustomerTouchBridge].
     *
     * Sin esto delegábamos propina/calificación a una pantalla que nadie podía
     * tocar y el cobro se quedaba esperando; con esto solo se delega/se muestra
     * teclado donde el dedo sí sirve.
     */
    fun setTouchCapable(value: Boolean) {
        touchCapable = value
        recompute()
    }

    private fun recompute() {
        _customerCapturesInput.value = presenting && enabledByUser && touchCapable
    }

    /**
     * ¿Este equipo admite invertir las pantallas? (segunda pantalla física).
     *
     * 🔴 NO se deduce de [touchCapable], y la tentación de unir las dos
     * condiciones es real: son cosas distintas. El puente táctil hace que el
     * CLIENTE pueda tocar SU pantalla —unos botones grandes, sin teclado—, que es
     * todo lo que necesita propina/calificación. Invertir exige que el CAJERO
     * trabaje ahí: la app entera, campos de texto, teclado en pantalla y el foco
     * de entrada del sistema. Eso el puente no lo resuelve (la ventana del
     * cliente es `FLAG_NOT_FOCUSABLE` justo para no robarle el teclado a la
     * caja), así que `invertible` sigue exigiendo pantalla FÍSICA. Ver
     * `resolveDisplayRoles`.
     */
    private val _invertible = MutableStateFlow(false)
    val invertible: StateFlow<Boolean> = _invertible.asStateFlow()

    /**
     * Hechos físicos observados por el manager en ESTE proceso.
     *
     * `null` no significa "sin pantalla": significa que todavía no se ha
     * enumerado hardware. Conservar ese tercer estado evita anunciar
     * `false/false` durante el arranque y degradar por error un equipo capaz.
     */
    private val _capabilities = MutableStateFlow<DisplayCapabilitySnapshot?>(null)
    val capabilities: StateFlow<DisplayCapabilitySnapshot?> = _capabilities.asStateFlow()

    /** Actualiza el snapshot remoto y la API histórica desde una sola observación. */
    fun updateCapabilities(snapshot: DisplayCapabilitySnapshot) {
        _invertible.value = snapshot.invertible
        // Se publica al coordinator al final: al despertar, la API histórica
        // ya refleja exactamente la misma observación.
        _capabilities.value = snapshot
    }

    /**
     * El equipo NO permitió mover la caja a la otra pantalla (dos intentos
     * fallidos). Solo en memoria: nunca se toca la preferencia del usuario.
     */
    private val _invertUnsupported = MutableStateFlow(false)
    val invertUnsupported: StateFlow<Boolean> = _invertUnsupported.asStateFlow()

    fun setInvertUnsupported(value: Boolean) {
        _invertUnsupported.value = value
    }

    /**
     * El diseño que el negocio configuró (F2). 🔴 Un diseño nuevo NUNCA reemplaza al actual delante del cliente a media
     * venta: [offerDesign] lo aplica ya sólo en reposo (`Idle`); si no, queda pendiente y se promueve en el siguiente paso
     * a `Idle` (ver [promotePendingDesign]). Mismo criterio que la propina y el upsell: lo que ve el cliente no cambia solo.
     */
    private val _design = MutableStateFlow<AppliedDesign?>(null)
    val design: StateFlow<AppliedDesign?> = _design.asStateFlow()

    // `hasPendingDesign` distingue «nada pendiente» de «pendiente: quitar el diseño» (`offerDesign(null)`).
    // ponytail: sin candado; se llama desde Main como el resto de este estado. Si algún día se ofrece desde otro hilo, sincronizar.
    private var pendingDesign: AppliedDesign? = null
    private var hasPendingDesign = false

    /** Ofrece un diseño: ya si el cliente está en reposo; si no, espera (el último ofrecido gana). */
    fun offerDesign(next: AppliedDesign?) {
        pendingDesign = next
        hasPendingDesign = true
        if (_content.value is CustomerContent.Idle) promotePendingDesign()
    }

    /** Quita el diseño YA (también el pendiente): no hay nada que proteger de un diseño que dejó de existir. */
    fun clearDesign() {
        pendingDesign = null
        hasPendingDesign = false
        _design.value = null
    }

    private fun promotePendingDesign() {
        if (!hasPendingDesign) return
        _design.value = pendingDesign
        pendingDesign = null
        hasPendingDesign = false
    }

    /** Callbacks de VUELTA: lo que el cliente toca en su pantalla. */
    var onRatingPicked: ((Int) -> Unit)? = null
    var onTipPicked: ((Int) -> Unit)? = null

    /**
     * Upsell: el cliente marca/desmarca una tarjeta. Sólo cambia la selección — NO
     * agrega nada al carrito.
     */
    var onUpsellToggled: ((String) -> Unit)? = null

    /** El cliente confirmó con "Agregar". AQUÍ sí entra al carrito. */
    var onUpsellConfirmed: (() -> Unit)? = null

    /** "No, gracias". Cierra sin tocar el carrito. */
    var onUpsellDismissed: (() -> Unit)? = null

    /**
     * Refleja en la pantalla del cliente lo que se marcó desde CUALQUIER superficie.
     * El cajero marca en la suya y el cliente lo ve al instante: una sola verdad,
     * dos superficies de entrada — el mismo patrón que propina y calificación.
     */
    fun updateUpsellSelection(selected: Set<String>) {
        val current = _content.value
        if (current is CustomerContent.Upsell) {
            _content.value = current.copy(selectedRuleIds = selected)
        }
    }

    /** El cliente eligió recibir su recibo por WhatsApp / correo (tecleado en SU pantalla). */
    var onWhatsAppSubmit: ((String) -> Unit)? = null
    var onEmailSubmit: ((String) -> Unit)? = null

    /** Feedback del envío del recibo para mostrarlo en la pantalla del cliente. */
    enum class ReceiptSend { Idle, Sending, Sent, Error }
    private val _receiptSend = MutableStateFlow(ReceiptSend.Idle)
    val receiptSend: StateFlow<ReceiptSend> = _receiptSend.asStateFlow()
    fun setReceiptSend(value: ReceiptSend) { _receiptSend.value = value }

    fun show(content: CustomerContent) {
        _content.value = content
        if (content is CustomerContent.Idle) promotePendingDesign()
        // Cualquier cambio cancela el timer previo. Si lo nuevo es "Gracias",
        // se programa el regreso al reposo tras un rato (suficiente para leer el
        // QR del recibo); una nueva venta lo interrumpe antes con su carrito.
        revertJob?.cancel()
        if (content is CustomerContent.Done) {
            // Recibo nuevo: el envío arranca en limpio (ni "enviando" ni "enviado"
            // heredados de la venta anterior).
            _receiptSend.value = ReceiptSend.Idle
            scheduleRevert()
        }
    }

    private fun scheduleRevert() {
        revertJob?.cancel()
        revertJob = timerScope.launch {
            kotlinx.coroutines.delay(DONE_TIMEOUT_MS)
            _content.value = CustomerContent.Idle
            promotePendingDesign()
        }
    }

    /**
     * El cliente sigue interactuando en la pantalla "Gracias" (tecleando su
     * WhatsApp/correo): reinicia el temporizador de regreso al reposo para que NO
     * se le desaparezca a media captura. Solo aplica mientras se muestra "Gracias".
     */
    fun keepAlive() {
        if (_content.value is CustomerContent.Done) scheduleRevert()
    }

    /** Carrito vacío = volver a la marca (no dejar un carrito fantasma). */
    fun showCart(state: com.avoqado.pos.pos.presentation.cart.CartState) {
        // Una venta nueva cancela el timer del "Gracias": si no, el timer viejo
        // borraría este carrito 30 s después.
        revertJob?.cancel()
        _content.value = if (state.items.isEmpty()) {
            CustomerContent.Idle
        } else {
            CustomerContent.Cart(
                renglones = renglonesParaElCliente(state.items),
                subtotalCents = state.subtotalCents,
                // El premio de cartilla también es un descuento para el cliente: sin él, sus
                // productos sumarían más que el total y nada lo explicaría.
                descuentos = descuentosParaElCliente(state, premioCents = state.stampRewardCents),
                taxCents = state.taxCents,
                totalCents = state.totalCents,
            )
        }
        if (_content.value is CustomerContent.Idle) promotePendingDesign()
    }

    fun idle() {
        revertJob?.cancel()
        _content.value = CustomerContent.Idle
        promotePendingDesign()
        onRatingPicked = null
        onTipPicked = null
    }

    private companion object {
        // 30 s: alcanza para que el cliente escanee el QR del recibo y luego
        // vuelve solo a la marca del negocio.
        const val DONE_TIMEOUT_MS = 30_000L
    }
}
