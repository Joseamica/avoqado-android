package com.avoqado.pos.kds.domain

import kotlinx.serialization.Serializable

// MARK: - Domain Models

/** `@Serializable` por la foto del tablero en el aparato (`KdsPrefs.guardarFoto`, Codex 3.6 #2). */
@Serializable
data class KDSOrder(
    val id: String,
    /**
     * El id de la ORDEN de venta, distinto del id de esta comanda. Es el que se necesita
     * para aceptar o rechazar el pedido en la app de delivery.
     */
    val orderId: String? = null,
    val orderNumber: String,
    val orderType: String,
    /**
     * ¿Falta que alguien acepte este pedido en la app de delivery?
     *
     * Sólo pasa en canales configurados en MANUAL: la venta entra pendiente porque NADIE le
     * ha dicho que sí al proveedor, y el plazo (~11.5 min en Uber) ya está corriendo. Si
     * nadie lo acepta, el proveedor lo cancela y el cliente se queda sin comida.
     */
    val needsAcceptance: Boolean = false,
    /**
     * ¿Falta que un aparato reclame e imprima esta comanda?
     *
     * Sólo llega en `true` para pedidos de marketplace: los que manda un mesero desde una
     * tablet ya se imprimen en ese mismo gesto. Aquí no hay gesto humano — el pedido aparece
     * en todas las pantallas a la vez y alguien tiene que reclamar el trabajo.
     */
    val needsPrint: Boolean = false,
    val items: List<KDSOrderItem>,
    val createdAt: Long,
    var status: KDSOrderStatus,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    /** Folio de la comanda (spec 2026-09-27 §1): `sale:…`, `round:…`, `order:…`. `null` = Uber o fila vieja. */
    val sourceKey: String? = null,
    /** Estación a la que el servidor la repartió. `null` = «Sin estación»: sale en todas las pantallas. */
    val printStationId: String? = null,
    val preparationVersion: Int = 0,
    /** Dónde imprimir esta comanda de delivery según el canal. `null` = servidor viejo o sin ajuste: como en el salón. */
    val impresionReparto: ImpresionReparto? = null,
)

/** Lo que el negocio eligió para imprimir los pedidos de un proveedor de delivery. */
@Serializable
data class ImpresionReparto(
    val proveedor: String? = null,
    val estacionUnicaId: String? = null,
    val empaque: Boolean = true,
    val empaqueEstacionId: String? = null,
)

@Serializable
data class KDSOrderItem(
    val id: String,
    val productName: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String? = null,
    /** Para RUTEAR el renglón a su estación. `null` = no supimos de qué producto es. */
    val productId: String? = null,
    val categoryId: String? = null,
    /** KDS 3.6: el tiempo del platillo en una mesa («Aperitivos»). `null` = sin tiempo. */
    val course: String? = null,
    val orderItemId: String? = null,
    val externalId: String? = null,
    val orderPromotionId: String? = null,
    val serviceCourse: com.avoqado.pos.pos.data.model.ServiceCourseSnapshot? = null,
    val preparation: PreparationCounts? = null,
    val preparationRevision: Int = 0,
    val pendingPreparation: Boolean = false,
    /**
     * D17: en cuántas estaciones se prepara, según el SERVIDOR. `null` = no se sabe (servidor viejo, comanda que llegó por el WiFi del
     * local): la cocina sólo ofrece «Liberar» cuando el servidor dijo 1 (Codex r7 #1); lo de varias estaciones lo libera la caja.
     */
    val stationCount: Int? = null,
    /**
     * D15: el SERVIDOR lo quitó de la cuenta después de mandarlo (anulado en la caja, o retirado por la plataforma). Sigue en la
     * comanda para que la cocina vea que cambió; su nombre ya trae la etiqueta («ANULADO · », «RETIRADO · ») para las pantallas
     * viejas. `null` = sigue vivo.
     */
    val removedAt: String? = null,
)

/** D15 × D17 (QA del conjunto): «Recientes» cuenta sólo lo que se preparó; lo anulado (o retirado) va aparte. */
fun resumenDePlatillos(items: List<KDSOrderItem>): String {
    val anulados = items.count { it.removedAt != null }
    val vivos = items.size - anulados
    val platillos = "$vivos ${if (vivos == 1) "platillo" else "platillos"}"
    return if (anulados == 0) platillos else "$platillos · $anulados ${if (anulados == 1) "anulado" else "anulados"}"
}

/** Un renglón quitado, partido para pintarlo: la etiqueta sin tachar y el nombre tachado. */
data class RenglonQuitado(val etiqueta: String, val nombre: String)

private val ETIQUETAS_DE_QUITADO = listOf("ANULADO", "RETIRADO")

/** D15: `null` si el renglón sigue vivo; si el servidor lo quitó, su etiqueta (la del nombre, o «ANULADO») y el nombre sin ella. */
fun KDSOrderItem.quitado(): RenglonQuitado? {
    if (removedAt == null) return null
    val etiqueta = ETIQUETAS_DE_QUITADO.firstOrNull { productName.startsWith("$it · ") } ?: return RenglonQuitado("ANULADO", productName)
    return RenglonQuitado(etiqueta, productName.removePrefix("$etiqueta · "))
}

/**
 * Etapa 3 del KDS (3.5, D8): una comanda guardada en ESTE aparato — llegó por el WiFi del local (antes de acusar) o se
 * marcó LISTO sin red (`listaEnMillis`). Espejo de `KdsTicketLocal` de iOS.
 */
data class KdsTicketLocal(
    val sourceKey: String,
    val venueId: String,
    val stationId: String,
    val orderNumber: String,
    val orderType: String,
    val items: List<KDSOrderItem>,
    val recibidaEnMillis: Long,
    val listaEnMillis: Long?,
)

enum class KDSOrderStatus(val label: String) {
    NEW("Nuevo"),
    PREPARING("En preparacion"),
    READY("Listo"),
    COMPLETED("Completado"),
}

/**
 * Un canal de reparto tal como lo necesita el POS: nada de secretos ni configuración.
 *
 * `pausado=true` con `pausadoHasta=null` es la pausa INDEFINIDA que puso el dueño desde el
 * dashboard: se muestra, pero sin cuenta regresiva y sin botón de reanudar — desde el piso
 * no se reabre lo que el dueño cerró.
 */
data class CanalReparto(
    val id: String,
    val proveedor: String,
    val pausado: Boolean,
    val pausadoHasta: String?,
    /** `ACTIVE`, `PAUSED`, `PENDING` o `DISABLED` (servidor). Sólo `ACTIVE` y `PAUSED` pueden traer pedidos. */
    val estado: String = "ACTIVE",
)
