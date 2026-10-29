package com.avoqado.pos.giftcards

import com.avoqado.pos.orders.data.model.OrderPaymentInfo
import com.avoqado.pos.transactions.data.model.Transaction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EtiquetaDelPagoConSaldoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `transaccion OTHER marcada se llama Tarjeta de regalo`() {
        val tx = json.decodeFromString<Transaction>("""{"id":"pago","method":"OTHER","esSaldoDeTarjeta":true}""")
        assertEquals("Tarjeta de regalo", tx.methodDescription)
    }

    @Test
    fun `la marca manda aunque vengan datos de tarjeta bancaria`() {
        val tx = json.decodeFromString<Transaction>("""{"id":"pago","method":"CARD","cardBrand":"Visa","maskedPan":"1234","esSaldoDeTarjeta":true}""")
        assertEquals("Tarjeta de regalo", tx.methodDescription)
    }

    @Test
    fun `OTHER sin marca false o null conserva Otro`() {
        for (extra in listOf("", ",\"esSaldoDeTarjeta\":false", ",\"esSaldoDeTarjeta\":null")) {
            val tx = json.decodeFromString<Transaction>("""{"id":"pago","method":"OTHER"$extra}""")
            assertEquals("Otro", tx.methodDescription)
        }
    }

    @Test
    fun `refundToGiftCardBalance no se usa como marca del metodo`() {
        val tx = Transaction(id = "pago", method = "OTHER", refundToGiftCardBalance = true)
        assertEquals("Otro", tx.methodDescription)
    }

    @Test
    fun `cuenta OTHER marcada ya nombra la tarjeta desde AN3`() {
        val pago = json.decodeFromString<OrderPaymentInfo>("""{"id":"pago","method":"OTHER","esSaldoDeTarjeta":true}""")
        assertEquals("Tarjeta de regalo", pago.methodLabel)
    }

    @Test
    fun `cuenta OTHER sin marca false o null conserva Otro`() {
        for (extra in listOf("", ",\"esSaldoDeTarjeta\":false", ",\"esSaldoDeTarjeta\":null")) {
            val pago = json.decodeFromString<OrderPaymentInfo>("""{"id":"pago","method":"OTHER"$extra}""")
            assertEquals("Otro", pago.methodLabel)
        }
    }
}
