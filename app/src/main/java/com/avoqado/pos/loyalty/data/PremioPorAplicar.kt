package com.avoqado.pos.loyalty.data

import com.avoqado.pos.pos.data.model.CartItem
import kotlin.math.roundToInt

/**
 * El premio de cartilla que el cajero aplicó a la venta en curso. Todavía NO está canjeado:
 * el servidor lo canjea al crear la venta.
 *
 * 🔴 DINERO. Estándar de Square y Toast: el premio es un descuento en la cuenta ANTES de elegir
 * cómo se paga, así que el carrito resta [premioEstimadoCents] de su total y efectivo, tarjeta
 * y pago dividido ven el mismo número. Al crear la venta manda lo que el servidor CONFIRMA
 * (`stampReward.discountAmount`), no este estimado — ver `totalACobrarCents`.
 */
data class PremioPorAplicar(
    val id: String,
    val etiqueta: String,
    val tipo: String?,
    val valor: Double?,
)

fun ScannedReward.comoPremioPorAplicar(): PremioPorAplicar =
    PremioPorAplicar(id = id, etiqueta = rewardLabel, tipo = rewardType, valor = rewardValue)

/**
 * Cuánto baja la cuenta [premio] — ESTIMADO con la regla del servidor (`calcularDescuento` de
 * `avoqado-server/src/services/wallet/redeemStampReward.service.ts`). Si cambias una, cambia
 * la otra (y la de iOS, `PremioPorAplicar.swift`).
 *
 * [baseCents] es la cuenta ya descontada (subtotal − descuentos), igual que en el servidor.
 */
fun premioEstimadoCents(premio: PremioPorAplicar, items: List<CartItem>, baseCents: Int): Int {
    if (baseCents <= 0) return 0
    val valor = premio.valor
    val bruto = when (premio.tipo) {
        "FIXED_AMOUNT" -> valor?.let { (it * 100).roundToInt() } ?: 0
        // 🔴 El porcentaje es de la CUENTA: tratar el 10 como pesos cobra de menos en una cuenta
        // grande y de más en una chica.
        "PERCENTAGE" -> valor?.let { (baseCents * it / 100.0).roundToInt() } ?: 0
        // El artículo MÁS CARO (decisión D10, como Square), por su precio unitario — el mismo
        // `unitPrice` que el servidor lee de cada línea.
        // 🔴 Con promociones en la cuenta NO se estima: una línea de promoción guarda su precio ya
        // repartido y el servidor usa el de lista (2 cafés de $50 en 2×1: $25 contra $50). Lo
        // calcula el servidor al cobrar.
        "FREE_PRODUCT" -> if (items.any { it.promotionInstanceId != null }) 0 else items.maxOfOrNull { it.unitPrice } ?: 0
        // Un tipo que esta versión no conoce: no se inventa un descuento. El servidor lo
        // confirma al cobrar y ese número manda.
        else -> 0
    }
    // 🔴 Tope contra la cuenta, como el servidor: sin él la cuenta quedaría en negativo.
    return bruto.coerceIn(0, baseCents)
}
