package com.avoqado.pos.customers.presentation

import com.avoqado.pos.customers.data.model.Customer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saldo a favor, A4: la ficha del cliente (JSON del servidor → modelo → renglón) y la regla del teléfono en el alta. */
class SaldoAFavorDeLaFichaTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun ficha(storeCredit: String) =
        json.decodeFromString<Customer>("""{"id":"c1","firstName":"Ana"$storeCredit}""")

    @Test fun `con saldo la ficha dice Saldo a favor y el monto en pesos`() {
        val c = ficha(""","storeCredit":{"balanceCents":35000,"phoneLast4":"5678","giftCardId":"g1"}""")
        assertEquals("Saldo a favor: ${'$'}350.00", lineaSaldoAFavorDeLaFicha(c))
    }

    @Test fun `sin saldo o con un servidor anterior no se pinta nada`() {
        assertNull(lineaSaldoAFavorDeLaFicha(ficha("")))
        assertNull(lineaSaldoAFavorDeLaFicha(ficha(""","storeCredit":null""")))
        assertNull(lineaSaldoAFavorDeLaFicha(ficha(""","storeCredit":"basura"""")))
    }

    @Test fun `congelado lo dice y trae el monto`() {
        val c = ficha(""","storeCredit":{"balanceCents":1250000,"phoneLast4":"5678","giftCardId":"g1","blocked":true}""")
        assertEquals("Saldo a favor congelado: ${'$'}12,500.00", lineaSaldoAFavorDeLaFicha(c))
    }

    @Test fun `el alta con teléfono obligatorio rechaza 9 dígitos y acepta 10`() {
        assertFalse(puedeGuardarCliente("Ana", "", "551234567", "", requirePhone = true))
        assertTrue(puedeGuardarCliente("Ana", "", "5512345678", "", requirePhone = true))
        assertTrue(puedeGuardarCliente("Ana", "", "55 1234-5678", "", requirePhone = true))
        assertTrue(puedeGuardarCliente("Ana", "", "+525512345678", "", requirePhone = true))
        assertFalse(puedeGuardarCliente("Ana", "", "5512345678901234", "", requirePhone = true))
        // Sin teléfono obligatorio, el alta de siempre no cambia.
        assertTrue(puedeGuardarCliente("Ana", "", "551234567", "", requirePhone = false))
    }
}
