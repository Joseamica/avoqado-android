package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.EstadoDeLaApertura
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.conLlavesLegadas
import com.avoqado.pos.cashdrawer.data.estadoDeAperturaVisible
import com.avoqado.pos.cashdrawer.data.localIdLegado
import com.avoqado.pos.cashdrawer.data.necesitaLlaveLegada
import com.avoqado.pos.cashdrawer.presentation.lineaDeApertura
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lote de P2/P3 de la auditoria de apps (5-sep-2026): lo que la pantalla de Caja DICE cuando la
 * caja todavia no llego al servidor (#4), y el movimiento legado que nunca se descarta en
 * silencio (#6). Los dos tocan dinero y offline; los otros dos del lote (#8 y #11) viven en sus
 * suites de siempre.
 */
class CashDrawerLoteP2P3Test {

    private fun op(kind: String, sessionId: String, cents: Int = 5_000, localId: String? = null, at: Long = 1, rechazadaEn: Long? = null) =
        PendingDrawerOp(kind, sessionId, cents, null, localId, at, rechazadaEn, if (rechazadaEn == null) null else "Monto invalido")

    // MARK: - #4 · «Caja abierta sin conexion»

    /**
     * 🔴 Una caja cuyo `OPEN` sigue en la cola se veia IDENTICA a una sana: ni un indicador, ni un
     * texto. Offline es un estado NORMAL y se DICE (regla del workspace).
     */
    @Test
    fun `una caja con su apertura en la cola se reporta como PENDIENTE`() {
        val cola = listOf(op("OPEN", "prov-1", 200_000, "loc-open"))
        assertEquals(EstadoDeLaApertura.PENDIENTE, estadoDeAperturaVisible(cola, "prov-1"))
    }

    /** En cuanto la apertura confirma, sale de la cola y la banda desaparece. */
    @Test
    fun `sin apertura en la cola la caja esta confirmada`() {
        val cola = listOf(op("PAY_OUT", "srv-1", localId = "loc-a", at = 2))
        assertEquals(EstadoDeLaApertura.CONFIRMADA, estadoDeAperturaVisible(cola, "srv-1"))
        assertEquals("sin caja no hay nada que decir", EstadoDeLaApertura.CONFIRMADA, estadoDeAperturaVisible(cola, null))
    }

    /**
     * 🔴 Y una apertura RECHAZADA no es «sin conexion»: de eso habla el aviso ROJO, que ademas
     * ofrece «Reintentar». Pintar la banda ambar encima diria que se va a arreglar sola.
     */
    @Test
    fun `una apertura rechazada no se reporta como pendiente`() {
        val cola = listOf(op("OPEN", "prov-1", 200_000, "loc-open", rechazadaEn = 99))
        assertEquals(EstadoDeLaApertura.RECHAZADA, estadoDeAperturaVisible(cola, "prov-1"))
    }

    /** La apertura de OTRA caja no puede pintar la banda de esta. */
    @Test
    fun `la apertura pendiente de otra caja no marca a esta`() {
        val cola = listOf(op("OPEN", "prov-OTRA", 200_000, "loc-open"))
        assertEquals(EstadoDeLaApertura.CONFIRMADA, estadoDeAperturaVisible(cola, "srv-1"))
    }

    // MARK: - #6 · un movimiento sin llave nunca se pierde

    /**
     * 🔴 EL CASO DE DINERO: una cola escrita por una version anterior a la idempotencia trae
     * retiros sin `localId`. Android los DESCARTABA en silencio (`?: return CONFIRMADA`): el
     * dinero salio del cajon, el servidor nunca se entero, y el arqueo salia con un faltante que
     * nadie podia explicar.
     */
    @Test
    fun `un movimiento legado sin llave recibe una determinista`() {
        val legado = op("PAY_OUT", "srv-1", 5_000, localId = null, at = 1_700_000_000_000)

        assertTrue(necesitaLlaveLegada(legado))
        val llave = localIdLegado(legado)
        assertTrue("la llave tiene que decir de donde viene: $llave", llave.startsWith("legacy-"))
        assertEquals("legacy- + 32 hex", "legacy-".length + 32, llave.length)
    }

    /** DETERMINISTA: el mismo movimiento da la misma llave, o cada reintento seria un retiro nuevo. */
    @Test
    fun `la llave legada es la misma en cada reintento`() {
        val legado = op("PAY_OUT", "srv-1", 5_000, localId = null, at = 1_700_000_000_000)
        assertEquals(localIdLegado(legado), localIdLegado(legado.copy()))
    }

    /** Y dos movimientos distintos NO pueden compartir llave: el servidor deduplica por ella. */
    @Test
    fun `movimientos distintos dan llaves distintas`() {
        val base = op("PAY_OUT", "srv-1", 5_000, localId = null, at = 1_700_000_000_000)
        assertNotEquals(localIdLegado(base), localIdLegado(base.copy(amountCents = 5_001)))
        assertNotEquals(localIdLegado(base), localIdLegado(base.copy(at = base.at + 1)))
        assertNotEquals(localIdLegado(base), localIdLegado(base.copy(kind = "PAY_IN")))
        assertNotEquals(localIdLegado(base), localIdLegado(base.copy(sessionId = "srv-2")))
    }

    /** Una entrada que YA tiene llave no se toca: reescribirla romperia su idempotencia. */
    @Test
    fun `una entrada con llave no se toca`() {
        val cola = listOf(
            op("PAY_OUT", "srv-1", localId = "loc-a", at = 1),
            op("PAY_IN", "srv-1", localId = null, at = 2),
            op("CLOSE", "srv-1", at = 3),
        )

        val migrada = conLlavesLegadas(cola)

        assertEquals("loc-a", migrada[0].localId)
        assertTrue("el legado si recibe llave: ${migrada[1].localId}", migrada[1].localId!!.startsWith("legacy-"))
        assertEquals("un CIERRE no lleva llave y no se inventa una", null, migrada[2].localId)
        assertFalse("un CLOSE nunca necesita llave", necesitaLlaveLegada(cola[2]))
    }

    /**
     * 🔴 Y EL RECORRIDO COMPLETO CONTRA EL SERVIDOR FALSO: el retiro legado SALE, con su llave en
     * el cuerpo, y la cola queda vacia. Antes desaparecia sin haberse mandado nunca.
     */
    @Test
    fun `el retiro legado se manda con su llave y se saca de la cola`() = runTest {
        var cola: String? = """[{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"at":1}]"""
        val st = mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } returns VENUE_ID
            every { it.userId } returns "staff-1"
            every { it.pendingDrawerOpsJson(any()) } answers { cola }
            every { it.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
        }
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = com.avoqado.pos.cashdrawer.data.CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = st,
            client = cashDrawerClient(
                "/cash-drawer/pay-out" to eventoJson("srv-e1", "PAY_OUT", "50.00"),
                capturadas = llamadas,
            ),
            pendingCashSales = sinCobrosEnCola(),
        )

        repo.reproducirPendientes()

        val salida = llamadas.firstOrNull { it.path.endsWith("/pay-out") }
        assertTrue("el retiro legado nunca se mando: $llamadas", salida != null)
        assertTrue("viajo SIN llave: ${salida!!.body}", salida.body.contains("\"localId\":\"legacy-"))
        val pendientes = cola?.let {
            Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it)
        } ?: emptyList()
        assertTrue("el retiro confirmado siguio en la cola: $pendientes", pendientes.isEmpty())
    }

    // MARK: - #11 · «Abierta {fecha} por » nunca queda colgando

    @Test
    fun `la linea de apertura completa dice quien y desde que aparato`() {
        assertEquals(
            "Abierta 04 sep, 19:24 por Ana Pérez · SM-X133",
            lineaDeApertura("04 sep, 19:24", "Ana Pérez", "SM-X133"),
        )
    }

    /** Sin nombre, el aparato NO se hace pasar por persona: se une con punto medio, sin «por». */
    @Test
    fun `sin nombre la linea no dice por`() {
        assertEquals("Abierta 04 sep, 19:24 · SM-X133", lineaDeApertura("04 sep, 19:24", "", "SM-X133"))
        assertEquals("Abierta 04 sep, 19:24 · SM-X133", lineaDeApertura("04 sep, 19:24", "   ", "SM-X133"))
    }

    /** Y sin nada, la linea se queda en la fecha: nunca «por » colgando. */
    @Test
    fun `sin nombre ni aparato la linea es solo la fecha`() {
        assertEquals("Abierta 04 sep, 19:24", lineaDeApertura("04 sep, 19:24", "", null))
        assertEquals("Abierta 04 sep, 19:24", lineaDeApertura("04 sep, 19:24", "  ", "  "))
    }
}
