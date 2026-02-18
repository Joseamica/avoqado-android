package com.avoqado.pos.payment.data

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CashPaymentRepository @Inject constructor() {

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
}

sealed class CashPaymentResult {
    data class Success(val changeCents: Int) : CashPaymentResult()
    data class InsufficientFunds(val shortfall: Int) : CashPaymentResult()
}
