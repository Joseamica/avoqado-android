package com.avoqado.pos.escritorio

import com.avoqado.pos.pos.data.LectorHid
import com.avoqado.pos.pos.data.TeclaHid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HALLAZGO (29-sep): por qué escritorio NO usa LectorHid (app) para la pistola. En un teclado físico se come teclas de
 * una persona, y en Windows pistola y teclado son el mismo aparato. Escritorio reconoce la pistola con su propio
 * `teclado/LectorDePistola` (retiene y devuelve, nunca come a ciegas; 7-oct) y sólo emite al bus de LectorHidBus.
 * Si alguien arregla LectorHid en Android, estas pruebas fallan: ésa es la señal de que se puede volver a una sola pieza.
 */
class LectorHidHallazgoTest {
    @Test fun `en un teclado fisico la 2a tecla de un par tecleado a 80 ms no llega al campo`() {
        val lector = LectorHid()
        assertEquals(TeclaHid.DejarPasar, lector.procesar('h', false, true, 1, 1_000))
        assertEquals(TeclaHid.Consumir, lector.procesar('o', false, true, 1, 1_080))
    }

    @Test fun `despues de un escaneo el tecleo lento del mismo aparato tambien se consume`() {
        val lector = LectorHid()
        "7501055".forEachIndexed { i, c -> lector.procesar(c, false, true, 1, 1_000L + i * 10) }
        assertEquals(TeclaHid.Codigo("7501055"), lector.procesar(null, true, true, 1, 1_070))
        assertEquals(TeclaHid.Consumir, lector.procesar('a', false, true, 1, 5_000))
    }
}
