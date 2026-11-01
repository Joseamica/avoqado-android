package com.avoqado.pos.transactions

import com.avoqado.pos.customers.presentation.puedeGuardarCliente
import com.avoqado.pos.transactions.data.model.PayoutOption
import com.avoqado.pos.transactions.data.model.StoreCreditAbono
import com.avoqado.pos.transactions.data.model.StoreCreditOption
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.esRechazoDefinitivoDeSaldo
import com.avoqado.pos.transactions.presentation.exchange.*
import org.junit.Assert.*
import org.junit.Test

/** Saldo a favor en el Cambio (A2): reglas puras. Espejo exacto de SaldoAFavorCambioTests.swift. */
class SaldoAFavorCambioTest {
    private val ana = ExchangeStoreCreditCustomer("customer", "Ana López", "+525512345678", phoneChecked = true)
    private val offered = listOf(PayoutOption("CASH", 50000, 50000, false), PayoutOption("BANK_TRANSFER", 50000, 50000, false),
        PayoutOption("STORE_CREDIT", 50000, 0, false))

    private fun methods(storeCreditOnly: Boolean, hasDefect: Boolean, options: List<PayoutOption> = offered) =
        ExchangeSettlement.payoutOptions(options, 20000, true, false, storeCreditOnly = storeCreditOnly, hasDefect = hasDefect,
            customer = ana).map { it.method }

    @Test fun `sin sólo saldo salen todas y el saldo a favor va primero`() {
        assertEquals(listOf("STORE_CREDIT", "CASH", "BANK_TRANSFER"), methods(storeCreditOnly = false, hasDefect = false))
    }

    @Test fun `con sólo saldo y sin Defecto sólo queda el saldo a favor`() {
        assertEquals(listOf("STORE_CREDIT"), methods(storeCreditOnly = true, hasDefect = false))
    }

    @Test fun `con sólo saldo y una pieza con Defecto salen todas`() {
        assertEquals(listOf("STORE_CREDIT", "CASH", "BANK_TRANSFER"), methods(storeCreditOnly = true, hasDefect = true))
    }

    @Test fun `con sólo saldo regresar al mismo sobre de regalo sigue permitido`() {
        val giftCard = listOf(PayoutOption("GIFT_CARD_BALANCE", 50000, 50000, false), PayoutOption("CASH", 50000, 0, true))
        assertEquals(listOf("GIFT_CARD_BALANCE"), methods(storeCreditOnly = true, hasDefect = false, options = giftCard))
    }

    @Test fun `sin las llaves el saldo a favor se ve en gris y se explica`() {
        val options = ExchangeSettlement.payoutOptions(offered.take(2), 20000, true, false, storeCreditAvailable = false, customer = ana)
        assertEquals(listOf("STORE_CREDIT", "CASH", "BANK_TRANSFER"), options.map { it.method })
        assertEquals("Saldo a favor", options.first().label)
        assertEquals("Pide a Avoqado que active el saldo a favor", options.first().disabledReason)
    }

    @Test fun `el saldo a favor pide red, a quién abonar y su teléfono`() {
        fun reason(online: Boolean, customer: ExchangeStoreCreditCustomer?) = ExchangeSettlement.payoutOptions(offered, 20000, true,
            false, online = online, customer = customer).first { it.method == "STORE_CREDIT" }.disabledReason
        assertEquals("El saldo a favor necesita conexión", reason(false, ana))
        assertEquals("Elige a quién se le abona el saldo a favor", reason(true, null))
        assertEquals("Para darle saldo a favor a Ana López hace falta su teléfono: con él lo usa. Agrégalo a su ficha.",
            reason(true, ana.copy(phone = null)))
        // El cliente de la venta llega sin teléfono: el servidor lo revisa y, si falta, rechaza con su mensaje.
        assertNull(reason(true, ExchangeStoreCreditCustomer("customer", "Ana López")))
        assertNull(reason(true, ana))
    }

    @Test fun `sin el dato del servidor el saldo a favor no aparece, en falso sale en gris y en verdadero se ofrece`() {
        fun options(available: Boolean?, offeredByServer: Boolean) = ExchangeSettlement.payoutOptions(
            if (offeredByServer) offered else offered.take(2), 20000, true, false, storeCreditAvailable = available, customer = ana)
        assertEquals(listOf("CASH", "BANK_TRANSFER"), options(null, false).map { it.method })
        assertEquals("Pide a Avoqado que active el saldo a favor", options(false, false).first { it.method == "STORE_CREDIT" }.disabledReason)
        assertEquals(listOf("CASH", "BANK_TRANSFER"), options(true, false).map { it.method })
        assertNull(options(true, true).first { it.method == "STORE_CREDIT" }.disabledReason)
    }

    @Test fun `Pide a Avoqado sólo sale donde el saldo a favor aplicaría`() {
        val sinAplicar = ExchangeSettlement.payoutOptions(offered.take(2), 20000, true, false, storeCreditAvailable = false,
            storeCreditApplies = false, customer = ana)
        assertEquals(listOf("CASH", "BANK_TRANSFER"), sinAplicar.map { it.method })
        val porActivar = StoreCreditOption(false, "Pide a Avoqado que active el saldo a favor.")
        assertTrue(ExchangeSettlement.storeCreditApplies(Transaction("t", storeCreditOption = porActivar)))
        assertFalse(ExchangeSettlement.storeCreditApplies(Transaction("t", storeCreditOption = porActivar, tenderSystemKind = "GIFT_CARD")))
        assertFalse(ExchangeSettlement.storeCreditApplies(Transaction("t")))
    }

    @Test fun `el renglón dice a quién y con qué teléfono, con respaldo sin nombre`() {
        assertEquals("Saldo a favor para: Ana López · +525512345678", ExchangeSettlement.storeCreditCustomerLabel(ana))
        assertEquals("Saldo a favor para: +525512345678", ExchangeSettlement.storeCreditCustomerLabel(ana.copy(name = " ")))
        assertEquals("Saldo a favor para: Cliente", ExchangeSettlement.storeCreditCustomerLabel(ExchangeStoreCreditCustomer("c", "")))
        assertEquals("Elige a quién se le abona el saldo a favor", ExchangeSettlement.storeCreditCustomerLabel(null))
    }

    @Test fun `el alta desde el saldo a favor pide nombre y teléfono`() {
        assertTrue(puedeGuardarCliente("Ana", "", "", "", requirePhone = false))
        assertFalse(puedeGuardarCliente("Ana", "", "", "", requirePhone = true))
        assertFalse(puedeGuardarCliente("", "", "5512345678", "", requirePhone = true))
        assertTrue(puedeGuardarCliente("", "López", "5512345678", "", requirePhone = true))
        assertFalse(puedeGuardarCliente(" ", "", " ", "", requirePhone = false))
    }

    @Test fun `el saldo a favor se llama igual en el diálogo y en el ticket`() {
        assertEquals("Saldo a favor", ExchangeSettlement.methodLabel("STORE_CREDIT"))
    }

    @Test fun `la regla de rechazo definitivo es por prefijo`() {
        assertTrue(esRechazoDefinitivoDeSaldo(422, "STORE_CREDIT_OVER_CAP"))
        assertTrue(esRechazoDefinitivoDeSaldo(409, "STORE_CREDIT_TERMINAL_REFUND_IN_PROGRESS"))
        assertTrue(esRechazoDefinitivoDeSaldo(400, "STORE_CREDIT_REJECTED"))
        assertTrue(esRechazoDefinitivoDeSaldo(422, "STORE_CREDIT_UN_NOMBRE_QUE_LA_APP_NO_CONOCE"))
        assertTrue(esRechazoDefinitivoDeSaldo(400, null))
        assertFalse(esRechazoDefinitivoDeSaldo(409, "IDEMPOTENCY_KEY_REUSED"))
        for (status in listOf(401, 403, 408, 429)) assertFalse("$status", esRechazoDefinitivoDeSaldo(status, "STORE_CREDIT_REJECTED"))
        assertFalse(esRechazoDefinitivoDeSaldo(503, "STORE_CREDIT_OVER_CAP"))
        assertFalse(esRechazoDefinitivoDeSaldo(500, null))
        assertFalse(esRechazoDefinitivoDeSaldo(422, null))
        assertFalse(esRechazoDefinitivoDeSaldo(409, "EXCHANGE_DIFFERENCE_CHANGED"))
    }

    @Test fun `el aviso de teléfono que no coincide sale sólo cuando no coincide`() {
        val sc = StoreCreditAbono("customer", "Ana López", 20000, 35000, "5678", true)
        assertEquals(listOf("Saldo a favor para Ana López: $200.00 · saldo nuevo $350.00",
            "El saldo de Ana López se usa con el teléfono que termina en 5678, no con el de su ficha. Un gerente lo cambia en el DASHBOARD."),
            exchangeStoreCreditNotice(sc))
        assertEquals(listOf("Saldo a favor para Ana López: $200.00 · saldo nuevo $350.00"),
            exchangeStoreCreditNotice(sc.copy(phoneMismatch = false)))
        assertEquals(emptyList<String>(), exchangeStoreCreditNotice(StoreCreditAbono(customerName = "Ana López")))
    }
}
