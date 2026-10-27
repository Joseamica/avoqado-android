package com.avoqado.pos.core.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PaymentSyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED,

    /**
     * Cobro en efectivo guardado ANTES de mandarlo al servidor (30-sep-2026). Nadie lo reproduce
     * solo y el cajón no lo cuenta: si el proceso muere a media petición no se sabe si la venta
     * se completó, así que al reabrir aparece como «sin confirmar» y una persona decide.
     * Ver `CashPaymentRepository.reservarCobro`.
     */
    EN_VUELO,
}

@Entity(tableName = "pending_payments", indices = [Index(value = ["venueId", "q15Reason", "q15ReviewedAt", "q15DetectedAt", "id"]), Index(value = ["venueId", "capturedOrderRef"])])
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
    /** La parte capturada se reproduce con su etiqueta original. Legacy conserva FULLPAYMENT. */
    @ColumnInfo(defaultValue = "'FULLPAYMENT'") val splitType: String = "FULLPAYMENT",
    // Q15: NULL on legacy rows; never infer original financial evidence during migration.
    val q15CaptureJson: String? = null,
    val q15InitialResultJson: String? = null,
    val q15ResultJson: String? = null,
    val q15Reason: String? = null,
    val q15DetectedAt: Long? = null,
    val q15ReviewedAt: Long? = null,
    val q15ReviewedBy: String? = null,
    val q15ReviewRole: String? = null,
    val capturedOrderRef: String? = null,
    /**
     * 🔴 LA CAJA DE LA VENTA (efectivo sin red en el turno correcto, 21-oct-2026): la caja que estaba abierta en
     * ESTE aparato cuando el cajero confirmó el cobro, con el id que el aparato tenía en ese momento (local si la
     * apertura no había llegado al servidor). Se escribe en la reserva, ANTES de tocar la red.
     *
     * Al reproducir viaja traducida al id del servidor (`CashDrawerRepository.idDeCajaParaElServidor`) como
     * `cashDrawerSessionId`: el servidor saca de ahí el turno de la venta, y una venta cuyo turno ya cerró queda
     * aparte con aviso en vez de caer en el turno nuevo. También es la barrera del cierre: el cierre encolado de
     * esa caja espera a que sus cobros suban.
     *
     * La PRIMERA captura manda ([filaAlReemplazar]): un reemplazo posterior de la fila conserva la de disco.
     * Nula en las filas de antes de la v17 y cuando no había caja abierta: esas suben exactamente como hoy.
     */
    val cashDrawerSessionId: String? = null,
)
