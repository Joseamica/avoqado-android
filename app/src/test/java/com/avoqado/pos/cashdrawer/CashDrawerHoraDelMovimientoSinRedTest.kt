package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.HoraDelMovimiento
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
 * ahora el REENVÍO la manda (`createdAt`, ISO-8601) junto con cuánto esperó (`edadMs`, con el mismo reloj del aparato). El SERVIDOR
 * guarda «llegada − espera» (un reloj desfasado se cancela solo) acotado a la vida de la caja. El primer POST con red va sin hora.
 *
 * Codex r2 #1: el umbral anterior de 60 s perdía la hora si el reloj se movía durante la espera; #2: esperar no probaba que faltara
 * internet. Ahora todo reenvío manda la hora real, que es lo que decidió el founder.
 */
class CashDrawerHoraDelMovimientoSinRedTest {

    private val veinte03 = java.time.Instant.parse("2026-11-12T02:03:00Z").toEpochMilli()

    @Test
    fun `P1 el reenvio manda la hora en ISO y cuanto espero`() {
        assertEquals(
            HoraDelMovimiento("2026-11-12T02:03:00Z", 8 * 60_000L),
            horaDelMovimientoParaElServidor(veinte03, veinte03 + 8 * 60_000),
        )
    }

    @Test
    fun `P1 reloj movido hacia atras durante la espera - viaja la hora sin espera`() {
        assertEquals(HoraDelMovimiento("2026-11-12T02:03:00Z", null), horaDelMovimientoParaElServidor(veinte03, veinte03 - 3_000_000))
    }

    @Test
    fun `una cola legada sin hora creible no manda hora`() {
        assertNull(horaDelMovimientoParaElServidor(1, veinte03))
        assertNull(horaDelMovimientoParaElServidor(0, veinte03))
    }

    @Test
    fun `P1 el reenvio del retiro lleva su hora y su espera en el cuerpo, junto a su llave`() = runTest {
        val cola: String? = """[{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"localId":"loc-r1","at":$veinte03}]"""
        val llamadas = mutableListOf<LlamadaCapturada>()
        repoConCola(cola, llamadas).reproducirPendientes()

        val salida = llamadas.first { it.path.endsWith("/pay-out") }
        assertTrue("viajó sin su hora: ${salida.body}", salida.body.contains("\"createdAt\":\"2026-11-12T02:03:00Z\""))
        assertTrue("viajó sin su espera: ${salida.body}", salida.body.contains("\"edadMs\":"))
        assertTrue("viajó sin su llave: ${salida.body}", salida.body.contains("\"localId\":\"loc-r1\""))
    }

    @Test
    fun `regresion un movimiento legado sin hora creible se reenvia sin hora`() = runTest {
        val cola: String? = """[{"kind":"PAY_IN","sessionId":"srv-1","amountCents":5000,"localId":"loc-i1","at":1}]"""
        val llamadas = mutableListOf<LlamadaCapturada>()
        repoConCola(cola, llamadas).reproducirPendientes()

        val salida = llamadas.first { it.path.endsWith("/pay-in") }
        assertFalse("una cola legada no debe llevar hora: ${salida.body}", salida.body.contains("createdAt"))
        assertFalse("ni espera: ${salida.body}", salida.body.contains("edadMs"))
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
