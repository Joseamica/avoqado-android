package com.avoqado.pos.transactions

import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.transactions.data.model.centavosDelImporte
import com.avoqado.pos.transactions.data.model.importeEnCentavosExactos
import com.avoqado.pos.transactions.data.model.textoDelBotonDeReembolso
import org.junit.Assert.assertEquals
import org.junit.Test

/** D9: un toque devuelve dinero y no hay confirmación aparte, así que el botón dice cuánto. */
class RefundButtonTextTest {

    @Test
    fun `dice el importe con el formato de la pantalla`() {
        assertEquals("Reembolsar $129.00", textoDelBotonDeReembolso(129.0))
        assertEquals("Reembolsar $1,234.50", textoDelBotonDeReembolso(1234.5))
        assertEquals("Reembolsar $0.01", textoDelBotonDeReembolso(0.01))
    }

    /** Con «1.005» el botón decía $1.01 y se mandaban 100 centavos. */
    @Test
    fun `el boton dice lo mismo que se manda, aunque se escriban mas de dos decimales`() {
        listOf(1.005, 1.015, 2.675, 10.125, 0.999, 12.50, 129.0, 1234.5).forEach { escrito ->
            val importe = importeEnCentavosExactos(escrito)
            assertEquals(
                "con $escrito",
                "Reembolsar ${formatMoneyFromCents(centavosDelImporte(importe))}",
                textoDelBotonDeReembolso(importe),
            )
            assertEquals("con $escrito cambió lo que se manda", centavosDelImporte(escrito), centavosDelImporte(importe))
        }
    }

    @Test
    fun `un importe que no llega a un centavo no enciende el verbo con monto`() {
        assertEquals("Reembolsar", textoDelBotonDeReembolso(importeEnCentavosExactos(0.001)))
    }

    /** «NaN» e «Infinity» son números para `toDoubleOrNull()`, y `roundToInt()` revienta con NaN: la pantalla se caía (Codex r2). */
    @Test
    fun `lo que no es un importe vale cero y no tumba la pantalla`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertEquals("con $it", 0.0, importeEnCentavosExactos(it), 0.0)
        }
    }

    @Test
    fun `sin importe sólo dice el verbo`() {
        assertEquals("Reembolsar", textoDelBotonDeReembolso(0.0))
        assertEquals("Reembolsar", textoDelBotonDeReembolso(-1.0))
    }
}
