package com.avoqado.pos.tables.data

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.promotionRefDe

/** One package reference, with independent choices per group. Never also send its products. */
fun buildTableRoundRequests(
    items: List<CartItem>,
    legacyCourses: Map<String, String?> = emptyMap(),
    seats: Map<String, Int?> = emptyMap(),
): List<AddOrderItemRequest> {
    val emitted = mutableSetOf<String>()
    return items.mapNotNull { item ->
        item.promotionInstanceId?.let { instance ->
            if (!emitted.add(instance)) return@mapNotNull null
            val siblings = items.filter { it.promotionInstanceId == instance }
            require(siblings.all { !it.promotionId.isNullOrBlank() && !it.promotionGroupId.isNullOrBlank() && !it.promotionOptionId.isNullOrBlank() } &&
                siblings.map { it.promotionId }.distinct().size == 1) { "El combo está incompleto. Vuelve a elegirlo antes de enviar." }
            val ref = requireNotNull(promotionRefDe(siblings, instance))
            return@mapNotNull AddOrderItemRequest(quantity = 1, promotionRef = ref)
        }
        AddOrderItemRequest(
            productId = (item.type as? CartItemType.ProductItem)?.productId,
            quantity = item.quantity,
            notes = item.itemNote,
            modifierIds = item.selectedModifiers.map { it.modifierId }.ifEmpty { null },
            course = item.serviceCourse?.legacyCourse ?: legacyCourses[item.id].takeIf { item.serviceCourse == null },
            serviceCourse = item.serviceCourse,
            customName = item.name.takeUnless { item.type is CartItemType.ProductItem },
            customUnitPriceCents = item.effectiveUnitPrice.takeUnless { item.type is CartItemType.ProductItem },
            isCortesia = item.isCortesia.takeIf { it },
            cortesiaReason = item.cortesiaReason?.takeIf { item.isCortesia },
            seat = seats[item.id],
        )
    }
}

/** Exact identities used by the reducer, including a stable group key inside each package. */
fun stampTableRoundLines(lines: List<com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine>, roundKey: String): List<com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine> {
    var wrapperIndex = 0
    val packageIndexes = mutableMapOf<String, Int>()
    return lines.map { line ->
        val instance = line.item.promotionInstanceId
        val index = if (instance == null) wrapperIndex++ else packageIndexes.getOrPut(instance) { wrapperIndex++ }
        val key = RondaConLlave.llave(roundKey, index) + (line.item.promotionGroupId?.takeIf { instance != null }?.let { ":g:$it" } ?: "")
        line.copy(externalId = key)
    }
}

/** Preserve ordinary history; never repeat package components as separately priced products. */
fun buildRepeatedTableLines(items: List<OrderDetailItem>): List<CartItem> {
    require(items.none { it.orderPromotionId != null }) { "Este tiempo incluye un combo. Elige el paquete completo desde Combos para conservar su precio." }
    return items.mapNotNull { item ->
        val productId = item.productId ?: return@mapNotNull null
        CartItem(type = CartItemType.ProductItem(productId), name = item.productName ?: "Artículo",
            unitPrice = kotlin.math.round(item.unitPrice * 100).toInt(), quantity = item.quantity,
            selectedModifiers = item.modifiers.map { com.avoqado.pos.pos.data.model.SelectedModifier("", "", it.id, it.name, kotlin.math.round(it.price * 100).toInt()) },
            itemNote = item.notes, serviceCourse = item.serviceCourse)
    }
}

fun kitchenCourseLabel(snapshot: com.avoqado.pos.pos.data.model.ServiceCourseSnapshot?, legacy: String?): String? =
    if (snapshot?.kind == "IMMEDIATE" && snapshot.label == "Inmediato") null else snapshot?.label ?: legacy

/** The same frozen routing and external identities as the comanda, before its server acknowledgement. */
fun compactRoundPreparationConfig(lines: List<com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine>,
    config: com.avoqado.pos.printing.routing.PrintConfig): com.avoqado.pos.printing.routing.PrintConfig {
    val products = lines.mapNotNull { (it.item.type as? CartItemType.ProductItem)?.productId }.toSet()
    val categories = lines.mapNotNull { it.item.categoryId }.toSet()
    val overrides = config.productOverrides.filter { it.productId in products }
    val routes = config.categoryRouting.filter { it.categoryId in categories }
    val ids = (overrides.map { it.printStationId } + routes.map { it.printStationId } + listOfNotNull(config.defaultStationId, config.packingStationId)).toSet()
    val stations = config.stations.filter { it.id in ids }
    val printers = stations.mapNotNull { it.printerId }.toSet()
    return config.copy(stations = stations, printers = config.printers.filter { it.id in printers }, categoryRouting = routes, productOverrides = overrides)
}

fun seedTableRoundPreparation(
    lines: List<com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine>, roundKey: String,
    orderId: String, orderNumber: String, config: com.avoqado.pos.printing.routing.PrintConfig,
    provisional: Boolean = false,
): List<com.avoqado.pos.kds.domain.PreparationLine> {
    require(lines.size <= 100 && lines.map { it.item.id }.distinct().size == lines.size &&
        lines.all { it.item.id.isNotBlank() && it.item.quantity > 0 }) { "La ronda guardada necesita revisarse. No se borró." }
    val stamped = stampTableRoundLines(lines, roundKey)
    require(stamped.map { it.externalId }.distinct().size == stamped.size) { "La ronda guardada tiene identificadores repetidos. No se borró." }
    val source = stamped.associateBy { it.item.id }
    val inputs = stamped.mapNotNull { line ->
        val product = line.item.type as? CartItemType.ProductItem ?: return@mapNotNull null
        com.avoqado.pos.printing.routing.RoutableItem(line.item.id, product.productId, line.item.categoryId,
            line.item.name, line.item.quantity, line.item.selectedModifiers.map { it.modifierName }, line.item.itemNote,
            line.item.promotionName, line.item.serviceCourse, line.externalId, line.item.promotionInstanceId)
    }
    return com.avoqado.pos.printing.routing.PrintRoutingMapper.buildComandas(inputs, config).flatMap { plan ->
        if (plan.lines.none { it.serviceCourse?.preparationVersion == 1 }) emptyList() else
            plan.lines.flatMap { it.orderItemIds }.map { id ->
                val line = source.getValue(id)
                com.avoqado.pos.kds.domain.PreparationLine(
                    id = "local:$roundKey:$id:${plan.stationId.orEmpty()}", orderId = orderId,
                    localOrderId = orderId.takeIf { provisional }, externalId = line.externalId,
                    sourceKey = com.avoqado.pos.printing.routing.KitchenDeliveryPolicy.folio("round:$roundKey", plan.stationId),
                    stationId = plan.stationId, orderNumber = orderNumber, productName = line.item.name,
                    quantity = line.item.quantity, serviceCourse = line.item.serviceCourse,
                    orderPromotionId = line.item.promotionInstanceId,
                    modifiers = line.item.selectedModifiers.map { it.modifierName }, notes = line.item.itemNote,
                    preparation = com.avoqado.pos.kds.domain.PreparationCounts.initial(line.item.quantity,
                        line.item.serviceCourse?.kind == "STANDARD"),
                )
            }
    }
}
