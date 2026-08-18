package com.avoqado.pos.core.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PaymentSyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED,
}

@Entity(tableName = "pending_payments")
data class PendingPaymentEntity(
    @PrimaryKey val id: String,           // UUID (idempotency key)
    val venueId: String,
    val staffId: String,
    val amountCents: Int,
    val tipCents: Int,
    val method: String,                    // "CASH"
    val paymentType: String,               // "FAST" or "ORDER"
    val orderId: String? = null,
    val orderNumber: String? = null,
    val cashTenderedCents: Int? = null,
    val changeCents: Int? = null,
    val rating: Int? = null,
    val itemsJson: String? = null,         // Serialized items
    val orderRequestJson: String? = null,  // Full CreateOrderRequest JSON for retry
    val syncStatus: String = PaymentSyncStatus.PENDING.name,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRetryAt: Long? = null,
    /**
     * Tipo de pago del catálogo elegido por el cajero ("Uber Eats", "Terminal BBVA").
     *
     * 🔴 Sin esto, una venta cobrada SIN RED perdía el tipo al reproducirse y
     * aterrizaba como EFECTIVO, en silencio — y la idempotencia impedía repararla.
     * Se guarda la REFERENCIA, nunca la comisión: el server la resuelve, y honra
     * esta `revision` (la que el cajero vio) aunque el catálogo cambie mientras la
     * tablet está desconectada.
     */
    val tenderTypeId: String? = null,
    val tenderRevision: Int? = null,
    /**
     * 🔴 EL CLIENTE DE LA VENTA, elegido por el cajero en el carrito.
     *
     * Sin esta columna, un cobro RÁPIDO ("Otro importe") hecho sin red perdía al
     * cliente: el `orderRequestJson` —que sí lo llevaba— sólo existe cuando la venta
     * tiene productos, así que la venta rápida se reproducía anónima al reconectar.
     * Nadie se entera: el ticket ya salió bien y la idempotencia impide repararlo.
     *
     * El server valida contra el negocio al reproducir; un id inválido NO rechaza el
     * cobro encolado (registra anónima y avisa), así que esta columna nunca puede
     * atorar la cola.
     */
    val customerId: String? = null,
)
