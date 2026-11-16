package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.ESPERA_PARA_MANDAR_LA_HORA_MS
import com.avoqado.pos.cashdrawer.data.horaDelMovimientoParaElServidor
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encargo 921513 (hz:e5ee98bc:D14, founder 16-nov «A»): el retiro hecho sin internet a las 20:03 quedaba en el SERVIDOR a las 20:11:42,
 * la hora en que subió (ensayo general, escenario 14b). La cola del cajón ya guardaba la hora del movimiento (`PendingDrawerOp.at`);
 * ahora el reenvío la manda (`createdAt`, ISO-8601) y el SERVIDOR la acota a la vida de la caja.
 *
 * 🔴 Con internet todo queda como hoy (Codex r1 #4): la hora sólo viaja si el movimiento ESPERÓ en la cola (≥ 60 s, medido con el
 * mismo reloj del aparato, así que un reloj desfasado no lo engaña). Un primer envío o un reintento inmediato van sin hora y el
 * SERVIDOR pone la de llegada, como siempre.
 */
class CashDrawerHoraDelMovimientoSinRedTest {

    private val veinte03 = java.time.Instant.parse("2026-11-12T02:03:00Z").toEpochMilli()

    @Test
    fun `P1 un movimiento que espero en la cola manda su hora en ISO`() {
        assertEquals("2026-11-12T02:03:00Z", horaDelMovimientoParaElServidor(veinte03, veinte03 + 8 * 60_000))
    }

    @Test
    fun `P1 uno que no espero (envio con red o reintento inmediato) no manda hora`() {
        assertNull(horaDelMovimientoParaElServidor(veinte03, veinte03 + 2_000))
        assertNull(horaDelMovimientoParaElServidor(veinte03, veinte03 + ESPERA_PARA_MANDAR_LA_HORA_MS - 1))
    }

    @Test
    fun `una hora rara (cero o futura respecto del aparato) no viaja`() {
        assertNull(horaDelMovimientoParaElServidor(0, veinte03))
        assertNull(horaDelMovimientoParaElServidor(veinte03 + 60_000, veinte03))
    }

    @Test
    fun `P1 el reenvio del retiro lleva su hora en el cuerpo, junto a su llave`() = runTest {
        val cola: String? = """[{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"localId":"loc-r1","at":$veinte03}]"""
        val llamadas = mutableListOf<LlamadaCapturada>()
        repoConCola(cola, llamadas).reproducirPendientes()

        val salida = llamadas.first { it.path.endsWith("/pay-out") }
        assertTrue("viajó sin su hora: ${salida.body}", salida.body.contains("\"createdAt\":\"2026-11-12T02:03:00Z\""))
        assertTrue("viajó sin su llave: ${salida.body}", salida.body.contains("\"localId\":\"loc-r1\""))
    }

    @Test
    fun `regresion un movimiento recien encolado se reenvia sin hora`() = runTest {
        val ahora = System.currentTimeMillis()
        val cola: String? = """[{"kind":"PAY_IN","sessionId":"srv-1","amountCents":5000,"localId":"loc-i1","at":$ahora}]"""
        val llamadas = mutableListOf<LlamadaCapturada>()
        repoConCola(cola, llamadas).reproducirPendientes()

        val salida = llamadas.first { it.path.endsWith("/pay-in") }
        assertFalse("un envío sin espera no debe llevar hora: ${salida.body}", salida.body.contains("createdAt"))
    }

    private fun repoConCola(inicial: String?, llamadas: MutableList<LlamadaCapturada>): CashDrawerRepository {
        var cola = inicial
        val st = mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } returns VENUE_ID
            every { it.userId } returns "staff-1"
            every { it.pendingDrawerOpsJson(any()) } answers { cola }
            every { it.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
        }
        return CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = st,
            client = cashDrawerClient(
                "/cash-drawer/pay-out" to eventoJson("srv-e1", "PAY_OUT", "50.00"),
                "/cash-drawer/pay-in" to eventoJson("srv-e2", "PAY_IN", "50.00"),
                capturadas = llamadas,
            ),
            pendingCashSales = sinCobrosEnCola(),
            conectividad = conectividadDePrueba(),
        )
    }
}
