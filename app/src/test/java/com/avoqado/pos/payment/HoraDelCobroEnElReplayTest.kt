package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.PaymentSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regla C del folio revivido (founder, 10-nov): el reenvío sin red de un cobro de ORDEN (`POST /orders/:id/pay` de la cola) manda la
 * hora en que el cajero cobró, `createdAtLocal`, en epoch ms (número): el SERVIDOR la lee con `horaDeLaVenta` (sólo número o texto de
 * dígitos; un ISO lo tomaría como ilegible) y la guarda en `Order.loyaltySaleAt`. Es la hora de la PRIMERA captura (la de la fila), no
 * la del reenvío. La venta rápida (`/fast`) no la manda: queda como hoy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoraDelCobroEnElReplayTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private val cobradoA = 1_792_854_040_934L

    private fun fila(tipo: String) = PendingPaymentEntity(
        id = "llave-1", venueId = "venue-1", staffId = "staff", amountCents = 13400, tipCents = 0, method = "CASH",
        paymentType = tipo, orderId = if (tipo == "ORDER") "folio-1" else null, createdAt = cobradoA,
    )

    @Test fun `el cobro de una orden reenviado lleva la hora en que se cobro, en epoch ms`() {
        val cuerpo = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(fila("ORDER"), "venue-1", esCobroDeOrden = true)).jsonObject
        val hora = cuerpo["createdAtLocal"] as JsonPrimitive
        assertFalse("número, no texto: el SERVIDOR no lee un ISO", hora.isString)
        assertEquals(cobradoA, hora.long)
        assertEquals(true, (cuerpo["isOfflineReplay"] as JsonPrimitive).content.toBoolean())
    }

    @Test fun `la venta rapida no la manda`() {
        assertFalse(PaymentSyncService.buildReplayBody(fila("FAST"), "venue-1", esCobroDeOrden = false).contains("createdAtLocal"))
    }

    @Test fun `con la cola real la hora del pay es la de la captura, la misma que la de la orden`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = ServidorFalso()
            f.server.dispatcher = falso
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            val key = f.venta("venta-A")
            val capturada = (f.row(key)["createdAt"] as Number).toLong()

            f.service.start(); f.service.esperarSincronizacionActualQ16()
            f.service.stop()

            val pago = falso.de("pay").single()
            assertEquals(key, pago.getString("idempotencyKey"))
            assertEquals("la hora de la captura (la fila), no la del reenvío", capturada, pago.getLong("createdAtLocal"))
            assertEquals("la misma que ya viaja al crear la orden", falso.de("orders").single().getLong("createdAtLocal"),
                pago.getLong("createdAtLocal"))
            assertTrue(pago.getBoolean("isOfflineReplay"))
        }
    }
}
