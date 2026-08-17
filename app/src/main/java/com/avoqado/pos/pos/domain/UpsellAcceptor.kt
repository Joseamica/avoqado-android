package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.LinkedDiscount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ResolvedModifier
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.UpsellCard
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel

/**
 * Upsell — meter al carrito lo que el cliente aceptó.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md (R4, C1, C2)
 *
 * 🔴 Existe como CONTRATO con dos implementaciones, no como un `if`, porque agregar
 * en mostrador y agregar en mesa son operaciones de dinero DISTINTAS:
 *   - Mostrador: mutación del carrito local. La orden todavía no existe.
 *   - Mesa: la orden YA vive en el server; hay que pasar por ADD_ITEMS con
 *     comparación de versión para no pisar a otro mesero.
 * Enterrar esa diferencia en un `if` dentro de una pantalla de 1,700 líneas es como
 * se cuelan los bugs de doble cobro.
 */
interface UpsellAcceptor {
    /**
     * 🔴 DEVUELVE el carrito resultante. No es cosmético: el llamador DEBE congelar
     * el total a partir de este valor.
     *
     * En Compose, `val cartState by viewModel.cartState.collectAsState()` es una
     * lectura que NO se actualiza hasta la siguiente recomposición. Agregar y luego
     * leer esa variable en la misma función devuelve el carrito VIEJO — o sea, se
     * cobraría sin el producto aceptado y `clearCart()` lo borraría después. Es el
     * bug original del snapshot congelado, resucitado por otra puerta.
     */
    suspend fun accept(cards: List<UpsellCard>, catalog: Map<String, Product>): AcceptResult
}

data class AcceptResult(
    /** El carrito YA con lo aceptado. De aquí sale el snapshot, de ningún otro lado. */
    val cart: CartState,
    /** Lo que de verdad entró. Puede ser menos que lo aceptado (ver abajo). */
    val added: List<UpsellCard>,
    /** Lo que se cayó entre la sugerencia y el toque: agotado, desactivado, borrado. */
    val unavailable: List<UpsellCard>,
)

/**
 * Mostrador: el carrito es local y la orden todavía no existe, así que agregar es
 * una mutación en memoria.
 */
class CounterUpsellAcceptor(
    private val cartViewModel: CartViewModel,
) : UpsellAcceptor {

    override suspend fun accept(cards: List<UpsellCard>, catalog: Map<String, Product>): AcceptResult {
        val added = mutableListOf<UpsellCard>()
        val unavailable = mutableListOf<UpsellCard>()

        cards.forEach { card ->
            // 🔴 Re-validación contra el catálogo VIVO. Entre que se pintó la tarjeta
            // y el toque pueden pasar 20 segundos, y en ese rato el producto pudo
            // agotarse o desactivarse. Se agrega lo que siga siendo válido y lo
            // demás se reporta — nunca se agrega a ciegas.
            val product = catalog[card.productId]
            if (product == null || product.isOutOfStock || product.active == false) {
                unavailable += card
                return@forEach
            }
            // 🔴 El descuento ligado también se re-valida. Si murió entre que el POS
            // bajó las reglas y el toque, hay dos salidas y las dos son malas:
            // mandar el id tumba la venta ENTERA con 400 (el server rechaza ids que
            // no conoce, a propósito), y agregarla sin descuento cobra el precio de
            // lista después de haberle prometido al cliente el rebajado. Se reporta
            // como no disponible: se pierde la sugerencia, nunca la venta.
            val descuento = card.linkedDiscount?.let { resolverDescuentoVivo(it, product) }
            if (card.linkedDiscount != null && descuento == null) {
                unavailable += card
                return@forEach
            }
            // Se agrega con el precio VIVO del producto, no con el de la tarjeta:
            // cobrar un precio viejo porque la pantalla lo dijo es la misma clase de
            // bug que el snapshot congelado.
            //
            // 🔴 Con modificadores obligatorios YA resueltos (spec 2026-08-16, B3),
            // la línea tiene que entrar por el MISMO camino que agregar a mano con
            // modificadores. Si se quedara en addProduct(), entraría SIN el tamaño
            // y se cobraría el precio pelón: la tarjeta dijo un precio y se cobra
            // otro.
            //
            // 🔴 Con descuento ligado va por el mismo camino, por la misma razón:
            // `addProduct` no sabe de descuentos Y fusiona con una línea igual del
            // carrito — una línea rebajada fusionada con una a precio de lista
            // cobraría el descuento dos veces o ninguna.
            if (card.modifiers.isNotEmpty() || descuento != null) {
                cartViewModel.addProductWithModifiers(
                    product = product,
                    modifiers = card.modifiers.map { it.toSelectedModifier() },
                    discount = descuento,
                )
            } else {
                cartViewModel.addProduct(product)
            }
            added += card
        }

        // Del FLUJO, no de una variable compuesta. Ver la nota del contrato.
        return AcceptResult(
            cart = cartViewModel.cartState.value,
            added = added,
            unavailable = unavailable,
        )
    }

    /**
     * El descuento de la tarjeta, comprobado contra el catálogo VIVO del POS.
     * Devuelve null si ya no sirve — el llamador convierte eso en "no disponible".
     *
     * 🔴 Catálogo VACÍO significa "todavía no sé", no "ya no existe". Arranque en
     * frío o sin red entran aquí, y tratarlos como ausencia apagaría TODAS las
     * promociones del local justo cuando no hay red — exactamente el error que ya
     * costó las comandas offline (`PrintConfigRepository`, ver
     * `.claude/rules/offline-first-y-hub-lan.md` §4). Se confía en la regla, que
     * el server ya validó al servirla.
     */
    private fun resolverDescuentoVivo(ligado: LinkedDiscount, product: Product): Discount? {
        val vivos = cartViewModel.discountsRepository.discounts.value
        if (vivos.isEmpty()) return ligado.toDiscount()
        val vivo = vivos.firstOrNull { it.id == ligado.id } ?: return null
        if (!vivo.active) return null
        // El server rechaza la orden entera si el descuento no aplica al producto
        // (`validateDiscountScopeForItem`). Mejor perder la tarjeta que la venta.
        if (!vivo.appliesTo(product.id, product.categoryId)) return null
        return vivo
    }
}

/**
 * El descuento ligado tal como lo sirvió el server, en la forma que entiende el
 * carrito. Se usa sólo cuando el POS todavía no tiene catálogo de descuentos.
 *
 * `scope = "ITEM"` porque el server únicamente liga descuentos de artículo a una
 * regla de upsell (`nightly-upsell-rules.job.ts` filtra `scope: 'ITEM'`).
 */
private fun LinkedDiscount.toDiscount() = Discount(
    id = id,
    name = badge,
    value = value,
    type = type,
    scope = "ITEM",
)

/**
 * El server ya resolvió nombre y precio — mapa directo, sin tocar el catálogo
 * (los ids ya vienen resueltos).
 *
 * `groupName` queda vacío: el DTO no lo trae (sólo `groupId`/`modifierId`/`name`/
 * `price` del MODIFICADOR) y no se usa en cobro, comanda ni recibo — sólo
 * `modifierName`, vía `CartItem.modifiersSummary`. Sólo se notaría si esta línea
 * se reabre para editar modificadores a mano.
 */
private fun ResolvedModifier.toSelectedModifier() = SelectedModifier(
    groupId = groupId,
    groupName = "",
    modifierId = modifierId,
    modifierName = name,
    priceInCents = priceInCents,
)
