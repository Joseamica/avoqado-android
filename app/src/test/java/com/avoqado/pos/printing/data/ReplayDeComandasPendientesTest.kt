package com.avoqado.pos.printing.data

import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class AlmacenFalsoReplay : AlmacenDeTexto {
    private var texto: String? = null
    override fun leer(): String? = texto
    override fun escribir(texto: String): Boolean { this.texto = texto; return true }
    override fun borrar() { texto = null }
}

/**
 * El que hace que la comanda salga sola cuando prendes la impresora — lo que pidió el founder.
 *
 * 🔴 El reintento automático se rendía tras ~1 minuto. Si el cocinero prendía la impresora diez
 * minutos después, la comanda seguía guardada pero alguien tenía que tocar el botón.
 */
class ReplayDeComandasPendientesTest {

    private val plan = TicketPlan(
        stationId = "st_cocina",
        unrouted = false,
        lines = listOf(ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))),
    )
    private val config = PrintConfig(
        printers = listOf(PrinterInfo("pr_cocina", "Cocina", "NETWORK", "192.168.1.50:9100")),
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1)),
    )
    private val trabajo = TrabajoPendiente(
        planes = listOf(plan), copiasPendientes = emptyMap(), saltadas = emptyList(),
        config = config, orderNumber = "ORD-5", orderType = "En tienda", serverName = null,
        comboNames = emptyMap(), venueId = "venue-1", orderId = "order-5",
    )
    private val fallo = EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "ORD-5", trabajo)

    private fun conPendiente(): Pair<ComandasPendientesStore, AlmacenFalsoReplay> {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(fallo)
        return store to almacen
    }

    @Test
    fun `cuando por fin sale, el pendiente se borra del aparato`() = runTest {
        val (store, almacen) = conPendiente()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } returns EstadoDeComanda.Salio

        val estado = ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals(EstadoDeComanda.Salio, estado)
        assertNull("la comanda salió y siguió guardada: se reimprimiría sola otra vez", almacen.leer())
        assertNull(store.pendiente.value)
    }

    /**
     * 🔴 Si sigue sin salir, el pendiente NO se toca: el siguiente tic vuelve a intentarlo. Es
     * justo lo que hace que salga cuando prendan la impresora media hora después.
     */
    @Test
    fun `si sigue fallando, el pendiente se conserva para el siguiente intento`() = runTest {
        val (store, almacen) = conPendiente()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } returns fallo

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals("se perdió el pendiente tras un intento fallido", "ORD-5", store.pendiente.value?.orderNumber)
        assertEquals(true, almacen.leer() != null)
    }

    @Test
    fun `sin nada pendiente no molesta a la impresora`() = runTest {
        val store = ComandasPendientesStore(AlmacenFalsoReplay())
        var llamadas = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers { llamadas++; EstadoDeComanda.Salio }

        assertNull(ReplayDeComandasPendientes(store, dispatcher).intentarAhora())
        assertEquals(0, llamadas)
    }

    /**
     * 🔴 La invariante que impide el ticket duplicado: el reloj y el botón «Volver a imprimir»
     * comparten ejecutor. Si el reloj ya está adentro, el toque del cajero NO encola un segundo
     * envío — se omite. Sin esto, los dos pueden mandar la misma comanda a la vez y la cocina
     * recibe dos.
     */
    @Test
    fun `P1 con un intento en curso, el segundo NO manda otra vez`() = runTest {
        val (store, _) = conPendiente()
        val puerta = CompletableDeferred<Unit>()
        var envios = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers {
            envios++
            puerta.await()
            EstadoDeComanda.Salio
        }
        val replay = ReplayDeComandasPendientes(store, dispatcher)

        val enVuelo = backgroundScope.async { replay.intentarAhora() }
        // Deja que el primero entre y se quede esperando en la puerta.
        kotlinx.coroutines.yield()

        val segundo = replay.intentarAhora()

        assertNull("el segundo intento mandó la comanda otra vez", segundo)
        assertEquals("la cocina habría recibido dos", 1, envios)
        puerta.complete(Unit)
        enVuelo.await()
    }
}
