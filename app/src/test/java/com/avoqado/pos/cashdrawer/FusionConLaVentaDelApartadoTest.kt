package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 D2, rediseño r9 — prueba 8 (Codex r9 #2 y r10 #2): la fusión con el servidor conserva la venta de un apartado
 * (`idDeLaVentaEnEfectivo(K)`) por su pareja EXACTA con el cobro `K`, mientras `K` todavía pueda subir y el servidor no la haya
 * reclamado, SIN la ventana de 5 minutos. Las ventas sin llave siguen con la ventana de `542c5e21` (`CashDrawerRelojDesfasadoTest`).
 */
class FusionConLaVentaDelApartadoTest {

    private val abrio = haceMinutos(60)
    private val enLosPrimerosCincoMinutos = abrio + 2 * 60_000

    private fun cobro(id: String, cents: Int, estado: String = "PENDING", caja: String = "srv-1") = PendingPaymentEntity(
        id = id, venueId = VENUE_ID, staffId = "staff-1", amountCents = cents, tipCents = 0, method = "CASH", paymentType = "FAST",
        syncStatus = estado, createdAt = enLosPrimerosCincoMinutos, cashDrawerSessionId = caja,
    )

    private fun cajon(dao: FakeCashDrawerDao, filas: List<PendingPaymentEntity>, vararg eventosDelServidor: String) = cashDrawerRepo(
        dao,
        cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, *eventosDelServidor, openedAt = abrio)),
        pendingCashSales = PendingCashSales(
            intentDao = mockk { coEvery { pendingPayloads(any(), any()) } returns emptyList() },
            pendingPaymentDao = mockk<PendingPaymentDao>(relaxed = true).also {
                coEvery { it.forVenue(any()) } returns filas
                coEvery { it.cobrosDeEfectivoVivos(any(), any(), any(), any(), any()) } answers { filas.tandaDeVivos(arg(0), arg(1), arg(2), arg(3), arg(4)) }
            },
        ),
    )

    /** La caja del aparato con la venta del apartado `K`, anotada en los primeros 5 minutos de la caja. */
    private fun cajaConLaVentaDe(llave: String, cents: Int) = FakeCashDrawerDao().apply {
        sessions["srv-1"] = sesionLocal("srv-1", openedAt = abrio)
        val id = CashDrawerRepository.idDeLaVentaEnEfectivo(llave)
        events[id] = eventoLocal(id, "srv-1", "CASH_SALE", cents, createdAt = enLosPrimerosCincoMinutos)
    }

    @Test
    fun `la venta de un apartado de los primeros 5 minutos sobrevive a la fusion mientras su cobro siga por subir`() = runTest {
        val dao = cajaConLaVentaDe("K", 30_000)
        val cajon = cajon(dao, listOf(cobro("K", 30_000)), eventoJson("srv-ev-otra", "CASH_SALE", "100.00", orderId = "otra"))

        cajon.syncFromApi()

        assertTrue("el servidor confirmó OTRA venta: la de K no se borra", dao.events.containsKey(CashDrawerRepository.idDeLaVentaEnEfectivo("K")))
        assertEquals("fondo + la otra + la de K", FONDO_CENTS + 10_000 + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `cuando el servidor la reclama la venta del apartado cuenta una vez`() = runTest {
        val dao = cajaConLaVentaDe("K", 30_000)
        val cajon = cajon(dao, listOf(cobro("K", 30_000)), eventoJson("srv-ev-K", "CASH_SALE", "300.00", orderId = "orden-srv-K"))

        cajon.syncFromApi()

        assertFalse(dao.events.containsKey(CashDrawerRepository.idDeLaVentaEnEfectivo("K")))
        assertEquals(FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `la pareja exacta no protege a una venta sin llave ni a la de un cobro que ya subio`() = runTest {
        val dao = cajaConLaVentaDe("ya-subio", 30_000).apply {
            events["venta-sin-llave"] = eventoLocal("venta-sin-llave", "srv-1", "CASH_SALE", 20_000, createdAt = enLosPrimerosCincoMinutos)
        }
        val cajon = cajon(
            dao,
            listOf(cobro("ya-subio", 30_000, estado = "SYNCED"), cobro("K-sin-venta", 20_000)),
            eventoJson("srv-ev-otra", "CASH_SALE", "100.00", orderId = "otra"),
        )

        cajon.syncFromApi()

        assertFalse("su cobro ya subió: la del servidor la sustituye", dao.events.containsKey(CashDrawerRepository.idDeLaVentaEnEfectivo("ya-subio")))
        assertFalse("sin llave, en los primeros 5 minutos: la ventana de siempre (sobrante, nunca faltante)", dao.events.containsKey("venta-sin-llave"))
    }
}
