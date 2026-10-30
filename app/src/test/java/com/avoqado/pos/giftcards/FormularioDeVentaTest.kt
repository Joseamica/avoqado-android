package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FormularioDeVentaTest {
    private val ajustes = AjustesDeTarjetas(true, listOf(500.0), 100.0, 10000.0, sale = VentaPermitida(true), topePorOperacion = 15000.0)
    private val sobre = SobreEnFormulario(50000, "PRINTED", "", "", "", "")
    @Test fun `Q25 no convierte una lada extranjera elegida en numero mexicano`() {
        val f = FormularioDeVenta(listOf(sobre.copy(canal = "WHATSAPP", telefonoDigitos = "5512345678", lada = "1")), "Ana")
        assertTrue(f.invalidos(ajustes).contains("telefono0"))
        assertFalse(f.copy(sobres = f.sobres.map { it.copy(lada = "52") }).invalidos(ajustes).contains("telefono0"))
    }
    @Test fun `P1 valida todos los limites y entrega`() {
        fun invalidos(s: SobreEnFormulario = sobre, de: String = "") = FormularioDeVenta(listOf(s), de).invalidos(ajustes)
        assertTrue(invalidos(sobre.copy(montoCents = 9900)).contains("monto0"))
        assertTrue(invalidos(sobre.copy(montoCents = 1000001)).contains("monto0"))
        assertTrue(invalidos(sobre.copy(canal = "WHATSAPP", telefonoDigitos = "551234567")).contains("telefono0"))
        assertTrue(invalidos(sobre.copy(canal = "EMAIL", correo = "sin-arroba")).contains("correo0"))
        assertTrue(invalidos(sobre.copy(canal = "EMAIL", correo = "a@" + "b".repeat(118) + ".c")).contains("correo0"))
        assertTrue(invalidos(sobre.copy(mensaje = "x".repeat(201))).contains("mensaje0"))
        assertTrue(invalidos(sobre.copy(nombre = "x".repeat(81))).contains("nombre0"))
        assertTrue(invalidos(sobre.copy(canal = "WHATSAPP", telefonoDigitos = "5512345678")).contains("deParteDe"))
        assertTrue(invalidos().isEmpty())
        assertTrue(FormularioDeVenta(List(21) { sobre }, "").invalidos(ajustes).contains("sobres"))
        assertTrue(FormularioDeVenta(List(3) { sobre.copy(montoCents = 500000) }, "").invalidos(ajustes).contains("total"))
    }
    @Test fun `P1 cuerpo conserva dinero y autor y solo manda destino de su canal`() {
        val f = FormularioDeVenta(listOf(sobre.copy(canal = "WHATSAPP", telefonoDigitos = "5512345678"), sobre.copy(canal = "HANDED_TO_BUYER", telefonoDigitos = "5512345678")), " Ana López ")
        val cuerpo = f.cuerpo("k", "a", "Ana López")
        val obj = Json.parseToJsonElement(cuerpo).jsonObject
        assertTrue("El cuerpo debe llevar al autor original", obj.containsKey("iniciadoPor"))
        assertEquals("a", obj["iniciadoPor"]!!.jsonPrimitive.content)
        assertEquals("Ana López", obj["purchaserName"]!!.jsonPrimitive.content)
        val rows = obj["sobres"]!!.jsonArray
        assertEquals("+525512345678", rows[0].jsonObject["recipientPhone"]!!.jsonPrimitive.content)
        assertFalse(rows[1].jsonObject.containsKey("recipientPhone"))
        assertTrue(cuerpo.contains("500.00"))
        assertEquals("+525512345678", telefonoMx("5512345678"))
        for (bad in listOf("551234567", "55123456789", "55-12345678", "５512345678")) assertNull(telefonoMx(bad))
    }
}
