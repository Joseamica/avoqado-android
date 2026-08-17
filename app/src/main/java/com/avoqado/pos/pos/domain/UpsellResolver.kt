package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.UpsellCard
import com.avoqado.pos.pos.data.model.UpsellRule
import com.avoqado.pos.pos.data.model.descuentoDeLineaCents
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Upsell "¿Algo más?" — decisión PURA de qué tarjetas mostrar.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md
 *
 * 🔴 Sin Android, sin red, sin reloj implícito: el momento entra por parámetro. Toda
 * la corrección vive aquí y se prueba entera sin hardware — la misma disciplina que
 * `chooseCustomerDisplayId` y el núcleo del hub LAN.
 *
 * 🔴 A diferencia de `UpsellHoldout` (que SÍ comparte vectores JSON con TypeScript
 * vía `upsell-test-vectors.json` — ver `UpsellVectorParityTest`), ESTE archivo NO
 * tiene vectores compartidos. iOS espeja esta lógica A MANO, leyendo el código
 * Kotlin real — no hay un test que haga ruido solo si diverge. Por eso Tarea 6
 * (el port a iOS) tiene que leer este archivo tal cual está, no de memoria ni de
 * un resumen: sin vectores, la única red es que alguien lo compare línea por línea.
 */

/** Tope de tarjetas. Más de 3 en una pantalla de señalización no se leen. */
const val MAX_UPSELL_CARDS = 3

/**
 * Filtra, ordena y recorta las sugerencias para un carrito concreto.
 *
 * @param cartProductIds productos que YA están en el carrito
 * @param cartCategoryIds categorías representadas en el carrito
 * @param catalog catálogo cacheado, por id
 * @param nowLocal momento en hora LOCAL DEL VENUE (no del dispositivo, no UTC)
 */
fun resolveUpsellSuggestions(
    rules: List<UpsellRule>,
    cartProductIds: Set<String>,
    cartCategoryIds: Set<String>,
    catalog: Map<String, Product>,
    nowLocal: LocalDateTime,
    maxCards: Int = MAX_UPSELL_CARDS,
): List<UpsellCard> {
    val applicable = rules.filter { rule ->
        val product = catalog[rule.suggestedProductId]

        when {
            // Regla huérfana: el producto ya no está en el catálogo cacheado.
            product == null -> false

            // 🔴 El VETO del dueño. Gana sobre las cuatro capas, incluida la IA.
            // El server ya filtra, pero no dependemos de que una versión vieja del
            // POS lo respete.
            product.upsellEnabled != true -> false

            product.active == false -> false

            // Vender lo que no hay.
            product.isOutOfStock -> false

            // Ofrecer lo que ya llevan quema la tarjeta.
            rule.suggestedProductId in cartProductIds -> false

            // Tocarlo abriría la captura de peso. Una tarjeta debe ser UN toque.
            product.soldByWeight -> false

            // Tocarlo abriría el panel de modificadores. Misma razón que el peso.
            // Es la regla de Square: un artículo con obligatorios SIEMPRE abre su
            // pantalla de detalle.
            //
            // 🔴 SALVO que la regla ya los haya resuelto TODOS (spec 2026-08-16,
            // B3) — no "trae algo", sino un pick por CADA grupo obligatorio.
            // El server ya lo garantiza en cada GET (mismo check en
            // `validateAndResolveModifiers`), pero el POS cachea REGLAS y
            // CATÁLOGO por separado: si el catálogo sincroniza DESPUÉS y el
            // producto gana un segundo obligatorio, una regla vieja que sólo
            // resolvió el primero no puede colarse — la línea entraría
            // sub-especificada a cocina y, si el grupo sin resolver tiene
            // precio, el local cobra de menos.
            !rule.coversAllRequiredGroups(product) -> false

            !rule.matchesTrigger(cartProductIds, cartCategoryIds) -> false

            !rule.isWithinWindow(nowLocal) -> false

            else -> true
        }
    }

    // 🔴 Deduplicar POR PRODUCTO antes de ordenar: las capas (dueño, datos, IA,
    // promoción) pueden sugerir el mismo producto a la vez y el cliente vería tres
    // tarjetas iguales. Gana la de mayor prioridad; desempata el orden de capa.
    val byProduct = applicable
        .groupBy { it.suggestedProductId }
        .mapValues { (_, group) -> group.minWithOrNull(ruleWinnerOrder)!! }
        .values

    return byProduct
        .sortedWith(cardOrder)
        .take(maxCards)
        .mapNotNull { rule -> catalog[rule.suggestedProductId]?.let { rule.toCard(it) } }
}

/** Ante empate de producto: mayor prioridad, luego mayor lift, luego capa más confiable. */
private val ruleWinnerOrder = compareByDescending<UpsellRule> { it.priority }
    .thenByDescending { it.lift ?: 0.0 }

/** Orden de presentación: prioridad, luego evidencia, luego alfabético para ser estable. */
private val cardOrder = compareByDescending<UpsellRule> { it.priority }
    .thenByDescending { it.lift ?: 0.0 }
    .thenBy { it.suggestedProductId }

/** Ids de TODOS los grupos obligatorios del producto — lo que la regla tiene que cubrir. */
private val Product.requiredModifierGroupIds: Set<String>
    get() = sortedModifierGroups.filter { it.required }.mapTo(mutableSetOf()) { it.id }

/**
 * ¿La regla resolvió CADA grupo obligatorio del producto? Vacío en ambos lados
 * (sin obligatorios) es verdad por vacuidad — así esta única llamada reemplaza
 * el viejo par "¿tiene obligatorios? Y ¿vino algo?" sin perder ningún caso: si
 * `requiredModifierGroupIds` está vacío, `all {}` es `true` sin evaluar nada.
 *
 * Mismo check que hace el server en `validateAndResolveModifiers`
 * (`avoqado-server/src/services/upsell/upsellModifiers.ts`): un pick POR grupo
 * obligatorio, no basta con que la regla traiga uno solo si el producto pide
 * varios.
 */
private fun UpsellRule.coversAllRequiredGroups(product: Product): Boolean {
    val resolvedGroupIds = suggestedModifiers.mapTo(mutableSetOf()) { it.groupId }
    return product.requiredModifierGroupIds.all { it in resolvedGroupIds }
}

private fun UpsellRule.matchesTrigger(cartProductIds: Set<String>, cartCategoryIds: Set<String>): Boolean =
    when (triggerType.uppercase()) {
        "PRODUCT" -> triggerProductIds.any { it in cartProductIds }
        "CATEGORY" -> triggerCategoryIds.any { it in cartCategoryIds }
        else -> true // ALWAYS
    }

/**
 * Ventana de días y horas, en hora LOCAL DEL VENUE.
 *
 * 🔴 Convención de días: 0=domingo .. 6=sábado, igual que `Discount.daysOfWeek`.
 * `java.time.DayOfWeek` es 1=lunes..7=domingo, así que se traduce; equivocarse aquí
 * desplaza toda la semana un día y nadie lo nota hasta que el local reclama.
 *
 * 🔴 Ventana que CRUZA MEDIANOCHE: si `timeUntil < timeFrom` (22:00–02:00), la
 * ventana envuelve, y `daysOfWeek` se refiere al día en que EMPIEZA. Una regla de
 * viernes 22:00–02:00 sigue viva a la 01:00 del sábado.
 */
internal fun UpsellRule.isWithinWindow(nowLocal: LocalDateTime): Boolean {
    val from = timeFrom?.toLocalTimeOrNull()
    val until = timeUntil?.toLocalTimeOrNull()
    val time = nowLocal.toLocalTime()

    // Sin ventana horaria: sólo manda el día.
    if (from == null || until == null) {
        return daysOfWeek.isEmpty() || nowLocal.dayOfWeek.toAvoqadoDay() in daysOfWeek
    }

    val wraps = until < from
    val inTime = if (wraps) time >= from || time < until else time >= from && time < until
    if (!inTime) return false
    if (daysOfWeek.isEmpty()) return true

    // En una ventana que envuelve, después de medianoche el día que cuenta es el
    // ANTERIOR: es la misma jornada del negocio.
    val effectiveDay = if (wraps && time < until) nowLocal.dayOfWeek.minus(1) else nowLocal.dayOfWeek
    return effectiveDay.toAvoqadoDay() in daysOfWeek
}

/** java.time: 1=lunes..7=domingo → Avoqado: 0=domingo..6=sábado. */
private fun DayOfWeek.toAvoqadoDay(): Int = value % 7

private fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this) }.getOrNull()

private fun UpsellRule.toCard(product: Product): UpsellCard {
    // 🔴 El descuento pega sobre la LÍNEA COMPLETA — producto + modificadores —,
    // no sólo sobre el producto, porque eso es lo que hace el server: descuenta
    // sobre `itemTotal = precio × cantidad + modificadores × cantidad`
    // (`order.mobile.service.ts`). Descontar antes de sumar los modificadores daba
    // $43 en la tarjeta y $40 en la orden con un -20% sobre $35 + $15.
    //
    // Misma función que usa la línea del carrito ([CartItem.itemDiscountCents]):
    // una sola aritmética, imposible que la tarjeta y el cobro digan cosas
    // distintas.
    val lineaCompleta = product.priceInCents + suggestedModifiers.sumOf { it.priceInCents }
    val withModifiers = lineaCompleta - descuentoDeLineaCents(
        type = linkedDiscount?.type,
        value = linkedDiscount?.value,
        baseCents = lineaCompleta,
    )

    // El nombre RESUELTO: si la regla trae obligatorios ya elegidos, la tarjeta
    // tiene que decir CUÁLES — si sólo dijera "Agua Mineral 1L" a $50, nadie
    // entiende por qué no es el precio de lista ($35).
    val resolvedName = if (suggestedModifiers.isEmpty()) {
        product.name
    } else {
        "${product.name} (${suggestedModifiers.joinToString(", ") { it.name }})"
    }

    return UpsellCard(
        ruleId = id,
        productId = product.id,
        name = resolvedName,
        displayPriceCents = withModifiers,
        imageUrl = product.imageUrl,
        headline = headline,
        badge = linkedDiscount?.badge,
        linkedDiscount = linkedDiscount,
        modifiers = suggestedModifiers,
    )
}
