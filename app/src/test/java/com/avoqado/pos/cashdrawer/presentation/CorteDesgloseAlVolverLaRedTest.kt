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

    /** El parcial de una caja: su fin «ahora» cambia en cada lectura y la llave no (Codex r5 #1 de #266). */
    private val C = "caja-1|parcial"

    private val efectivo = listOf(CashDrawerRepository.TenderRow("CASH", 12_000))

    @Test fun `sin red falta el desglose y al volver la red se pide otra vez`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { repo.getTenderBreakdown(1L, 2L) } returnsMany listOf(null, efectivo)
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(C, 1L, 2L)
        assertNull(vm.desgloses.value[C]?.filas)
        vm.recargarDesgloseSiFalta(C, 1L, 2L)
        assertEquals(efectivo, vm.desgloses.value[C]?.filas)
        // Ya lo tiene: volver a cambiar la red no lo pide otra vez ni lo borra.
        vm.recargarDesgloseSiFalta(C, 1L, 2L)
        coVerify(exactly = 2) { repo.getTenderBreakdown(1L, 2L) }
        assertEquals(efectivo, vm.desgloses.value[C]?.filas)
    }

    @Test fun `con una lectura en vuelo no se pide una segunda`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val respuesta = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        coEvery { repo.getTenderBreakdown(1L, 2L) } coAnswers { respuesta.await() }
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(C, 1L, 2L)
        vm.recargarDesgloseSiFalta(C, 1L, 2L)
        respuesta.complete(efectivo)
        coVerify(exactly = 1) { repo.getTenderBreakdown(1L, 2L) }
        assertEquals(efectivo, vm.desgloses.value[C]?.filas)
    }

    @Test fun `Codex r1 2 la red vuelve durante una lectura que falla y al terminar se pide otra vez`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val primera = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        var llamadas = 0
        coEvery { repo.getTenderBreakdown(1L, 2L) } coAnswers { llamadas++; if (llamadas == 1) primera.await() else efectivo }
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(C, 1L, 2L)
        vm.recargarDesgloseSiFalta(C, 1L, 2L) // volvió la red con la lectura en vuelo
        primera.complete(null) // y esa lectura falló
        assertEquals(2, llamadas)
        assertEquals(efectivo, vm.desgloses.value[C]?.filas)
    }

    @Test fun `una lectura que empezo antes y termina despues no pisa a la nueva`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val vieja = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        val otro = listOf(CashDrawerRepository.TenderRow("CASH", 99))
        coEvery { repo.getTenderBreakdown(1L, 2L) } coAnswers { vieja.await() }
        coEvery { repo.getTenderBreakdown(1L, 3L) } returns efectivo
        val vm = pantalla(repo)
        vm.loadTenderBreakdown(C, 1L, 2L)
        vm.loadTenderBreakdown(C, 1L, 3L)
        vieja.complete(otro)
        assertEquals(efectivo, vm.desgloses.value[C]?.filas)
    }

}
