package com.avoqado.pos.payment.domain

import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tables.data.kitchenCourseLabel

/** Frozen choices reach paper and KDS; null keeps the existing counter dispatch unchanged. */
fun buildCounterCoursePedidos(items: List<CartItem>): List<ComandaDispatcher.Pedido>? {
    if (items.none { it.serviceCourse != null }) return null
    return items.groupBy { kitchenCourseLabel(it.serviceCourse, null) }.map { (course, lines) ->
        ComandaDispatcher.Pedido(
            lines = lines.map { item -> RoutableItem(
                orderItemId = item.id, productId = (item.type as? CartItemType.ProductItem)?.productId,
                categoryId = item.categoryId, productName = item.nombreEnCocina, quantity = item.quantity,
                modifiers = item.selectedModifiers.map { it.modifierName }, notes = item.itemNote,
                comboName = item.promotionInstanceId?.let { item.promotionName ?: "Combo" },
                serviceCourse = item.serviceCourse,
                externalId = if (item.promotionInstanceId != null) "combo:" + item.promotionInstanceId + ":g:" + item.promotionGroupId else item.id,
                orderPromotionId = item.promotionInstanceId,
            ) },
            orderType = "En tienda" + (course?.let { " · $it" } ?: ""),
            curso = course, etiquetaPantalla = "En tienda",
        )
    }
}
