package com.avoqado.pos.payment

import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendientesDeTarjetaTest {
    @Test fun `agrega varios, conserva el orden y no duplica`() {
        var json: String? = null
        json = PendientesDeTarjeta.agregar(json, "r1", "orden-7", "{}")
        json = PendientesDeTarjeta.agregar(json, "r2", "orden-8", "{}")
        json = PendientesDeTarjeta.agregar(json, "r1", "orden-7", "{}")
        assertEquals(listOf("r1", "r2"), PendientesDeTarjeta.leer(json).map { it.requestId })
    }

    @Test fun `quitar sólo toca SU cobro`() {
        var json = PendientesDeTarjeta.agregar(null, "r1", "orden-7", "{}")
        json = PendientesDeTarjeta.agregar(json, "r2", "orden-8", "{}")
        json = PendientesDeTarjeta.quitar(json, "r1")
        assertEquals(listOf("r2"), PendientesDeTarjeta.leer(json).map { it.requestId })
    }

    /**
     * H1 (Codex, 26-sep): el tope de 20 tiraba el pendiente MÁS VIEJO al guardar el 21, antes del POST y sin desenlace — al
     * reabrir su venta ya nadie la frenaba. Una entrada sale sólo con un desenlace acreditado o con «Entendido».
     */
    @Test fun `P1 con 21 pendientes se conservan los 21`() {
        var json: String? = null
        repeat(21) { json = PendientesDeTarjeta.agregar(json, "r$it", "orden-$it", "{}") }
        val ids = PendientesDeTarjeta.leer(json).map { it.requestId }
        assertEquals(21, ids.size)
        assertEquals("el más viejo sigue ahí", "r0", ids.first())
        assertEquals("r20", ids.last())
    }

    /**
     * H2: un cobro que SÍ pasó se MARCA en su lugar, con sus datos de siempre — no se borra ni cambia de venta. Y si ya no
     * estaba (lo soltó este proceso), vuelve marcado.
     */
    @Test fun `P1 un cobro que SI paso se marca en su lugar y conserva sus datos`() {
        var json = PendientesDeTarjeta.agregar(null, "r1", "orden-7", """{"a":1}""")
        json = PendientesDeTarjeta.agregar(json, "r2", "orden-8", "{}")
        json = PendientesDeTarjeta.agregar(json, "r1", "orden-99", "{}", cobrado = true)
        val lista = PendientesDeTarjeta.leer(json)
        assertEquals(listOf("r1", "r2"), lista.map { it.requestId })
        assertEquals(listOf(true, false), lista.map { it.cobrado })
        assertEquals("orden-7", lista.first().orderId)
        assertEquals("""{"a":1}""", lista.first().contexto)

        val rearmada = PendientesDeTarjeta.leer(PendientesDeTarjeta.agregar(json, "r3", "orden-9", "{}", cobrado = true))
        assertEquals(listOf("r1", "r2", "r3"), rearmada.map { it.requestId })
        assertTrue(rearmada.last().cobrado)
        // Una lista guardada antes de esto (sin el campo) se lee como NO cobrada.
        assertFalse(PendientesDeTarjeta.leer("""[{"requestId":"v","contexto":"{}"}]""").single().cobrado)
    }

    /** Ronda 2 (founder, 26-sep): la ventana de 10 min arranca en el cobro; si su contexto no lo trae, en cuándo se CONFIRMÓ. */
    @Test fun `el SI paso guarda cuando se confirmo, la primera vez`() {
        var json = PendientesDeTarjeta.agregar(null, "r1", "orden-7", "{}")
        json = PendientesDeTarjeta.agregar(json, "r1", "orden-7", "{}", cobrado = true, cobradoEn = 1_000L)
        json = PendientesDeTarjeta.agregar(json, "r1", "orden-7", "{}", cobrado = true, cobradoEn = 2_000L)
        json = PendientesDeTarjeta.agregar(json, "r2", "orden-8", "{}", cobrado = true, cobradoEn = 3_000L)
        assertEquals(listOf(1_000L, 3_000L), PendientesDeTarjeta.leer(json).map { it.cobradoEn })
    }

    /**
     * M-10: un blob que no se pudo leer se lee como lista vacía, y la siguiente escritura lo pisaba — lo que traía se perdía
     * de verdad. Antes de escribir encima se respalda tal cual; la lista queda con la entrada nueva.
     */
    @Test fun `M-10 un blob ilegible se respalda tal cual y la lista queda con la entrada nueva`() {
        val danado = """[{"requestId":"r-perdido","cont"""
        assertEquals("se respalda el crudo, byte por byte", danado, PendientesDeTarjeta.ilegible(danado))
        assertEquals(listOf("nuevo"), PendientesDeTarjeta.leer(PendientesDeTarjeta.agregar(danado, "nuevo", null, "{}")).map { it.requestId })
        // Lo que sí se lee (o no existe) no se respalda.
        assertNull(PendientesDeTarjeta.ilegible(null))
        assertNull(PendientesDeTarjeta.ilegible("[]"))
        assertNull(PendientesDeTarjeta.ilegible(PendientesDeTarjeta.agregar(null, "r1", null, "{}")))
    }

    @Test fun `P1 la llave vieja se migra a la lista y no se pierde`() {
        val migrada = PendientesDeTarjeta.desdeLlaveUnica("viejo", """{"requestId":"viejo","orderId":"orden-3"}""")
        val lista = PendientesDeTarjeta.leer(migrada)
        assertEquals(listOf("viejo"), lista.map { it.requestId })
        assertEquals("orden-3", lista.single().orderId)
        assertNull(PendientesDeTarjeta.desdeLlaveUnica(null, null))
    }

    /**
     * La llave vieja se re-armaba (resultado tardío, `armarLlaveSiLibre`) sin reescribir el contexto: el contexto
     * guardado podía ser de OTRO cobro. Heredar su `orderId` le colgaría el pendiente a la venta equivocada — ésa
     * esperaría por nada y la verdadera cobraría sin freno. `TerminalPaymentService.contextoDe` ya lo rechaza igual.
     */
    @Test fun `P1 un contexto de OTRO cobro no le cuelga su orden al pendiente migrado`() {
        val lista = PendientesDeTarjeta.leer(
            PendientesDeTarjeta.desdeLlaveUnica("viejo", """{"requestId":"otro","orderId":"orden-9"}"""),
        )
        assertEquals(listOf("viejo"), lista.map { it.requestId })
        assertNull(lista.single().orderId)
        assertEquals("{}", lista.single().contexto)
    }

    @Test fun `un JSON dañado se lee como lista vacía y no truena`() {
        assertEquals(emptyList<Any>(), PendientesDeTarjeta.leer("{no es json"))
    }
}
