package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocolo del hub LAN + el árbitro respondiendo.
 *
 * Lo que se protege aquí es el FORMATO DE CABLE: una tablet Android y un iPad
 * tienen que arbitrarse entre sí, así que cualquier cambio de nombres de campo
 * rompe la interoperabilidad en silencio. Los JSON literales de abajo son el
 * contrato — si un test falla porque "cambió el nombre del campo", hay que
 * cambiarlo TAMBIÉN en avoqado-ios/Services/LAN/LeaseProtocol.swift.
 */
class LeaseProtocolTest {

    @Test
    fun `un acquire viaja con los campos exactos que espera la otra plataforma`() {
        val encoded = LeaseProtocol.encode(
            LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "mesa-5", deviceId = "d1", staffId = "s1", staffName = "Juan"),
        )

        // La versión viaja como "v" (corto: son paquetes por WiFi de local).
        assertTrue(encoded, encoded.contains("\"v\":1"))
        assertTrue(encoded, encoded.contains("\"op\":\"acquire\""))
        assertTrue(encoded, encoded.contains("\"tableId\":\"mesa-5\""))
        assertTrue(encoded, encoded.contains("\"staffName\":\"Juan\""))
    }

    @Test
    fun `decodifica un request generado por la otra plataforma`() {
        // JSON tal cual lo produce el encoder de Swift (orden de campos distinto
        // y sin los opcionales): tiene que entenderse igual.
        val fromIOS = """{"op":"renew","tableId":"mesa-9","deviceId":"ipad-1","epoch":4,"v":1}"""

        val request = LeaseProtocol.decodeRequest(fromIOS)

        assertNotNull(request)
        assertEquals(LeaseProtocol.OP_RENEW, request!!.op)
        assertEquals("mesa-9", request.tableId)
        assertEquals(4L, request.epoch)
    }

    @Test
    fun `decodifica una respuesta de Swift, que OMITE los opcionales nulos`() {
        // 🔴 Asimetría real del cable: Swift (JSONEncoder) NO manda los campos
        // nil, Kotlin sí los manda como null explícito. Android tiene que
        // entender la forma corta o un iPad-árbitro sería inservible para las
        // tablets. Es el bug que no se ve probando una sola plataforma.
        val fromIOS = """{"v":1,"status":"denied","holder":{"tableId":"m5","holderDeviceId":"ipad","holderStaffId":"s1","holderName":"Juan","epoch":3,"expiresAtMillis":1030000}}"""

        val response = LeaseProtocol.decodeResponse(fromIOS)

        assertNotNull(response)
        assertEquals(LeaseProtocol.STATUS_DENIED, response!!.status)
        assertEquals("Juan", response.holder?.holderName)
        assertEquals(3L, response.holder?.epoch)
        assertNull(response.lease)
        assertNull(response.message)
    }

    @Test
    fun `decodifica un request de Swift sin los campos que no aplican`() {
        // Swift manda `list` sin tableId/deviceId/etc; los defaults cubren.
        val fromIOS = """{"v":1,"op":"list"}"""

        val request = LeaseProtocol.decodeRequest(fromIOS)

        assertNotNull(request)
        assertEquals(LeaseProtocol.OP_LIST, request!!.op)
        assertEquals("", request.tableId)
        assertEquals(0L, request.epoch)
    }

    @Test
    fun `campos desconocidos de un peer más nuevo NO rompen el decode`() {
        val fromNewerPeer = """{"v":1,"op":"list","futureField":"algo","otro":42}"""

        assertNotNull(LeaseProtocol.decodeRequest(fromNewerPeer))
    }

    @Test
    fun `una línea basura decodifica a null en vez de tronar`() {
        assertNull(LeaseProtocol.decodeRequest("esto no es json"))
        assertNull(LeaseProtocol.decodeResponse("{roto"))
    }

    @Test
    fun `el lease sobrevive el viaje de ida y vuelta por el cable`() {
        val original = TableLease("mesa-3", "d1", "s1", "Juan", epoch = 7, expiresAtMillis = 1_700_000)

        val roundTripped = original.toWire().toDomain()

        assertEquals(original, roundTripped)
    }
}

class LeaseServerTest {

    private var clock = 1_000_000L
    private fun server() = LeaseServer(nowMillis = { clock })

    private fun ask(s: LeaseServer, request: LeaseRequest): LeaseResponse =
        s.respondTo(LeaseProtocol.encode(request))

    @Test
    fun `acquire concede la mesa y devuelve la época`() {
        val s = server()

        val response = ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "d1", staffId = "s1", staffName = "Juan"))

        assertEquals(LeaseProtocol.STATUS_GRANTED, response.status)
        assertEquals(1L, response.lease?.epoch)
        assertEquals("Juan", response.lease?.holderName)
    }

    @Test
    fun `EL CASO por cable - la segunda tablet recibe denied con el nombre del dueño`() {
        val s = server()
        ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "juan", staffId = "s1", staffName = "Juan"))

        val response = ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "alberto", staffId = "s2", staffName = "Alberto"))

        assertEquals(LeaseProtocol.STATUS_DENIED, response.status)
        // Sin el nombre, la otra tablet solo podría decir "no se pudo".
        assertEquals("Juan", response.holder?.holderName)
    }

    @Test
    fun `ZOMBI por cable - renovar con época vieja no revive al dueño anterior`() {
        val s = server()
        ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "juan", staffId = "s1", staffName = "Juan"))

        clock += LeaseRegistry.DEFAULT_TTL_MILLIS + 1 // el lease de Juan caduca
        ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "alberto", staffId = "s2", staffName = "Alberto"))

        val zombi = ask(s, LeaseRequest(op = LeaseProtocol.OP_RENEW, tableId = "m5", deviceId = "juan", epoch = 1))

        assertEquals(LeaseProtocol.STATUS_DENIED, zombi.status)
        assertEquals("Alberto", zombi.holder?.holderName)
    }

    @Test
    fun `un peer con OTRA versión del protocolo se rechaza explícito`() {
        val s = server()
        // Escenario real: el APK nuevo tarda días en llegar a todas las tablets.
        val fromFuture = """{"v":99,"op":"acquire","tableId":"m5","deviceId":"d1"}"""

        val response = s.respondTo(fromFuture)

        assertEquals(LeaseProtocol.STATUS_ERROR, response.status)
        assertEquals(LeaseProtocol.ERROR_VERSION_MISMATCH, response.message)
    }

    @Test
    fun `list devuelve las mesas ocupadas para pintar el plano`() {
        val s = server()
        ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m1", deviceId = "d1", staffId = "s1", staffName = "Juan"))
        ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m2", deviceId = "d2", staffId = "s2", staffName = "Alberto"))

        val response = ask(s, LeaseRequest(op = LeaseProtocol.OP_LIST))

        assertEquals(LeaseProtocol.STATUS_LEASES, response.status)
        assertEquals(listOf("m1", "m2"), response.leases?.map { it.tableId })
    }

    @Test
    fun `release solo lo logra el dueño con la época vigente`() {
        val s = server()
        ask(s, LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "m5", deviceId = "d1", staffId = "s1", staffName = "Juan"))

        val ajeno = ask(s, LeaseRequest(op = LeaseProtocol.OP_RELEASE, tableId = "m5", deviceId = "d2", epoch = 1))
        assertEquals(LeaseProtocol.STATUS_STALE, ajeno.status)

        val propio = ask(s, LeaseRequest(op = LeaseProtocol.OP_RELEASE, tableId = "m5", deviceId = "d1", epoch = 1))
        assertEquals(LeaseProtocol.STATUS_OK, propio.status)
    }

    @Test
    fun `una petición ilegible responde error en vez de tumbar al árbitro`() {
        assertEquals(LeaseProtocol.STATUS_ERROR, server().respondTo("<<basura>>").status)
    }
}
