package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.data.network.ClaseDeFallo.DEFINITIVO
import com.avoqado.pos.core.data.network.ClaseDeFallo.DENEGACION_PROPIA
import com.avoqado.pos.core.data.network.ClaseDeFallo.REINTENTABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** D22 (diseño r2 §C): qué fallo detiene la recarga automática y cuál la deja reintentar. */
class ClaseDeFalloTest {
    private val nuestro403 = """{"error":"Forbidden","message":"Permission 'reports:read' required","required":"reports:read"}"""
    private val portal403 = "<html><body>Acceso restringido por la red</body></html>"

    @Test fun `401 y el 403 de nuestra API son denegaciones propias`() {
        assertEquals(DENEGACION_PROPIA, ClaseDeFallo.deRespuesta(401, ""))
        assertEquals(DENEGACION_PROPIA, ClaseDeFallo.deRespuesta(403, nuestro403))
    }

    @Test fun `r1-7 - el 403 de un intermediario es reintentable`() {
        assertEquals(REINTENTABLE, ClaseDeFallo.deRespuesta(403, portal403))
        assertEquals(REINTENTABLE, ClaseDeFallo.deRespuesta(403, ""))
        assertEquals(REINTENTABLE, ClaseDeFallo.deRespuesta(403, """{"otra":"cosa"}"""))
    }

    @Test fun `408, 429, 5xx y un 2xx ilegible reintentan y los demas 4xx son definitivos`() {
        for (codigo in listOf(408, 429, 500, 502, 503, 200)) assertEquals("$codigo", REINTENTABLE, ClaseDeFallo.deRespuesta(codigo, ""))
        for (codigo in listOf(400, 404, 409, 422)) assertEquals("$codigo", DEFINITIVO, ClaseDeFallo.deRespuesta(codigo, ""))
    }

    @Test fun `sin respuesta reintenta y sin sucursal o sesion es definitivo`() {
        assertEquals(listOf(REINTENTABLE), ClaseDeFallo.de(IOException("sin red")))
        assertEquals(listOf(REINTENTABLE), ClaseDeFallo.de(SesionSinRenovar("red:UnknownHost")))
        assertEquals(listOf(DEFINITIVO), ClaseDeFallo.de(IllegalStateException("Sin venue activo")))
        assertEquals(listOf(DENEGACION_PROPIA), ClaseDeFallo.de(LecturaFallida(FalloDeLectura.SERVIDOR, clases = listOf(DENEGACION_PROPIA))))
    }

    @Test fun `r1-6 - precedencia de las cuatro consultas de Informes`() {
        assertTrue("429 x4 reintenta", List(4) { REINTENTABLE }.laAutomaticaReintenta())
        assertFalse("403 propio + 5xx se detiene", listOf(DENEGACION_PROPIA, REINTENTABLE).laAutomaticaReintenta())
        assertTrue("404 + sin red reintenta", listOf(DEFINITIVO, REINTENTABLE).laAutomaticaReintenta())
        assertFalse("404 x4 se detiene", List(4) { DEFINITIVO }.laAutomaticaReintenta())
    }

    @Test fun `la regla del 403 es la misma del interceptor`() {
        assertTrue(RespuestaProhibida.esDenegacionPropia("""{"message":"No"}"""))
        assertFalse(RespuestaProhibida.esDenegacionPropia("""{"error":null,"message":null}"""))
        assertFalse(RespuestaProhibida.esDenegacionPropia(portal403))
    }
}
