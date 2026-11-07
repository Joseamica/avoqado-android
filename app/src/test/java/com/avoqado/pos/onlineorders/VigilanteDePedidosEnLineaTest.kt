package com.avoqado.pos.onlineorders

import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task A2: el vigilante global de «Pedidos en línea». Reloj virtual y repositorio falso: nada de red ni de tiempo real.
 * Cada prueba detiene su ciclo con `onStop` (un `delay` vivo hace girar a `runTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VigilanteDePedidosEnLineaTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private fun pedido(id: String, folio: String = id) = OnlineOrder(
        orderId = id, onlineOrderId = "o-$id", orderNumber = folio, status = OnlineOrderStatus.PENDING_ACCEPTANCE,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = "PENDING", customerName = null, customerPhone = null, note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 10000, remainingCents = 10000, needsPrint = false, items = emptyList(),
    )

    private fun inbox(pendientes: List<OnlineOrder> = emptyList(), active: Int = 0, storeOff: Boolean = false) = Inbox(
        serverTime = Instant.parse("2026-10-22T10:00:00Z"), pending = pendientes.size, active = active, done = 0,
        orders = pendientes, storeOff = storeOff, truncated = false, nextCursor = null,
    )

    /** El repositorio falso: cada llamada se anota (estado, comoBandeja) y responde lo que diga [respuesta]. */
    private class Falso(var respuesta: Result<Inbox>) {
        val llamadas = mutableListOf<Pair<String, Boolean>>()
        var antes: () -> Unit = {}
        suspend fun consultar(status: String, comoBandeja: Boolean): Result<Inbox> {
            llamadas += status to comoBandeja
            antes()
            return respuesta
        }
    }

    private class Fixture(
        val falso: Falso,
        var puedeLeer: Boolean = true,
        var tienePlan: Boolean = true,
    ) {
        var sonidos = 0
        var venue: String? = "v1"
        lateinit var vigilante: VigilanteDePedidosEnLinea
    }

    private fun TestScope.armar(
        respuesta: Result<Inbox>,
        puedeLeer: Boolean = true,
        tienePlan: Boolean = true,
    ): Fixture {
        val f = Fixture(Falso(respuesta), puedeLeer, tienePlan)
        f.vigilante = VigilanteDePedidosEnLinea(
            consultar = f.falso::consultar,
            puedeLeer = { f.puedeLeer },
            tienePlan = { f.tienePlan },
            sonar = { f.sonidos++ },
            scope = backgroundScope,
            venueActual = { f.venue },
        )
        return f
    }

    @Test
    fun `polls every 10s with background header and no tray header`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("consulta al arrancar", 1, f.falso.llamadas.size)
        advanceTimeBy(9_999); runCurrent()
        assertEquals("antes de 10 s no vuelve a consultar", 1, f.falso.llamadas.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.falso.llamadas.size)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, f.falso.llamadas.size)
        // El vigilante nunca manda la cabecera de bandeja (comoBandeja = false); la de fondo la pone el repositorio siempre.
        assertTrue(f.falso.llamadas.all { it == "pending" to false })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `backs off to 60s when store off and nothing pending`() = runTest {
        val f = armar(Result.success(inbox(storeOff = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.falso.llamadas.size)
        advanceTimeBy(59_999); runCurrent()
        assertEquals("con la tienda apagada y sin pendientes no martilla cada 10 s", 1, f.falso.llamadas.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sound repeats every 4s while pending and stops when list empties`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("suena en cuanto llega el pedido", 1, f.sonidos)
        advanceTimeBy(3_999); runCurrent()
        assertEquals(1, f.sonidos)
        advanceTimeBy(1); runCurrent()
        assertEquals("a los 4 s vuelve a sonar", 2, f.sonidos)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(3, f.sonidos)
        // Alguien acepta: el siguiente sondeo (a los 10 s) trae la lista vacía y el sonido para ahí mismo.
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(2_000); runCurrent() // t = 10 s
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        val alVaciar = f.sonidos
        advanceTimeBy(60_000); runCurrent()
        assertEquals("con la lista vacía no suena más", alVaciar, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `silenciarHastaNuevo stops the sound until a new order arrives`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.vigilante.silenciarHastaNuevo()
        runCurrent()
        val antes = f.sonidos
        advanceTimeBy(30_000); runCurrent()
        assertEquals("silenciado: el mismo pedido ya no suena", antes, f.sonidos)
        f.falso.respuesta = Result.success(inbox(listOf(pedido("a"), pedido("b"))))
        advanceTimeBy(10_000); runCurrent()
        assertTrue("un pedido nuevo vuelve a sonar", f.sonidos > antes)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `stops on onStop and resumes on onStart`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.vigilante.onStop(owner)
        val consultas = f.falso.llamadas.size
        val sonidos = f.sonidos
        advanceTimeBy(120_000); runCurrent()
        assertEquals("en segundo plano no consulta", consultas, f.falso.llamadas.size)
        assertEquals("en segundo plano no suena", sonidos, f.sonidos)
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("al volver consulta de inmediato", consultas + 1, f.falso.llamadas.size)
        assertTrue("tras el primer sondeo bueno vuelve a sonar", f.sonidos > sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `an order gone in the first fresh poll after resume never rings`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.vigilante.onStop(owner)
        val sonidos = f.sonidos
        // En segundo plano el pedido venció: el primer sondeo al volver ya viene vacío.
        f.falso.respuesta = Result.success(inbox())
        f.vigilante.onStart(owner)
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals("no suena con datos viejos", sonidos, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a field change on the same order does not add an extra ring`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.sonidos)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, f.sonidos)
        // Mismo pedido, otro campo (cambia el folio): el sondeo de los 10 s no reinicia el compás de 4 s.
        f.falso.respuesta = Result.success(inbox(listOf(pedido("a", folio = "99"))))
        advanceTimeBy(4_000); runCurrent() // t = 8 s
        assertEquals(3, f.sonidos)
        advanceTimeBy(2_000); runCurrent() // t = 10 s: sondeo con el campo cambiado
        assertEquals("el sondeo no da un timbre extra", 3, f.sonidos)
        advanceTimeBy(2_000); runCurrent() // t = 12 s
        assertEquals("sigue el compás de 4 s", 4, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a poll that returns after a venue switch is discarded`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.falso.antes = { f.venue = "v2" } // la sucursal cambia mientras el sondeo va en vuelo
        f.vigilante.onStart(owner)
        runCurrent()
        assertTrue("el resultado de la sucursal vieja no se guarda", f.vigilante.pendientes.value.isEmpty())
        assertEquals(0, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a discarded poll from the old branch also clears its list and open orders state`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")), active = 2)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.vigilante.pendientes.value.size)
        assertTrue(f.vigilante.hayPedidosAbiertos.value)
        // La sucursal cambia mientras el siguiente sondeo va en vuelo: se descarta, y NO queda el banner de la otra sucursal.
        f.falso.antes = { f.venue = "v2" }
        advanceTimeBy(10_000); runCurrent()
        assertTrue("el banner de la sucursal vieja no se queda", f.vigilante.pendientes.value.isEmpty())
        assertFalse("tampoco los pedidos abiertos de la vieja", f.vigilante.hayPedidosAbiertos.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a failed poll after a venue switch does not keep the old branch list`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")), active = 1)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.vigilante.pendientes.value.size)
        f.venue = "v2"
        f.falso.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        assertFalse(f.vigilante.hayPedidosAbiertos.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sondearYa polls once right away and does not start a second loop`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.falso.llamadas.size)
        f.falso.respuesta = Result.success(inbox())
        f.vigilante.sondearYa()
        runCurrent()
        assertEquals("un sondeo inmediato", 2, f.falso.llamadas.size)
        assertTrue("y la lista ya se vació", f.vigilante.pendientes.value.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals("el compás sigue siendo uno solo, cada 10 s", 3, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sondearYa without an active cycle does nothing`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.sondearYa()
        runCurrent()
        assertEquals(0, f.falso.llamadas.size)
    }

    @Test
    fun `double onStart does not double the polls`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        f.vigilante.onStart(owner)
        runCurrent()
        val alArrancar = f.falso.llamadas.size
        advanceTimeBy(30_000); runCurrent()
        // Tres ciclos más de 10 s: uno por ciclo, no dos.
        assertEquals(alArrancar + 3, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `does not run without orders read`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))), puedeLeer = false)
        f.vigilante.onStart(owner)
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals("sin orders:read no hay ni una consulta", 0, f.falso.llamadas.size)
        assertEquals(0, f.sonidos)
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        // Si le dan el permiso, empieza solo.
        f.puedeLeer = true
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.falso.llamadas.isNotEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `keeps working without plan while orders exist and slows to 5 min without plan and without orders`() = runTest {
        // Sin plan pero con un pedido abierto (en preparación): sigue cada 10 s (R14).
        val f = armar(Result.success(inbox(active = 1)), tienePlan = false)
        f.vigilante.onStart(owner)
        runCurrent()
        assertTrue("hay pedidos abiertos", f.vigilante.hayPedidosAbiertos.value)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("sin plan pero con pedidos abiertos sondea cada 10 s", 2, f.falso.llamadas.size)

        // Se cierran todos: sin plan y sin pedidos abiertos baja a cada 5 min.
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(10_000); runCurrent()
        assertFalse(f.vigilante.hayPedidosAbiertos.value)
        val n = f.falso.llamadas.size
        advanceTimeBy(299_999); runCurrent()
        assertEquals("no consulta antes de 5 min", n, f.falso.llamadas.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(n + 1, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `network failure sets sinConexion and keeps last list`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertFalse(f.vigilante.sinConexion.value)
        f.falso.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.vigilante.sinConexion.value)
        assertEquals("conserva la última lista", listOf("a"), f.vigilante.pendientes.value.map { it.orderId })
        assertTrue("y sigue contando pedidos abiertos", f.vigilante.hayPedidosAbiertos.value)
        f.falso.respuesta = Result.success(inbox(listOf(pedido("a"))))
        advanceTimeBy(10_000); runCurrent()
        assertFalse("al volver la red se apaga el aviso", f.vigilante.sinConexion.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `non network failure keeps last list and does not claim sinConexion`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.falso.respuesta = Result.failure(IllegalStateException("403"))
        advanceTimeBy(10_000); runCurrent()
        assertFalse("un 403 o un JSON roto no es «sin conexión»", f.vigilante.sinConexion.value)
        assertEquals(listOf("a"), f.vigilante.pendientes.value.map { it.orderId })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `expired order disappears and sound stops`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a"), pedido("b")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(2, f.vigilante.pendientes.value.size)
        // El pedido «a» vence y «b» lo cancela el comensal: el siguiente sondeo ya no los trae.
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        val sonidos = f.sonidos
        advanceTimeBy(30_000); runCurrent()
        assertEquals("ya no suena", sonidos, f.sonidos)
        f.vigilante.onStop(owner)
    }

    // MARK: - Tienda híbrida (A2): los folios por pagar

    private fun folioPorPagar(id: String) = pedido(id, folio = "W${id.uppercase()}AAA".take(5)).copy(requiresAcceptance = false)

    private fun listaFolios(n: Int, toPay: Int = n, toDeliver: Int = 0, hayMas: Boolean = false, poll: Int = 30, disponible: Boolean = true) = ListaPorPagar(
        disponible, true, poll, ConteosPorPagar(toPay, toDeliver), (1..n).map { folioPorPagar("f$it") }, emptyList(),
        CursoresPorPagar(if (hayMas) "c" else null, null),
    )

    private class Folios(var respuesta: Result<ListaPorPagar>) {
        val latidos = mutableListOf<Boolean>()
        var guardada: ListaPorPagar? = null
        suspend fun leer(conLatido: Boolean): Result<ListaPorPagar> { latidos += conLatido; return respuesta }
    }

    private fun TestScope.armarConFolios(
        folios: Folios, puedeCobrar: Boolean = true, inbox: Inbox = inbox(), enCola: () -> Map<String, Long> = { emptyMap() },
        conTarjeta: () -> List<com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta> = { emptyList() },
    ): Fixture {
        val f = Fixture(Falso(Result.success(inbox)))
        f.vigilante = VigilanteDePedidosEnLinea(
            consultar = f.falso::consultar, puedeLeer = { f.puedeLeer }, tienePlan = { f.tienePlan }, sonar = { f.sonidos++ },
            scope = backgroundScope, venueActual = { f.venue },
            consultarPorPagar = folios::leer, puedeCobrar = { puedeCobrar }, foliosGuardados = { folios.guardada },
            efectivoEnCola = { enCola() },
            foliosConTarjeta = { _ -> conTarjeta() },
        )
        return f
    }

    @Test
    fun `A5 the menu number leaves out a folio this device charged by card until the server counts it`() = runTest {
        val marca = com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta("f2", montoCents = 10000, saldoAntesCents = 10000, enMs = 0)
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios, conTarjeta = { listOf(marca) })
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("la lista todavía lo trae con todo su saldo: ya no es «por pagar»", 2, f.vigilante.foliosPorPagar.value)

        // El SERVIDOR ya lo cuenta (salió de «Por pagar»): el número es el de la lista, sin restar nada más.
        folios.respuesta = Result.success(listaFolios(3).let { it.copy(porPagar = it.porPagar.filter { p -> p.orderId != "f2" }, counts = ConteosPorPagar(2, 1)) })
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `A3 the menu number leaves out what this device charged in cash, also without network`() = runTest {
        // La pantalla pinta en «Por entregar» lo cobrado aquí sin subir; el número del menú dice lo mismo.
        var cola = mapOf("f2" to 13400L)
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios, enCola = { cola })
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(2, f.vigilante.foliosPorPagar.value)

        // Sin red se cobra otro: el siguiente sondeo falla, pero el número ya no lo cuenta.
        folios.respuesta = Result.failure(IOException("sin red"))
        cola = mapOf("f2" to 13400L, "f3" to 9500L)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    /** R102: una respuesta más vieja que la copia guardada no mueve el número del menú. */
    @Test
    fun `an old to-pay response does not move the menu number`() = runTest {
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(3, f.vigilante.foliosPorPagar.value)

        folios.respuesta = Result.success(listaFolios(1).copy(masViejaQueLaCopia = true, copiaGuardada = false))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the watcher never rings for a folio and keeps them out of the pending list`() = runTest {
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("el conteo de folios llega al menú", 3, f.vigilante.foliosPorPagar.value)
        assertEquals("un folio nunca suena", 0, f.sonidos)
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the heartbeat only goes with payments create`() = runTest {
        val con = Folios(Result.success(listaFolios(1)))
        val a = armarConFolios(con, puedeCobrar = true)
        a.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf(true), con.latidos)
        a.vigilante.onStop(owner)

        val sin = Folios(Result.success(listaFolios(1)))
        val b = armarConFolios(sin, puedeCobrar = false)
        b.vigilante.onStart(owner); runCurrent()
        assertEquals("sin payments:create se lee la lista pero no se late", listOf(false), sin.latidos)
        b.vigilante.onStop(owner)
    }

    @Test
    fun `folios are polled every pollSeconds and open the entry`() = runTest {
        val folios = Folios(Result.success(listaFolios(1, poll = 30)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(1, folios.latidos.size)
        assertTrue("con folios el menú se atiende aunque el plan venza", f.vigilante.hayPedidosAbiertos.value)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, folios.latidos.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, folios.latidos.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the menu number never contradicts the list and a failure keeps it`() = runTest {
        val folios = Folios(Result.success(listaFolios(2, toPay = 3)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("un folio dañado se saltó: el número es el de la lista", 2, f.vigilante.foliosPorPagar.value)

        folios.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(30_000); runCurrent()
        assertEquals("sin red se conserva el último número", 2, f.vigilante.foliosPorPagar.value)

        folios.respuesta = Result.success(listaFolios(0, disponible = false))
        advanceTimeBy(30_000); runCurrent()
        assertEquals("el servidor dice que no hay sección: cero", 0, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `cold start without network takes the number from the saved list`() = runTest {
        val folios = Folios(Result.failure(IOException("sin red")))
        folios.guardada = listaFolios(4)
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(4, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `without orders read no folio is read`() = runTest {
        val folios = Folios(Result.success(listaFolios(2)))
        val f = armarConFolios(folios)
        f.puedeLeer = false
        f.vigilante.onStart(owner); runCurrent()
        assertTrue(folios.latidos.isEmpty())
        assertEquals(0, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }
}
