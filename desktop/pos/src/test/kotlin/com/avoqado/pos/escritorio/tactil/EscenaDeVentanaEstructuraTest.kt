package com.avoqado.pos.escritorio.tactil

import kotlin.test.Test
import kotlin.test.assertNull

/** Si alguien sube Compose Multiplatform, esto truena ANTES de que el dedo deje de desplazar en una PC real. */
class EscenaDeVentanaEstructuraTest {
    @Test fun `la version fijada de Compose tiene el camino que usa el puente`() {
        assertNull(EscenaDeVentana.caminoFaltante())
    }
}
