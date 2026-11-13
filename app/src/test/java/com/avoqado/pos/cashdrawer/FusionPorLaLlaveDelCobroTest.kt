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
 * 🔴 D23 (`hz:e5ee98bc:D23`): la venta que escribe el SERVIDOR trae la llave del cobro (`paymentIdempotencyKey`) y la caja reconoce su
 * copia `idDeLaVentaEnEfectivo(K)` por esa llave, sin adivinar por orden ni por importe.
 *
 * Antes la fusión adivinaba: F1, una copia escrita DESPUÉS de que un refresco ya importó su venta (guardar el cobro falló a medias y la
 * respuesta se perdió) contaba dos veces hasta que el cobro subía; F2, la venta de OTRO aparato del mismo importe (o de la misma mesa)
 * se llevaba la copia de un cobro de este aparato que seguía en la cola, y el corte enseñaba un sobrante aparente. Familia completa en
 * `docs/audit/2026-11-11-mesas-cobro-sin-red/t-mapa.md`; promesa en `docs/audit/2026-11-12-d23-llave-del-cobro/promesa.md`.
 */
class FusionPorLaLlaveDelCobroTest {

    private val abrio = haceMinutos(60)
    private val despuesDeLaVentana = abrio + 10 * 60_000

    private fun venta(llave: String) = CashDrawerRepository.idDeLaVentaEnEfectivo(llave)

    /** Un cobro en efectivo de este aparato que sigue en la cola de cobros (su llave K es el id de la fila). */
    private fun cobro(llave: String, cents: Int, orden: String? = null, caja: String = "srv-1", tenderTypeId: String? = null) =
        PendingPaymentEntity(
            id = llave, venueId = VENUE_ID, staffId = "staff-1", amountCents = cents, tipCents = 0, method = "CASH",
            paymentType = if (orden == null) "FAST" else "ORDER", orderId = orden, syncStatus = "PENDING", createdAt = despuesDeLaVentana,
            cashDrawerSessionId = caja, tenderTypeId = tenderTypeId, tenderRevision = tenderTypeId?.let { 1 },
        )

    private fun cajon(dao: FakeCashDrawerDao, cobros: List<PendingPaymentEntity>, vararg eventosDelServidor: String) = cashDrawerRepo(
        dao,
        cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, *eventosDelServidor, openedAt = abrio)),
        pendingCashSales = PendingCashSales(
            intentDao = mockk { coEvery { pendingPayloads(any(), any()) } returns emptyList() },
            pendingPaymentDao = mockk<PendingPaymentDao>(relaxed = true).also {
                coEvery { it.forVenue(any()) } returns cobros
                coEvery { it.cobrosDeEfectivoVivos(any(), any(), any(), any(), any()) } answers { cobros.tandaDeVivos(arg(0), arg(1), arg(2), arg(3), arg(4)) }
            },
        ),
    )

    /** La caja del aparato con sus movimientos (id → importe, orden). */
    private fun caja(vararg ventas: Triple<String, Int, String?>) = FakeCashDrawerDao().apply {
        sessions["srv-1"] = sesionLocal("srv-1", openedAt = abrio)
        for ((id, cents, orden) in ventas) {
            events[id] = eventoLocal(id, "srv-1", "CASH_SALE", cents, orderId = orden, createdAt = despuesDeLaVentana)
        }
    }

    /** Una venta en efectivo escrita por el servidor, con la llave del cobro que la originó. */
    private fun ventaDelServidor(id: String, pesos: String, llave: String?, orden: String? = null) =
        eventoJson(id, "CASH_SALE", pesos, localId = "srv-cash-sale:pay-$id", orderId = orden, paymentIdempotencyKey = llave)

    /** El servidor LEYÓ las llaves y el pago de esta venta no tiene (un cobro del dashboard): `"paymentIdempotencyKey": null`. */
    private fun ventaDelServidorSinLlave(id: String, pesos: String) =
        ventaDelServidor(id, pesos, llave = null).replace("\"createdAt\"", "\"paymentIdempotencyKey\":null,\"createdAt\"")

    @Test
    fun `F1 - la copia escrita despues de que el refresco ya importo su venta cuenta una sola vez`() = runTest {
        // El refresco anterior ya trajo la venta del servidor (srv-ev-K) y la copia del cobro K se escribió después («Reintentar»).
        // K sigue en la cola: la respuesta de su envío se perdió.
        val dao = caja(Triple("srv-ev-K", 30_000, null), Triple(venta("K"), 30_000, null))
        val cajon = cajon(dao, listOf(cobro("K", 30_000)), ventaDelServidor("srv-ev-K", "300.00", llave = "K"))

        cajon.syncFromApi()

        assertFalse("la copia se reconoce por la llave y se funde con la del servidor", dao.events.containsKey(venta("K")))
        assertTrue(dao.events.containsKey("srv-ev-K"))
        assertEquals("fondo + la venta, UNA vez", FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `F2 - la venta de otro aparato del mismo importe no se lleva la copia de un cobro que sigue en la cola`() = runTest {
        val dao = caja(Triple(venta("K"), 30_000, null))
        val cajon = cajon(dao, listOf(cobro("K", 30_000)), ventaDelServidor("srv-ev-n86", "300.00", llave = "K-n86", orden = "orden-n86"))

        cajon.syncFromApi()

        assertTrue("la llave de la otra venta no es K: no es su copia", dao.events.containsKey(venta("K")))
        assertEquals("fondo + la de la N86 + la de la D3", FONDO_CENTS + 60_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `F2 - tampoco por la orden - otro aparato cobra otra parte de la misma mesa`() = runTest {
        val dao = caja(Triple(venta("K"), 30_000, "orden-M"))
        val cajon = cajon(dao, listOf(cobro("K", 30_000, orden = "orden-M")), ventaDelServidor("srv-ev-n86", "200.00", llave = "K-n86", orden = "orden-M"))

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K")))
        assertEquals(FONDO_CENTS + 20_000 + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `F2 - tampoco cuando el cobro no tiene pareja exacta - cobro nombrando otra caja (Codex r1)`() = runTest {
        // La caja local donde se cobró se fundió con la de otro aparato sin alias: el cobro sigue nombrando la caja local.
        val dao = caja(Triple(venta("K"), 30_000, null))
        val cajon = cajon(dao, listOf(cobro("K", 30_000, caja = "local-L")), ventaDelServidor("srv-ev-n86", "300.00", llave = "K-n86"))

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K")))
        assertEquals(FONDO_CENTS + 60_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `F2 - tampoco con el Efectivo del catalogo (Codex r1)`() = runTest {
        val dao = caja(Triple(venta("K"), 30_000, null))
        val cajon = cajon(dao, listOf(cobro("K", 30_000, tenderTypeId = "tender-efectivo")), ventaDelServidor("srv-ev-n86", "300.00", llave = "K-n86"))

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K")))
        assertEquals(FONDO_CENTS + 60_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `una venta cuyo pago no tiene llave (null) no se da por ajena - igual que hoy (Codex r2)`() = runTest {
        // `null` no prueba que la venta sea de otro: un camino que no guardara la llave dejaría la copia sin dueño (doble conteo).
        val dao = caja(Triple(venta("K"), 30_000, null))
        val cajon = cajon(dao, listOf(cobro("K", 30_000)), ventaDelServidorSinLlave("srv-ev-dash", "300.00"))

        cajon.syncFromApi()

        assertFalse(dao.events.containsKey(venta("K")))
        assertEquals(FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `la venta propia que llega por primera vez adopta la copia por su llave`() = runTest {
        // Respuesta perdida: el servidor ya cobró K y la cola aún no lo sabe. Hay otra copia del mismo importe que sigue esperando.
        val dao = caja(Triple(venta("K1"), 30_000, null), Triple(venta("K2"), 30_000, null))
        val cajon = cajon(
            dao,
            listOf(cobro("K1", 30_000), cobro("K2", 30_000)),
            ventaDelServidor("srv-ev-K2", "300.00", llave = "K2"),
        )

        cajon.syncFromApi()

        assertTrue("K1 sigue en la cola y no es la que llegó", dao.events.containsKey(venta("K1")))
        assertFalse(dao.events.containsKey(venta("K2")))
        assertTrue(dao.events.containsKey("srv-ev-K2"))
        assertEquals(FONDO_CENTS + 60_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `servidor de antes de D23 (sin la llave) - igual que hoy`() = runTest {
        val dao = caja(Triple(venta("K"), 30_000, null))
        val cajon = cajon(dao, listOf(cobro("K", 30_000)), ventaDelServidor("srv-ev-n86", "300.00", llave = null, orden = "orden-n86"))

        cajon.syncFromApi()

        // Sin identidad, la pasada 0 adivina por importe como antes (residuo F2 conocido): sobrante aparente, nunca faltante.
        assertFalse(dao.events.containsKey(venta("K")))
        assertEquals(FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }
}
