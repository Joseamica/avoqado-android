package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 🔴 EL CORTE NO PUEDE TRUNCAR CENTAVOS.
 *
 * `getTenderBreakdown` convertía los pesos del servidor a centavos con `(dollars * 100).toInt()`,
 * y `toInt()` TRUNCA. Con la representación binaria de un `Double`, $2.30 vale 2.2999999…, así que
 * `2.30 → 229` (un centavo menos), `4.06 → 405` y `19.99 → 1998`. El resto del repositorio ya
 * redondeaba (`roundToInt`, en el fondo, el conteo y el sobrante/faltante) y iOS también
 * (`.rounded()`): era el único sitio que se había quedado atrás, y cae justo en el papel que el
 * cajero se queda en el cajón.
 *
 * No es un centavo suelto: son las ventas y las propinas del turno, renglón por renglón, siempre
 * en la MISMA dirección — de menos. El desglose del corte queda por debajo de lo que el servidor
 * dice haber cobrado, y ese descuadre no tiene forma de explicarse.
 */
class CashDrawerCentavosDelCorteTest {

    private fun cuerpo(vararg filas: String) =
        """{"success":true,"data":{"tenderBreakdown":[${filas.joinToString(",")}]}}"""

    private fun fila(method: String, total: String, tips: String) =
        """{"method":"$method","total":$total,"tips":$tips}"""

    private suspend fun desglose(vararg filas: String) = cashDrawerRepo(
        dao = FakeCashDrawerDao(),
        client = cashDrawerClient("/cash-drawer/tender-breakdown" to cuerpo(*filas)),
    ).getTenderBreakdown(haceMinutos(60), haceMinutos(0))

    /**
     * Los tres montos medidos: $2.30, $4.06 y $19.99. Los tres caen del lado malo del binario, y
     * los tres salían con un centavo de menos.
     */
    @Test
    fun `P1 los pesos del servidor se redondean a centavos, nunca se truncan`() = runTest {
        val filas = desglose(
            fila("CASH", "2.30", "19.99"),
            fila("CARD", "4.06", "0.00"),
        )

        assertNotNull("el desglose no debió reportarse como no consultado", filas)
        assertEquals(2, filas!!.size)
        assertEquals("2.30 se truncaba a 229", 230, filas[0].totalCents)
        assertEquals("19.99 se truncaba a 1998", 1_999, filas[0].tipsCents)
        assertEquals("4.06 se truncaba a 405", 406, filas[1].totalCents)
        assertEquals(0, filas[1].tipsCents)
    }

    /** Y un renglón que sí es exacto sigue dando exactamente lo mismo que antes. */
    @Test
    fun `un monto exacto no cambia`() = runTest {
        val filas = desglose(fila("CASH", "1234.00", "56.50"))

        assertEquals(123_400, filas!!.single().totalCents)
        assertEquals(5_650, filas.single().tipsCents)
    }
}
