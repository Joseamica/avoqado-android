package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.pos.domain.MotivoElegido
import com.avoqado.pos.tables.presentation.firmaDelManual
import com.avoqado.pos.tables.presentation.manualDejaLaCuentaEnCero
import com.avoqado.pos.tables.presentation.valorDelManual
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

    @Test fun `Codex r2 6 - el % redondea mitad hacia arriba como el SERVIDOR`() {
        // $1.25 con $0.22 ya descontados: quedan 103 centavos; 82 % de 125 = 102.5 → 103 ⇒ deja la cuenta en cero.
        assertTrue(manualDejaLaCuentaEnCero("PERCENTAGE", 82.0, 1.25, 0.22))
        // Codex r2 1: el valor se normaliza a 2 decimales antes de decidir (99.99996 % → 100 %).
        assertEquals(100.0, valorDelManual(99.99996), 0.0)
        assertTrue(manualDejaLaCuentaEnCero("PERCENTAGE", 99.99996, 20000.0, 0.0))
        assertTrue(manualDejaLaCuentaEnCero("FIXED_AMOUNT", 1.005, 1.01, 0.0))
    }

    @Test fun `Codex r2 9 - la firma no confunde un comentario ausente con el texto null`() {
        val sin = firmaDelManual("v", "o", "PERCENTAGE", 10.0, "", MotivoElegido("Queja", null))
        val conTexto = firmaDelManual("v", "o", "PERCENTAGE", 10.0, "", MotivoElegido("Queja", "null"))
        assertNotEquals(sin, conTexto)
        assertEquals(sin, firmaDelManual("v", "o", "PERCENTAGE", 10.0, "", MotivoElegido("Queja", null)))
        assertNotEquals(sin, firmaDelManual("v", "otra", "PERCENTAGE", 10.0, "", MotivoElegido("Queja", null)))
    }

    @Test fun `Codex r2 2 - la llave del manual vive en el repositorio hasta la respuesta buena`() {
        val m = mundo()
        val firma = firmaDelManual(venue, "orden-1", "FIXED_AMOUNT", 20.0, "Amigo", null)
        val primera = m.repo.llaveDelManual(firma)
        assertEquals(primera, m.repo.llaveDelManual(firma)) // otro toque igual (o volver a la mesa): la misma
        m.repo.soltarLlaveDelManual(firma)
        assertNotEquals(primera, m.repo.llaveDelManual(firma)) // ya confirmado: otro descuento igual es otro
    }

    @Test fun `Codex r3 3 - otro manual de la misma cuenta sin confirmar se ve, uno de otra cuenta no`() {
        val m = mundo()
        val a = firmaDelManual(venue, "orden-1", "FIXED_AMOUNT", 60.0, "", null)
        val b = firmaDelManual(venue, "orden-1", "FIXED_AMOUNT", 50.0, "", null)
        val otraCuenta = firmaDelManual(venue, "orden-10", "FIXED_AMOUNT", 50.0, "", null)
        assertFalse(m.repo.hayOtrosManualesSinConfirmar(venue, "orden-1", b))
        m.repo.llaveDelManual(a)
        assertTrue(m.repo.hayOtrosManualesSinConfirmar(venue, "orden-1", b))
        assertFalse(m.repo.hayOtrosManualesSinConfirmar(venue, "orden-1", a)) // la misma firma no cuenta como «otro»
        assertFalse(m.repo.hayOtrosManualesSinConfirmar(venue, "orden-10", otraCuenta)) // "orden-1" no es prefijo de "orden-10"
        assertTrue(m.repo.hayLlaveDelManual(a))
    }

    @Test fun `la pantalla de MESAS ofrece el descuento manual con el formulario de MOSTRADOR y su motivo`() {
        val path = "src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertTrue(source.contains("Text(\"Descuento manual\")"))
        assertTrue(source.contains("com.avoqado.pos.pos.presentation.checkout.ManualDiscountEntry("))
        assertTrue(source.contains("viewModel.applyManualDiscount(manual.type, manual.value, manual.name, motivo)"))
    }
}
