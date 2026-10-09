package com.avoqado.pos.customerdisplay

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.model.ComboPrintLines

/**
 * Lo que el CLIENTE lee de su compra en la segunda pantalla. Funciones PURAS: la
 * parte que se prueba (`DesgloseParaElClienteTest`).
 *
 * 🔴 Cero matemática de dinero nueva: todo sale de [CartItem] y [CartState], las
 * mismas cuentas que cobran. Aquí sólo se decide qué se NOMBRA. Antes (9-oct) cada
 * renglón era «cantidad · nombre · precio»: un Latte de $55 salía en $65 sin decir
 * «Leche de almendra», un combo salía como productos sueltos y los tres descuentos
 * posibles se juntaban en un solo «Descuento» sin nombre.
 */

/** Un renglón de la lista de productos, tal como lo ve el cliente. */
data class RenglonCliente(
    val key: String,
    val cantidad: Int,
    val nombre: String,
    /** Debajo del nombre, más chico: modificadores, peso, cortesía, descuento, nota. */
    val detalle: String?,
    /** null en un producto de combo: su precio va en el renglón del combo. */
    val precioCents: Int?,
    val esComponente: Boolean = false,
)

data class DescuentoCliente(val etiqueta: String, val cents: Int)

/**
 * Combos como en el ticket impreso ([ComboPrintLines]): el nombre del combo con la
 * SUMA de sus productos, y debajo cada producto sin precio. Una instancia = un combo.
 * El precio de cada renglón va BRUTO; los descuentos se restan abajo.
 */
fun renglonesParaElCliente(items: List<CartItem>): List<RenglonCliente> =
    ComboPrintLines.group(
        tagged = items.map { item ->
            val tag = item.promotionInstanceId?.let { ComboPrintLines.Tag(key = it, name = item.promotionName ?: "Combo") }
            tag to RenglonCliente(
                key = item.id,
                cantidad = item.quantity,
                nombre = item.name,
                detalle = detalleDeLinea(item),
                precioCents = item.grossPrice,
            )
        },
        header = { tag, miembros ->
            RenglonCliente(
                key = "combo:${tag.key}",
                cantidad = 1,
                nombre = tag.name,
                detalle = null,
                precioCents = miembros.sumOf { it.precioCents ?: 0 },
            )
        },
        component = { it.copy(precioCents = null, esComponente = true) },
    )

/** Las mismas palabras que el carrito del cajero, unidas con « · ». */
internal fun detalleDeLinea(item: CartItem): String? = listOfNotNull(
    item.weightSummary,
    item.modifiersSummary,
    "Cortesía".takeIf { item.isCortesia },
    item.itemDiscountCents.takeIf { it > 0 }?.let { "${item.itemDiscountName ?: "Descuento"} −${money(it)}" },
    item.itemNote?.takeIf { it.isNotBlank() }?.let { "Nota: $it" },
).joinToString(" · ").ifEmpty { null }

/**
 * Cada descuento con su origen, con las etiquetas del carrito del cajero.
 * [premioCents] lo pasa quien llama: en el carrito es el ESTIMADO, y en el cobro, lo
 * que el servidor confirmó (`premioEnLaCuenta`).
 */
fun descuentosParaElCliente(cart: CartState, premioCents: Int): List<DescuentoCliente> = listOfNotNull(
    DescuentoCliente("Descuento en productos", cart.itemDiscountCents).takeIf { it.cents > 0 },
    DescuentoCliente(
        cart.orderDiscount?.name?.takeIf { it.isNotBlank() }?.let { "Descuento ($it)" } ?: "Descuento",
        cart.orderDiscountCents,
    ).takeIf { it.cents > 0 },
    DescuentoCliente(
        cart.pendingStampReward?.etiqueta?.takeIf { it.isNotBlank() }?.let { "Premio: $it" } ?: "Premio",
        premioCents,
    ).takeIf { it.cents > 0 },
)

/**
 * Lo que ve el cliente mientras el CAJERO elige cómo cobrar.
 *
 * 🔴 DINERO A LA VISTA. Con la cuenta dividida, el total del carrito es la cuenta
 * ENTERA; mostrarlo aquí y luego cobrar la parte enseñaba $800 y cobraba $400 (9-oct).
 * Una parte se muestra sola y en grande como «Tu parte», con [montoACobrarCents] —el
 * mismo importe que luego va a la terminal o a la caja— más la propina.
 *
 * @param premioCents el premio de cartilla que manda en la cuenta: lo confirmado por
 *   el servidor si ya lo hay, si no el estimado del carrito.
 */
fun desgloseDelCobro(
    cart: CartState?,
    tipCents: Int,
    premioCents: Int,
    esPagoCompleto: Boolean,
    montoACobrarCents: Int,
): CustomerContent.Total = when {
    !esPagoCompleto -> CustomerContent.Total(totalCents = montoACobrarCents + tipCents, etiqueta = "Tu parte")
    cart == null || cart.items.isEmpty() -> CustomerContent.Total(totalCents = montoACobrarCents + tipCents)
    else -> CustomerContent.Total(
        // El carrito ya restó su estimado del premio; se cambia por el que manda.
        totalCents = cart.totalCents + cart.stampRewardCents - premioCents + tipCents,
        renglones = renglonesParaElCliente(cart.items),
        subtotalCents = cart.subtotalCents,
        descuentos = descuentosParaElCliente(cart, premioCents),
        taxCents = cart.taxCents,
        tipCents = tipCents,
    )
}

internal fun money(cents: Int): String = "$%,.2f".format(cents / 100.0)
