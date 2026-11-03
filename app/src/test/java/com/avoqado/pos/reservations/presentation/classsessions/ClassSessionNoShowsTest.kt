package com.avoqado.pos.reservations.presentation.classsessions

import androidx.lifecycle.SavedStateHandle
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.reservations.data.ClassSessionRepository
import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.ClassSession
import com.avoqado.pos.reservations.data.model.NoShowCorrectionRef
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.UndoNoShowPlan
import com.avoqado.pos.reservations.data.model.UndoNoShowResult
import com.avoqado.pos.reservations.domain.ReservationsCapability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D4 · la lista de la clase enseña a quien quedó como «No se presentó», con «Sí llegó» (salvo los de un pase). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassSessionNoShowsTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sesionJson = """
        {"id":"s1","venueId":"v1","productId":"p1","product":{"id":"p1","name":"Yoga"},
         "startsAt":"2026-10-28T14:51:00.000Z","endsAt":"2026-10-28T15:51:00.000Z","duration":60,"capacity":10,
         "reservations":[{"id":"r0","status":"CHECKED_IN","guestName":"Ana Martínez"}],"enrolled":1,
         "noShows":[
           {"id":"r1","confirmationCode":"A1","partySize":1,"guestName":null,"guestPhone":"+52",
            "customer":{"id":"c1","firstName":"Karla","lastName":"Rodríguez","phone":"+52"},
            "noShowAt":"2026-10-28T15:01:00.000Z","status":"NO_SHOW","aggregatorBooking":null},
           {"id":"r2","partySize":1,"guestName":"Pedro Gómez","noShowAt":"2026-10-28T15:01:00.000Z","status":"NO_SHOW",
            "aggregatorBooking":{"id":"ab1","provider":"TOTALPASS"}}
         ]}
    """.trimIndent()

    private fun sesion() = json.decodeFromString(ClassSession.serializer(), sesionJson)

    @Test
    fun `el detalle de la clase trae las faltas aparte y una clase vieja sin ellas sigue leyendose`() {
        val s = sesion()
        assertEquals(listOf("r0"), s.reservations.map { it.id })
        assertEquals(1, s.enrolled)
        assertEquals(listOf("r1", "r2"), s.noShows.map { it.id })
        assertEquals("Karla Rodríguez", s.noShows[0].displayName)
        assertEquals("TOTALPASS", s.noShows[1].aggregatorBooking?.provider)

        val vieja = json.decodeFromString(ClassSession.serializer(), sesionJson.substringBefore(",\n \"noShows\"") + "}")
        assertTrue(vieja.noShows.isEmpty())
        assertEquals(0, vieja.moreNoShows)
        assertEquals("sin total del servidor no se inventa", 0, s.moreNoShows)
    }

    @Test
    fun `mas faltas que las que caben - la lista dice y N mas`() {
        val s = json.decodeFromString(ClassSession.serializer(), sesionJson.trimEnd().removeSuffix("}") + ",\"noShowsTotal\":205}")
        assertEquals(203, s.moreNoShows)
        assertEquals("y 203 más", com.avoqado.pos.reservations.domain.UndoNoShowTexts.more(s.moreNoShows))
    }

    private val classRepo: ClassSessionRepository = mockk<ClassSessionRepository>().also {
        every { it.changes } returns MutableSharedFlow()
        coEvery { it.fetchOne("s1", any()) } returns Result.success(sesion())
    }
    private val reservationRepo: ReservationRepository = mockk()

    private fun vm(canUpdate: Boolean = true) = ClassSessionDetailViewModel(
        repository = classRepo,
        customersRepository = mockk<CustomersRepository>().also { coEvery { it.fetchCustomers(any()) } returns Result.success(emptyList()) },
        staffRepository = mockk<StaffRepository>().also { coEvery { it.getActiveStaff(any()) } returns Result.success(emptyList()) },
        secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.venueTimezone } returns "America/Mexico_City" },
        classCheckoutSeed = ClassCheckoutSeed(),
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1")),
        reservationRepository = reservationRepo,
        capabilityProvider = { ReservationsCapability(true, true, canUpdate, true) },
        connectivity = mockk<ConnectivityMonitor>(relaxed = true).also {
            every { it.isConnected } returns MutableStateFlow(true)
            every { it.isServerReachable } returns MutableStateFlow(true)
            every { it.isFullyConnected } returns true
        },
    )

    @Test
    fun `Si llego en la lista - solo con permiso y nunca en una falta de pase`() = runTest(scheduler) {
        val s = sesion()
        assertTrue(vm().canUndo(s.noShows[0]))
        assertFalse("falta de un pase: se corrige desde su app", vm().canUndo(s.noShows[1]))
        assertFalse(vm(canUpdate = false).canUndo(s.noShows[0]))
    }

    @Test
    fun `Si llego desde la lista confirma con la clase y la hora, y el resultado ensena el pendiente`() = runTest(scheduler) {
        val llego = Reservation(
            id = "r1", venueId = "v1", confirmationCode = "A1", cancelSecret = "s",
            status = ReservationStatus.CHECKED_IN, channel = ReservationChannel.DASHBOARD,
            startsAt = "2026-10-28T14:51:00.000Z", endsAt = "2026-10-28T15:51:00.000Z", duration = 60, createdAt = "x", updatedAt = "x",
        )
        coEvery { reservationRepo.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan(creditToReconsume = 1))
        coEvery { reservationRepo.undoNoShow("r1", null) } returns Result.success(
            UndoNoShowResult("UNDONE", llego, UndoNoShowPlan(), NoShowCorrectionRef("c1", listOf("CREDIT_NOT_RECONSUMED"))),
        )
        val vm = vm()
        advanceUntilIdle()

        vm.undoNoShow(sesion().noShows[0])
        advanceUntilIdle()
        // 14:51 UTC = 08:51 en la Ciudad de México
        assertEquals("¿Karla R. sí llegó a Yoga de las 08:51?", vm.undo.ui.value.prompt?.title)

        vm.undo.confirm()
        advanceUntilIdle()

        assertEquals(listOf("No había clase que descontar: cóbrala o regálala"), vm.undo.ui.value.done?.pending)
        coVerify(atLeast = 2) { classRepo.fetchOne("s1", any()) } // la lista se vuelve a pedir: la fila sale de «No se presentó»
    }

    @Test
    fun `ya no aplicaba - dice el motivo del servidor y vuelve a leer la lista`() = runTest(scheduler) {
        coEvery { reservationRepo.previewUndoNoShow("r1") } returns Result.failure(
            ReservationApiException(409, "La reservacion esta en estado CANCELLED: «Sí llegó» solo corrige un «No asistió»", code = "NO_SHOW_UNDO_NOT_APPLICABLE"),
        )
        every { reservationRepo.notifyChanged() } returns Unit
        coEvery { reservationRepo.fetchOne("r1") } returns Result.success(
            Reservation(
                id = "r1", venueId = "v1", confirmationCode = "A1", cancelSecret = "s",
                status = ReservationStatus.CANCELLED, channel = ReservationChannel.DASHBOARD,
                startsAt = "2026-10-28T14:51:00.000Z", endsAt = "2026-10-28T15:51:00.000Z", duration = 60, createdAt = "x", updatedAt = "x",
            ),
        )
        val vm = vm()
        advanceUntilIdle()

        vm.undoNoShow(sesion().noShows[0])
        advanceUntilIdle()

        val n = vm.undo.ui.value.notice!!
        assertEquals("No se pudo completar la acción", n.title)
        assertNull("nunca el texto crudo del servidor", n.message)
        assertFalse(n.warning)
        io.mockk.verify(exactly = 1) { reservationRepo.notifyChanged() }
        coVerify(atLeast = 2) { classRepo.fetchOne("s1", any()) }
    }
}
