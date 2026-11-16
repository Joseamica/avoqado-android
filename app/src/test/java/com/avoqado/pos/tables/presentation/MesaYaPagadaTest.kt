package com.avoqado.pos.tables.presentation

import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.tables.data.OrderDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D59 (D3, 12-nov, caso 8.4): la mesa se cobró en la terminal (TPV › Mesas). Al releer, la tablet veía «Ya pagado» pero «Pagar $0.00»
 * seguía activo (Codex D59 r1 #2): la cuenta que el SERVIDOR ya marcó pagada no se vuelve a cobrar, y se dice por qué.
 */
class MesaYaPagadaTest {

    private val sinTrabajo = TrabajoSinResolver(0, 0, emptySet(), 0)

    @Test
    fun `D59 la cuenta que el servidor ya marco pagada no se vuelve a cobrar`() {
        val pagada = OrderDetail(id = "o1", orderNumber = "ORD-1", paymentStatus = "PAID", items = emptyList())
        assertEquals(MESA_YA_PAGADA, motivoParaNoCobrarLaMesa(false, pagada, "o1", true, false, false, sinTrabajo))
    }

    @Test
    fun `D59 una cuenta sin pagar o de otra orden sigue como siempre`() {
        val pendiente = OrderDetail(id = "o1", orderNumber = "ORD-1", paymentStatus = "PENDING", items = emptyList())
        assertNull(motivoParaNoCobrarLaMesa(false, pendiente, "o1", true, false, false, sinTrabajo))
        val otra = OrderDetail(id = "o2", orderNumber = "ORD-2", paymentStatus = "PAID", items = emptyList())
        assertNull(motivoParaNoCobrarLaMesa(false, otra, "o1", true, false, false, sinTrabajo))
    }
}
