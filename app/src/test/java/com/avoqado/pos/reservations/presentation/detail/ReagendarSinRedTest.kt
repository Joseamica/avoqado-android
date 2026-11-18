package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationsCapability
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * na15bdb8 (tarjeta de la nube, 16-nov, SAMSUNG): «Reagendar reserva» abierta sin red enseñaba en rojo
 * «Failed to connect to /192.168.100.70:3020», el texto crudo del sistema, en inglés. La hoja vive en su propia ruta con
 * su propio ViewModel, que vuelve a pedir la reserva al abrirse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReagendarSinRedTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun s() { Dispatchers.setMain(dispatcher) }
    @After fun t() { Dispatchers.resetMain() }

    private fun vm(repo: ReservationRepository) = ReservationDetailViewModel(
        repository = repo,
        capabilityProvider = { ReservationsCapability(true, true, true, true) },
        secureStorage = mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } returns "v"
            every { it.venueTimezone } returns "America/Mexico_City"
        },
        printConfigRepository = mockk(relaxed = true),
        comandaPrinter = mockk(relaxed = true),
        productsRepository = mockk(relaxed = true),
        savedStateHandle = SavedStateHandle(mapOf("reservationId" to "r1")),
        connectivity = mockk<ConnectivityMonitor>(relaxed = true).also {
            every { it.isConnected } returns MutableStateFlow(false)
            every { it.isServerReachable } returns MutableStateFlow(false)
            every { it.isFullyConnected } returns false
        },
    )

    @Test
    fun `sin red al abrir la hoja el aviso va en espanol y nunca el texto crudo del sistema`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk(relaxed = true)
        every { repo.changes } returns MutableSharedFlow()
        coEvery { repo.fetchOne("r1", any()) } returns
            Result.failure(java.net.ConnectException("Failed to connect to /192.168.100.70:3020"))

        val v = vm(repo)
        advanceUntilIdle()

        val error = v.state.value.error.orEmpty()
        assertFalse("texto crudo en pantalla: $error", error.contains("Failed to connect"))
        assertEquals(true, error.startsWith("Sin conexión"))
    }
}
