package com.avoqado.pos.areatickets.data

import com.avoqado.pos.areatickets.domain.PapelDelAparato
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** D3: el aparato guarda su papel en el servidor (`PUT …/terminals/:id/area-role`). */
class GuardarPapelDelAparatoTest {

    private val api = mockk<ApiService>()
    private val cache = mockk<PayloadCache>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns "v1" }
    private val repo = AreaTicketRepository(api = api, secureStorage = storage, session = AreaTicketSession(storage), payloadCache = cache)

    private val ajustes = AreaTicketSettingsData(
        venueId = "v1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = AreaTicketTerminalCapabilities(id = "t1", name = "PC", canCheckoutAreaTickets = true),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
        areas = listOf(AreaOpcion("a1", "Cremería")),
        areasTotal = 1,
    )

    private fun error(status: Int, cuerpo: String) =
        HttpException(Response.error<Any>(status, cuerpo.toResponseBody("application/json".toMediaType())))

    @Test
    fun `P1 cobra manda sólo el papel, sin área (el servidor rechaza campos de más)`() = runTest {
        val cuerpo = slot<JsonObject>()
        coEvery { api.setAreaTicketRole("v1", "t1", capture(cuerpo)) } returns AreaTicketEnvelope(true, ajustes)

        repo.guardarPapel("v1", "t1", PapelDelAparato.Cobra)

        assertEquals(JsonObject(mapOf("role" to JsonPrimitive("CHECKOUT"))), cuerpo.captured)
    }

    @Test
    fun `emitir vales manda el papel y el área`() = runTest {
        val cuerpo = slot<JsonObject>()
        coEvery { api.setAreaTicketRole("v1", "t1", capture(cuerpo)) } returns AreaTicketEnvelope(true, ajustes)

        repo.guardarPapel("v1", "t1", PapelDelAparato.EmiteVales("a1", "Cremería", tambienCobra = false))

        assertEquals(
            JsonObject(mapOf("role" to JsonPrimitive("ISSUE"), "fulfillmentAreaId" to JsonPrimitive("a1"))),
            cuerpo.captured,
        )
    }

    @Test
    fun `al guardar refresca la caché de ajustes de vales`() = runTest {
        coEvery { api.setAreaTicketRole(any(), any(), any()) } returns AreaTicketEnvelope(true, ajustes)

        val resultado = repo.guardarPapel("v1", "t1", PapelDelAparato.Cobra)

        assertEquals(ResultadoPapel.Guardado(ajustes), resultado)
        coVerify { cache.save(PayloadCache.TYPE_AREA_TICKET_SETTINGS, "v1", any()) }
    }

    @Test
    fun `P1 un 403 sin código es falta de permiso`() = runTest {
        coEvery { api.setAreaTicketRole(any(), any(), any()) } throws error(403, """{"error":"Forbidden","message":"Sin permiso"}""")

        assertEquals(ResultadoPapel.SinPermiso, repo.guardarPapel("v1", "t1", PapelDelAparato.Cobra))
    }

    @Test
    fun `un 403 de vales apagados es rechazo con su mensaje`() = runTest {
        coEvery { api.setAreaTicketRole(any(), any(), any()) } throws error(
            403,
            """{"success":false,"error":{"code":"AREA_TICKETS_DISABLED","message":"Los vales por área no están activos."}}""",
        )

        assertEquals(
            ResultadoPapel.Rechazado("Los vales por área no están activos."),
            repo.guardarPapel("v1", "t1", PapelDelAparato.Cobra),
        )
    }

    @Test
    fun `otro aparato es rechazo con el mensaje del servidor`() = runTest {
        coEvery { api.setAreaTicketRole(any(), any(), any()) } throws error(
            404,
            """{"success":false,"error":{"code":"NOT_FOUND","message":"Este aparato no es esa terminal."}}""",
        )

        assertEquals(
            ResultadoPapel.Rechazado("Este aparato no es esa terminal."),
            repo.guardarPapel("v1", "t1", PapelDelAparato.Cobra),
        )
    }

    @Test
    fun `sin red es SinRed`() = runTest {
        coEvery { api.setAreaTicketRole(any(), any(), any()) } throws IOException("sin red")

        assertEquals(ResultadoPapel.SinRed, repo.guardarPapel("v1", "t1", PapelDelAparato.Cobra))
    }

    @Test
    fun `entregar o sin elegir no se mandan`() = runTest {
        assertTrue(repo.guardarPapel("v1", "t1", PapelDelAparato.Entrega) is ResultadoPapel.Rechazado)
        assertTrue(repo.guardarPapel("v1", "t1", PapelDelAparato.SinElegir) is ResultadoPapel.Rechazado)
        coVerify(exactly = 0) { api.setAreaTicketRole(any(), any(), any()) }
    }
}
