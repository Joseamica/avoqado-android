package com.avoqado.pos.tables.presentation

import android.content.Context
import com.avoqado.pos.core.data.local.database.FilaSinResolver
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** D5 · B2a: la función pura del saldo de una mesa abierta sin internet, su lectura de la cola y la cerca de la sesión. */
class SaldoSinRedTest {
    private fun item(cents: Int, cantidad: Int = 1, extra: Int = 0) = CartItem(type = CartItemType.CustomAmount, name = "Artículo",
        unitPrice = cents, quantity = cantidad, selectedModifiers = if (extra > 0) listOf(SelectedModifier("g", "Opciones", "m", "Extra", extra)) else emptyList())
    private fun fila(id: String, tipo: String, estado: String = "PENDING", importe: Int? = null) = FilaDeLaOrden(id, tipo, estado, importe)
    private val abierta = fila("open", "OPEN_TABLE")

    @Test fun `rondas en la cola menos el efectivo en la cola`() {
        val saldo = saldoDeMesaProvisional(mapOf("r1" to listOf(item(5000, 2, 500)), "r2" to listOf(item(2000))),
            listOf(abierta, fila("r1", "ADD_ITEMS"), fila("r2", "ADD_ITEMS", "HELD"), fila("c1", "PAY_CASH", importe = 3000)))
        assertEquals(SaldoSinRed.Conocido(13000, 3000), saldo)
    }

    @Test fun `abono y resto restan los dos`() {
        val saldo = saldoDeMesaProvisional(mapOf("r1" to listOf(item(10000))),
            listOf(abierta, fila("r1", "ADD_ITEMS"), fila("c1", "PAY_CASH", importe = 4000), fila("c2", "PAY_CASH", importe = 6000)))
        assertEquals(SaldoSinRed.Conocido(10000, 10000), saldo)
    }

    @Test fun `cortesia vale cero y una mesa sin rondas vale cero`() {
        val gratis = item(2000).apply { isCortesia = true }
        assertEquals(SaldoSinRed.Conocido(0, 0), saldoDeMesaProvisional(mapOf("r1" to listOf(gratis)), listOf(abierta, fila("r1", "ADD_ITEMS"))))
        assertEquals(SaldoSinRed.Conocido(0, 0), saldoDeMesaProvisional(emptyMap(), listOf(abierta)))
    }

    @Test fun `rechazo u otro cambio sin red deja la mesa en gris`() {
        for (otra in listOf(fila("x", "ADD_ITEMS", "REJECTED"), fila("x", "PAY_CASH", "REJECTED", 100), fila("x", "APPLY_DISCOUNT"),
            fila("x", "SPLIT_ORDER"), fila("x", "MERGE_ORDERS"), fila("x", "MOVE_ORDER"), fila("x", "CANCEL_ORDER"), fila("x", "COMP_ORDER"))) {
            val rondas = if (otra.id == "x" && otra.tipo == "ADD_ITEMS") mapOf("x" to listOf(item(100))) else emptyMap()
            assertEquals(otra.toString(), SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR), saldoDeMesaProvisional(rondas, listOf(abierta, otra)))
        }
    }

    @Test fun `sin su apertura en la cola la mesa espera al internet`() {
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO),
            saldoDeMesaProvisional(mapOf("r1" to listOf(item(100))), listOf(fila("r1", "ADD_ITEMS"))))
    }

    @Test fun `ronda en la cola sin borrador no se adivina`() {
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO),
            saldoDeMesaProvisional(mapOf("r1" to listOf(item(100))), listOf(abierta, fila("r1", "ADD_ITEMS"), fila("r2", "ADD_ITEMS"))))
    }

    @Test fun `borrador sin fila espera a que se vuelva a encolar`() {
        assertEquals(SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA),
            saldoDeMesaProvisional(mapOf("r1" to listOf(item(100)), "r2" to listOf(item(200))), listOf(abierta, fila("r1", "ADD_ITEMS"))))
    }

    @Test fun `importe que no cabe o efectivo ilegible no se convierte en un numero`() {
        val grande = Int.MAX_VALUE / 2 + 1
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldoDeMesaProvisional(mapOf("r1" to listOf(item(grande)), "r2" to listOf(item(grande))),
            listOf(abierta, fila("r1", "ADD_ITEMS"), fila("r2", "ADD_ITEMS"))))
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldoDeMesaProvisional(emptyMap(),
            listOf(abierta, fila("c1", "PAY_CASH", importe = Int.MAX_VALUE), fila("c2", "PAY_CASH", importe = 1))))
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldoDeMesaProvisional(emptyMap(), listOf(abierta, fila("c1", "PAY_CASH"))))
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldoDeMesaProvisional(emptyMap(), listOf(abierta, fila("c1", "PAY_CASH", importe = -1))))
    }

    // MARK: - La lectura de la cola (SyncOutbox) y la cerca de la sesión

    private fun outbox(dao: SyncIntentDao) = SyncOutbox(dao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk<Context>(relaxed = true), mockk(relaxed = true))

    @Test fun `la cola da sus filas de la orden con id y el importe del efectivo`() = runTest {
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.filasSinResolver("v1") } returns listOf(
            FilaSinResolver("open", "OPEN_TABLE", "PENDING", """{"tableId":"t1","localOrderId":"L-1"}"""),
            FilaSinResolver("r1", "ADD_ITEMS", "PENDING", """{"localOrderId":"L-1","items":[]}"""),
            FilaSinResolver("c1", "PAY_CASH", "PENDING", """{"localOrderId":"L-1","amountCents":5000,"tipCents":500}"""),
            FilaSinResolver("otra", "PAY_CASH", "PENDING", """{"orderId":"o-otra","amountCents":9900}"""),
            FilaSinResolver("libera", "CLEAR_TABLE", "PENDING", """{"tableId":"t1"}"""),
        )
        assertEquals(listOf(FilaDeLaOrden("open", "OPEN_TABLE", "PENDING", null), FilaDeLaOrden("r1", "ADD_ITEMS", "PENDING", null),
            FilaDeLaOrden("c1", "PAY_CASH", "PENDING", 5000)), outbox(dao).filasSinResolverDeLaOrden("v1", setOf("L-1")))
    }

    @Test fun `efectivo ilegible en la cola falla en vez de leerse como cero`() = runTest {
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.filasSinResolver("v1") } returns listOf(FilaSinResolver("c1", "PAY_CASH", "PENDING", """{"localOrderId":"L-1","amountCents":"5000"}"""))
        assertTrue(runCatching { outbox(dao).filasSinResolverDeLaOrden("v1", setOf("L-1")) }.isFailure)
    }

    @Test fun `la sesion sólo guarda el saldo rearmado si nada cambio mientras se leia`() {
        val session = TableSession()
        val abiertaSinRed = TableSession.Active("t1", "5", null, "L-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING, isProvisional = true,
            saldoSinRedConocido = false)
        session.start(abiertaSinRed, "v1")
        val revision = session.revisionActual()
        session.sumarRondaSinRed(0) // otra escritura de la sesión durante la lectura
        assertFalse(session.rehidratarSaldoSinRed("v1", session.current()!!, revision, 3000))
        assertFalse(session.current()!!.saldoSinRedConocido)
        assertFalse(session.rehidratarSaldoSinRed("otra", session.current()!!, session.revisionActual(), 3000))
        assertTrue(session.rehidratarSaldoSinRed("v1", session.current()!!, session.revisionActual(), 3000))
        assertEquals(3000, session.current()!!.valorRondasSinRedCents); assertTrue(session.current()!!.saldoSinRedConocido)
        // Ya conocida: no se vuelve a pisar.
        assertFalse(session.rehidratarSaldoSinRed("v1", session.current()!!, session.revisionActual(), 1))
    }
}
