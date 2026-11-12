package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.SyncIntentPayload
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 D5 · bloque T (Codex b1-r1 #2): el efectivo de una MESA no va por la cola de cobros, va como `PAY_CASH` en la cola de mesas. Desde
 * B0 el id de ese intent ES la llave del cobro y su venta del cajón es `idDeLaVentaEnEfectivo(llave)`, así que la fusión con el servidor
 * la conserva por su pareja EXACTA mientras el cobro siga en la cola, también en los primeros 5 minutos de la caja (sin la ventana).
 * Antes sólo la protegía la pasada por orden o monto, con la ventana: un refresco del cajón en esos 5 minutos le quitaba la venta y el
 * corte enseñaba un sobrante aparente. Hermana de `FusionConLaVentaDelApartadoTest` (la misma pareja, en la cola de cobros).
 */
class FusionConElCobroDeLaColaDeMesasTest {

    private val abrio = haceMinutos(60)
    private val enLosPrimerosCincoMinutos = abrio + 2 * 60_000
    private val despuesDeLaVentana = abrio + 10 * 60_000
    private val otraVentaDelServidor = eventoJson("srv-ev-otra", "CASH_SALE", "100.00", orderId = "otra")

    /** Un `PAY_CASH` con la forma que arma `recordCashPaymentForOrder`: provisional `{ localOrderId }` o, desde B1, `{ orderId }`. */
    private fun payCash(
        llave: String,
        cents: Int,
        orden: String = "mesa-local-1",
        ordenDelServidor: Boolean = false,
        caja: String? = "srv-1",
        method: String? = null,
        tenderTypeId: String? = null,
        q15Reason: String? = null,
        createdAt: Long = enLosPrimerosCincoMinutos,
    ) = SyncIntentPayload(
        payloadJson = buildJsonObject {
            put(if (ordenDelServidor) "orderId" else "localOrderId", orden)
            put("amountCents", cents)
            put("tipCents", 0)
            method?.let { put("method", it) }
            tenderTypeId?.let { put("tenderTypeId", it) }
            caja?.let { put("cashDrawerSessionId", it) }
        }.toString(),
        createdAt = createdAt,
        id = llave,
        q15Reason = q15Reason,
    )

    private fun cobro(id: String, cents: Int, createdAt: Long = enLosPrimerosCincoMinutos) = PendingPaymentEntity(
        id = id, venueId = VENUE_ID, staffId = "staff-1", amountCents = cents, tipCents = 0, method = "CASH", paymentType = "FAST",
        syncStatus = "PENDING", createdAt = createdAt, cashDrawerSessionId = "srv-1",
    )

    private fun cajon(
        dao: FakeCashDrawerDao,
        intents: List<SyncIntentPayload>,
        vararg eventosDelServidor: String,
        cobros: List<PendingPaymentEntity> = emptyList(),
    ) = cashDrawerRepo(
        dao,
        cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, *eventosDelServidor, openedAt = abrio)),
        pendingCashSales = PendingCashSales(
            intentDao = mockk { coEvery { pendingPayloads(any(), any()) } returns intents },
            pendingPaymentDao = mockk<PendingPaymentDao>(relaxed = true).also {
                coEvery { it.forVenue(any()) } returns cobros
                coEvery { it.cobrosDeEfectivoVivos(any(), any(), any(), any(), any()) } answers { cobros.tandaDeVivos(arg(0), arg(1), arg(2), arg(3), arg(4)) }
            },
        ),
    )

    private fun venta(llave: String) = CashDrawerRepository.idDeLaVentaEnEfectivo(llave)

    /** La caja del aparato con las ventas en efectivo ya anotadas (id → importe, orden, hora). */
    private fun caja(vararg ventas: Triple<String, Int, Pair<String?, Long>>) = FakeCashDrawerDao().apply {
        sessions["srv-1"] = sesionLocal("srv-1", openedAt = abrio)
        for ((id, cents, ordenYHora) in ventas) {
            events[id] = eventoLocal(id, "srv-1", "CASH_SALE", cents, orderId = ordenYHora.first, createdAt = ordenYHora.second)
        }
    }

    private fun anotada(id: String, cents: Int, orden: String? = "mesa-local-1", createdAt: Long = enLosPrimerosCincoMinutos) =
        Triple(id, cents, orden to createdAt)

    @Test
    fun `una mesa cobrada sin red en los primeros 5 minutos conserva su venta mientras su cobro siga en la cola de mesas`() = runTest {
        val dao = caja(anotada(venta("K"), 30_000))
        val cajon = cajon(dao, listOf(payCash("K", 30_000)), otraVentaDelServidor)

        cajon.syncFromApi()

        assertTrue("el servidor confirmó OTRA venta: la de la mesa no se borra", dao.events.containsKey(venta("K")))
        assertEquals("fondo + la otra + la de la mesa", FONDO_CENTS + 10_000 + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `con la forma de B1 (la orden del servidor) tambien la conserva`() = runTest {
        val dao = caja(anotada(venta("K"), 30_000, orden = "orden-srv-M"))
        val cajon = cajon(dao, listOf(payCash("K", 30_000, orden = "orden-srv-M", ordenDelServidor = true)), otraVentaDelServidor)

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K")))
        assertEquals(FONDO_CENTS + 10_000 + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `cuando el servidor ya trae la venta de la mesa cuenta una vez`() = runTest {
        // Provisional: el servidor la registra con SU orden (otra id que la local); B1: con la misma orden.
        for ((ordenLocal, ordenDelServidor) in listOf("mesa-local-1" to false, "orden-srv-M" to true)) {
            val dao = caja(anotada(venta("K"), 30_000, orden = ordenLocal))
            val cajon = cajon(
                dao,
                listOf(payCash("K", 30_000, orden = ordenLocal, ordenDelServidor = ordenDelServidor)),
                eventoJson("srv-ev-K", "CASH_SALE", "300.00", orderId = "orden-srv-M"),
            )

            cajon.syncFromApi()

            assertFalse("el servidor la reclama ($ordenLocal): la copia se va", dao.events.containsKey(venta("K")))
            assertEquals("una sola vez ($ordenLocal)", FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
        }
    }

    @Test
    fun `el cobro de otra caja no la protege`() = runTest {
        val dao = caja(anotada(venta("K"), 30_000))
        val cajon = cajon(dao, listOf(payCash("K", 30_000, caja = "srv-vieja")), otraVentaDelServidor)

        cajon.syncFromApi()

        assertFalse("de otra caja, en los primeros 5 minutos: como hoy (sobrante, nunca faltante)", dao.events.containsKey(venta("K")))
    }

    @Test
    fun `el cobro declarado a mano o con terminal ajena no la protege`() = runTest {
        for (intent in listOf(payCash("K", 30_000, method = "CARD"), payCash("K", 30_000, tenderTypeId = "tender-1"))) {
            val dao = caja(anotada(venta("K"), 30_000))
            val cajon = cajon(dao, listOf(intent), otraVentaDelServidor)

            cajon.syncFromApi()

            assertFalse("no es efectivo puro: ${intent.payloadJson}", dao.events.containsKey(venta("K")))
        }
    }

    @Test
    fun `el cobro retenido para revision no la protege`() = runTest {
        val dao = caja(anotada(venta("K"), 30_000))
        val cajon = cajon(dao, listOf(payCash("K", 30_000, q15Reason = "CAPTURA_SIN_EVIDENCIA")), otraVentaDelServidor)

        cajon.syncFromApi()

        assertFalse("retenido: puede haber aterrizado; ante la duda, sobrante", dao.events.containsKey(venta("K")))
    }

    @Test
    fun `dos cobros del mismo monto protegen sus dos ventas`() = runTest {
        val dao = caja(anotada(venta("K1"), 30_000), anotada(venta("K2"), 30_000, createdAt = enLosPrimerosCincoMinutos + 1))
        val cajon = cajon(dao, listOf(payCash("K1", 30_000), payCash("K2", 30_000)), otraVentaDelServidor)

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K1")))
        assertTrue(dao.events.containsKey(venta("K2")))
        assertEquals(FONDO_CENTS + 10_000 + 60_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `el cobro que sigue en la cola protege SU venta y no la de su hermano de la misma mesa`() = runTest {
        // Mesa dividida: K1 ($200) sigue en la cola; K2 ($150) ya subió (su intent ya no está pendiente). Las dos ventas tienen la
        // misma orden y la de K2 es la más reciente. K1 se consume en la pasada exacta: no puede salvar además a la de K2.
        val dao = caja(
            anotada(venta("K1"), 20_000, createdAt = despuesDeLaVentana),
            anotada(venta("K2"), 15_000, createdAt = despuesDeLaVentana + 1),
        )
        val cajon = cajon(dao, listOf(payCash("K1", 20_000, createdAt = despuesDeLaVentana)), otraVentaDelServidor)

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K1")))
        assertFalse("su cobro ya subió: no la salva el de su hermana", dao.events.containsKey(venta("K2")))
        assertEquals(FONDO_CENTS + 10_000 + 20_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `la misma llave en las dos colas protege una sola venta`() = runTest {
        val dao = caja(
            anotada(venta("K"), 30_000, createdAt = despuesDeLaVentana),
            anotada("venta-sin-llave", 30_000, orden = null, createdAt = despuesDeLaVentana + 1),
        )
        val cajon = cajon(
            dao,
            listOf(payCash("K", 30_000, createdAt = despuesDeLaVentana)),
            otraVentaDelServidor,
            cobros = listOf(cobro("K", 30_000, createdAt = despuesDeLaVentana)),
        )

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K")))
        assertFalse("una llave es un cobro: no salva otra venta del mismo monto", dao.events.containsKey("venta-sin-llave"))
        assertEquals(FONDO_CENTS + 10_000 + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `el cobro de la cola de cobros y el de la cola de mesas protegen cada uno la suya`() = runTest {
        val dao = caja(anotada(venta("K-cobros"), 25_000, orden = null), anotada(venta("K-mesa"), 30_000))
        val cajon = cajon(dao, listOf(payCash("K-mesa", 30_000)), otraVentaDelServidor, cobros = listOf(cobro("K-cobros", 25_000)))

        cajon.syncFromApi()

        assertTrue(dao.events.containsKey(venta("K-cobros")))
        assertTrue(dao.events.containsKey(venta("K-mesa")))
        assertEquals(FONDO_CENTS + 10_000 + 55_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }
}
