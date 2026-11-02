package com.avoqado.pos.customers.presentation

import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.transactions.presentation.exchange.exchangeMoney

/**
 * El renglón de la ficha del cliente (spec 2026-10-30 §4, §5.7). `null` = el cliente no tiene sobre: no se pinta nada (ni «$0.00»).
 * Congelado dice «Saldo a favor congelado» SIN monto (O-7, A5): el servidor manda `balanceCents: 0` porque lo congelado no se cuenta, y
 * «$0.00» se leía como dinero perdido. Espejo de `lineaSaldoAFavorDeLaFicha` en iOS.
 */
fun lineaSaldoAFavorDeLaFicha(customer: Customer): String? {
    val saldo = customer.storeCredit ?: return null
    if (saldo.blocked == true) return TextosCambio.SALDO_A_FAVOR_CONGELADO_FICHA
    return TextosCambio.SALDO_A_FAVOR_FICHA.replace("{monto}", exchangeMoney(saldo.balanceCents ?: 0L))
}
