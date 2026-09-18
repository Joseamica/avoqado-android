package com.avoqado.pos.settings

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.settings.presentation.AjustesDelCobro
import com.avoqado.pos.settings.presentation.SetupWizardViewModel
import com.avoqado.pos.tpvsettings.data.AjusteGuardado
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * «Pantallas del cobro» en Más > Configuración (founder, 2026-09-18).
 *
 * Lo que se fija aquí es la parte que el cajero LEE cuando algo sale mal: un ajuste que vive en el
 * servidor no se puede cambiar sin red, y la app tiene que decir CUÁL de los cuatro problemas
 * ocurrió — «sin conexión» y «no tienes permiso» se arreglan de formas distintas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PantallasDelCobroViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val navegacion = MutableStateFlow(TerminalNavigationSettings.DEFAULT)
    private val repository = mockk<TpvSettingsRepository>(relaxed = true)

    private fun viewModel(): SetupWizardViewModel {
        every { repository.settings } returns MutableStateFlow(TpvSettings.DEFAULT)
        every { repository.terminalNavigation } returns navegacion
        return SetupWizardViewModel(repository)
    }

    @Test
    fun `sólo se ofrecen los ajustes que el servidor autoriza para este aparato`() = runTest {
        navegacion.value = TerminalNavigationSettings.DEFAULT.copy(
            configurableSettings = listOf(AjustesDelCobro.CALIFICACION, AjustesDelCobro.PROPINA),
        )

        assertEquals(listOf("showReviewScreen", "showTipScreen"), viewModel().configurables.value)
    }

    @Test
    fun `un aparato sin autorización no ofrece nada, no es que la lista falle abierta`() = runTest {
        navegacion.value = TerminalNavigationSettings.DEFAULT

        assertTrue(viewModel().configurables.value.isEmpty())
    }

    @Test
    fun `apagar la calificación se manda con ese ajuste y sin tocar la propina`() = runTest {
        coEvery { repository.guardarPantallasDelCobro(any(), any()) } returns AjusteGuardado.Ok
        val vm = viewModel()

        vm.mostrarCalificacion(false)

        coVerify { repository.guardarPantallasDelCobro(showReviewScreen = false, showTipScreen = null) }
        assertNull("si guardó bien no hay nada que avisar", vm.avisoCobro.value)
    }

    @Test
    fun `P1 sin red el aviso dice que NO se guardó, no un error genérico`() = runTest {
        coEvery { repository.guardarPantallasDelCobro(any(), any()) } returns AjusteGuardado.SinConexion
        val vm = viewModel()

        vm.mostrarCalificacion(false)

        val aviso = vm.avisoCobro.value
        assertTrue("el aviso debe decir que no se guardó: $aviso", aviso!!.contains("no se guardó"))
        assertTrue("y debe nombrar la causa real: $aviso", aviso.contains("Sin conexión"))
    }

    @Test
    fun `sin permiso se explica a quién pedirlo, en vez de un 403`() = runTest {
        coEvery { repository.guardarPantallasDelCobro(any(), any()) } returns AjusteGuardado.SinPermiso
        val vm = viewModel()

        vm.mostrarPropina(false)

        val aviso = vm.avisoCobro.value
        assertTrue("$aviso", aviso!!.contains("permiso"))
        assertTrue("debe decir a quién acudir: $aviso", aviso.contains("dueño"))
    }

    @Test
    fun `cada desenlace de fallo deja un aviso distinto y ninguno queda mudo`() = runTest {
        val avisos = mutableSetOf<String>()
        for (desenlace in listOf(AjusteGuardado.SinConexion, AjusteGuardado.SinPermiso, AjusteGuardado.SinFicha, AjusteGuardado.Rechazado(422))) {
            coEvery { repository.guardarPantallasDelCobro(any(), any()) } returns desenlace
            val vm = viewModel()
            vm.mostrarCalificacion(false)
            val aviso = vm.avisoCobro.value
            assertTrue("$desenlace se quedó sin aviso", !aviso.isNullOrBlank())
            avisos += aviso!!
        }
        assertEquals("dos fallos con el mismo texto mandan al cajero a probar lo mismo dos veces", 4, avisos.size)
    }
}
