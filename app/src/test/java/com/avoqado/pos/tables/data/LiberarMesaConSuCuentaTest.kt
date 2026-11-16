package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * D75 (hz:8024bc1c:D75): «Liberar mesa» sin red nombra la cuenta que este aparato quiso soltar. Sin ella, el SERVIDOR no sabía que
 * la mesa abierta sin red había terminado en cuenta aparte (D51/D71) y lo rechazaba por la OTRA cuenta de la mesa: una «operación por
 * revisar» que nadie puede resolver (D3 + SAMSUNG, 14-nov). Misma referencia que las demás operaciones de la cuenta.
 */
class LiberarMesaConSuCuentaTest {
    private val api = mockk<ApiService>()
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val payload = slot<JsonObject>()

    // Constructor de hoy: apiService, payloadCache, syncOutbox, tableSession, roleManager, draftStore.
    private fun repo() = TableServiceRepository(api, mockk<PayloadCache>(relaxed = true), outbox, session, mockk(relaxed = true), mockk(relaxed = true))

    private suspend fun liberarSinRed(tableId: String, orderId: String): JsonObject {
        coEvery { api.clearTable(any(), any()) } throws IOException("sin red")
        coEvery { outbox.enqueue("v1", "CLEAR_TABLE", capture(payload), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "i"
        assertTrue(repo().clearTable("v1", tableId, orderId).isSuccess)
        return payload.captured
    }

    @Test fun `mesa abierta sin red - lleva la mesa y su cuenta local`() = runTest {
        session.start(TableSession.Active("t1", "5", null, "L-1", "LOCAL", 1, 4500, TableSession.Mode.PAYING, isProvisional = true), "v1")

        assertEquals(JsonObject(mapOf("tableId" to JsonPrimitive("t1"), "localOrderId" to JsonPrimitive("L-1"))), liberarSinRed("t1", "L-1"))
    }

    @Test fun `cuenta del servidor - lleva la mesa y su cuenta`() = runTest {
        assertEquals(JsonObject(mapOf("tableId" to JsonPrimitive("t1"), "orderId" to JsonPrimitive("o-1"))), liberarSinRed("t1", "o-1"))
    }

    // D87 (hz:8024bc1c:D87): tras el rechazo de «liberar» por OTRA cuenta, se pregunta por la cuenta misma, en segundo plano.
    private fun cuenta(id: String, status: String) = OrderDetailResponse(order = OrderDetail(id = id, status = status))

    @Test fun `D87 cuenta cobrada en el servidor - solo COMPLETED de ESA cuenta`() = runTest {
        coEvery { api.getOrderDetailEnSegundoPlano("v1", "o-1") } returns cuenta("o-1", "COMPLETED")
        assertTrue(repo().cuentaCobradaEnServidor("v1", "o-1"))
        coEvery { api.getOrderDetailEnSegundoPlano("v1", "o-1") } returns cuenta("o-1", "PENDING")
        assertFalse("abono parcial: todavía debe", repo().cuentaCobradaEnServidor("v1", "o-1"))
        coEvery { api.getOrderDetailEnSegundoPlano("v1", "o-1") } returns cuenta("o-1", "CANCELLED")
        assertFalse("anulada no es cobrada", repo().cuentaCobradaEnServidor("v1", "o-1"))
        coEvery { api.getOrderDetailEnSegundoPlano("v1", "o-1") } returns cuenta("o-2", "COMPLETED")
        assertFalse("respuesta de otra cuenta", repo().cuentaCobradaEnServidor("v1", "o-1"))
        coEvery { api.getOrderDetailEnSegundoPlano("v1", "o-1") } returns OrderDetailResponse(order = null)
        assertFalse(repo().cuentaCobradaEnServidor("v1", "o-1"))
        coEvery { api.getOrderDetailEnSegundoPlano("v1", "o-1") } throws IOException("sin red")
        assertFalse("sin respuesta: como antes", repo().cuentaCobradaEnServidor("v1", "o-1"))
        coVerify(exactly = 0) { api.getOrderDetail(any(), any()) }
    }

    @Test fun `con red no se encola nada`() = runTest {
        coEvery { api.clearTable("v1", "t1") } returns mockk(relaxed = true)

        assertTrue(repo().clearTable("v1", "t1", "o-1").isSuccess)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
