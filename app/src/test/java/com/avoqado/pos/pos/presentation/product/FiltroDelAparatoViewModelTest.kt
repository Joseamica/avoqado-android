package com.avoqado.pos.pos.presentation.product

import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FiltroDelAparatoViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(nav: MutableStateFlow<TerminalNavigationSettings>): FiltroDelAparatoViewModel {
        val repo = mockk<TpvSettingsRepository>()
        every { repo.terminalNavigation } returns nav
        every { repo.generacionDelFiltro } returns MutableStateFlow(0L)
        return FiltroDelAparatoViewModel(repo)
    }

    @Test fun `sin filtro los dos son nulos`() {
        val v = vm(MutableStateFlow(TerminalNavigationSettings.DEFAULT))
        assertNull(v.visibleCategoryIds.value)
        assertNull(v.visibleMenuIds.value)
    }

    @Test fun `expone las categorias y los menus de los ajustes y sigue un refresco`() {
        val nav = MutableStateFlow(TerminalNavigationSettings(visibleCategoryIds = listOf("a"), visibleMenuIds = listOf("m1")))
        val v = vm(nav)
        assertEquals(listOf("a"), v.visibleCategoryIds.value)
        assertEquals(listOf("m1"), v.visibleMenuIds.value)
        nav.value = nav.value.copy(visibleCategoryIds = listOf("a", "b"), visibleMenuIds = null)
        assertEquals(listOf("a", "b"), v.visibleCategoryIds.value)
        assertNull(v.visibleMenuIds.value)
    }

    @Test fun `el id del aparato cambia al cambiar de negocio`() {
        val nav = MutableStateFlow(TerminalNavigationSettings(terminalId = "t1"))
        val v = vm(nav)
        assertEquals("t1", v.terminalId.value)
        nav.value = TerminalNavigationSettings(terminalId = "t2")
        assertEquals("t2", v.terminalId.value)
    }
}
