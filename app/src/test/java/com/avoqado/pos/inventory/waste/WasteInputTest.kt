package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.domain.fechaDelAparato
import com.avoqado.pos.inventory.waste.domain.normalizarCantidad
import com.avoqado.pos.inventory.waste.domain.recortarNota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Lo que teclea una persona, convertido a lo que el servidor acepta. Todo esto
 * corre ANTES de persistir el folio: una merma que el servidor va a rechazar con
 * un 422 permanente no debe llegar nunca a la cola, porque ahí ya no tiene salida.
 */
class WasteInputTest {

    // MARK: - Cantidad

    /**
     * 🔴 El teclado en español escribe COMA. El servidor exige `^\d+(\.\d+)?$`:
     * un "3,5" se vuelve 422 permanente con la fila ya en disco y sin salida.
     */
    @Test
    fun `la coma decimal se convierte en punto`() {
        assertEquals("3.5", normalizarCantidad("3,5"))
        assertEquals("0.25", normalizarCantidad("0,25"))
    }

    @Test
    fun `los espacios sobran y se quitan`() {
        assertEquals("3.5", normalizarCantidad("  3,5 "))
    }

    /** Un separador de millares NO se adivina: es ambiguo y el servidor lo rechazaría. */
    @Test
    fun `lo que no es un decimal simple se rechaza aqui, no en el servidor`() {
        assertNull(normalizarCantidad("1,234.5"))
        assertNull(normalizarCantidad("3.5.1"))
        assertNull(normalizarCantidad("abc"))
        assertNull(normalizarCantidad(""))
        assertNull(normalizarCantidad("-2"))
        assertNull(normalizarCantidad("0x10"))
        assertNull(normalizarCantidad("1e3"))
        assertNull(normalizarCantidad("+3"))
    }

    /** Una merma de 0 no es merma: el servidor la rechaza con 422. */
    @Test
    fun `el cero se rechaza en todas sus formas`() {
        assertNull(normalizarCantidad("0"))
        assertNull(normalizarCantidad("0.000"))
        assertNull(normalizarCantidad("00,0"))
    }

    /** Lo válido pasa intacto, sin redondear ni reformatear. */
    @Test
    fun `una cantidad valida pasa tal cual`() {
        assertEquals("3", normalizarCantidad("3"))
        assertEquals("0.001", normalizarCantidad("0.001"))
        assertEquals("999999999.999", normalizarCantidad("999999999.999"))
    }

    // MARK: - Nota

    /**
     * 🔴 El tope del servidor es 280, y lo mide como lo mide JavaScript (`z.string().max(280)`):
     * en unidades UTF-16. Pasarse deja un 422 PERMANENTE sobre una fila ya escrita.
     */
    @Test
    fun `la nota se recorta al tope del servidor`() {
        assertEquals(280, recortarNota("x".repeat(400)).length)
        assertEquals("hola", recortarNota("hola"))
        assertEquals("", recortarNota("   "))
    }

    /** Un emoji ocupa 4 bytes; si se contaran bytes, 100 emojis ya pasarían el tope. */
    @Test
    fun `cien emojis caben, porque el servidor no cuenta bytes`() {
        val nota = "🥑".repeat(100) // 🥑 ×100 = 200 unidades UTF-16
        assertEquals(nota, recortarNota(nota))
    }

    /**
     * 🔴 Cada emoji son DOS unidades para el servidor. Contar grafemas dejaría pasar 150 emojis
     * (300 unidades, 422 permanente); cortar a ciegas en 280 dejaría medio emoji al final. Se
     * corta en el último carácter entero que cabe: 1 + 139 × 2 = 279.
     */
    @Test
    fun `una nota con emojis se corta en lo que cuenta el servidor, sin partir un emoji`() {
        val recortada = recortarNota("a" + "🥑".repeat(150))
        assertEquals("a" + "🥑".repeat(139), recortada)
        assertTrue(recortada.length <= 280)
    }

    // MARK: - Fecha del aparato

    /**
     * 🔴 `clientOccurredAt` es informativo, pero si no trae zona el cuerpo entero
     * se cae con 422. Un reloj mal puesto sigue produciendo una fecha VÁLIDA.
     */
    @Test
    fun `la fecha del aparato siempre lleva zona, aunque el reloj este mal`() {
        val zona = ZoneId.of("America/Mexico_City")
        val relojAdelantadoDosAnios = Instant.parse("2028-09-22T15:04:05Z")

        val texto = fechaDelAparato(relojAdelantadoDosAnios, zona)

        assertEquals("2028-09-22T09:04:05-06:00", texto)
        // Y vuelve a leerse como el MISMO instante: la zona no se perdió.
        assertEquals(relojAdelantadoDosAnios, OffsetDateTime.parse(texto).toInstant())
    }

    /** En UTC el offset se escribe explícito, nunca como la `Z` a secas. */
    @Test
    fun `en UTC el offset sale como mas cero cero, no como Z`() {
        val texto = fechaDelAparato(Instant.parse("2026-09-22T15:04:05Z"), ZoneId.of("UTC"))
        assertEquals("2026-09-22T15:04:05+00:00", texto)
        assertEquals(Instant.parse("2026-09-22T15:04:05Z"), OffsetDateTime.parse(texto).toInstant())
    }
}
