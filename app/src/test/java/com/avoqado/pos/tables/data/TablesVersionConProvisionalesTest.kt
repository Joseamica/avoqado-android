package com.avoqado.pos.tables.data

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Con cuentas provisionales la lista no está reconciliada: no se guarda versión (S1, auditoría Codex Sol). */
class TablesVersionConProvisionalesTest {
    @get:Rule val main = MainDispatcherRule()

    private fun montar(root: java.io.File, api: ApiService, session: TableSession): TableServiceRepository {
        val rows = mutableMapOf<String, CachedPayloadEntity>()
        val dao = mockk<CachedPayloadDao>()
        coEvery { dao.get(any()) } answers { rows[firstArg()] }
        coEvery { dao.upsert(any()) } answers { val row = firstArg<CachedPayloadEntity>(); rows[row.cacheKey] = row }
        return TableServiceRepository(api, PayloadCache(dao), mockk(relaxed = true), session, mockk(relaxed = true), TableRoundDraftStore(root))
    }

    @Test
    fun `con una cuenta provisional conservada refresh no guarda version de mesas`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("ver-prov").toFile()
        try {
            val api = mockk<ApiService>()
            val session = TableSession()
            coEvery { api.getTables("v1") } returns TablesResponse(data = listOf(DiningTable(id = "t1", number = "5")), tablesVersion = "t-1", floorPlanVersion = "p-1")
            val repo = montar(root, api, session)
            repo.refresh("v1").getOrThrow()
            assertEquals("t-1", repo.tablesVersion)
            session.start(TableSession.Active(tableId = "t1", tableNumber = "5", areaName = null, orderId = "local", orderNumber = "LOCAL", version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING, isProvisional = true))
            repo.markTableOccupiedLocally("t1")
            repo.refresh("v1").getOrThrow()
            assertEquals("local", repo.tables.value.single().primaryCheck!!.id)
            assertEquals(null, repo.tablesVersion)
            assertEquals("p-1", repo.floorPlanVersionFromTables)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `sin provisionales tablesVersion es la de la respuesta`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("ver-prov").toFile()
        try {
            val api = mockk<ApiService>()
            coEvery { api.getTables("v1") } returns TablesResponse(data = listOf(DiningTable(id = "t1", number = "5")), tablesVersion = "t-1")
            val repo = montar(root, api, TableSession())
            repo.refresh("v1").getOrThrow()
            assertEquals("t-1", repo.tablesVersion)
        } finally { root.deleteRecursively() }
    }
}
