package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * D4 (hz:f34960c5:D4): en Mesas, un descuento que pide la aprobación de un gerente NUNCA se encola. Sin red no hay PIN, y la cola
 * lo subiría sin aprobar (y la pantalla diría «Descuento aplicado»). Un 403 tampoco se encola. Lo demás sigue igual que hoy.
 */
class DescuentoConAprobacionMesaTest {

    private val api = mockk<ApiService>()
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val cache = mockk<PayloadCache>(relaxed = true)

    // Constructor de hoy: apiService, payloadCache, syncOutbox, tableSession, roleManager, draftStore.
    private fun repo() = TableServiceRepository(api, cache, outbox, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `pide aprobacion y la red falla - el fallo sube y NO se encola`() = runTest {
        coEvery { api.applyOrderDiscount(any(), any(), any()) } throws IOException("sin red")

        val r = repo().applyOrderDiscount("v1", "o1", "d-gerente", pideAprobacion = true)

        assertTrue(r.isFailure)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pide aprobacion y el servidor dice 403 - falla sin encolar`() = runTest {
        coEvery { api.applyOrderDiscount(any(), any(), any()) } throws
            HttpException(Response.error<Any>(403, """{"error":"Forbidden","required":"tpv-orders:discount"}""".toResponseBody()))

        val r = repo().applyOrderDiscount("v1", "o1", "d-gerente", pideAprobacion = true)

        assertTrue(r.isFailure)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `regresion - un descuento sin aprobacion se encola sin red como hoy`() = runTest {
        coEvery { api.applyOrderDiscount(any(), any(), any()) } throws IOException("sin red")

        val r = repo().applyOrderDiscount("v1", "o1", "d-10")

        assertTrue(r.isSuccess)
        coVerify(exactly = 1) { outbox.enqueue("v1", "APPLY_DISCOUNT", any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
