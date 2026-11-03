package com.avoqado.pos.tables.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.tables.data.ApiFuenteDeVersiones
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.FloorPlanArea
import com.avoqado.pos.tables.data.FloorPlanElement
import com.avoqado.pos.tables.data.FloorPlanPayload
import com.avoqado.pos.tables.data.FloorPlanRepository
import com.avoqado.pos.tables.data.FloorPlanResponse
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tables.data.TablesResponse
import com.avoqado.pos.tables.domain.FloorLayout
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * El armado del plano con los repositorios y el ViewModel reales (auditoría Codex del código, hallazgo 1). Espejo:
 * avoqado-ios `testTrasUnFalloParcialLaVistaSeRecuperaAunqueLaListaSeaIgual`.
 */
class VistaDelPlanoTest {

    @get:Rule val main = MainDispatcherRule()

    private val api = mockk<ApiService>()
    private val cache = mockk<PayloadCache>(relaxed = true).also { coEvery { it.load(any(), any()) } returns null }
    private val mesas = TableServiceRepository(api, cache, mockk(relaxed = true), TableSession(), mockk(relaxed = true), mockk(relaxed = true))
    private val plano = FloorPlanRepository(api, cache)
    private val vm = TablesViewModel(
        mesas, mockk(relaxed = true), TableSession(), mockk(relaxed = true),
        mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v1" },
        mockk(relaxed = true), mockk(relaxed = true), plano, mockk(relaxed = true), mockk<ApiFuenteDeVersiones>(relaxed = true), mockk(relaxed = true),
    )

    private val mesa = DiningTable(id = "t1", number = "1", positionX = 0.5f, positionY = 0.5f, areaId = "a1", areaName = "Salón", areaFloorShape = "WIDE")
    private val salon = FloorPlanArea("a1", "Salón", "WIDE", 0)
    private val planoViejo = FloorPlanPayload(floorPlanVersion = "p1", areas = listOf(salon))

    // El dueño agrega un área vacía (Terraza) y una pared en el Salón: las mesas no cambian, sólo la versión del plano.
    private val planoNuevo = FloorPlanPayload(
        floorPlanVersion = "p2",
        areas = listOf(salon, FloorPlanArea("a2", "Terraza", "WIDE", 1)),
        elements = listOf(FloorPlanElement("w1", "WALL", "a1", 0.1, 0.1, endX = 0.9, endY = 0.1)),
    )

    /**
     * El armado corre en otro hilo (`flowOn(Default)`): se espera con reloj real, no con el virtual de la prueba. Si en
     * 5 s no llega, devuelve la vista que quedó (y la aserción de ese paso dice cuál).
     */
    private suspend fun esperar(que: (FloorLayout) -> Boolean): FloorLayout =
        withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { vm.layout.first(que) } } ?: vm.layout.value

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `tras un fallo parcial la vista se recupera aunque la lista sea igual`() = runTest {
        // La pantalla mira el plano todo el tiempo (sin esto, cada `esperar` volvería a armar desde cero y taparía el defecto).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.layout.collect {} }

        coEvery { api.getTables("v1") } returns TablesResponse(data = listOf(mesa), tablesVersion = "t1", floorPlanVersion = "p1")
        coEvery { api.getFloorPlan("v1") } returns FloorPlanResponse(data = planoViejo)
        vm.refresh()
        assertEquals("al entrar", listOf("a1"), esperar { it.tabs.map { t -> t.key } == listOf("a1") }.tabs.map { it.key })

        // Falla /tables y llega el plano nuevo: vista provisional con las áreas de las mesas (sin la Terraza vacía).
        coEvery { api.getTables("v1") } throws IOException("sin red")
        coEvery { api.getFloorPlan("v1") } returns FloorPlanResponse(data = planoNuevo)
        vm.refresh()
        val parcial = esperar { it.elementsByTab["a1"]?.size == 1 }
        assertEquals("la pared del plano nuevo ya se ve", 1, parcial.elementsByTab["a1"]?.size)
        assertEquals("sin la Terraza vacía", listOf("a1"), parcial.tabs.map { it.key })

        // /tables vuelve con la MISMA lista y ya anuncia el plano nuevo: la vista se completa.
        coEvery { api.getTables("v1") } returns TablesResponse(data = listOf(mesa), tablesVersion = "t1", floorPlanVersion = "p2")
        vm.refresh()
        assertEquals("la vista se completa", listOf("a1", "a2"), esperar { it.tabs.size == 2 }.tabs.map { it.key })
    }
}
