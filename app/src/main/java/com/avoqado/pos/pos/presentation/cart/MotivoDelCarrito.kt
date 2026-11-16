package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.domain.MotivoElegido
import com.avoqado.pos.pos.domain.MotivosDeDescuento

/*
 * Encargo f998c8 (founder «A», 15-nov): qué descuentos del carrito piden motivo y cuáles no lo traen todavía. Puro: lo usan los
 * paneles (para preguntar al poner el descuento) y la última puerta antes de cobrar, «Pagar después» o guardar a la fila (para un
 * carrito restaurado, una venta que regresó del cobro con saldo o cualquier camino que no preguntó).
 */

/**
 * La línea lleva un descuento que puso una persona a mano: uno del catálogo (no el que trae una sugerencia) o un «Ajuste de
 * precio». La cortesía tiene su propio motivo y una línea de promoción no se descuenta suelta.
 */
val CartItem.descuentoPideMotivo: Boolean
    get() = !isCortesia && !isPromotionLine &&
        ((!itemDiscountId.isNullOrBlank() && !descuentoSugerido) || priceAdjustment != null)

/**
 * El descuento de la CUENTA lo eligió una persona: de la lista, uno manual o la cortesía de toda la cuenta. No el cupón (lo trae el
 * cliente), ni el del cliente o un referido (se ponen solos: traen `source`).
 */
val CartState.cuentaPideMotivo: Boolean
    get() = orderDiscount?.let { it.couponCode.isNullOrBlank() && it.source == null } == true && orderDiscountCents > 0

/** Lo que pide motivo y todavía no lo trae, en palabras para la ventana («Playera · Ajuste de precio», «Descuento de la cuenta»). */
fun CartState.descuentosSinMotivo(motivos: MotivosDeDescuento): List<String> {
    if (!motivos.sePide) return emptyList()
    val lineas = items.filter { it.descuentoPideMotivo && it.discountReason.isNullOrBlank() }.map { item ->
        val cual = if (item.priceAdjustment != null) "Ajuste de precio" else item.itemDiscountName ?: "Descuento"
        "${item.name} · $cual"
    }
    val cuenta = if (cuentaPideMotivo && orderDiscountReason.isNullOrBlank()) listOf("Cuenta · ${orderDiscount?.name ?: "Descuento"}") else emptyList()
    return lineas + cuenta
}

/** Pone [motivo] en todo lo que lo pide y no lo trae; lo que ya traía motivo se queda como está. */
fun CartState.conMotivoEnLoQueFalta(motivo: MotivoElegido): CartState = copy(
    items = items.map { item ->
        if (item.descuentoPideMotivo && item.discountReason.isNullOrBlank()) {
            item.copy(discountReason = motivo.motivo, discountReasonComment = motivo.comentario)
        } else {
            item
        }
    },
    orderDiscountReason = if (cuentaPideMotivo && orderDiscountReason.isNullOrBlank()) motivo.motivo else orderDiscountReason,
    orderDiscountReasonComment =
        if (cuentaPideMotivo && orderDiscountReason.isNullOrBlank()) motivo.comentario else orderDiscountReasonComment,
)
