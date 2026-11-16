package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.HoraDelMovimiento
import com.avoqado.pos.cashdrawer.data.InstanteMonotonico
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
 * ahora el REENVÍO la manda (`createdAt`, ISO-8601) junto con cuánto esperó (`edadMs`), medido con el reloj MONOTÓNICO del aparato
 * (arranque + `elapsedRealtime`), que no cambia aunque alguien mueva la hora a mano. El SERVIDOR guarda «llegada − espera» acotado a
 * la vida de la caja. El primer POST con red va sin hora.
 *
 * Codex r2 #1 y r3 #1: una resta de dos horas de PARED no prueba tiempo transcurrido (mover el reloj durante la espera daba una hora
 * falsa); #2: esperar no probaba que faltara internet, y ahora todo reenvío manda la hora real, que es lo que decidió el founder.
 */
class CashDrawerHoraDelMovimientoSinRedTest {

    private val veinte03 = java.time.Instant.parse("2026-11-12T02:03:00Z").toEpochMilli()

    private val arranque = "boot-1"

    @Test
    fun `P1 la espera sale del reloj monotonico aunque la hora de pared se mueva`() {
        // Retiro a las 20:03 con monotónico 1,000 s; reenvío 8 min después en el monotónico. La hora de pared no participa.
        assertEquals(
            HoraDelMovimiento("2026-11-12T02:03:00Z", 8 * 60_000L),
            horaDelMovimientoParaElServidor(veinte03, InstanteMonotonico(arranque, 1_000_000), InstanteMonotonico(arranque, 1_000_000 + 8 * 60_000)),
        )
    }

    @Test
    fun `P1 si el aparato se reinicio mientras esperaba viaja la hora sin espera`() {
        assertEquals(
            HoraDelMovimiento("2026-11-12T02:03:00Z", null),
            horaDelMovimientoParaElServidor(veinte03, InstanteMonotonico(arranque, 9_000_000), InstanteMonotonico("boot-2", 5_000)),
        )
    }

    @Test
    fun `una operacion de una version anterior sin instante viaja con la hora sola`() {
        assertEquals(HoraDelMovimiento("2026-11-12T02:03:00Z", null), horaDelMovimientoParaElServidor(veinte03, null, InstanteMonotonico(arranque, 5)))
    }

    @Test
    fun `P1 Codex r4 - reloj de pared en 2019 con espera medida - viaja la espera sin la fecha`() {
        val en2019 = java.time.Instant.parse("2019-06-01T00:00:00Z").toEpochMilli()
        assertEquals(
            HoraDelMovimiento(null, 8 * 60_000L),
            horaDelMovimientoParaElServidor(en2019, InstanteMonotonico(arranque, 1_000), InstanteMonotonico(arranque, 1_000 + 8 * 60_000)),
        )
    }

    @Test
    fun `P1 Codex r4 - con el arranque de respaldo no se acredita espera`() {
        assertEquals(
            HoraDelMovimiento("2026-11-12T02:03:00Z", null),
            horaDelMovimientoParaElServidor(veinte03, InstanteMonotonico("t29880000", 10_000), InstanteMonotonico("t29880000", 80_000)),
        )
    }

    @Test
    fun `una cola legada sin hora creible no manda hora`() {
        assertNull(horaDelMovimientoParaElServidor(1, null, InstanteMonotonico(arranque, 5)))
        assertNull(horaDelMovimientoParaElServidor(0, InstanteMonotonico(arranque, 1), InstanteMonotonico(arranque, 5)))
    }

    @Test
    fun `P1 el reenvio de un retiro de una version anterior lleva su hora y su llave, sin inventar espera`() = runTest {
        // Una operación de una versión anterior (sin instante monotónico): viaja la hora; la espera, sólo si es del mismo arranque.
        val cola: String? = """[{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"localId":"loc-r1","at":$veinte03}]"""
        val llamadas = mutableListOf<LlamadaCapturada>()
        repoConCola(cola, llamadas).reproducirPendientes()

        val salida = llamadas.first { it.path.endsWith("/pay-out") }
        assertTrue("viajó sin su hora: ${salida.body}", salida.body.contains("\"createdAt\":\"2026-11-12T02:03:00Z\""))
        assertFalse("sin instante monotónico no se inventa una espera: ${salida.body}", salida.body.contains("edadMs"))
        assertTrue("viajó sin su llave: ${salida.body}", salida.body.contains("\"localId\":\"loc-r1\""))
    }

    @Test
    fun `P1 el reenvio de un retiro de este arranque lleva su hora y su espera monotonica`() = runTest {
        // En las pruebas JVM `elapsedRealtime` devuelve 0: mismo arranque y mismo monotónico ⇒ espera 0, que sí viaja.
        val boot = com.avoqado.pos.core.data.lan.DuenoDeCuentas.ARRANQUE
        val cola: String? =
            """[{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"localId":"loc-r2","at":$veinte03,"arranque":"$boot","atMonotonico":0}]"""
        val llamadas = mutableListOf<LlamadaCapturada>()
        repoConCola(cola, llamadas).reproducirPendientes()

        val salida = llamadas.first { it.path.endsWith("/pay-out") }
        assertTrue("viajó sin su hora: ${salida.body}", salida.body.contains("\"createdAt\":\"2026-11-12T02:03:00Z\""))
        assertTrue("viajó sin su espera: ${salida.body}", salida.body.contains("\"edadMs\":0"))
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
