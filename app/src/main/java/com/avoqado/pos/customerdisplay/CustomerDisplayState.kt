package com.avoqado.pos.customerdisplay

import com.avoqado.pos.pos.data.model.CartItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Carrito en vivo mientras el cajero teclea. */
    data class Cart(
        val items: List<CartItem>,
        val subtotalCents: Int,
        val discountCents: Int,
        val taxCents: Int,
        val totalCents: Int,
    ) : CustomerContent

    /** Las estrellas: el cliente califica en SU pantalla. */
    data class Rating(val amountCents: Int) : CustomerContent

    /** Propina: el cliente elige en SU pantalla. */
    data class Tip(
        val amountCents: Int,
        val suggestions: List<Int>,
        val selectedTipCents: Int?,
    ) : CustomerContent

    /** Total final confirmado; el cobro ocurre en la terminal. */
    data class Charging(val totalCents: Int, val message: String) : CustomerContent

    /** Gracias + QR del recibo digital. */
    data class Done(val totalCents: Int, val receiptUrl: String?) : CustomerContent
}

/**
 * Canal único hacia la pantalla del cliente. Los ViewModels de pantalla
 * (carrito, flujo de pago) EMPUJAN aquí; la Presentation solo observa.
 * Singleton porque la pantalla secundaria vive fuera del ciclo de vida de
 * cualquier pantalla en particular.
 */
@Singleton
class CustomerDisplayState @Inject constructor() {

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

    private val _customerCapturesInput = MutableStateFlow(false)
    val customerCapturesInput: StateFlow<Boolean> = _customerCapturesInput.asStateFlow()

    fun setPresenting(value: Boolean) {
        presenting = value
        _isPresenting.value = value
        recompute()
    }

    /** Lo prende/apaga el negocio desde Ajustes → Pantalla del cliente. */
    fun setCustomerCaptureEnabled(value: Boolean) {
        enabledByUser = value
        recompute()
    }

    private fun recompute() {
        _customerCapturesInput.value = presenting && enabledByUser
    }

    /** Callbacks de VUELTA: lo que el cliente toca en su pantalla. */
    var onRatingPicked: ((Int) -> Unit)? = null
    var onTipPicked: ((Int) -> Unit)? = null

    fun show(content: CustomerContent) {
        _content.value = content
    }

    /** Carrito vacío = volver a la marca (no dejar un carrito fantasma). */
    fun showCart(state: com.avoqado.pos.pos.presentation.cart.CartState) {
        _content.value = if (state.items.isEmpty()) {
            CustomerContent.Idle
        } else {
            CustomerContent.Cart(
                items = state.items,
                subtotalCents = state.subtotalCents,
                discountCents = state.discountCents,
                taxCents = state.taxCents,
                totalCents = state.totalCents,
            )
        }
    }

    fun idle() {
        _content.value = CustomerContent.Idle
        onRatingPicked = null
        onTipPicked = null
    }
}
