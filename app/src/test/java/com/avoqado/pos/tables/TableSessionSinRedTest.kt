package com.avoqado.pos.tables

import com.avoqado.pos.tables.data.TableSession
import org.junit.Assert.*
import org.junit.Test

class TableSessionSinRedTest {
    private fun provisional(orderId: String = "L-1") = TableSession.Active(
        tableId = "t1", tableNumber = "5", areaName = "Patio", orderId = orderId,
        orderNumber = "L-001", version = 1, totalCents = 0,
        mode = TableSession.Mode.ORDERING, isProvisional = true,
    )

    @Test fun `constructores existentes conservan sus defaults`() {
        val active = provisional().copy(orderId = "o1", isProvisional = false)
        assertNull(active.localOrderId)
        assertEquals(0, active.valorRondasSinRedCents)
        assertEquals(setOf("o1"), active.idsDeLaOrden)
    }

    @Test fun `rondas sin red suman en la sesion y sobreviven al cambio de modo`() {
        val session = TableSession().apply { start(provisional()) }
        session.sumarRondaSinRed(3300)
        session.sumarRondaSinRed(3000)
        session.start(session.current()!!.copy(mode = TableSession.Mode.PAYING))
        assertEquals(6300, session.current()!!.valorRondasSinRedCents)
        assertEquals(TableSession.Mode.PAYING, session.current()!!.mode)
    }

    @Test fun `rondas negativas y cero no restan lo que ya se envio`() {
        val session = TableSession().apply { start(provisional()); sumarRondaSinRed(3300) }
        session.sumarRondaSinRed(-1000)
        session.sumarRondaSinRed(0)
        assertEquals(3300, session.current()!!.valorRondasSinRedCents)
    }

    @Test fun `promocion conserva id local y reinicia solo el valor de su orden`() {
        val session = TableSession().apply { start(provisional()); sumarRondaSinRed(3300) }
        session.promoteProvisional("L-1", "o1", "A-7", 2)
        val active = session.current()!!
        assertEquals("o1", active.orderId)
        assertEquals("L-1", active.localOrderId)
        assertEquals(setOf("o1", "L-1"), active.idsDeLaOrden)
        assertEquals(0, active.valorRondasSinRedCents)
        assertEquals("A-7", active.orderNumber)
        assertEquals(2, active.version)
        assertFalse(active.isProvisional)
        assertEquals("Mesa 5 · Patio", active.label)
    }

    @Test fun `id local explicito no se reemplaza y los ids no se duplican`() {
        assertEquals(setOf("L-1"), provisional().copy(localOrderId = "L-1").idsDeLaOrden)
        val session = TableSession().apply { start(provisional().copy(localOrderId = "L-origen")) }
        session.promoteProvisional("L-1", "o1", null, 2)
        assertEquals("L-origen", session.current()!!.localOrderId)
        assertEquals(setOf("o1", "L-origen"), session.current()!!.idsDeLaOrden)
        assertEquals("L-001", session.current()!!.orderNumber)
    }

    @Test fun `sin sesion los cambios y ack no crean una cuenta`() {
        val session = TableSession()
        session.sumarRondaSinRed(3300)
        session.promoteProvisional("L-1", "o1", "A-7", 2)
        assertNull(session.current())
    }

    @Test fun `ack de otra orden conserva la sesion completa`() {
        val session = TableSession().apply { start(provisional("L-2")); sumarRondaSinRed(2700) }
        val before = session.current()
        session.promoteProvisional("L-1", "o1", "A-7", 99)
        assertEquals(before, session.current())
        assertEquals(2700, session.current()!!.valorRondasSinRedCents)
    }

    @Test fun `ack duplicado no borra una ronda posterior ni retrocede la version`() {
        val session = TableSession().apply { start(provisional()) }
        session.promoteProvisional("L-1", "o1", "A-7", 2)
        session.sumarRondaSinRed(750)
        session.updateVersion(3)
        val before = session.current()
        session.promoteProvisional("L-1", "o1", "VIEJO", 1)
        assertEquals(before, session.current())
        assertEquals(750, session.current()!!.valorRondasSinRedCents)
    }

    @Test fun `nueva sesion y clear no heredan rondas ni reaccionan al ack antiguo`() {
        val session = TableSession().apply { start(provisional()); sumarRondaSinRed(3300) }
        session.clear()
        assertNull(session.current())
        session.start(provisional("L-2"))
        session.promoteProvisional("L-1", "o1", "A-7", 2)
        assertEquals("L-2", session.current()!!.orderId)
        assertNull(session.current()!!.localOrderId)
        assertEquals(0, session.current()!!.valorRondasSinRedCents)
    }

    @Test fun `actualizar saldo y version conserva ids y rondas`() {
        val session = TableSession().apply {
            start(provisional().copy(localOrderId = "L-1"))
            sumarRondaSinRed(3300)
        }
        session.updateVersion(4)
        session.updateRemaining(2100)
        val active = session.current()!!
        assertEquals(4, active.version)
        assertEquals(2100, active.totalCents)
        assertEquals(3300, active.valorRondasSinRedCents)
        assertEquals("L-1", active.localOrderId)
    }

    @Test fun `limite Int32 se conserva ante cero y rondas negativas`() {
        val session = TableSession().apply { start(provisional()); sumarRondaSinRed(Int.MAX_VALUE) }
        session.sumarRondaSinRed(0)
        session.sumarRondaSinRed(-1)
        assertEquals(Int.MAX_VALUE, session.current()!!.valorRondasSinRedCents)
    }

    @Test fun `overflow falla sin convertir el saldo a negativo ni modificar la sesion`() {
        val session = TableSession().apply {
            start(provisional().copy(valorRondasSinRedCents = Int.MAX_VALUE))
        }
        assertThrows(ArithmeticException::class.java) { session.sumarRondaSinRed(1) }
        assertEquals(Int.MAX_VALUE, session.current()!!.valorRondasSinRedCents)
    }
}
