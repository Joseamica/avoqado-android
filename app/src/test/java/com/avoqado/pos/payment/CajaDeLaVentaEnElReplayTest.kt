package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A3 (efectivo sin red en el turno correcto, 21-oct-2026) — la caja de la venta viaja en el replay, traducida al id del
 * servidor, en los tres caminos de la cola de cobros: `POST /orders` (el pedido congelado), `/orders/:o/pay` y `/fast`.
 *
 * Servicio, HTTP (MockWebServer, `ServidorFalso`) y la fila en SQL reales (`Q16PrecioCapturadoTest.Fixture`); la caja la
 * escribe el escritor real (`CashPaymentRepository.queueCashPayment`). Sin caja, o con una caja local sin alias, el campo NO
 * viaja: el cuerpo queda como hoy (Review Focus #1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CajaDeLaVentaEnElReplayTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private fun Q16PrecioCapturadoTest.Fixture.arrancar(): ServidorFalso {
        val falso = ServidorFalso()
        server.dispatcher = falso
        service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
        return falso
    }
    private suspend fun PaymentSyncService.primera() { start(); esperarSincronizacionActualQ16() }

    @Test fun `P1 la venta sin red sube con la caja traducida en POST orders y en pay`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            every { f.drawer.idDeCajaParaElServidor("caja-A-local", any()) } returns "srv-A"
            val key = f.venta("venta-A", caja = "caja-A-local")
            f.service.primera()
            f.service.stop()

            val orden = falso.de("orders").single()
            assertEquals("srv-A", orden.getString("cashDrawerSessionId"))
            assertEquals(true, orden.getBoolean("isOfflineReplay"))
            val pago = falso.de("pay").single()
            assertEquals(key, pago.getString("idempotencyKey"))
            assertEquals("srv-A", pago.getString("cashDrawerSessionId"))
            assertEquals(true, pago.getBoolean("isOfflineReplay"))
            assertEquals("la fila conserva la caja con la que se capturó", "caja-A-local", f.row(key)["original"]
                .let { org.json.JSONObject(it as String).getString("caja") })
        }
    }

    @Test fun `P1 la venta rapida sube con la caja traducida en fast`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            every { f.drawer.idDeCajaParaElServidor("caja-A-local", any()) } returns "srv-A"
            f.importeLibre("rapida-A", 5_000, caja = "caja-A-local")
            f.service.primera()
            f.service.stop()

            val rapida = falso.de("fast").single()
            assertEquals("srv-A", rapida.getString("cashDrawerSessionId"))
            assertEquals(true, rapida.getBoolean("isOfflineReplay"))
        }
    }

    @Test fun `P1 una caja local sin alias no viaja`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            every { f.drawer.idDeCajaParaElServidor("caja-sin-alias", any()) } returns null
            f.venta("venta-A", caja = "caja-sin-alias")
            f.service.primera()
            f.service.stop()

            assertFalse(falso.de("orders").single().has("cashDrawerSessionId"))
            assertFalse(falso.de("pay").single().has("cashDrawerSessionId"))
        }
    }

    @Test fun `una fila sin caja sube como hoy y no pregunta por ningun alias`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = f.arrancar()
            f.venta("venta-A")
            f.service.primera()
            f.service.stop()

            assertFalse(falso.de("orders").single().has("cashDrawerSessionId"))
            assertFalse(falso.de("pay").single().has("cashDrawerSessionId"))
            coVerify(exactly = 0) { f.drawer.idDeCajaParaElServidor(any(), any()) }
        }
    }

    @Test fun `el cuerpo del replay sin caja es identico al de hoy y con caja la agrega`() {
        val fila = com.avoqado.pos.core.data.local.database.PendingPaymentEntity(
            id = "llave", venueId = "v1", staffId = "s1", amountCents = 10_000, tipCents = 500, method = "CASH", paymentType = "FAST",
            customerId = "cliente",
        )
        val hoy = PaymentSyncService.buildReplayBody(fila, "v1", esCobroDeOrden = false)
        assertEquals(hoy, PaymentSyncService.buildReplayBody(fila, "v1", esCobroDeOrden = false, cajaParaElServidor = null))
        assertFalse(hoy.contains("cashDrawerSessionId"))
        val conCaja = org.json.JSONObject(PaymentSyncService.buildReplayBody(fila, "v1", esCobroDeOrden = true, cajaParaElServidor = "srv-A"))
        assertEquals("srv-A", conCaja.getString("cashDrawerSessionId"))
        assertTrue(conCaja.getBoolean("isOfflineReplay"))

        val pedido = """{"externalId":"llave","isOfflineReplay":true,"items":[]}"""
        assertEquals("sin caja el pedido es el MISMO texto", pedido, PaymentSyncService.pedidoConLaCaja(pedido, null))
        assertEquals("srv-A", org.json.JSONObject(PaymentSyncService.pedidoConLaCaja(pedido, "srv-A")).getString("cashDrawerSessionId"))
        assertEquals("un pedido ilegible no se toca", "no-es-json", PaymentSyncService.pedidoConLaCaja("no-es-json", "srv-A"))
    }
}
