package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Las mesas recuerdan su versión y se guardan antes de pintarse (spec 2026-10-09 §4.1). Espejo: avoqado-ios `MesasConVersionTests`. */
class MesasConVersionTest {

    private val api = mockk<ApiService>()
    private val cache = mockk<PayloadCache>(relaxed = true).also {
        // Sin nada guardado (un mock relajado devolvería un `Cached` falso que no se puede leer).
        coEvery { it.load(any(), any()) } returns null
    }
    // Constructor de hoy (d386048e): apiService, payloadCache, syncOutbox, tableSession, roleManager, draftStore.
    private fun repo() = TableServiceRepository(api, cache, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    private val respuesta = TablesResponse(
        data = listOf(DiningTable(id = "t1", number = "1")),
        tablesVersion = "mesas-v1",
        floorPlanVersion = "plano-v1",
    )

    @Test
    fun `las mesas recuerdan su version y la del plano`() = runTest {
        coEvery { api.getTables("v1") } returns respuesta
        val r = repo()
        r.refresh("v1")
        assertEquals("mesas-v1", r.tablesVersion)
        assertEquals("plano-v1", r.floorPlanVersionFromTables)
    }

    @Test
    fun `las mesas se guardan en el aparato antes de pintarse`() = runTest {
        coEvery { api.getTables("v1") } returns respuesta
        val r = repo()
        var pintadasAlGuardar: List<DiningTable>? = null
        coEvery { cache.save(PayloadCache.TYPE_TABLES, "v1", any()) } answers { pintadasAlGuardar = r.tables.value }
        r.refresh("v1")
        assertEquals(emptyList<DiningTable>(), pintadasAlGuardar)
        assertEquals(1, r.tables.value.size)
    }

    @Test
    fun `las mesas guardadas traen su version`() = runTest {
        coEvery { api.getTables("v1") } returns respuesta
        val guardado = slot<String>()
        coEvery { cache.save(PayloadCache.TYPE_TABLES, "v1", capture(guardado)) } returns Unit
        repo().refresh("v1")

        coEvery { api.getTables("v1") } throws IOException("sin red")
        coEvery { cache.load(PayloadCache.TYPE_TABLES, "v1") } returns PayloadCache.Cached(guardado.captured, System.currentTimeMillis())
        val sinRed = repo()
        sinRed.refresh("v1")

        assertEquals(1, sinRed.tables.value.size)
        assertEquals("mesas-v1", sinRed.tablesVersion)
        assertEquals("plano-v1", sinRed.floorPlanVersionFromTables)
    }

    @Test
    fun `las mesas de otra sucursal no se pintan`() = runTest {
        // Corrección C5/C8 (auditoría Codex, hallazgo 1): A cargada; luego B sin red y sin nada guardado de B.
        coEvery { api.getTables("A") } returns respuesta
        coEvery { api.getTables("B") } throws IOException("sin red")
        val r = repo()
        r.refresh("A")
        r.refresh("B")
        assertEquals(emptyList<DiningTable>(), r.tables.value)
        assertEquals(null, r.tablesVersion)
        assertEquals(null, r.floorPlanVersionFromTables)
    }

    @Test
    fun `una respuesta tardia de otra sucursal se descarta`() = runTest {
        // A pide /tables; mientras espera, la app ya cambió a B (sin red, sin caché). La respuesta de A no se pinta en B.
        val puerta = kotlinx.coroutines.CompletableDeferred<Unit>()
        val llego = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { api.getTables("A") } coAnswers { llego.complete(Unit); puerta.await(); respuesta }
        val r = repo()
        val deA = launch { r.refresh("A") }
        llego.await()
        r.hydrate("B")
        puerta.complete(Unit)
        deA.join()
        assertEquals(emptyList<DiningTable>(), r.tables.value)
        assertEquals(null, r.tablesVersion)
    }
}
