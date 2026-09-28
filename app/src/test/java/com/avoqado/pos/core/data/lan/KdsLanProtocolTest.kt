package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cable de la operación `comanda` (etapa 3 del KDS, 3.5, D4). Igual que `LeaseProtocolTest`: los JSON literales de
 * abajo son el contrato con avoqado-ios (`KdsLanProtocolTests`). Si un nombre de campo cambia, cambia en los dos.
 */
class KdsLanProtocolTest {

    private val comanda = KdsComanda(
        venueId = "venue-1", deviceId = "tablet-1", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra",
        orderNumber = "1234", orderType = "En tienda", orderId = null, createdAtMillis = 1_700_000_000_000L,
        items = listOf(KdsComandaItem(id = "oi_2", productName = "Café", quantity = 1, modifiers = listOf("Sin azúcar"), notes = null)),
    )

    @Test
    fun `la comanda viaja con los campos exactos que espera Swift - y los nulos van EXPLICITOS`() {
        val encoded = KdsLanProtocol.encode(comanda)
        assertTrue(encoded, encoded.contains("\"v\":1"))
        assertTrue(encoded, encoded.contains("\"op\":\"comanda\""))
        assertTrue(encoded, encoded.contains("\"sourceKey\":\"sale:ext-1:st_barra\""))
        assertTrue(encoded, encoded.contains("\"createdAtMillis\":1700000000000"))
        // 🔴 Asimetría del cable (regla §3.2 del hub): Kotlin manda `null`; Swift tiene que aguantarlo.
        assertTrue(encoded, encoded.contains("\"orderId\":null"))
        assertTrue(encoded, encoded.contains("\"notes\":null"))
    }

    @Test
    fun `decodifica una comanda de Swift, que OMITE los nulos`() {
        val fromIOS = """{"v":1,"op":"comanda","venueId":"venue-1","deviceId":"ipad-1","sourceKey":"round:rk-9:st_barra","stationId":"st_barra","orderNumber":"77","orderType":"Mesa 8 · Aperitivos","createdAtMillis":1700000000000,"items":[{"id":"oi_9","productName":"Café","quantity":2,"modifiers":["Sin azúcar"]}]}"""

        val c = KdsLanProtocol.decodeComanda(fromIOS)

        assertNotNull(c)
        assertEquals("round:rk-9:st_barra", c!!.sourceKey)
        assertEquals("Mesa 8 · Aperitivos", c.orderType)
        assertNull(c.orderId)
        assertNull(c.items.single().notes)
        assertEquals(listOf("Sin azúcar"), c.items.single().modifiers)
    }

    @Test
    fun `decodifica un acuse de Swift y lo reconoce como acuse de SU folio`() {
        val fromIOS = """{"v":1,"status":"ok","sourceKey":"sale:ext-1:st_barra"}"""
        assertTrue(KdsLanProtocol.esAcuse(fromIOS, "sale:ext-1:st_barra"))
    }

    @Test
    fun `P1 un acuse con otro folio, un error o basura NO es acuse`() {
        val folio = "sale:ext-1:st_barra"
        assertFalse(KdsLanProtocol.esAcuse("""{"v":1,"status":"ok","sourceKey":"sale:ext-2:st_barra"}""", folio))
        assertFalse(KdsLanProtocol.esAcuse("""{"v":1,"status":"ok"}""", folio))
        // Lo que contesta un Android VIEJO (LeaseResponse con nulos explícitos) y un iPad VIEJO (sin nulos): sin acuse ⇒ papel.
        assertFalse(KdsLanProtocol.esAcuse("""{"v":1,"status":"error","lease":null,"holder":null,"currentEpoch":null,"leases":null,"message":"Operación desconocida: comanda"}""", folio))
        assertFalse(KdsLanProtocol.esAcuse("""{"v":1,"status":"error","message":"Operación desconocida: comanda"}""", folio))
        assertFalse(KdsLanProtocol.esAcuse("""{"v":2,"status":"ok","sourceKey":"$folio"}""", folio))
        assertFalse(KdsLanProtocol.esAcuse("{roto", folio))
        assertFalse(KdsLanProtocol.esAcuse(null, folio))
    }

    @Test
    fun `el acuse y el rechazo que produce esta app se leen igual`() {
        assertTrue(KdsLanProtocol.esAcuse(KdsLanProtocol.encode(KdsLanProtocol.acuse("sale:ext-1:st_barra")), "sale:ext-1:st_barra"))
        val rechazo = KdsLanProtocol.encode(KdsLanProtocol.rechazo("Venue ajeno"))
        assertFalse(KdsLanProtocol.esAcuse(rechazo, "sale:ext-1:st_barra"))
        assertEquals("Venue ajeno", KdsLanProtocol.decodeAck(rechazo)?.message)
    }

    @Test
    fun `opDe lee solo el op, sin decodificar el resto`() {
        assertEquals("comanda", KdsLanProtocol.opDe(KdsLanProtocol.encode(comanda)))
        assertEquals("list", KdsLanProtocol.opDe("""{"v":1,"op":"list"}"""))
        assertEquals("acquire", KdsLanProtocol.opDe("""  {"op":"acquire","tableId":"m5"}  """))
        assertNull(KdsLanProtocol.opDe("esto no es json"))
        assertNull(KdsLanProtocol.opDe("""{"v":1}"""))
    }

    @Test
    fun `campos desconocidos de un peer mas nuevo no rompen el decode`() {
        val fromNewer = """{"v":1,"op":"comanda","venueId":"v","deviceId":"d","sourceKey":"sale:x:st","stationId":"st","orderNumber":"1","orderType":"En tienda","createdAtMillis":1,"items":[],"futureField":"algo"}"""
        assertNotNull(KdsLanProtocol.decodeComanda(fromNewer))
        assertNotNull(KdsLanProtocol.decodeAck("""{"v":1,"status":"ok","sourceKey":"x","extra":42}"""))
    }
}
