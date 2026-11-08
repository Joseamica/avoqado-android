package com.avoqado.pos.articles

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Al EDITAR, vaciar el máximo de un grupo de modificadores o el límite por cliente de un paquete tiene que llegar al
 * servidor como `null` explícito (sin el campo, el servidor conserva el valor anterior). Crear no cambia. Mismo criterio
 * que los límites de un cupón (ArticlesViewModelTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesLimitesAlEditarTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ArticlesRepository>(relaxed = true)

    @Before
    fun setup() {
        every { repository.products } returns MutableStateFlow(emptyList())
        every { repository.categories } returns MutableStateFlow(emptyList())
        every { repository.modifierGroups } returns MutableStateFlow(emptyList())
        every { repository.discounts } returns MutableStateFlow(emptyList())
        every { repository.coupons } returns MutableStateFlow(emptyList())
        every { repository.creditPacks } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.errorMessage } returns MutableStateFlow(null)
    }

    private fun createViewModel(): ArticlesViewModel {
        val storage = mockk<SecureStorage>()
        every { storage.planSnapshot } returns null
        every { storage.planTier } returns null
        every { storage.planExempt } returns false
        val refreshGateFactory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns
            com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return ArticlesViewModel(repository, PlanManager(storage), refreshGateFactory)
    }

    // MARK: - Paquete de créditos: límite por cliente

    private fun TestScope.paqueteAlEditar(maxPerCustomer: Int?): JsonObject {
        val payload = slot<String>()
        coEvery { repository.updateCreditPack("pack-1", capture(payload)) } returns true
        createViewModel().updateCreditPack(
            packId = "pack-1",
            name = "10 clases",
            price = 500.0,
            maxPerCustomer = maxPerCustomer,
            active = true,
            items = emptyList(),
        )
        advanceUntilIdle()
        return Json.parseToJsonElement(payload.captured).jsonObject
    }

    @Test
    fun `P1 editar un paquete con el limite por cliente vacio manda null explicito`() = runTest {
        assertEquals("null", paqueteAlEditar(maxPerCustomer = null)["maxPerCustomer"]?.toString())
    }

    @Test
    fun `editar un paquete con numero en el limite por cliente lo manda`() = runTest {
        assertEquals("3", paqueteAlEditar(maxPerCustomer = 3)["maxPerCustomer"]?.toString())
    }

    @Test
    fun `crear un paquete con el limite por cliente vacio no manda el campo`() = runTest {
        val payload = slot<String>()
        coEvery { repository.createCreditPack(capture(payload)) } returns true
        createViewModel().createCreditPack(
            name = "10 clases",
            price = 500.0,
            maxPerCustomer = null,
            active = true,
            items = emptyList(),
        )
        advanceUntilIdle()

        assertFalse("maxPerCustomer" in Json.parseToJsonElement(payload.captured).jsonObject)
    }

    // MARK: - Grupo de modificadores: máximo de selecciones

    private fun TestScope.grupoAlEditar(allowMultiple: Boolean, minSelections: Int?, maxSelections: Int?): JsonObject {
        val payload = slot<String>()
        coEvery { repository.updateModifierGroup("group-1", capture(payload)) } returns true
        createViewModel().updateModifierGroup(
            groupId = "group-1",
            name = "Salsas",
            required = false,
            allowMultiple = allowMultiple,
            minSelections = minSelections,
            maxSelections = maxSelections,
            originalModifiers = emptyList(),
            currentModifiers = emptyList(),
        )
        advanceUntilIdle()
        return Json.parseToJsonElement(payload.captured).jsonObject
    }

    @Test
    fun `P1 editar un grupo con seleccion multiple y el maximo vacio manda null explicito`() = runTest {
        assertEquals("null", grupoAlEditar(allowMultiple = true, minSelections = null, maxSelections = null)["maxSelections"]?.toString())
    }

    @Test
    fun `editar un grupo con seleccion multiple y un maximo lo manda`() = runTest {
        assertEquals("4", grupoAlEditar(allowMultiple = true, minSelections = 1, maxSelections = 4)["maxSelections"]?.toString())
    }

    @Test
    fun `P1 editar un grupo sin seleccion multiple no manda el maximo - el campo ni se ve y no lo vacio nadie`() = runTest {
        val json = grupoAlEditar(allowMultiple = false, minSelections = null, maxSelections = null)

        assertFalse("maxSelections" in json)
    }

    @Test
    fun `editar un grupo con el minimo vacio no manda el minimo - el servidor no acepta null ahi`() = runTest {
        val json = grupoAlEditar(allowMultiple = true, minSelections = null, maxSelections = null)

        assertFalse("minSelections" in json)
    }

    @Test
    fun `crear un grupo con seleccion multiple y el maximo vacio no manda el campo`() = runTest {
        val payload = slot<String>()
        coEvery { repository.createModifierGroup(capture(payload)) } returns true
        createViewModel().createModifierGroup(
            name = "Salsas",
            required = false,
            allowMultiple = true,
            minSelections = null,
            maxSelections = null,
            modifiers = emptyList(),
        )
        advanceUntilIdle()

        assertFalse("maxSelections" in Json.parseToJsonElement(payload.captured).jsonObject)
    }
}
