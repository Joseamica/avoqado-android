package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** «Leer hasta el salto de línea, máximo 64 KiB» (etapa 3 del KDS, 3.5, D3). Espejo de `LectorDeLineaTests`. */
class LineaAcotadaTest {

    private fun stream(texto: String) = ByteArrayInputStream(texto.toByteArray(Charsets.UTF_8))

    @Test
    fun `lee hasta el salto de linea y quita el retorno de carro`() {
        assertEquals("""{"v":1,"op":"list"}""", LineaAcotada.leer(stream("{\"v\":1,\"op\":\"list\"}\n{otra}")))
        assertEquals("hola", LineaAcotada.leer(stream("hola\r\n")))
        assertEquals("", LineaAcotada.leer(stream("\n")))
    }

    @Test
    fun `P1 una linea mas larga que el tope devuelve null en vez de crecer sin fin`() {
        val enorme = "x".repeat(LineaAcotada.MAX_BYTES + 1) + "\n"
        assertNull(LineaAcotada.leer(stream(enorme)))
        assertNull(LineaAcotada.leer(stream("y".repeat(100)), maxBytes = 10))
    }

    @Test
    fun `una comanda de mas de 8 KB pasa completa`() {
        val grande = "{" + "\"a\":\"" + "b".repeat(20_000) + "\"}"
        assertEquals(grande, LineaAcotada.leer(stream(grande + "\n")))
    }

    @Test
    fun `si el otro lado cierra sin salto de linea se entrega lo acumulado, y sin nada es null`() {
        assertEquals("sin salto", LineaAcotada.leer(stream("sin salto")))
        assertNull(LineaAcotada.leer(stream("")))
    }
}
