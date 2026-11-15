package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_CONCEDIDA
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_ESPERAR
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_OCUPADA
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_ACQUIRE
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_RELEASE
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_RENEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hub D3 (mesa declarada) — el MISMO árbitro con apps viejas (acquire/renew/release sobre [LeaseRegistry]): adopta los permisos
 * viejos del aparato que ya declara su estado, niega el acquire viejo de una mesa declarada, cuenta el permiso viejo como ocupada
 * y `list` sale sin duplicados. Reloj monotónico y de pared inyectados.
 */
class PresenciaYPermisosViejosTest {

    private var mono = 0L
    private var pared = 1_000_000L
    private val registro = LeaseRegistry()
    private val server = LeaseServer(registry = registro, nowMillis = { pared }, deviceId = "X", monoMillis = { mono })
    private val nombres = mapOf("A" to "Ana", "B" to "Beto", "D" to "Dani")

    @Before
    fun montar() {
        server.asumirArbitraje(emptySet())
    }

    private fun estado(
        dev: String,
        gen: Long,
        seq: Long,
        mesas: List<String> = emptyList(),
        aperturas: List<WireApertura> = emptyList(),
    ): LeaseResponse = server.respondTo(
        LeaseProtocol.encode(
            LeaseRequest(
                op = LeaseProtocol.OP_ESTADO, deviceId = dev, staffName = nombres[dev] ?: dev,
                gen = gen, seq = seq, mesas = mesas, aperturas = aperturas,
            ),
        ),
    )

    private fun abre(dev: String, gen: Long, seq: Long, id: String, mesa: String): WireResultadoApertura =
        estado(dev, gen, seq, aperturas = listOf(WireApertura(id, mesa))).aperturas!!.single { it.id == id }

    /** Lo que manda una app vieja, tal cual por el cable. */
    private fun viejo(op: String, dev: String, mesa: String, epoch: Long = 0): LeaseResponse = server.respondTo(
        LeaseProtocol.encode(
            LeaseRequest(op = op, tableId = mesa, deviceId = dev, staffId = "s$dev", staffName = nombres[dev] ?: dev, epoch = epoch),
        ),
    )

    private fun lista(): List<WireLease> = server.respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_LIST))).leases!!

    @Test
    fun `p13 el acquire viejo se niega si una caja nueva declara la mesa, y la nueva ve el permiso viejo como ocupada`() {
        estado("A", 1, 1, mesas = listOf("m13"))
        mono += 10_000

        val negado = viejo(OP_ACQUIRE, "D", "m13")
        assertEquals(LeaseProtocol.STATUS_DENIED, negado.status)
        val holder = negado.holder!!
        assertEquals("Ana", holder.holderName)
        assertEquals("A", holder.holderDeviceId)
        assertEquals(0L, holder.epoch)
        assertEquals(pared + 20_000, holder.expiresAtMillis) // lo que le queda de los 30 s, en hora de pared

        assertEquals(LeaseProtocol.STATUS_GRANTED, viejo(OP_ACQUIRE, "D", "m20").status)
        val r = abre("A", 1, 2, "u1", "m20")
        assertEquals(APERTURA_OCUPADA, r.resultado)
        assertEquals("Dani", r.quien)

        // D no está en presencia: su renew y su release siguen como hoy; al soltar, A la obtiene.
        assertEquals(LeaseProtocol.STATUS_GRANTED, viejo(OP_RENEW, "D", "m20", epoch = 1).status)
        assertEquals(LeaseProtocol.STATUS_OK, viejo(OP_RELEASE, "D", "m20", epoch = 1).status)
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 3, "u1", "m20").resultado)
    }

    @Test
    fun `p22 tras un estado, el acquire viejo del mismo aparato que llega tarde es error y la mesa sigue libre`() {
        estado("A", 1, 1) // ninguna

        val tarde = viejo(OP_ACQUIRE, "A", "m13")
        assertEquals(LeaseProtocol.STATUS_ERROR, tarde.status)
        assertEquals(LeaseProtocol.ERROR_EN_PRESENCIA, tarde.message)
        assertEquals(APERTURA_CONCEDIDA, abre("B", 1, 1, "b1", "m13").resultado)

        assertEquals(LeaseProtocol.STATUS_ERROR, viejo(OP_RENEW, "A", "m13", epoch = 1).status)
        assertEquals(LeaseProtocol.STATUS_ERROR, viejo(OP_RELEASE, "A", "m13", epoch = 1).status)
        assertTrue(lista().none { it.holderDeviceId == "A" })

        // «En presencia» aunque calle más de 30 s; se olvida a los 10 min y vuelve a hablar como vieja.
        mono += 599_999
        assertEquals(LeaseProtocol.STATUS_ERROR, viejo(OP_ACQUIRE, "A", "m30").status)
        mono += 1
        assertEquals(LeaseProtocol.STATUS_GRANTED, viejo(OP_ACQUIRE, "A", "m30").status)

        // Si reaparece con un estado (cualquier gen/seq tras el olvido), ese permiso viejo se adopta.
        assertEquals(LeaseProtocol.STATUS_OK, estado("A", 1, 1).status)
        assertTrue(lista().none { it.tableId == "m30" })
    }

    @Test
    fun `p22b su propio permiso viejo no le ocupa la mesa y su primer estado lo adopta`() {
        assertEquals(LeaseProtocol.STATUS_GRANTED, viejo(OP_ACQUIRE, "A", "m13").status)

        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 1, "a1", "m13").resultado)
        val m13 = lista().single { it.tableId == "m13" }
        assertEquals("A", m13.holderDeviceId)
        assertEquals(0L, m13.epoch) // ya es la presencia: el permiso viejo se borró
    }

    @Test
    fun `p27 un permiso viejo que se colo se borra con el siguiente estado aceptado del mismo aparato`() {
        estado("A", 1, 1)
        registro.acquire("m13", "A", "sA", "Ana", pared) // como si un acquire viejo se hubiera colado
        assertEquals(listOf("m13"), lista().map { it.tableId })
        assertEquals("Ana", abre("B", 1, 1, "b1", "m13").quien) // mientras siga ahí, cuenta como ocupada

        // Un estado repetido (stale) o inválido (error) no adopta nada.
        assertEquals(LeaseProtocol.STATUS_STALE, estado("A", 1, 1).status)
        assertEquals(LeaseProtocol.STATUS_ERROR, estado("A", 0, 3).status)
        assertEquals(listOf("m13"), lista().map { it.tableId })

        assertEquals(LeaseProtocol.STATUS_OK, estado("A", 1, 2).status)
        assertTrue(lista().none { it.tableId == "m13" })
        assertEquals(APERTURA_CONCEDIDA, abre("B", 1, 2, "b1", "m13").resultado)
        // La época más alta se conserva: un zombi con la época 1 sigue fuera.
        assertEquals(LeaseResult.Stale(1), registro.renew("m13", "A", 1, pared))
    }

    @Test
    fun `p26 list sale una vez por mesa, la de presencia con epoch 0, el deviceId menor y sin vencidas`() {
        assertEquals(LeaseProtocol.STATUS_GRANTED, viejo(OP_ACQUIRE, "D", "m1").status)
        estado("B", 1, 1, mesas = listOf("m2", "m3"), aperturas = listOf(WireApertura("b1", "m4")))
        estado("A", 1, 1, mesas = listOf("m1", "m2"))
        mono += 5_000

        val l = lista()
        assertEquals(listOf("m1", "m2", "m3", "m4"), l.map { it.tableId })
        assertEquals("D", l[0].holderDeviceId) // el permiso viejo va primero y gana su mesa
        assertEquals("A", l[1].holderDeviceId) // A y B declaran m2: el deviceId menor
        assertEquals("Ana", l[1].holderName)
        assertEquals(0L, l[1].epoch)
        assertEquals(pared + 25_000, l[1].expiresAtMillis)
        assertEquals("B", l[3].holderDeviceId) // la apertura concedida también cuenta

        mono += 25_000 // A y B callaron 30 s
        assertEquals(listOf("m1"), lista().map { it.tableId })
    }

    @Test
    fun `p23b una caja vieja que pide durante el calentamiento recibe la respuesta de hoy`() {
        server.asumirArbitraje(setOf("A", "B"))
        assertEquals(APERTURA_ESPERAR, abre("A", 1, 1, "u1", "m5").resultado) // la nueva espera

        val r = viejo(OP_ACQUIRE, "D", "m5") // una apertura en ESPERAR no ocupa la mesa
        assertEquals(LeaseProtocol.STATUS_GRANTED, r.status)
        assertEquals(1L, r.lease!!.epoch)
    }
}
