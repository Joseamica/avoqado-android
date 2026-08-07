package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.Product
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
            // Se agrega con el precio VIVO del producto, no con el de la tarjeta:
            // cobrar un precio viejo porque la pantalla lo dijo es la misma clase de
            // bug que el snapshot congelado.
            cartViewModel.addProduct(product)
            added += card
        }

        // Del FLUJO, no de una variable compuesta. Ver la nota del contrato.
        return AcceptResult(
            cart = cartViewModel.cartState.value,
            added = added,
            unavailable = unavailable,
        )
    }
}
