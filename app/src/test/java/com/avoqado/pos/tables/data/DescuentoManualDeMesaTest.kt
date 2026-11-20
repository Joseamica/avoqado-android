package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.pos.domain.MotivoElegido
import com.avoqado.pos.tables.presentation.manualDejaLaCuentaEnCero
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Encargo 7133e3 (hz:0555ee15:D2): «Descuento manual» en la cuenta de MESAS.
 * - La MISMA llave viaja en el POST y en el intent de la cola: si el POST sí llegó y se perdió la respuesta, el SERVIDOR no lo duplica.
 * - Quien necesita el PIN no se encola (sin red no hay PIN); una cuenta abierta sin red va directo a la cola.
 * - Dejar la cuenta en $0 es una cortesía (como el SERVIDOR, en centavos).
 */
class DescuentoManualDeMesaTest {
    private val venue = "venue-7133"
    private val llave = "6f1c2a3b-4d5e-4f60-8a71-9b0c1d2e3f40"
    private val manual = DescuentoManualDeMesa("FIXED_AMOUNT", 20.0, "Amigo", llave)

    private class Mundo(val repo: TableServiceRepository, val api: ApiService, val encolados: MutableList<Pair<String, JsonObject>>)

    private fun mundo(sesion: TableSession = TableSession()): Mundo {
        val dao = mockk<CachedPayloadDao>(relaxed = true)
        val api = mockk<ApiService>()
        val encolados = mutableListOf<Pair<String, JsonObject>>()
        val outbox = mockk<SyncOutbox>()
        coEvery { outbox.enqueue(venue, any(), any(), any()) } answers {
            encolados += secondArg<String>() to thirdArg<JsonObject>()
            "intent-${encolados.size}"
        }
        val repo = TableServiceRepository(api, PayloadCache(dao), outbox, sesion, mockk(relaxed = true), mockk(relaxed = true))
        return Mundo(repo, api, encolados)
    }

    @Test fun `en linea manda el POST con su llave y su motivo y no encola nada`() = runTest {
        val m = mundo()
        val cuerpo = slot<ApplyManualOrderDiscountRequest>()
        coEvery { m.api.applyManualOrderDiscount(venue, "orden-1", capture(cuerpo)) } returns SimpleSuccessResponse(success = true)
        val r = m.repo.applyManualOrderDiscount(venue, "orden-1", manual, MotivoElegido("Queja", "tardó"), encolarSinRed = true, provisional = false)
        assertEquals(DesenlaceDelManual.APLICADO, r.getOrThrow())
        assertEquals(ApplyManualOrderDiscountRequest("FIXED_AMOUNT", 20.0, "Amigo", llave, "Queja", "tardó"), cuerpo.captured)
        assertTrue(m.encolados.isEmpty())
    }

    @Test fun `P1 la red cae a media llamada - la cola lleva la MISMA llave que el POST`() = runTest {
        val m = mundo()
        val cuerpo = slot<ApplyManualOrderDiscountRequest>()
        coEvery { m.api.applyManualOrderDiscount(venue, "orden-1", capture(cuerpo)) } throws java.net.SocketTimeoutException("sin respuesta")
        val r = m.repo.applyManualOrderDiscount(venue, "orden-1", manual, null, encolarSinRed = true, provisional = false)
        assertEquals(DesenlaceDelManual.GUARDADO_SIN_RED, r.getOrThrow())
        val (tipo, payload) = m.encolados.single()
        assertEquals("APPLY_DISCOUNT", tipo)
        assertEquals("orden-1", payload["orderId"]!!.jsonPrimitive.content)
        val enCola = payload["manual"]!!.jsonObject
        assertEquals(cuerpo.captured.llave, enCola["llave"]!!.jsonPrimitive.content)
        assertEquals("FIXED_AMOUNT", enCola["type"]!!.jsonPrimitive.content)
        assertEquals(20.0, enCola["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertNull(payload["discountId"])
    }

    @Test fun `quien necesita PIN no se encola - la falla de red sube tal cual`() = runTest {
        val m = mundo()
        coEvery { m.api.applyManualOrderDiscount(any(), any(), any()) } throws java.net.ConnectException("sin red")
        val r = m.repo.applyManualOrderDiscount(venue, "orden-1", manual, null, encolarSinRed = false, provisional = false)
        assertTrue(r.isFailure)
        assertTrue(m.encolados.isEmpty())
    }

    @Test fun `un rechazo del SERVIDOR no se encola (403, 400)`() = runTest {
        val m = mundo()
        coEvery { m.api.applyManualOrderDiscount(any(), any(), any()) } throws
            retrofit2.HttpException(retrofit2.Response.error<Any>(400, "{}".toResponseBody()))
        val r = m.repo.applyManualOrderDiscount(venue, "orden-1", manual, null, encolarSinRed = true, provisional = false)
        assertTrue(r.isFailure)
        assertTrue(m.encolados.isEmpty())
    }

    @Test fun `cuenta abierta sin red en este aparato - va directo a la cola con su id local, sin tocar la red`() = runTest {
        val sesion = TableSession().also {
            it.start(TableSession.Active(tableId = "t7", tableNumber = "M7", areaName = null, orderId = "local-1", orderNumber = "L-1",
                version = 0, totalCents = 10000, mode = TableSession.Mode.ORDERING, isProvisional = true, localOrderId = "local-1"))
        }
        val m = mundo(sesion)
        val r = m.repo.applyManualOrderDiscount(venue, "local-1", manual, null, encolarSinRed = true, provisional = true)
        assertEquals(DesenlaceDelManual.GUARDADO_SIN_RED, r.getOrThrow())
        coVerify(exactly = 0) { m.api.applyManualOrderDiscount(any(), any(), any()) }
        assertEquals("local-1", m.encolados.single().second["localOrderId"]!!.jsonPrimitive.content)
    }

    @Test fun `dejar la cuenta en cero es cortesia - en centavos, contra lo que queda`() {
        // Cuenta de $100 sin descuento.
        assertTrue(manualDejaLaCuentaEnCero("PERCENTAGE", 100.0, 100.0, 0.0))
        assertFalse(manualDejaLaCuentaEnCero("PERCENTAGE", 99.0, 100.0, 0.0))
        assertTrue(manualDejaLaCuentaEnCero("FIXED_AMOUNT", 500.0, 100.0, 0.0))
        assertFalse(manualDejaLaCuentaEnCero("FIXED_AMOUNT", 99.99, 100.0, 0.0))
        // Con $20 ya descontados, $80 la deja en cero.
        assertTrue(manualDejaLaCuentaEnCero("FIXED_AMOUNT", 80.0, 100.0, 20.0))
        // Sin la cuenta leída no se sabe: se trata como cortesía (pide el PIN, nunca se encola a ciegas).
        assertTrue(manualDejaLaCuentaEnCero("PERCENTAGE", 10.0, null, null))
    }

    @Test fun `la pantalla de MESAS ofrece el descuento manual con el formulario de MOSTRADOR y su motivo`() {
        val path = "src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertTrue(source.contains("Text(\"Descuento manual\")"))
        assertTrue(source.contains("com.avoqado.pos.pos.presentation.checkout.ManualDiscountEntry("))
        assertTrue(source.contains("viewModel.applyManualDiscount(manual.type, manual.value, manual.name, motivo)"))
    }
}
