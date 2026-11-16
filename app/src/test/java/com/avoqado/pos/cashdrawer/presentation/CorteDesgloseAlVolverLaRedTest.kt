package com.avoqado.pos.cashdrawer.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * D68 #13 (prueba final del 13-nov): el corte hecho sin red seguía diciendo «No se pudo consultar el desglose por método de pago…»
 * después de volver la red. Al volver la red la pantalla pide el desglose otra vez, sólo si falta y si no hay otra lectura en vuelo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorteDesgloseAlVolverLaRedTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun pantalla(repo: CashDrawerRepository) = CashDrawerViewModel(
        repository = repo,
        printerService = mockk(relaxed = true),
        roleManager = mockk(relaxed = true),
        tpvSettingsRepository = mockk(relaxed = true),
        conectividad = mockk(relaxed = true),
        permissionOverrideRepository = mockk(relaxed = true),
        comprobantes = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true),
    )

    private val efectivo = listOf(CashDrawerRepository.TenderRow("CASH", 12_000))

    @Test fun `sin red falta el desglose y al volver la red se pide otra vez`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { repo.getTenderBreakdown(1L, 2L) } returnsMany listOf(null, efectivo)
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        assertNull(vm.tenderBreakdown.value)
        vm.recargarDesgloseSiFalta(1L, 2L)
        assertEquals(efectivo, vm.tenderBreakdown.value)
        // Ya lo tiene: volver a cambiar la red no lo pide otra vez ni lo borra.
        vm.recargarDesgloseSiFalta(1L, 2L)
        coVerify(exactly = 2) { repo.getTenderBreakdown(1L, 2L) }
        assertEquals(efectivo, vm.tenderBreakdown.value)
    }

    @Test fun `con una lectura en vuelo no se pide una segunda`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val respuesta = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        coEvery { repo.getTenderBreakdown(1L, 2L) } coAnswers { respuesta.await() }
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        vm.recargarDesgloseSiFalta(1L, 2L)
        respuesta.complete(efectivo)
        coVerify(exactly = 1) { repo.getTenderBreakdown(1L, 2L) }
        assertEquals(efectivo, vm.tenderBreakdown.value)
    }

    @Test fun `Codex r1 2 la red vuelve durante una lectura que falla y al terminar se pide otra vez`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val primera = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        var llamadas = 0
        coEvery { repo.getTenderBreakdown(1L, 2L) } coAnswers { llamadas++; if (llamadas == 1) primera.await() else efectivo }
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        vm.recargarDesgloseSiFalta(1L, 2L) // volvió la red con la lectura en vuelo
        primera.complete(null) // y esa lectura falló
        assertEquals(2, llamadas)
        assertEquals(efectivo, vm.tenderBreakdown.value)
    }

    @Test fun `Codex r3 2 reabrir la misma caja sin red no borra su desglose y otra caja no hereda el ajeno`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { repo.getTenderBreakdown(1L, 2L) } returnsMany listOf(efectivo, null)
        coEvery { repo.getTenderBreakdown(5L, 6L) } returns null
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        assertEquals(efectivo, vm.tenderBreakdown.value)
        vm.loadTenderBreakdown(1L, 2L) // la misma caja, ya sin red
        assertEquals(efectivo, vm.tenderBreakdown.value)
        vm.loadTenderBreakdown(5L, 6L) // otra caja sin red: no enseña el desglose de la anterior
        assertNull(vm.tenderBreakdown.value)
    }

    @Test fun `una lectura que empezo antes y termina despues no pisa a la nueva`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val vieja = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        val otro = listOf(CashDrawerRepository.TenderRow("CASH", 99))
        coEvery { repo.getTenderBreakdown(1L, 2L) } coAnswers { vieja.await() }
        coEvery { repo.getTenderBreakdown(1L, 3L) } returns efectivo
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        vm.loadTenderBreakdown(1L, 3L)
        vieja.complete(otro)
        assertEquals(efectivo, vm.tenderBreakdown.value)
    }

    @Test fun `Codex r4 1 el parcial no pasa por el definitivo de la misma caja`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { repo.getTenderBreakdown(1L, 2L) } returns efectivo // parcial
        coEvery { repo.getTenderBreakdown(1L, 9L) } returns null // definitivo, sin red
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        vm.loadTenderBreakdown(1L, 9L)
        assertNull(vm.tenderBreakdown.value) // «No se pudo consultar», con su «Reintentar», no el parcial
    }

    @Test fun `Codex r4 3 con el desglose de otra caja en pantalla la red que vuelve en vuelo sí se reintenta`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val deB = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        var llamadasB = 0
        coEvery { repo.getTenderBreakdown(1L, 2L) } returns listOf(CashDrawerRepository.TenderRow("CASH", 1)) // caja A
        coEvery { repo.getTenderBreakdown(5L, 6L) } coAnswers { llamadasB++; if (llamadasB == 1) deB.await() else efectivo }
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(1L, 2L)
        vm.loadTenderBreakdown(5L, 6L) // se abre B; A sigue en pantalla mientras tanto
        vm.recargarDesgloseSiFalta(5L, 6L) // volvió la red con la lectura de B en vuelo
        deB.complete(null)
        assertEquals(2, llamadasB)
        assertEquals(efectivo, vm.tenderBreakdown.value)
    }
}
