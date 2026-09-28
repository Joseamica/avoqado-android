package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El enrutador del transporte único (etapa 3 del KDS, 3.5, D1/D4), sin sockets. Espejo de `EnrutadorLanTests` de iOS. */
class EnrutadorLanTest {

    private val comanda = KdsLanProtocol.encode(
        KdsComanda(
            venueId = "venue-1", deviceId = "tablet-1", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra",
            orderNumber = "1234", orderType = "En tienda", createdAtMillis = 1, items = emptyList(),
        ),
    )
    private val acquire = LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "d1", staffId = "s1", staffName = "Juan"))

    private suspend fun responder(
        linea: String,
        hub: ((String) -> LeaseResponse)? = null,
        receptor: (suspend (KdsComanda) -> Boolean)? = null,
        venue: String? = "venue-1",
        estaciones: Set<String> = setOf("st_barra"),
    ) = EnrutadorLan.responder(linea, venue, estaciones, hub, receptor)

    @Test
    fun `P1 sin hub un lease recibe Operacion desconocida y no envenena nada`() = runTest {
        val r = LeaseProtocol.decodeResponse(responder(acquire))
        assertEquals(LeaseProtocol.STATUS_ERROR, r?.status)
        assertEquals("Operación desconocida: acquire", r?.message)
    }

    @Test
    fun `con hub un lease se contesta con el arbitro`() = runTest {
        val server = LeaseServer(nowMillis = { 1_000_000 })
        assertEquals(LeaseProtocol.STATUS_GRANTED, LeaseProtocol.decodeResponse(responder(acquire, hub = server::respondTo))?.status)
    }

    @Test
    fun `P1 sin receptor una comanda no se acusa`() = runTest {
        val r = responder(comanda)
        assertFalse(KdsLanProtocol.esAcuse(r, "sale:ext-1:st_barra"))
        assertEquals("Operación desconocida: comanda", KdsLanProtocol.decodeAck(r)?.message)
    }

    @Test
    fun `P1 la pantalla rechaza venue ajeno y estacion no anunciada, y solo acusa si guardo`() = runTest {
        var llamadas = 0
        val receptor: suspend (KdsComanda) -> Boolean = { llamadas++; true }
        assertFalse(KdsLanProtocol.esAcuse(responder(comanda, receptor = receptor, venue = "venue-2"), "sale:ext-1:st_barra"))
        assertFalse(KdsLanProtocol.esAcuse(responder(comanda, receptor = receptor, estaciones = setOf("st_cocina")), "sale:ext-1:st_barra"))
        assertEquals("una comanda ajena ni se guarda", 0, llamadas)
        assertEquals("Venue ajeno", KdsLanProtocol.decodeAck(responder(comanda, receptor = receptor, venue = "venue-2"))?.message)
        assertEquals("Estación no anunciada", KdsLanProtocol.decodeAck(responder(comanda, receptor = receptor, estaciones = emptySet()))?.message)

        var recibida: KdsComanda? = null
        assertTrue(KdsLanProtocol.esAcuse(responder(comanda, receptor = { recibida = it; true }), "sale:ext-1:st_barra"))
        assertEquals("1234", recibida?.orderNumber)
    }

    @Test
    fun `si el receptor no pudo guardar no se acusa`() = runTest {
        val r = responder(comanda, receptor = { false })
        assertFalse(KdsLanProtocol.esAcuse(r, "sale:ext-1:st_barra"))
        assertEquals("No se pudo guardar", KdsLanProtocol.decodeAck(r)?.message)
    }

    @Test
    fun `una comanda con otra version se rechaza y la basura contesta Peticion ilegible`() = runTest {
        val v2 = comanda.replace("\"v\":1", "\"v\":2")
        assertEquals(LeaseProtocol.ERROR_VERSION_MISMATCH, KdsLanProtocol.decodeAck(responder(v2, receptor = { true }))?.message)
        assertEquals("Petición ilegible", LeaseProtocol.decodeResponse(responder("<<basura>>"))?.message)
    }
}
