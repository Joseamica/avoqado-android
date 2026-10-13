package com.avoqado.pos.tables

import com.avoqado.pos.tables.data.TableSession
import org.junit.Assert.*
import org.junit.Test

class TableSessionCargoTest {
    private val session = TableSession()
    private fun cuenta(cargo: Int = 1500, id: String = "o1") = TableSession.Active(
        "t1", "5", "Patio", id, "ORD-1", 3, 11500, TableSession.Mode.PAYING,
        localOrderId = "local-$id", serviceChargeCents = cargo,
    )

    @Test fun `T4 firma legacy no necesita cargo y su default es cero`() {
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 3, 11500, TableSession.Mode.PAYING))
        assertEquals(0, session.current()!!.serviceChargeCents); assertEquals(11500, session.current()!!.totalCents)
    }

    @Test fun `T4 tras un cobro parcial conserva saldo y borra cargo original`() {
        session.start(cuenta()); session.updateRemaining(6500)
        assertEquals(6500, session.current()!!.totalCents); assertEquals(0, session.current()!!.serviceChargeCents)
        assertEquals("o1", session.current()!!.orderId)
    }

    @Test fun `T4 estacionar y abrir desde piso no restaura el cargo viejo`() {
        session.start(cuenta(), "venue-A")
        assertTrue(session.estacionar("venue-A", session.current()!!)); assertNull(session.current())
        val restored = session.abrirDesdePiso("venue-A", cuenta().copy(serviceChargeCents = 9000))!!
        assertEquals(11500, restored.totalCents); assertEquals(0, restored.serviceChargeCents)
        assertTrue(restored.requiereLecturaServidor); assertEquals(setOf("o1", "local-o1"), restored.idsDeLaOrden)
    }

    @Test fun `T4 abrir desde piso la cuenta activa descarta cargo del resumen`() {
        session.start(cuenta(), "venue-A")
        val restored = session.abrirDesdePiso("venue-A", cuenta())!!
        assertEquals(0, restored.serviceChargeCents); assertEquals(11500, restored.totalCents)
    }

    @Test fun `T4 cambio confirmado conserva version saldo e identidad pero invalida el cargo`() {
        session.start(cuenta(), "venue-A")
        session.confirmarCambio("venue-A", setOf("local-o1"), 9, 9000, session.contextoActual())
        val current = session.current()!!
        assertEquals(9, current.version); assertEquals(9000, current.totalCents)
        assertEquals(0, current.serviceChargeCents); assertTrue(current.requiereLecturaServidor)
        assertEquals("o1", current.orderId)
    }

    @Test fun `T4 saldo desconocido invalida cargo pero conserva importe e identidad`() {
        session.start(cuenta(), "venue-A")
        session.marcarSaldoDesconocido("venue-A", setOf("o1"), session.contextoActual())
        assertEquals(0, session.current()!!.serviceChargeCents); assertEquals(11500, session.current()!!.totalCents)
        assertFalse(session.current()!!.saldoSinRedConocido); assertTrue(session.current()!!.requiereLecturaServidor)
    }

    @Test fun `T4 promocion provisional no acredita cargo del servidor antes de leer cheque`() {
        session.start(cuenta(id = "local-1").copy(isProvisional = true), "venue-A")
        session.promoteProvisional("venue-A", "local-1", "real-1", "ORD-9", 4, session.contextoActual())
        assertEquals("real-1", session.current()!!.orderId); assertFalse(session.current()!!.isProvisional)
        assertEquals(0, session.current()!!.serviceChargeCents); assertTrue(session.current()!!.requiereLecturaServidor)
    }

    @Test fun `T4 una actualizacion ajena no borra cargo ni dinero de la cuenta vigente`() {
        session.sincronizarContexto("staff-A", "venue-A"); session.start(cuenta(), "venue-A")
        val before = session.current()
        session.confirmarCambio("venue-B", setOf("o1"), 9, 0, session.contextoActual())
        session.marcarSaldoDesconocido("venue-A", setOf("otra"), session.contextoActual())
        session.confirmarCambio("venue-A", setOf("o1"), 9, 0, session.contextoActual() - 1)
        assertEquals(before, session.current())
    }
}
