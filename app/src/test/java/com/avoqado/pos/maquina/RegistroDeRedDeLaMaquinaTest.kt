package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.di.registroDeRed
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex código r1 #18: el registro de red de depuración (nivel BODY) nunca lleva la credencial de la máquina: ni el encabezado con que
 * sube cada cierre, ni el cuerpo con que el SERVIDOR la entrega al emparejar. Una copia del registro no se lleva lo que DPAPI cifra.
 */
class RegistroDeRedDeLaMaquinaTest {

    private val servidor = MockWebServer().apply { start() }
    private val lineas = mutableListOf<String>()
    private val cliente = OkHttpClient.Builder().addInterceptor(registroDeRed(depuracion = true) { lineas += it }).build()

    @After
    fun apagar() {
        servidor.shutdown()
        cliente.dispatcher.executorService.shutdown()
    }

    @Test
    fun `P1 ni el encabezado de la credencial ni la que entrega el emparejamiento quedan en el registro`() = runBlocking {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"bindingId":"b-1","credential":"SECRETO-DEL-ALTA"}"""))
        servidor.enqueue(MockResponse().setBody("""{"success":true}"""))
        val sesion = mockk<SecureStorage> {
            every { venueId } returns "venue-1"
            every { venueName } returns "La Galeterie"
        }
        val emparejamiento = EmparejamientoDeLaMaquina(sesion, cliente).apply { baseUrl = servidor.url("/api/v1").toString().trimEnd('/') }
        val api = CierresDeLaMaquinaApi(cliente).apply { baseUrl = servidor.url("/api/v1").toString().trimEnd('/') }

        assertTrue(emparejamiento.emparejar("192.168.1.50").isSuccess)
        api.subirCierre("venue-1", "CREDENCIAL-DE-LA-PC", JSONObject().put("referenceCC", "AQ000000000001"))

        assertEquals("la credencial sí viajó", "CREDENCIAL-DE-LA-PC", servidor.takeRequest().let { servidor.takeRequest() }.getHeader(CierresDeLaMaquinaApi.CREDENCIAL))
        val registro = lineas.joinToString("\n")
        assertTrue("el registro sí se escribió", registro.contains("/cash-machine/sales"))
        // Codex código POS r10: el cuerpo de un cierre es de un solo envío; el registro no lo lee (ni lo podría reenviar).
        assertTrue(registro, registro.contains("one-shot body omitted") && !registro.contains("AQ000000000001"))
        assertTrue(registro, !registro.contains("SECRETO-DEL-ALTA"))
        assertTrue(registro, !registro.contains("CREDENCIAL-DE-LA-PC"))
    }
}
