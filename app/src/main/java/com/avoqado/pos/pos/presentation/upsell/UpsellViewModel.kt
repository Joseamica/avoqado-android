package com.avoqado.pos.pos.presentation.upsell

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.UpsellRepository
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.UpsellCard
import com.avoqado.pos.pos.domain.UpsellHoldout
import com.avoqado.pos.pos.domain.resolveUpsellSuggestions
import com.avoqado.pos.pos.presentation.cart.CartState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/** Dónde ocurre el momento. Cada uno tiene su propia perilla en el dashboard. */
enum class UpsellContext(val wire: String) {
    COUNTER("counter"),
    TABLE_ORDERING("tableOrdering"),

    /** 🔴 EXIGE RED: el producto entra a una orden que ya vive en el server. */
    TABLE_PAYING("tablePaying"),
}

/**
 * Upsell "¿Algo más?" — el momento, justo antes de congelar el total.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md
 *
 * 🔴 Este ViewModel NO cobra ni congela nada. Resuelve qué mostrar, lo espeja en
 * la pantalla del cliente y registra la métrica. Quien mete al carrito es el
 * `UpsellAcceptor`, y quien congela el total es la pantalla de cobro — con el
 * carrito que devuelve el acomodador, nunca con una variable capturada.
 */
@HiltViewModel
class UpsellViewModel @Inject constructor(
    private val repository: UpsellRepository,
    private val productsRepository: ProductsRepository,
    private val customerDisplay: CustomerDisplayState,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    /**
     * Un momento en curso. `null` = no hay nada que atender y el cobro sigue de largo.
     */
    data class Moment(
        /** Lo genera el CLIENTE, como `localOrderId` en el reducer offline. */
        val impressionId: String,
        val cards: List<UpsellCard>,
        val selected: Set<String> = emptySet(),
        val context: UpsellContext,
        val cartSubtotalBefore: Int,
    ) {
        val selectedCards: List<UpsellCard> get() = cards.filter { it.ruleId in selected }
        val selectedDeltaCents: Int get() = selectedCards.sumOf { it.displayPriceCents }
    }

    private val _moment = MutableStateFlow<Moment?>(null)
    val moment: StateFlow<Moment?> = _moment.asStateFlow()

    /**
     * Impresión esperando saber en qué venta terminó. Se llena para TODO momento
     * —mostrado o de control, aceptado o rechazado— porque el promedio de ticket de
     * cada grupo se calcula sobre la población completa.
     */
    private var pendingConversionId: String? = null

    /**
     * Qué hacer cuando el CLIENTE resuelve desde SU pantalla. Lo registra la
     * pantalla de cobro con las mismas acciones que la tira del cajero: una sola
     * verdad, dos superficies de entrada — igual que propina y calificación.
     *
     * 🔴 Se registran aunque la segunda pantalla no capture toques (T3 Pro): ahí
     * simplemente nunca se disparan. Condicionarlos al hardware es cómo se cuela
     * el bug de "en la D3 sí y en la T3 no", que sólo aparece en un mostrador real.
     */
    private var customerConfirm: (() -> Unit)? = null
    private var customerDismiss: (() -> Unit)? = null

    fun bindCustomerActions(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        customerConfirm = onConfirm
        customerDismiss = onDismiss
    }

    val catalog: Map<String, Product> get() = productsRepository.products.value.associateBy { it.id }

    /** Refresca la tabla; si no hay red, levanta la última buena del disco. */
    fun refresh() {
        viewModelScope.launch {
            repository.hydrateIfEmpty()
            repository.fetchRules()
        }
    }

    /**
     * ¿Hay algo que ofrecer antes de cobrar?
     *
     * Devuelve el momento si SÍ (la pantalla debe esperar a que se resuelva), o
     * `null` para seguir al cobro de inmediato. Nunca lanza: una falla aquí no
     * puede impedir un cobro.
     *
     * 🔴 Un momento del GRUPO DE CONTROL devuelve `null` — no se muestra nada —
     * pero SÍ se registra. Sin ese registro no hay con qué comparar y el "aumento"
     * del reporte sería sólo el total de lo aceptado, que siempre se ve bien.
     */
    fun offer(cart: CartState, context: UpsellContext): Moment? = runCatching {
        if (cart.items.isEmpty()) return null
        if (!isSurfaceEnabled(context)) return null

        val impressionId = UUID.randomUUID().toString()
        val cartProductIds = cart.items.mapNotNull { (it.type as? CartItemType.ProductItem)?.productId }.toSet()
        val cards = resolveUpsellSuggestions(
            rules = repository.rules,
            cartProductIds = cartProductIds,
            cartCategoryIds = cartProductIds.mapNotNull { catalog[it]?.categoryId }.toSet(),
            catalog = catalog,
            nowLocal = LocalDateTime.now(),
        )
        if (cards.isEmpty()) return null

        if (UpsellHoldout.isHoldout(impressionId, repository.holdoutPercent)) {
            record(impressionId, cards.map { it.ruleId }, emptyList(), 0, cart.subtotalCents, SURFACE_HOLDOUT, context)
            // 🔴 El momento de CONTROL también tiene que decir en qué venta terminó.
            // Sin esto su ticket promedio sale de un conjunto vacío = 0, y el
            // "aumento real" del reporte se vuelve el ticket ENTERO del otro grupo.
            pendingConversionId = impressionId
            return null
        }

        val moment = Moment(impressionId, cards, emptySet(), context, cart.subtotalCents)
        _moment.value = moment
        // Espejo en la pantalla del cliente. Si no hay segunda pantalla montada,
        // esto es inerte y el cajero lo ofrece de viva voz desde su tira.
        customerDisplay.show(CustomerContent.Upsell(cards, cart.totalCents))
        customerDisplay.onUpsellToggled = { ruleId -> toggle(ruleId) }
        customerDisplay.onUpsellConfirmed = { customerConfirm?.invoke() }
        customerDisplay.onUpsellDismissed = { customerDismiss?.invoke() }
        moment
    }.getOrElse {
        Log.e(TAG, "❌ el momento de upsell falló, se sigue al cobro: ${it.message}")
        null
    }

    /** Marcar/desmarcar. NO agrega nada: sólo cambia la selección y la vista previa. */
    fun toggle(ruleId: String) {
        val current = _moment.value ?: return
        val next = if (ruleId in current.selected) current.selected - ruleId else current.selected + ruleId
        _moment.value = current.copy(selected = next)
        customerDisplay.updateUpsellSelection(next)
    }

    /**
     * Cierra el momento y registra la métrica. Se llama SIEMPRE — al aceptar y al
     * rechazar — porque un "no, gracias" es justo el dato que dice si la sugerencia
     * sirve o estorba.
     *
     * `addedCards` es lo que DE VERDAD entró al carrito (puede ser menos que lo
     * marcado si algo se agotó entre la tarjeta y el toque).
     */
    fun finish(addedCards: List<UpsellCard>, addedAmountCents: Int) {
        val moment = _moment.value ?: return
        _moment.value = null
        // Se sueltan los tres a la vez: dejar vivo un callback de un momento que
        // ya terminó es cómo un toque tardío del cliente reabre algo cerrado —
        // el mismo defecto que ya costó un bug en la pantalla de propina.
        customerDisplay.onUpsellToggled = null
        customerDisplay.onUpsellConfirmed = null
        customerDisplay.onUpsellDismissed = null

        val accepted = addedCards.mapIndexed { idx, card ->
            AcceptedLine(
                ruleId = card.ruleId,
                productId = card.productId,
                orderItemExternalId = UpsellHoldout.externalId(moment.impressionId, idx),
            )
        }
        record(
            moment.impressionId,
            moment.cards.map { it.ruleId },
            accepted,
            addedAmountCents,
            moment.cartSubtotalBefore,
            if (customerDisplay.isPresenting.value) SURFACE_CUSTOMER else SURFACE_CASHIER,
            moment.context,
        )

        // 🔴 SIEMPRE, aceptara o no.
        //
        // La comparación honesta es "ticket de quien VIO tarjetas" contra "ticket de
        // quien no las vio" — las dos poblaciones completas. Convertir sólo a quien
        // aceptó dejaría el promedio "con tarjetas" hecho puro de compradores, que
        // por construcción gastan más: el reporte mediría su propio sesgo.
        pendingConversionId = moment.impressionId
    }

    /**
     * El momento terminó en venta PAGADA: se ata a la orden real.
     *
     * Lo llama la pantalla de cobro en su éxito. El id vive AQUÍ y no en la vista
     * porque un momento del grupo de control nunca pasa por la vista y aun así tiene
     * que convertir.
     */
    fun onOrderPaid(orderId: String) {
        val impressionId = pendingConversionId ?: return
        pendingConversionId = null
        viewModelScope.launch { repository.convertImpression(impressionId, orderId) }
    }

    /**
     * El cobro se canceló. Se suelta sin convertir: la impresión aporta $0 y no
     * cuenta para el promedio, que es lo correcto — no hubo venta.
     *
     * Dejarla viva sería peor que perderla: la SIGUIENTE venta la convertiría contra
     * una orden que no tuvo nada que ver.
     */
    fun cancelPendingConversion() {
        pendingConversionId = null
    }

    private fun isSurfaceEnabled(context: UpsellContext) = when (context) {
        UpsellContext.COUNTER -> repository.surfaces.counter
        UpsellContext.TABLE_ORDERING -> repository.surfaces.tableOrdering
        UpsellContext.TABLE_PAYING -> repository.surfaces.tablePaying
    }

    /**
     * Fuego-y-olvido en el scope del ViewModel: el cobro NO espera esto. Los montos
     * viajan en PESOS (mayores), no en centavos — como todo el resto de la plataforma.
     */
    private fun record(
        impressionId: String,
        shownRuleIds: List<String>,
        accepted: List<AcceptedLine>,
        addedAmountCents: Int,
        cartSubtotalBeforeCents: Int,
        surface: String,
        context: UpsellContext,
    ) {
        val venue = secureStorage.venueId ?: return
        val body = buildString {
            append("""{"impressionId":"$impressionId",""")
            append(""""shownRuleIds":[${shownRuleIds.joinToString(",") { "\"$it\"" }}],""")
            append(""""accepted":[""")
            append(
                accepted.joinToString(",") {
                    """{"ruleId":"${it.ruleId}","productId":"${it.productId}","orderItemExternalId":"${it.orderItemExternalId}"}"""
                },
            )
            append("""],""")
            append(""""reportedAmount":${addedAmountCents / 100.0},""")
            append(""""cartSubtotalBefore":${cartSubtotalBeforeCents / 100.0},""")
            append(""""surface":"$surface","context":"${context.wire}"}""")
        }
        viewModelScope.launch { repository.recordImpression(body, venue) }
    }

    data class AcceptedLine(val ruleId: String, val productId: String, val orderItemExternalId: String)

    private companion object {
        const val TAG = "🛒UPSELL"
        const val SURFACE_CUSTOMER = "CUSTOMER_DISPLAY"
        const val SURFACE_CASHIER = "CASHIER"
        const val SURFACE_HOLDOUT = "HOLDOUT"
    }
}
