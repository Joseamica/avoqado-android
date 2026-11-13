package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_CONCEDIDA
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_ESPERAR
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_OCUPADA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hub D3 (mesa declarada) — el ÁRBITRO: cada caja dice en qué mesas está y qué aperturas pide; se cree su mensaje más nuevo
 * (`gen`, `seq`) y cada apertura se decide por `id`. Reloj monotónico inyectado: los 30 s, 12 s y 10 min se prueban sin esperar.
 */
class PresenciaEnElArbitroTest {

    private var mono = 0L
    private val server = LeaseServer(deviceId = "X", monoMillis = { mono })
    private val nombres = mapOf("A" to "Ana", "B" to "Beto", "C" to "Caro", "X" to "Xime")

    /** Por omisión el calentamiento ya terminó: nadie está pendiente (Codex plan r1 #6). */
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
        en: LeaseServer = server,
    ): LeaseResponse = en.respondTo(
        LeaseProtocol.encode(
            LeaseRequest(
                op = LeaseProtocol.OP_ESTADO, deviceId = dev, staffName = nombres[dev] ?: dev,
                gen = gen, seq = seq, mesas = mesas, aperturas = aperturas,
            ),
        ),
    )

    /** Una sola apertura y su veredicto. */
    private fun abre(dev: String, gen: Long, seq: Long, id: String, mesa: String, en: LeaseServer = server): WireResultadoApertura =
        estado(dev, gen, seq, aperturas = listOf(WireApertura(id, mesa)), en = en).aperturas!!.single { it.id == id }

    @Test
    fun `p07 (gen, seq) repetido o menor es stale y no cambia nada, gen mayor se acepta`() {
        val primero = estado("A", 1, 5, mesas = listOf("m13"))
        assertEquals(LeaseProtocol.STATUS_OK, primero.status)
        assertEquals("X", primero.arbitro)

        assertEquals(LeaseProtocol.STATUS_STALE, estado("A", 1, 5).status)
        assertEquals(LeaseProtocol.STATUS_STALE, estado("A", 1, 4).status)
        assertEquals(APERTURA_OCUPADA, abre("C", 1, 1, "c1", "m13").resultado)

        assertEquals(LeaseProtocol.STATUS_OK, estado("A", 2, 1).status)
        assertEquals(APERTURA_CONCEDIDA, abre("C", 1, 2, "c1", "m13").resultado)
    }

    @Test
    fun `p08 A y B dentro, sale A, C pide la mesa como libre y sale ocupada por B`() {
        estado("A", 1, 1, mesas = listOf("m13"))
        estado("B", 1, 1, mesas = listOf("m13"))
        estado("A", 1, 2)

        val r = abre("C", 1, 1, "c1", "m13")
        assertEquals(APERTURA_OCUPADA, r.resultado)
        assertEquals("Beto", r.quien)
    }

    @Test
    fun `p10 una apertura nueva de la misma mesa reemplaza a la vieja por id y la vieja ya no cuenta`() {
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 1, "u1", "m14").resultado)
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 2, "u2", "m14").resultado)
        assertEquals("Ana", abre("C", 1, 1, "c1", "m14").quien) // la u2 la ocupa

        estado("A", 1, 3) // A termina la u2; la u1 ya se había ido
        assertEquals(APERTURA_CONCEDIDA, abre("C", 1, 2, "c1", "m14").resultado)
    }

    @Test
    fun `p11 arbitro nuevo hace esperar hasta oir a las cajas esperadas o 12 s`() {
        server.asumirArbitraje(setOf("X", "A", "B"))
        val espera = abre("A", 1, 1, "u1", "m5")
        assertEquals(APERTURA_ESPERAR, espera.resultado)
        assertTrue("esperarMs=${espera.esperarMs}", espera.esperarMs!! in 1..12_000)

        estado("B", 1, 1)
        estado("X", 1, 1)
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 2, "u1", "m5").resultado)

        // Otra vuelta: B nunca habla; a los 12 s se concede igual.
        server.asumirArbitraje(setOf("A", "B"))
        assertEquals(APERTURA_ESPERAR, abre("A", 1, 3, "u1", "m5").resultado)
        mono += 11_999
        assertEquals(1L, abre("A", 1, 4, "u1", "m5").esperarMs)
        mono += 1 // 12_000 en total
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 5, "u1", "m5").resultado)
    }

    @Test
    fun `p17 lo que espero se decide al terminar el calentamiento y la primera que llega gana`() {
        server.asumirArbitraje(setOf("A", "B", "C"))
        assertEquals(APERTURA_ESPERAR, abre("A", 1, 1, "u1", "m7").resultado)
        assertEquals(APERTURA_ESPERAR, abre("B", 1, 1, "u2", "m7").resultado)

        mono += 12_000
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 2, "u1", "m7").resultado)
        val segunda = abre("B", 1, 2, "u2", "m7")
        assertEquals(APERTURA_OCUPADA, segunda.resultado)
        assertEquals("Ana", segunda.quien)
    }

    @Test
    fun `p18 una concesion que vence por silencio no revive si otra caja tomo la mesa`() {
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 1, "u1", "m14").resultado)
        estado("B", 1, 1, mesas = listOf("m14")) // B entra a la mesa ocupada (se permite)
        mono += 29_999
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 2, "u1", "m14").resultado) // viva: se conserva

        mono += 30_001 // A calla más de 30 s: su concesión vence
        estado("B", 1, 2, mesas = listOf("m14"))
        val r = abre("A", 1, 3, "u1", "m14")
        assertEquals(APERTURA_OCUPADA, r.resultado)
        assertEquals("Beto", r.quien)
    }

    @Test
    fun `p23 calentamiento siendo yo el arbitro termina al oirme, y sin asumir se espera 1 s`() {
        server.asumirArbitraje(setOf("X"))
        assertEquals(APERTURA_ESPERAR, abre("A", 1, 1, "a1", "m5").resultado)
        estado("X", 1, 1)
        assertEquals(APERTURA_CONCEDIDA, abre("A", 1, 2, "a1", "m5").resultado)

        val sinAsumir = LeaseServer(deviceId = "X", monoMillis = { mono })
        val r = abre("A", 1, 1, "a1", "m5", en = sinAsumir)
        assertEquals(APERTURA_ESPERAR, r.resultado)
        assertEquals(1_000L, r.esperarMs)

        server.dejarArbitraje()
        assertEquals(1_000L, abre("A", 1, 3, "a2", "m6").esperarMs)
    }

    @Test
    fun `p15 sin deviceId, gen 0 o seq 0 es error`() {
        assertEquals(LeaseProtocol.STATUS_ERROR, estado("", 1, 1).status)
        assertEquals(LeaseProtocol.STATUS_ERROR, estado("A", 0, 1).status)
        assertEquals(LeaseProtocol.STATUS_ERROR, estado("A", 1, 0).status)
    }

    @Test
    fun `p15b a los 10 min sin noticias la presencia se olvida`() {
        estado("A", 1, 5, mesas = listOf("m13"))
        mono += 599_999
        assertEquals(LeaseProtocol.STATUS_STALE, estado("A", 1, 1).status)
        mono += 1 // 600_000 sin noticias
        assertEquals(LeaseProtocol.STATUS_OK, estado("A", 1, 1).status)
    }

    @Test
    fun `p31 la presencia vive por el reloj monotonico aunque la hora de pared salte 1 h atras`() {
        var pared = 10_000_000_000L
        val s = LeaseServer(nowMillis = { pared }, deviceId = "X", monoMillis = { mono }).apply { asumirArbitraje(emptySet()) }
        estado("A", 1, 1, mesas = listOf("m13"), en = s)

        pared -= 3_600_000
        mono += 29_000
        assertEquals(APERTURA_OCUPADA, abre("C", 1, 1, "c1", "m13", en = s).resultado)
        mono += 1_000 // 30 s monotónicos: vencida, aunque la pared diga que es «antes»
        assertEquals(APERTURA_CONCEDIDA, abre("C", 1, 2, "c1", "m13", en = s).resultado)
    }

    @Test
    fun `p32 un estado fuera de los topes es error y la declaracion anterior sigue igual`() {
        assertEquals(LeaseProtocol.STATUS_OK, estado("A", 1, 1, mesas = listOf("m13")).status)

        val malos = listOf(
            estado("A", 1, 2, mesas = (1..51).map { "m$it" }),
            estado("A", 1, 2, aperturas = (1..21).map { WireApertura("u$it", "m$it") }),
            estado("A", 1, 2, aperturas = listOf(WireApertura("", "m1"))),
            estado("A", 1, 2, aperturas = listOf(WireApertura("x".repeat(65), "m1"))),
            estado("A", 1, 2, aperturas = listOf(WireApertura("u1", "m".repeat(65)))),
            estado("A", 1, 2, mesas = listOf("")),
        )
        malos.forEach { assertEquals(LeaseProtocol.STATUS_ERROR, it.status) }
        assertEquals(APERTURA_OCUPADA, abre("C", 1, 1, "c1", "m13").resultado)

        // Ni el (1, 2) quedó guardado; y justo en los topes se acepta.
        val enElTope = estado(
            "A", 1, 2,
            mesas = (1..49).map { "m$it" } + "m".repeat(64),
            aperturas = (1..20).map { WireApertura("u$it", "n$it") },
        )
        assertEquals(LeaseProtocol.STATUS_OK, enElTope.status)
        assertEquals(20, enElTope.aperturas!!.size)
    }
}
