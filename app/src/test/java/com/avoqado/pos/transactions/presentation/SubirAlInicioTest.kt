package com.avoqado.pos.transactions.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D22 (prueba 2 en la D3, 4-nov): con la lista vista desde arriba, la página 1 nueva traía la venta nueva primera, pero la
 * lista (llaves estables) se quedaba anclada a la fila que ya se veía y lo nuevo quedaba arriba, fuera de la vista. Como
 * Square y Gmail: si se veía desde arriba, queda arriba enseñando lo nuevo; si la cajera había bajado, no se mueve.
 */
class SubirAlInicioTest {
    @Test fun `vista desde arriba y entra algo nuevo arriba - sube`() {
        assertTrue(subirAlInicioTrasRecarga(primeraAntes = "header_Ayer", primeraAhora = "header_Hoy", primeraVisible = "header_Ayer", desplazamiento = 0))
    }

    @Test fun `si la cajera habia bajado no se mueve`() {
        assertFalse(subirAlInicioTrasRecarga("header_Ayer", "header_Hoy", primeraVisible = "pago-7", desplazamiento = 0))
        assertFalse("un poco desplazada también cuenta como bajar", subirAlInicioTrasRecarga("header_Ayer", "header_Hoy", "header_Ayer", desplazamiento = 12))
    }

    @Test fun `cargar mas pega al final y no sube`() {
        assertFalse(subirAlInicioTrasRecarga("header_Ayer", "header_Ayer", "header_Ayer", 0))
    }

    @Test fun `la primera vez no hay nada que comparar`() {
        assertFalse(subirAlInicioTrasRecarga(primeraAntes = null, primeraAhora = "header_Hoy", primeraVisible = null, desplazamiento = 0))
    }
}
