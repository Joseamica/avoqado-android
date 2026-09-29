package com.avoqado.pos.printing.data

import com.avoqado.pos.core.data.lan.ClienteDeComandas
import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.LanPeer
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** La caja guarda ANTES de empujar, empuja en paralelo y sólo borra lo acusado (etapa 3 del KDS, 3.5, D5/D7). */
class EntregaPorWifiTest {

    private val pantallaBarra = LanPeer("cpad", "10.0.0.5", 9000, kdsStations = setOf("st_barra"))
    private val transporte = mockk<TransporteLan> {
        every { deviceId } returns "tablet-1"
        every { peers } returns MutableStateFlow(listOf(pantallaBarra))
        every { pantallasDe("st_barra") } returns listOf(pantallaBarra)
        every { pantallasDe("st_postres") } returns emptyList()
    }
    private val dao = mockk<EntregasKdsPendientesDao>(relaxed = true)
    private val cliente = mockk<ClienteDeComandas>()
    private val entrega = EntregaPorWifi(transporte, dao, cliente)

    private fun mensaje(station: String) = KdsComanda(
        venueId = "venue-1", deviceId = "tablet-1", sourceKey = "sale:ext-1:$station", stationId = station,
        orderNumber = "1234", orderType = "En tienda", createdAtMillis = 1, items = emptyList(),
    )
    private val trabajo = TrabajoPendiente(
        planes = emptyList(), config = PrintConfig(), orderNumber = "1234", orderType = "En tienda",
        serverName = null, comboNames = emptyMap(), venueId = "venue-1", orderId = null,
    )

    @Test
    fun `P1 la entrega se guarda ANTES de empujar y la acusada se borra`() = runTest {
        val guardada = slot<EntregaKdsPendienteEntity>()
        coEvery { dao.guardar(capture(guardada)) } returns Unit
        coEvery { cliente.entregar(pantallaBarra, any(), any()) } returns true

        val acusadas = entrega.entregar(listOf(EntregaKds(mensaje("st_barra"), trabajo)), ahora = 42L)

        assertEquals(setOf("st_barra"), acusadas)
        coVerifyOrder {
            dao.guardar(any())
            cliente.entregar(pantallaBarra, any(), any())
            dao.borrar("sale:ext-1:st_barra")
        }
        assertEquals("sale:ext-1:st_barra", guardada.captured.sourceKey)
        assertEquals("venue-1", guardada.captured.venueId)
        assertEquals(42L, guardada.captured.creadaEnMillis)
    }

    @Test
    fun `sin acuse la fila se queda (la borra el despachador cuando el papel ya salio)`() = runTest {
        coEvery { cliente.entregar(any(), any(), any()) } returns false
        assertEquals(emptySet<String>(), entrega.entregar(listOf(EntregaKds(mensaje("st_barra"), trabajo))))
        coVerify(exactly = 0) { dao.borrar(any()) }
    }

    @Test
    fun `impresora mas pantalla (sin trabajo de respaldo) se empuja pero no se guarda`() = runTest {
        coEvery { cliente.entregar(any(), any(), any()) } returns true
        assertEquals(setOf("st_barra"), entrega.entregar(listOf(EntregaKds(mensaje("st_barra"), trabajoDeRespaldo = null))))
        coVerify(exactly = 0) { dao.guardar(any()) }
    }

    @Test
    fun `P1 sin pantallas descubiertas no se espera nada - sin acuse al instante`() = runTest {
        val acusadas = entrega.entregar(listOf(EntregaKds(mensaje("st_postres"), trabajo)))
        assertEquals(emptySet<String>(), acusadas)
        coVerify(exactly = 0) { cliente.entregar(any(), any(), any()) }
    }

    @Test
    fun `varias pantallas de la misma estacion - basta UN acuse, y van en paralelo`() = runTest {
        val otra = LanPeer("ipad", "10.0.0.6", 9001, kdsStations = setOf("st_barra"))
        every { transporte.pantallasDe("st_barra") } returns listOf(pantallaBarra, otra)
        // La PRIMERA pantalla espera a que la SEGUNDA conteste: una implementación secuencial se cuelga (y `runTest` la
        // corta en rojo); sólo en paralelo se abre la puerta.
        val puerta = CompletableDeferred<Unit>()
        coEvery { cliente.entregar(pantallaBarra, any(), any()) } coAnswers { puerta.await(); false }
        coEvery { cliente.entregar(otra, any(), any()) } coAnswers { puerta.complete(Unit); true }

        assertEquals(setOf("st_barra"), entrega.entregar(listOf(EntregaKds(mensaje("st_barra"), trabajo))))
    }

    @Test
    fun `cerrar borra las filas de ese despacho`() = runTest {
        entrega.cerrar(listOf(EntregaKds(mensaje("st_barra"), trabajo), EntregaKds(mensaje("st_postres"), null)))
        coVerify(exactly = 1) { dao.borrar("sale:ext-1:st_barra") }
        coVerify(exactly = 1) { dao.borrar("sale:ext-1:st_postres") }
    }

    /** Ronda 1 (I2): el papel de respaldo que sale DESPUÉS («Volver a imprimir», el reloj de la libreta) cierra SU fila. */
    @Test
    fun `cerrarPorPapel borra solo las filas de esos planes y de esa orden`() = runTest {
        val planBarra = TicketPlan("st_barra", false, listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_2"))))
        val otraRonda = TicketPlan("st_barra", false, listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_9"))))
        fun fila(folio: String, orden: String, plan: TicketPlan) = EntregaKdsPendienteEntity(
            sourceKey = folio, venueId = "venue-1", stationId = "st_barra", mensajeJson = "{}",
            trabajoJson = Json.encodeToString(TrabajoPendiente.serializer(), trabajo.copy(orderNumber = orden, planes = listOf(plan))),
            creadaEnMillis = 1,
        )
        coEvery { dao.delVenue("venue-1") } returns listOf(
            fila("round:r1:st_barra", "1234", planBarra),
            fila("round:r2:st_barra", "1234", otraRonda),
            fila("sale:otra:st_barra", "9999", planBarra),
            fila("sale:rota:st_barra", "1234", planBarra).copy(trabajoJson = "{roto"),
        )

        entrega.cerrarPorPapel("venue-1", "1234", listOf(planBarra))

        coVerify(exactly = 1) { dao.borrar("round:r1:st_barra") }
        coVerify(exactly = 1) { dao.borrar(any()) }
    }
}
