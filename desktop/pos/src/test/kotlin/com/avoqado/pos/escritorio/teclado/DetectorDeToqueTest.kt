package com.avoqado.pos.escritorio.teclado

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectorDeToqueTest {
    @Test fun `bajar y subir casi en el mismo sitio es un toque`() {
        val d = DetectorDeToque(8f)
        d.alBajar(100f, 100f, 1); d.alMover(103f, 102f, 1)
        assertTrue(d.fueToque())
    }

    @Test fun `moverse mas que la tolerancia es un gesto`() {
        val d = DetectorDeToque(8f)
        d.alBajar(100f, 100f, 1); d.alMover(100f, 140f, 1)
        assertFalse(d.fueToque())
    }

    @Test fun `con dos dedos nunca es un toque`() {
        val d = DetectorDeToque(8f)
        d.alBajar(100f, 100f, 1); d.alMover(100f, 100f, 2)
        assertFalse(d.fueToque())
    }

    @Test fun `un gesto no se borra al volver al punto de partida`() {
        val d = DetectorDeToque(8f)
        d.alBajar(0f, 0f, 1); d.alMover(50f, 0f, 1); d.alMover(0f, 0f, 1)
        assertFalse(d.fueToque())
    }
}
