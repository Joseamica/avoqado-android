package com.avoqado.pos.printing.routing

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrintConfigRepositoryTest {

    private val apiService = mockk<ApiService>(relaxed = true)
    private val payloadCache = mockk<PayloadCache>(relaxed = true)
    private val repository = PrintConfigRepository(apiService, payloadCache)

    private fun configConEstacion() = PrintConfig(
        printers = listOf(PrinterInfo(id = "pr_1", name = "Cocina", connectionType = "wifi", address = "192.168.1.50:9100")),
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1")),
        defaultStationId = "st_cocina",
        version = "v1",
    )

    @Test
    fun `getCurrentConfig starts as safe empty default`() {
        val config = repository.getCurrentConfig()
        assertTrue(config.stations.isEmpty())
        assertTrue(config.printers.isEmpty())
    }

    @Test
    fun `refresh success updates the flow with the fetched config`() = runTest {
        val fetched = configConEstacion()
        coEvery { apiService.getPrintConfig("venue-1") } returns PrintConfigResponse(success = true, data = fetched)

        repository.refresh("venue-1")

        assertEquals(fetched, repository.getCurrentConfig())
        assertEquals(fetched, repository.config.value)
    }

    @Test
    fun `sin red y sin cache queda vacío, sin crashear`() = runTest {
        coEvery { apiService.getPrintConfig("venue-1") } throws RuntimeException("network down")
        coEvery { payloadCache.load(any(), any()) } returns null

        repository.refresh("venue-1")

        // Un dispositivo que JAMÁS vio la config no puede inventarla.
        val config = repository.getCurrentConfig()
        assertTrue(config.stations.isEmpty())
        assertTrue(config.printers.isEmpty())
    }

    @Test
    fun `🔴 un refresh fallido NO borra la config buena — si no, deja de imprimir a media comida`() = runTest {
        coEvery { apiService.getPrintConfig("venue-1") } returns PrintConfigResponse(success = true, data = configConEstacion())
        repository.refresh("venue-1")
        assertEquals(1, repository.getCurrentConfig().stations.size)

        // Bache de WiFi mientras el local está lleno.
        coEvery { apiService.getPrintConfig("venue-1") } throws RuntimeException("timeout")
        repository.refresh("venue-1")

        // Antes esto se vaciaba ("fail-safe over stale"). El razonamiento era
        // equivocado para este dominio: el "fail-safe" era NO IMPRIMIR NADA, o
        // sea que la cocina nunca se entera del pedido. Una IP de impresora
        // ligeramente vieja es MUCHÍSIMO menos dañina que una comanda perdida —
        // y en una LAN esas IPs casi nunca cambian.
        // Verificado en hardware el 2026-07-25: con el comportamiento anterior,
        // enviar una ronda sin internet NO imprimía absolutamente nada.
        assertEquals(1, repository.getCurrentConfig().stations.size)
    }

    @Test
    fun `sin red se hidrata del cache en disco (sobrevive reinicio de la app)`() = runTest {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .encodeToString(PrintConfig.serializer(), configConEstacion())
        coEvery { apiService.getPrintConfig("venue-1") } throws RuntimeException("sin red")
        coEvery { payloadCache.load(PayloadCache.TYPE_PRINT_CONFIG, "venue-1") } returns
            PayloadCache.Cached(json = json, updatedAt = System.currentTimeMillis() - 12 * 60_000L)

        repository.refresh("venue-1")

        // Arrancar la app sin internet (memoria vacía) debe recuperar el ruteo:
        // es lo que hace que la comanda salga en un apagón de internet.
        assertEquals(1, repository.getCurrentConfig().stations.size)
        assertEquals("st_cocina", repository.getCurrentConfig().stations.first().id)
    }
}
