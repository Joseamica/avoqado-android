package com.avoqado.pos.pos.data.model

import com.avoqado.pos.core.util.formatMoneyFromCents
import kotlinx.serialization.Serializable

/*
 * «Se cobra el precio que vio el cliente» (17-oct-2026). El servidor decide el precio de cada línea —el MÁS BAJO entre el que
 * mostró la tablet (si fue real en las últimas 72 h) y el vigente— y lo dice en `order.priceChanges` (201) o, en línea, con un
 * 409 `ORDER_PRICE_CHANGED` (`details.priceChanges`). Aquí vive lo que la caja hace con eso: corregir sus líneas, decirlo en
 * palabras y no recalcular el descuento de cuenta que ya viajó. Espejo de `PrecioQueVioElCliente.swift` (avoqado-ios): mismos
 * nombres, misma semántica y textos idénticos.
 */

/**
 * Una línea que el servidor cobró a un precio distinto del que mostró la caja. Precios en CENTAVOS (el resto de la respuesta va
 * en pesos: el sufijo marca la excepción). La caja corrige la línea cuyo `CartItem.id == externalId`.
 */
@Serializable
data class CambioDePrecio(
    val externalId: String? = null,
    val productId: String,
    val productName: String = "",
    val catalogAt: String? = null,
    val displayedUnitPriceCents: Int,
    val unitPriceCents: Int,
)

const val TITULO_AVISO_DE_PRECIO = "Revisa el precio"

/**
 * D2 v7: la venta se cobró por otro camino y el borrado del apartado de un intento anterior no se pudo escribir en el aparato. Se
 * reintenta antes del siguiente cobro; mientras no quede escrito, no se cobra.
 */
const val MENSAJE_RESERVA_SIN_RETIRAR = "No se pudo guardar en el aparato que el cobro anterior no se hizo. Vuelve a intentar."

/**
 * Corrige `unitPrice` SÓLO de la línea de producto que el servidor nombró (`id == externalId`); todo lo demás queda intacto.
 * 🔴 Por identidad de línea, nunca por `productId`: el mismo producto puede ir en dos líneas con precios mostrados distintos.
 */
fun aplicarCambiosDePrecio(items: List<CartItem>, cambios: List<CambioDePrecio>): List<CartItem> = items.map { item ->
    if (item.type !is CartItemType.ProductItem) return@map item
    val cambio = cambios.firstOrNull { it.externalId == item.id } ?: return@map item
    item.copy(unitPrice = cambio.unitPriceCents)
}

/** El aviso al cajero: un renglón por producto y precio distintos, luego el cambio de más y el total nuevo si aplican. */
fun avisoDeCambioDePrecio(cambios: List<CambioDePrecio>, totalNuevoCents: Int? = null, cambioDeMasCents: Int = 0): String {
    val renglones = cambios.distinctBy { it.productId to it.unitPriceCents }.map { c ->
        val verbo = if (c.unitPriceCents < c.displayedUnitPriceCents) "bajó" else "cambió"
        "${c.productName} $verbo de precio: ahora cuesta ${formatMoneyFromCents(c.unitPriceCents)}."
    }.toMutableList()
    if (cambioDeMasCents > 0) renglones += "Devuelve ${formatMoneyFromCents(cambioDeMasCents)} más de cambio."
    totalNuevoCents?.let { renglones += "Total nuevo: ${formatMoneyFromCents(it)}." }
    return renglones.joinToString("\n")
}

/**
 * Junta las correcciones de UNA venta (p. ej. un 409 y luego el 201 del reintento): una entrada por `externalId`, con el ÚLTIMO
 * precio cobrado y el PRIMER precio mostrado —el que vio el cajero; el intermedio nunca lo vio—. Los cambios sin `externalId`
 * no se correlacionan y se suman tal cual. Igual que `acumular` de iOS.
 */
fun acumularCambiosDePrecio(acumulados: List<CambioDePrecio>, nuevos: List<CambioDePrecio>): List<CambioDePrecio> =
    nuevos.fold(acumulados) { lista, c ->
        val i = c.externalId?.let { id -> lista.indexOfFirst { it.externalId == id } } ?: -1
        if (i < 0) lista + c else lista.toMutableList().also { it[i] = c.copy(displayedUnitPriceCents = lista[i].displayedUnitPriceCents) }
    }

/**
 * El aviso de precio de una venta, tras acumular sus correcciones. Sin los cambios que se deshicieron solos (su precio final es
 * el que vio el cajero). «Total nuevo» sólo si hubo un 409 ([totalAntesCents] = el total que vio el cajero al empezar; null = no
 * hubo) y, si no queda ningún renglón, sólo si el total sí cambió. null = nada que decir. La corrección del carrito no depende
 * de esto. Misma semántica que `avisoDePrecioDeLaCreacion` de iOS.
 */
fun avisoDePrecioDeLaCreacion(
    cambios: List<CambioDePrecio>,
    totalNuevoCents: Int?,
    totalAntesCents: Int?,
    cambioDeMasCents: Int = 0,
): String? {
    val visibles = cambios.filter { it.displayedUnitPriceCents != it.unitPriceCents }
    val total = totalNuevoCents?.takeIf { totalAntesCents != null }?.takeUnless { visibles.isEmpty() && it == totalAntesCents }
    if (visibles.isEmpty() && total == null && cambioDeMasCents <= 0) return null
    return avisoDeCambioDePrecio(visibles, total, cambioDeMasCents)
}

/**
 * 🔴 DINERO. El descuento de CUENTA que viajó como `discount` es un monto FIJO: el servidor lo conserva al corregir precios, y la
 * caja también, para cobrar lo mismo que la orden. Sólo nace de un 201 con cambios (la orden YA existe con ese monto; un 409 no
 * creó nada y no congela). Vale mientras el carrito siga siendo esa venta —sus mismas líneas, o un subconjunto tras un pago
 * parcial, con las mismas cantidades y precios—; cualquier edición lo deja sin efecto y vuelve el cálculo de siempre.
 * Espejo de `DescuentoCongelado` de iOS (misma huella).
 */
@Serializable
data class DescuentoCongelado(val cents: Int, val huellas: Map<String, String>) {
    fun vigentePara(items: List<CartItem>): Boolean = items.all { huellas[it.id] == huella(it) }

    companion object {
        fun huella(item: CartItem): String =
            with(item) { "$quantity|$unitPrice|$priceAdjustment|$itemDiscountId|$weightKg|$isCortesia" }

        fun de(cents: Int, items: List<CartItem>) = DescuentoCongelado(cents, items.associate { it.id to huella(it) })
    }
}
