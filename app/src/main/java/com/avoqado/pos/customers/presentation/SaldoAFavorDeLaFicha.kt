package com.avoqado.pos.customers.presentation

import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.transactions.presentation.exchange.exchangeMoney

/**
 * El renglón de la ficha del cliente (spec 2026-10-30 §4, §5.7). `null` = el cliente no tiene sobre: no se pinta nada (ni «$0.00»).
 * Congelado dice «congelado» y trae el monto. Espejo de `lineaSaldoAFavorDeLaFicha` en iOS.
 */
fun lineaSaldoAFavorDeLaFicha(customer: Customer): String? {
    val saldo = customer.storeCredit ?: return null
    val plantilla = if (saldo.blocked == true) TextosCambio.SALDO_A_FAVOR_CONGELADO_FICHA else TextosCambio.SALDO_A_FAVOR_FICHA
    return plantilla.replace("{monto}", exchangeMoney(saldo.balanceCents ?: 0L))
}
