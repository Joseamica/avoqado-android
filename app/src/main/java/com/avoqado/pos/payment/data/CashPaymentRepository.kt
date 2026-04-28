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
    ): String {
        val localId = UUID.randomUUID().toString()
        val hasOrderItems = OrderRepository.hasProductItems(orderRequest)
        val paymentType = if (orderId != null || hasOrderItems) "ORDER" else "FAST"
        val entity = PendingPaymentEntity(
            id = localId,
            venueId = secureStorage.venueId ?: "",
            staffId = staffId,
            amountCents = orderRequest.total - orderRequest.tip,
            tipCents = orderRequest.tip,
            method = "CASH",
            paymentType = paymentType,
            orderId = orderId,
            orderNumber = null,
            cashTenderedCents = cashTenderedCents,
            changeCents = changeCents,
            rating = rating,
            itemsJson = null,
            orderRequestJson = if (hasOrderItems) {
                OrderRepository.buildCreateOrderPayload(orderRequest, staffId)
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
