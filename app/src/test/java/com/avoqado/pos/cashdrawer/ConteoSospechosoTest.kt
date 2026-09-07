package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.ConteoSospechoso
import com.avoqado.pos.cashdrawer.data.MotivoDeSospecha
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 EL CONTEO QUE NO ERA UN CONTEO (Testarudo, 6-sep-2026).
 *
 * El cajero registró dos retiros seguidos —propinas $2,814 y «venta final» $6,042— y quince
 * segundos después, cuando la hoja le pidió contar el cajón, escribió $8,856: la SUMA de lo que
 * acababa de sacar, no lo que quedó dentro. Su propia nota lo dice: «suma propinas y venta». El
 * servidor calculó bien ($1,852 esperados: el fondo menos el hielo) y el ticket imprimió un
 * sobrante de $7,004 que no existe.
 *
 * La app no puede enseñarle el esperado (conteo ciego, LFT 107/110), pero SÍ puede reconocer que
 * el número tecleado es uno que él mismo acaba de producir: la suma de sus últimos retiros, o el
 * efectivo cobrado del día que ya vio en el corte parcial. Ninguno de los dos revela nada nuevo.
 * Es una pregunta antes de confirmar, nunca un bloqueo. Espejo exacto en iOS.
 */
class ConteoSospechosoTest {

    private fun evento(type: CashDrawerEventType, cents: Int, nota: String? = null, at: Long) =
        CashDrawerEventEntity(
            id = "ev-$at", sessionId = "s-1", venueId = "v-1", type = type.name,
            amountCents = cents, note = nota, staffId = "st-1", staffName = "Daniel",
            createdAt = at,
        )

    /** El cajón de Testarudo del 6-sep, evento por evento. */
    private val testarudo6Sep = listOf(
        evento(CashDrawerEventType.OPEN, 200_000, "Caja abierta con \$2000", at = 1),
        evento(CashDrawerEventType.CASH_SALE, 885_600, at = 2),
        evento(CashDrawerEventType.PAY_OUT, 14_800, "hielo", at = 3),
        evento(CashDrawerEventType.PAY_OUT, 281_400, "propinas", at = 4),
        evento(CashDrawerEventType.PAY_OUT, 604_200, "venta final", at = 5),
    )

    // MARK: - Lo que se lee de los eventos

    @Test
    fun `los retiros del dia son solo los PAY_OUT, en orden`() {
        val retiros = ConteoSospechoso.retirosDelDia(testarudo6Sep)
        assertEquals(listOf("hielo", "propinas", "venta final"), retiros.map { it.nota })
        assertEquals(listOf(14_800, 281_400, 604_200), retiros.map { it.amountCents })
    }

    @Test
    fun `el efectivo cobrado es la suma de las CASH_SALE y nada mas`() {
        assertEquals(885_600, ConteoSospechoso.efectivoCobradoCents(testarudo6Sep))
        assertEquals(0, ConteoSospechoso.efectivoCobradoCents(testarudo6Sep.filter { it.type != "CASH_SALE" }))
    }

    // MARK: - El caso real

    /**
     * $8,856 = $2,814 + $6,042: los DOS últimos retiros. No es la suma de todos (el hielo de las
     * 14:15 no entra), así que la regla es «los últimos k retiros», no «todos los retiros».
     */
    @Test
    fun `P1 el conteo igual a lo que acaba de retirar se marca, con los retiros que coinciden`() {
        val sospecha = ConteoSospechoso.evaluar(
            actualCents = 885_600,
            retiros = ConteoSospechoso.retirosDelDia(testarudo6Sep),
            efectivoCobradoCents = 885_600,
        )
        assertEquals(MotivoDeSospecha.MISMO_QUE_RETIROS, sospecha?.motivo)
        assertEquals(885_600, sospecha?.montoCents)
        assertEquals(listOf("propinas", "venta final"), sospecha?.retirosQueCoinciden?.map { it.nota })
    }

    @Test
    fun `P1 el conteo igual al efectivo cobrado del dia se marca aunque no haya retiros`() {
        val sospecha = ConteoSospechoso.evaluar(
            actualCents = 885_600,
            retiros = emptyList(),
            efectivoCobradoCents = 885_600,
        )
        assertEquals(MotivoDeSospecha.MISMO_QUE_EFECTIVO_COBRADO, sospecha?.motivo)
        assertEquals(885_600, sospecha?.montoCents)
    }

    @Test
    fun `el ultimo retiro solo tambien cuenta`() {
        val sospecha = ConteoSospechoso.evaluar(
            actualCents = 604_200,
            retiros = ConteoSospechoso.retirosDelDia(testarudo6Sep),
            efectivoCobradoCents = 885_600,
        )
        assertEquals(MotivoDeSospecha.MISMO_QUE_RETIROS, sospecha?.motivo)
        assertEquals(listOf("venta final"), sospecha?.retirosQueCoinciden?.map { it.nota })
    }

    // MARK: - Lo que NO se marca (un conteo real no debe ver este diálogo)

    @Test
    fun `un peso de diferencia no se marca`() {
        val retiros = ConteoSospechoso.retirosDelDia(testarudo6Sep)
        assertNull(ConteoSospechoso.evaluar(885_500, retiros, 885_600))
        assertNull(ConteoSospechoso.evaluar(885_700, retiros, 885_600))
    }

    @Test
    fun `el conteo correcto de Testarudo no se marca`() {
        // Lo que de verdad quedaba dentro: $2,000 − $148 = $1,852.
        assertNull(ConteoSospechoso.evaluar(185_200, ConteoSospechoso.retirosDelDia(testarudo6Sep), 885_600))
    }

    @Test
    fun `un cajon en cero sin retiros ni ventas no se marca`() {
        assertNull(ConteoSospechoso.evaluar(0, emptyList(), 0))
    }

    @Test
    fun `un retiro de cero no vuelve sospechoso un conteo de cero`() {
        val retiros = listOf(com.avoqado.pos.cashdrawer.data.RetiroDelDia("nada", 0))
        assertNull(ConteoSospechoso.evaluar(0, retiros, 0))
    }

    /**
     * Límite declarado del detector: el 5-sep el mismo cajero escribió $7,124.75 = el efectivo
     * cobrado ($9,101.50) menos el retiro de propinas ($1,976.75). No es una suma de sus últimos
     * retiros ni el cobrado exacto, así que aquí no hay aviso. Ese caso lo cubre el texto de la
     * hoja, no este detector.
     */
    @Test
    fun `el conteo del 5-sep queda fuera del detector, a proposito`() {
        val retiros = listOf(
            com.avoqado.pos.cashdrawer.data.RetiroDelDia("hielo emergencia", 10_500),
            com.avoqado.pos.cashdrawer.data.RetiroDelDia("propinas", 197_675),
            com.avoqado.pos.cashdrawer.data.RetiroDelDia("propinas en efectivo", 45_150),
        )
        assertNull(ConteoSospechoso.evaluar(712_475, retiros, 910_150))
    }

    // MARK: - El texto que ve el cajero

    @Test
    fun `el mensaje de retiros nombra el monto y los retiros que coinciden, sin el esperado`() {
        val sospecha = ConteoSospechoso.evaluar(885_600, ConteoSospechoso.retirosDelDia(testarudo6Sep), 885_600)!!
        val mensaje = ConteoSospechoso.mensaje(sospecha)
        assertTrue(mensaje, mensaje.contains("\$8,856.00"))
        assertTrue(mensaje, mensaje.contains("propinas y venta final"))
        assertTrue(mensaje, mensaje.contains("ya no está en el cajón"))
        // El esperado ($1,852) jamás viaja en el texto: el conteo sigue siendo ciego.
        assertTrue(mensaje, !mensaje.contains("1,852"))
    }

    @Test
    fun `el mensaje de efectivo cobrado dice que el conteo incluye el fondo`() {
        val sospecha = ConteoSospechoso.evaluar(885_600, emptyList(), 885_600)!!
        val mensaje = ConteoSospechoso.mensaje(sospecha)
        assertTrue(mensaje, mensaje.contains("\$8,856.00"))
        assertTrue(mensaje, mensaje.contains("efectivo cobrado hoy"))
        assertTrue(mensaje, mensaje.contains("fondo"))
    }

    @Test
    fun `un retiro sin nota se nombra como retiro`() {
        val retiros = listOf(com.avoqado.pos.cashdrawer.data.RetiroDelDia(null, 50_000))
        val sospecha = ConteoSospechoso.evaluar(50_000, retiros, 0)!!
        assertTrue(ConteoSospechoso.mensaje(sospecha).contains("(retiro)"))
    }
}
