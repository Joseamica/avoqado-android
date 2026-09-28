package com.avoqado.pos.printing.routing

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.di.NetworkModule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `P1 un refresh fallido NO borra la config buena — si no, deja de imprimir a media comida`() = runTest {
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

    // MARK: - Etapa 3 del KDS (fase 3.3): la pantalla EFECTIVA y la puerta de lanzamiento

    @Test
    fun `la etapa 3 trae la pantalla efectiva por estacion y la puerta de lanzamiento`() {
        val r = NetworkModule.provideJson().decodeFromString(
            PrintConfigResponse.serializer(),
            """{"success":true,"data":{"stations":[{"id":"st1","name":"Barra","hasKitchenDisplay":true}],"kitchenDisplayOpenToClients":true,"version":"v2"}}""",
        )
        assertTrue(r.data.stations.single().hasKitchenDisplay)
        assertTrue(r.data.kitchenDisplayOpenToClients)
    }

    @Test
    fun `P1 un servidor anterior a la etapa 3 se lee como pantalla apagada y puerta cerrada`() {
        val r = NetworkModule.provideJson().decodeFromString(
            PrintConfigResponse.serializer(),
            """{"success":true,"data":{"stations":[{"id":"st1","name":"Barra"}],"version":"v1"}}""",
        )
        assertFalse(r.data.stations.single().hasKitchenDisplay)
        assertFalse(r.data.kitchenDisplayOpenToClients)
    }

    @Test
    fun `la copia en disco conserva la pantalla - sin red la tablet sigue sabiendo que esta prendida`() {
        // Las MISMAS opciones que `PrintConfigRepository.cacheJson`.
        val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val original = PrintConfig(
            stations = listOf(StationInfo(id = "st1", name = "Barra", hasKitchenDisplay = true)),
            kitchenDisplayOpenToClients = true,
            version = "v2",
        )
        val copia = cacheJson.decodeFromString(PrintConfig.serializer(), cacheJson.encodeToString(PrintConfig.serializer(), original))
        assertEquals(original, copia)
    }

    // MARK: - Etapa 3 del KDS (3.4): refresco con tope

    /**
     * H2 de la spec: con la API muerta y el WiFi vivo, esperar la config congelaba la comanda 30 s. Ahora se espera a lo
     * más el tope y se imprime con la guardada; la descarga NO se cancela: la config fresca llega sola después.
     */
    @Test
    fun `P1 refreshConTope no espera mas del tope - y la config fresca llega sola despues`() = runTest {
        val puerta = CompletableDeferred<Unit>()
        coEvery { apiService.getPrintConfig("venue-1") } coAnswers {
            puerta.await()
            PrintConfigResponse(success = true, data = configConEstacion())
        }

        repository.refreshConTope("venue-1", topeMs = 50)

        assertTrue("regresó sin esperar a la red colgada", repository.getCurrentConfig().stations.isEmpty())
        puerta.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (repository.getCurrentConfig().stations.isEmpty()) delay(10) }
        }
        assertEquals(1, repository.getCurrentConfig().stations.size)
    }

    /**
     * 🔴 La app recién abierta no tiene config en memoria, y el `refresh` sólo hidrata del disco cuando la red FALLA —
     * con la API colgada eso tarda el timeout (~30 s). Sin hidratar primero, la caja decidía en 1.5 s con una config
     * VACÍA: ticket legado o «SIN ESTACIÓN», sin ruteo ni respaldo. Hoy (sin tope) esa comanda sale bien, 30 s tarde.
     */
    @Test
    fun `P1 arranque en frio con la API colgada - decide con la config guardada en disco`() = runTest {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .encodeToString(PrintConfig.serializer(), configConEstacion())
        coEvery { payloadCache.load(PayloadCache.TYPE_PRINT_CONFIG, "venue-1") } returns
            PayloadCache.Cached(json = json, updatedAt = System.currentTimeMillis() - 60_000L)
        coEvery { apiService.getPrintConfig("venue-1") } coAnswers {
            CompletableDeferred<Unit>().await() // la API no contesta nunca
            PrintConfigResponse(success = true, data = PrintConfig())
        }

        repository.refreshConTope("venue-1", topeMs = 50)

        assertEquals(listOf("st_cocina"), repository.getCurrentConfig().stations.map { it.id })
    }
}
