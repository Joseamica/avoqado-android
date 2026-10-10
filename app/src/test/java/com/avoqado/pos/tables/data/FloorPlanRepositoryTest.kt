package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.di.NetworkModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Plano en el POS (spec 2026-10-09 §4.1): el plano guardado en el aparato. Espejo: avoqado-ios `FloorPlanRepositoryTests`. */
class FloorPlanRepositoryTest {

    private val api = mockk<ApiService>()
    private val cache = mockk<PayloadCache>(relaxed = true)
    private val repo = FloorPlanRepository(api, cache)
    private val json = Json { ignoreUnknownKeys = true }

    private fun plano(version: String) = FloorPlanPayload(floorPlanVersion = version, areas = listOf(FloorPlanArea("a1", "Salón", "WIDE", 0)))

    @Test
    fun `guarda el plano en el aparato antes de pintarlo`() = runTest {
        coEvery { api.getFloorPlan("v1") } returns FloorPlanResponse(data = plano("p1"))
        var pintadoAlGuardar: FloorPlanPayload? = plano("centinela")
        coEvery { cache.save(PayloadCache.TYPE_FLOOR_PLAN, "v1", any()) } answers { pintadoAlGuardar = repo.plan.value }

        assertTrue(repo.refresh("v1").isSuccess)

        assertNull("al guardar todavía no se pintaba", pintadoAlGuardar)
        assertEquals("p1", repo.plan.value?.floorPlanVersion)
        assertEquals("p1", repo.floorPlanVersion)
    }

    @Test
    fun `un fallo de red no borra el plano que ya se ve`() = runTest {
        coEvery { api.getFloorPlan("v1") } returns FloorPlanResponse(data = plano("p1"))
        repo.refresh("v1")
        coEvery { api.getFloorPlan("v1") } throws IOException("sin red")

        assertFalse(repo.refresh("v1").isSuccess)

        assertEquals("p1", repo.plan.value?.floorPlanVersion)
        coVerify(exactly = 0) { cache.clear(any(), any()) }
    }

    @Test
    fun `sin red al abrir se pinta el plano guardado`() = runTest {
        coEvery { api.getFloorPlan("v1") } throws IOException("sin red")
        coEvery { cache.load(PayloadCache.TYPE_FLOOR_PLAN, "v1") } returns
            PayloadCache.Cached(json.encodeToString(FloorPlanPayload.serializer(), plano("p-guardado")), System.currentTimeMillis())

        repo.refresh("v1")

        assertEquals("p-guardado", repo.plan.value?.floorPlanVersion)
    }

    @Test
    fun `el plano guardado de otra sucursal no se pinta`() = runTest {
        coEvery { cache.load(PayloadCache.TYPE_FLOOR_PLAN, "v1") } returns
            PayloadCache.Cached(json.encodeToString(FloorPlanPayload.serializer(), plano("p-v1")), System.currentTimeMillis())
        coEvery { cache.load(PayloadCache.TYPE_FLOOR_PLAN, "v2") } returns null
        repo.hydrate("v1")
        assertEquals("p-v1", repo.plan.value?.floorPlanVersion)

        repo.hydrate("v2")

        assertNull(repo.plan.value)
        assertNull(repo.floorPlanVersion)
    }

    @Test
    fun `lee un plano con campos faltantes sin romperse`() {
        val r = NetworkModule.provideJson().decodeFromString(
            FloorPlanResponse.serializer(),
            """{"success":true,"data":{"floorPlanVersion":"p1","areas":[{"id":"a1","name":"Salón"}],"elements":[{"id":"e1","type":"WALL","positionX":0,"positionY":0}],"campoNuevo":1}}""",
        )
        val p = r.data!!
        assertNull(p.areas.single().floorShape)
        assertEquals(0, p.areas.single().sortOrder)
        assertNull(p.elements.single().endX)
        assertFalse(p.overLimit)
    }
}
