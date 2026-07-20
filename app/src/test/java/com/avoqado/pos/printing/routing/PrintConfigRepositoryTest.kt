package com.avoqado.pos.printing.routing

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
    private val repository = PrintConfigRepository(apiService)

    @Test
    fun `getCurrentConfig starts as safe empty default`() {
        val config = repository.getCurrentConfig()
        assertTrue(config.stations.isEmpty())
        assertTrue(config.printers.isEmpty())
    }

    @Test
    fun `refresh success updates the flow with the fetched config`() = runTest {
        val station = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1")
        val printer = PrinterInfo(id = "pr_1", name = "Cocina Printer", connectionType = "wifi", address = "192.168.1.50:9100")
        val fetched = PrintConfig(
            printers = listOf(printer),
            stations = listOf(station),
            defaultStationId = "st_cocina",
            version = "v1",
        )
        coEvery { apiService.getPrintConfig("venue-1") } returns PrintConfigResponse(success = true, data = fetched)

        repository.refresh("venue-1")

        assertEquals(fetched, repository.getCurrentConfig())
        assertEquals(fetched, repository.config.value)
    }

    @Test
    fun `refresh failure leaves a safe empty default, not a crash`() = runTest {
        coEvery { apiService.getPrintConfig("venue-1") } throws RuntimeException("network down")

        repository.refresh("venue-1")

        val config = repository.getCurrentConfig()
        assertTrue(config.stations.isEmpty())
        assertTrue(config.printers.isEmpty())
    }

    @Test
    fun `refresh failure after a prior success resets to empty default (fail-safe over stale)`() = runTest {
        val station = StationInfo(id = "st_cocina", name = "Cocina")
        coEvery { apiService.getPrintConfig("venue-1") } returns PrintConfigResponse(
            success = true,
            data = PrintConfig(stations = listOf(station)),
        )
        repository.refresh("venue-1")
        assertEquals(1, repository.getCurrentConfig().stations.size)

        coEvery { apiService.getPrintConfig("venue-1") } throws RuntimeException("timeout")
        repository.refresh("venue-1")

        assertTrue(repository.getCurrentConfig().stations.isEmpty())
    }
}
