package com.avoqado.pos.tables.data

import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine

/** Un renglón de la ronda (ya con su llave `sync:<ronda>:<i>`) como lo rutea la cocina: el MISMO al enviar y al reimprimir. */
fun renglonDeCocinaDeRonda(line: PendingLine) = RoutableItem(
    orderItemId = line.item.id,
    productId = (line.item.type as? CartItemType.ProductItem)?.productId,
    categoryId = line.item.categoryId,
    productName = line.item.name,
    quantity = line.item.quantity,
    modifiers = line.item.selectedModifiers.map { it.modifierName },
    notes = line.item.itemNote,
    comboName = line.item.promotionName,
    serviceCourse = line.item.serviceCourse,
    externalId = line.externalId,
    orderPromotionId = line.item.promotionInstanceId,
)

/**
 * Los pedidos de cocina de una ronda: un lote por tiempo («Mesa 8 · Aperitivos»), sólo productos del catálogo (un importe libre
 * va a la cuenta, nunca a cocina). El mismo armado al enviarla y al reimprimirla.
 */
fun pedidosDeLaRonda(lines: List<PendingLine>, roundKey: String, tableNumber: String, configCongelada: PrintConfig?): List<ComandaDispatcher.Pedido> =
    stampTableRoundLines(lines, roundKey)
        .filter { it.item.type is CartItemType.ProductItem }
        .groupBy { kitchenCourseLabel(it.item.serviceCourse, it.course) }
        .map { (course, courseLines) ->
            ComandaDispatcher.Pedido(
                lines = courseLines.map(::renglonDeCocinaDeRonda),
                orderType = "Mesa $tableNumber" + (course?.let { " · $it" } ?: ""),
                // KDS 3.6: la pantalla junta los cursos en una tarjeta — «Mesa 8» arriba y el tiempo en cada platillo.
                curso = course,
                etiquetaPantalla = "Mesa $tableNumber",
                configCongelada = configCongelada,
                omitirSinComanda = true, // «No lleva comanda»: la ronda es una venta del POS.
            )
        }
