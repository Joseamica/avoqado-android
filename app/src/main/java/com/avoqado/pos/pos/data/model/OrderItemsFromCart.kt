package com.avoqado.pos.pos.data.model

import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.OrderModifierRequest
import com.avoqado.pos.payment.data.model.PromotionRefRequest
import com.avoqado.pos.payment.data.model.PromotionSelectionRequest

/**
 * Las líneas del carrito → los items de `POST /mobile/venues/:venueId/orders`.
 *
 * 🔴 **ÚNICO sitio donde se hace esta conversión.** Antes vivía copiada en
 * `PaymentFlowViewModel.buildOrderRequest` (cobrar) y en
 * `CartViewModel.createPayLaterOrder` (pagar después): dos copias del mismo
 * mapeo que se tienen que mover JUNTAS. Ya costó un bug con otro campo — el que
 * toca una y olvida la otra deja "pagar después" mandando algo distinto de lo
 * que se cobra, y nadie lo nota hasta que el ticket no cuadra.
 *
 * Lo que hace de especial con las promociones:
 *
 * - Las N líneas de producto de un combo (todas con el mismo
 *   [CartItem.promotionInstanceId]) **se colapsan en UNA** con `promotionRef`.
 *   Si además viajaran como productos sueltos, el server cobraría el combo
 *   Y sus productos: la promoción se pagaría dos veces.
 * - Esa línea viaja SOLA — sin `productId`, `name` ni `unitPrice`. El POS no
 *   manda precios de promoción: manda qué promoción y qué eligió la persona, y
 *   el server calcula.
 * - Una instancia = un combo. 3 combos son 3 líneas con 3 instancias; nunca
 *   `quantity: 3`, que el server rechaza con 400.
 *
 * El orden del carrito se conserva: la promoción ocupa el lugar de su PRIMERA
 * línea.
 */
fun buildOrderItemRequests(items: List<CartItem>): List<OrderItemRequest> {
    val refPorInstancia = items
        .mapNotNull { it.promotionInstanceId }
        .distinct()
        .associateWith { promotionRefDe(items, it) }
    val instanciasYaEmitidas = mutableSetOf<String>()
    return items.mapNotNull { item ->
        val instanceId = item.promotionInstanceId
            ?: return@mapNotNull item.toOrderItemRequest()
        // Sin ids no se puede armar el ref. TODAS las líneas de esa instancia
        // viajan como productos normales en vez de desaparecer: la mercancía ya
        // está en el mostrador y dejarla fuera del pedido sería regalarla. Sólo
        // pasa con un carrito corrupto — el guardado conserva los 5 campos.
        val ref = refPorInstancia[instanceId]
            ?: return@mapNotNull item.toOrderItemRequest()
        // La promoción entera ocupa el lugar de su PRIMERA línea; las hermanas
        // no viajan, o el server cobraría el combo Y sus productos.
        if (!instanciasYaEmitidas.add(instanceId)) return@mapNotNull null
        OrderItemRequest.promocion(ref)
    }
}

/**
 * Reconstruye `promotionRef` desde las líneas de UNA instancia: un par
 * `{groupId, optionId}` por grupo, en el orden en que entraron al carrito.
 *
 * Devuelve null cuando no alcanza para mandarlo — sin `promotionId` o sin
 * ninguna elección. Mandar un ref a medias es un 400 del server, y peor: con
 * `selections` incompletas el server cobraría OTRA cosa.
 */
private fun promotionRefDe(items: List<CartItem>, instanceId: String): PromotionRefRequest? {
    val lineas = items.filter { it.promotionInstanceId == instanceId }
    val promotionId = lineas.firstNotNullOfOrNull { it.promotionId }?.takeIf { it.isNotBlank() }
        ?: return null
    val selections = lineas
        .mapNotNull { linea ->
            val groupId = linea.promotionGroupId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val optionId = linea.promotionOptionId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PromotionSelectionRequest(groupId = groupId, optionId = optionId)
        }
        // El server resuelve cada grupo con `selections.find(s => s.groupId === group.id)`:
        // un grupo repetido sólo usaría la primera. Se deduplica aquí para que
        // lo que se manda diga exactamente lo que se va a cobrar.
        .distinctBy { it.groupId }
    if (selections.isEmpty()) return null
    return PromotionRefRequest(
        promotionId = promotionId,
        promotionInstanceId = instanceId,
        selections = selections,
    )
}

/** Una línea normal del carrito, tal cual viajaba antes de las promociones. */
private fun CartItem.toOrderItemRequest(): OrderItemRequest = OrderItemRequest(
    productId = when (val tipo = type) {
        is CartItemType.ProductItem -> tipo.productId
        CartItemType.CustomAmount -> null
        // No es una línea de producto: los créditos se otorgan aparte.
        is CartItemType.CreditPack -> null
    },
    name = name,
    quantity = quantity,
    unitPrice = effectiveUnitPrice,
    modifiers = selectedModifiers.map { modifier ->
        OrderModifierRequest(
            modifierId = modifier.modifierId,
            name = modifier.modifierName,
            price = modifier.priceInCents,
        )
    },
    note = itemNote,
    isCortesia = isCortesia,
    discountId = itemDiscountId,
    weightQuantity = weightKg,
)
