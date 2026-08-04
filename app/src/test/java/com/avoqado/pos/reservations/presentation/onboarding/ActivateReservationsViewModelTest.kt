package com.avoqado.pos.reservations.presentation.onboarding

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.reservations.domain.VenueMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivateReservationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun storageWith(planTier: String?, planExempt: Boolean = false): SecureStorage {
        val storage: SecureStorage = mockk(relaxed = true)
        every { storage.planTier } returns planTier
        every { storage.planExempt } returns planExempt
        return storage
    }

    /** Un PosModeManager que dice estar en [mode] y registra los switchMode. */
    private fun posModeIn(mode: com.avoqado.pos.settings.domain.PosMode): com.avoqado.pos.settings.domain.PosModeManager {
        val m: com.avoqado.pos.settings.domain.PosModeManager = mockk(relaxed = true)
        every { m.currentMode } returns MutableStateFlow(mode)
        return m
    }

    @Test
    fun `activate desde Retail persiste el flag y mueve el dispositivo a Reservas`() = runTest(dispatcher) {
        val storage = storageWith(planTier = null) // fail-open: plan unknown
        val posModeManager = posModeIn(com.avoqado.pos.settings.domain.PosMode.RETAIL)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeManager)

        vm.activate()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.didSucceed)
        assertEquals(false, s.isActivating)
        verify { storage.reservationsEnabled = true }
        // Una estética que empieza a tomar citas sí quiere la agenda al frente.
        verify { posModeManager.switchMode(com.avoqado.pos.settings.domain.PosMode.RESERVATIONS) }
    }

    @Test
    fun `activate NO saca del modo Restaurante — Mesas y Calendario conviven`() = runTest(dispatcher) {
        // 🔴 El bug: activar reservas movía SIEMPRE el dispositivo a modo
        // Reservas, y como Mesas sólo se pinta en modo RESTAURANT, el plano
        // desaparecía de la barra. Un restaurante perdía las mesas por tomar
        // citas. Medido en el iPad el 2026-08-04.
        val storage = storageWith(planTier = "PRO")
        val posModeManager = posModeIn(com.avoqado.pos.settings.domain.PosMode.RESTAURANT)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeManager)

        vm.activate()
        advanceUntilIdle()

        assertTrue(vm.state.value.didSucceed)
        verify { storage.reservationsEnabled = true }      // el calendario SÍ se activa
        verify(exactly = 0) { posModeManager.switchMode(any()) }  // …sin tocar el modo
    }

    @Test
    fun `activate ignored after success`() = runTest(dispatcher) {
        val storage = storageWith(planTier = null)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeIn(com.avoqado.pos.settings.domain.PosMode.RETAIL))

        vm.activate(); advanceUntilIdle()
        vm.activate(); advanceUntilIdle()

        // setter only called once for each prop
        verify(exactly = 1) { storage.reservationsEnabled = true }
    }

    // MARK: - Plan gating (RESERVATIONS, Pro)

    @Test
    fun `activate refuses on FREE plan and never flips local toggle`() = runTest(dispatcher) {
        val storage = storageWith(planTier = "FREE")
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeIn(com.avoqado.pos.settings.domain.PosMode.RETAIL))

        assertFalse(vm.hasReservationsFeature)

        vm.activate()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.didSucceed)
        assertNotNull(s.error)
        verify(exactly = 0) { storage.reservationsEnabled = true }
        verify(exactly = 0) { storage.reservationsEnabled = true }
    }

    @Test
    fun `activate works on PRO plan`() = runTest(dispatcher) {
        val storage = storageWith(planTier = "PRO")
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeIn(com.avoqado.pos.settings.domain.PosMode.RETAIL))

        assertTrue(vm.hasReservationsFeature)

        vm.activate()
        advanceUntilIdle()

        assertTrue(vm.state.value.didSucceed)
        verify { storage.reservationsEnabled = true }
    }

    @Test
    fun `activate works on exempt FREE venue (grandfathered)`() = runTest(dispatcher) {
        val storage = storageWith(planTier = "FREE", planExempt = true)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeIn(com.avoqado.pos.settings.domain.PosMode.RETAIL))

        assertTrue(vm.hasReservationsFeature)

        vm.activate()
        advanceUntilIdle()

        assertTrue(vm.state.value.didSucceed)
    }

    @Test
    fun `requiredTierLabel is Pro`() {
        val storage = storageWith(planTier = "FREE")
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeIn(com.avoqado.pos.settings.domain.PosMode.RETAIL))
        assertEquals("Pro", vm.requiredTierLabel)
    }
}
