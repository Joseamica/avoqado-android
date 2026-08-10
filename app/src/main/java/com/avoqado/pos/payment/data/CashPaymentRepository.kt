package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CashPaymentRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val pendingPaymentDao: PendingPaymentDao,
) {
    fun processCashPayment(
        totalCents: Int,
        cashReceivedCents: Int,
    ): CashPaymentResult {
        val changeCents = cashReceivedCents - totalCents
        Log.d("💵", "Cash payment: total=$totalCents, received=$cashReceivedCents, change=$changeCents")

        return if (changeCents >= 0) {
            CashPaymentResult.Success(changeCents = changeCents)
        } else {
            CashPaymentResult.InsufficientFunds(shortfall = -changeCents)
        }
    }

    /**
     * Queue a cash payment for offline sync.
     * Called when the order creation API fails with a network/server error.
     */
    suspend fun queueCashPayment(
        orderRequest: CreateOrderRequest,
        staffId: String,
        cashTenderedCents: Int?,
        changeCents: Int?,
        rating: Int?,
        orderId: String? = null,
        /** Identidad estable de la creación de orden original. */
        orderExternalId: String? = null,
        /**
         * Cliente elegido en el carrito. Se PERSISTE con el pago: sin esto, un
         * cobro hecho sin red se reproducía al reconectar como venta anónima
         * aunque el cajero sí hubiera elegido al cliente — y nadie se entera,
         * porque el ticket ya salió bien.
         */
        customerId: String? = null,
        /**
         * 🔴 LA MISMA llave que usó el intento en línea, no una nueva.
         *
         * El id de esta fila ES la llave que `PaymentSyncService` manda al
         * reintentar. Inventar una aquí rompe la deduplicación del server: si
         * el cobro SÍ se aplicó pero la respuesta se perdió (servidor
         * reiniciado, WiFi caído a media respuesta), el reintento llega con una
         * llave que el server no conoce, se salta el atajo idempotente y choca
         * contra "Order is already paid" — 400 permanente. El cobro queda en
         * cuarentena para siempre aunque el dinero YA esté cobrado, y el
         * gerente no tiene forma de saber si entró.
         *
         * Medido el 2026-08-09 con el log del backend: el pago quedó guardado
         * con la llave `65fb7769…`, hubo 6 reintentos y CERO deduplicaciones.
         */
        idempotencyKey: String? = null,
        /**
         * Cobro registrado a mano (terminal ajena, transferencia). null =
         * efectivo. Se PERSISTE: sin esto, un cobro con tarjeta hecho sin red
         * se reproducía como efectivo al reconectar y el corte pedía dinero
         * que nunca entró al cajón.
         */
        manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null,
    ): String {
        val localId = idempotencyKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val hasOrderItems = OrderRepository.hasProductItems(orderRequest)
        val paymentType = if (orderId != null || hasOrderItems) "ORDER" else "FAST"
        val entity = PendingPaymentEntity(
            id = localId,
            venueId = secureStorage.venueId ?: "",
            staffId = staffId,
            amountCents = orderRequest.total - orderRequest.tip,
            tipCents = orderRequest.tip,
            // Se guarda el nombre del método MANUAL (o "CASH"); el sync lo
            // traduce al enum del server al reproducirlo.
            method = manualMethod?.name ?: "CASH",
            paymentType = paymentType,
            orderId = orderId,
            orderNumber = null,
            cashTenderedCents = cashTenderedCents,
            changeCents = changeCents,
            rating = rating,
            itemsJson = null,
            orderRequestJson = if (hasOrderItems) {
                OrderRepository.buildCreateOrderPayload(
                    request = orderRequest,
                    staffId = staffId,
                    customerId = customerId,
                    externalId = orderExternalId ?: "offline-order:$localId",
                )
            } else {
                null
            },
            syncStatus = PaymentSyncStatus.PENDING.name,
            retryCount = 0,
            createdAt = System.currentTimeMillis(),
        )

        pendingPaymentDao.insert(entity)
        Log.d("💵", "💾 Cash payment queued offline: $localId ($paymentType)")
        return localId
    }
}

sealed class CashPaymentResult {
    data class Success(val changeCents: Int) : CashPaymentResult()
    data class InsufficientFunds(val shortfall: Int) : CashPaymentResult()
}
