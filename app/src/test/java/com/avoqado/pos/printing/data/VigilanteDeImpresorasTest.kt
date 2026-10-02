package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.printing.routing.ImpresoraObservadaRequest
import com.avoqado.pos.printing.routing.ImpresoraObservadaResponse
import com.avoqado.pos.printing.routing.ImpresoraObservadaResult
import com.avoqado.pos.printing.routing.PrintConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** El aviso al servidor («Cocina ahora está en .67») sale aunque haya pasado horas sin red, y nunca se pierde por un bache. */
class VigilanteDeImpresorasTest {

    private val aviso = AvisoDeImpresora(
        venueId = "venue_testarudo",
        previousAddress = "192.168.1.64",
        address = "192.168.1.67",
        stableKey = "mac:02107B1A76FC",
    )

    private fun armar(api: ApiService): Pair<VigilanteDeImpresoras, PrinterService> {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val printerService = PrinterService(context, mockk(relaxed = true), mockk(relaxed = true))
        val secure = mockk<SecureStorage>(relaxed = true)
        every { secure.venueId } returns "venue_testarudo"
        val config = mockk<PrintConfigRepository>(relaxed = true)
        return VigilanteDeImpresoras(context, printerService, config, api, secure) to printerService
    }

    @Test
    fun `un aviso que el servidor contesto sale de la lista`() = runBlocking {
        val api = mockk<ApiService>()
        coEvery { api.reportarImpresoraObservada(any(), any(), any()) } returns
            ImpresoraObservadaResponse(data = ImpresoraObservadaResult(updated = true, motivo = "ACTUALIZADA"))
        val (vigilante, service) = armar(api)
        service.avisosPendientes.poner("cocina", aviso)

        vigilante.mandarAvisos()

        coVerify {
            api.reportarImpresoraObservada(
                "venue_testarudo",
                "cocina",
                ImpresoraObservadaRequest("192.168.1.64", "192.168.1.67", "mac:02107B1A76FC"),
            )
        }
        assertNull(service.avisosPendientes.todas()["cocina"])
    }

    @Test
    fun `P1 sin red el aviso se queda para la siguiente vez`() = runBlocking {
        val api = mockk<ApiService>()
        coEvery { api.reportarImpresoraObservada(any(), any(), any()) } throws IOException("sin red")
        val (vigilante, service) = armar(api)
        service.avisosPendientes.poner("cocina", aviso)

        vigilante.mandarAvisos()

        assertEquals(aviso, service.avisosPendientes.todas()["cocina"])
    }

    @Test
    fun `un error del servidor tambien se reintenta`() = runBlocking {
        val api = mockk<ApiService>()
        coEvery { api.reportarImpresoraObservada(any(), any(), any()) } throws
            HttpException(Response.error<Any>(503, "".toResponseBody()))
        val (vigilante, service) = armar(api)
        service.avisosPendientes.poner("cocina", aviso)

        vigilante.mandarAvisos()

        assertEquals(aviso, service.avisosPendientes.todas()["cocina"])
    }

    @Test
    fun `un rechazo permanente no se reintenta para siempre`() = runBlocking {
        val api = mockk<ApiService>()
        coEvery { api.reportarImpresoraObservada(any(), any(), any()) } throws
            HttpException(Response.error<Any>(404, "".toResponseBody()))
        val (vigilante, service) = armar(api)
        service.avisosPendientes.poner("cocina", aviso)

        vigilante.mandarAvisos()

        assertNull(service.avisosPendientes.todas()["cocina"])
    }
}
