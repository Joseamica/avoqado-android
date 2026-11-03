package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PermisosRealesDelServer
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.kiosk.FakeReservationDao
import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.data.PendingReservationActionDao
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import com.avoqado.pos.reservations.data.ReservationActionsRetrier
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.NoShowCorrection
import com.avoqado.pos.reservations.data.model.NoShowCorrectionRef
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.UndoNoShowPlan
import com.avoqado.pos.reservations.data.model.UndoNoShowResult
import com.avoqado.pos.reservations.domain.UndoNoShowTexts
import com.avoqado.pos.reservations.presentation.undo.UndoNoShowFlow
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * D4 en la franja «Operaciones por revisar».
 *
 * 1. Una llegada del kiosco que el servidor rechazó porque su «No asistió» le ganó: el gerente puede tocar «Sí llegó»
 *    ahí mismo, y al corregirse la fila se va.
 * 2. Cuando el SERVIDOR corrigió solo la llegada y dejó un pendiente, el aparato lo guarda como aviso ANTES de borrar la
 *    fila de la cola; un reintento con la misma corrección no crea otro; «Marcar resuelta» lo cierra en el servidor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuarantineUndoNoShowTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val C = "2026-10-28T15:01:00.000Z" // cierre de la ventana, en hora del SERVIDOR
    private val MAX = ReservationRepository.MAX_ATTEMPTS
    private val textoNoAsistio =
        "Karla R. llegó a las 08:49 sin conexión, pero su reserva ya estaba como «No asistió» y la app no puede deshacerlo. Avísale al gerente."

    private val secureStorage: SecureStorage = mockk<SecureStorage>(relaxed = true).also {
        every { it.venueId } returns "v1"
        every { it.venueTimezone } returns "America/Mexico_City"
    }
    private val reservationRepository: ReservationRepository = mockk(relaxed = true)

    /** Con los permisos REALES que el servidor manda a cada rol (no una lista recortada). */
    private fun vm(rol: String = "MANAGER", permisos: List<String> = PermisosRealesDelServer.MANAGER): QuarantineViewModel {
        every { secureStorage.userRole } returns rol
        every { secureStorage.venuePermissions } returns permisos
        val outbox = mockk<SyncOutbox>(relaxed = true).also { every { it.rejectedCount } returns MutableStateFlow(0) }
        return QuarantineViewModel(
            syncOutbox = outbox,
            secureStorage = secureStorage,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            reservationRepository = reservationRepository,
            roleManager = RoleManager(secureStorage),
            cashPaymentRepository = mockk(relaxed = true),
        )
    }

    private fun fila(action: String = KioskTap.ACTION, lastError: String? = textoNoAsistio, payload: String? = null) =
        PendingReservationActionEntity(rowId = 7, reservationId = "r1", action = action, payloadJson = payload, attemptCount = MAX, lastError = lastError)

    private fun reserva() = Reservation(
        id = "r1", venueId = "v1", confirmationCode = "X", cancelSecret = "s",
        status = ReservationStatus.CHECKED_IN, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-10-28T14:51:00.000Z", endsAt = "2026-10-28T15:51:00.000Z",
        duration = 60, createdAt = "x", updatedAt = "x",
    )

    // MARK: - «Sí llegó» en la llegada rechazada

    @Test
    fun `llegada del kiosco rechazada por No asistio - el gerente ve Si llego, el cajero no`() = runTest(scheduler) {
        assertTrue(vm().canUndoNoShow(fila()))
        assertFalse("otro rechazo del kiosco no", vm().canUndoNoShow(fila(lastError = "La llegada lleva más de 72 horas sin poder enviarse.")))
        assertFalse("otra acción no", vm().canUndoNoShow(fila(action = "CHECK_IN")))
        assertFalse("sin reservations:update no", vm("CASHIER", PermisosRealesDelServer.CASHIER).canUndoNoShow(fila()))
    }

    @Test
    fun `Si llego corrige SU reserva y al salir bien la fila se va`() = runTest(scheduler) {
        coEvery { reservationRepository.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan(creditToReconsume = 1))
        coEvery { reservationRepository.fetchOne("r1") } returns Result.success(reserva())
        coEvery { reservationRepository.undoNoShow("r1", null) } returns Result.success(
            UndoNoShowResult("UNDONE", reserva(), UndoNoShowPlan(), NoShowCorrectionRef("c1", listOf("CLASS_UNPAID"))),
        )
        val vm = vm()

        vm.undoNoShow(fila())
        advanceUntilIdle()
        assertTrue("primero la confirmación, sin tocar nada", vm.undo.ui.value.prompt != null)
        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }

        vm.undo.confirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { reservationRepository.undoNoShow("r1", null) }
        coVerify(exactly = 1) { reservationRepository.dismissQuarantined(7) }
        assertEquals(listOf("Esta clase no estaba pagada: cóbrala en caja"), vm.undo.ui.value.done?.pending)
    }

    @Test
    fun `si no se pudo corregir la fila se queda`() = runTest(scheduler) {
        coEvery { reservationRepository.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan())
        coEvery { reservationRepository.fetchOne("r1") } returns Result.failure(IOException())
        coEvery { reservationRepository.undoNoShow("r1", null) } returns Result.failure(IOException())
        val vm = vm()

        vm.undoNoShow(fila()); advanceUntilIdle()
        vm.undo.confirm(); advanceUntilIdle()

        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
        assertEquals(
            UndoNoShowFlow.Notice(
                "No se pudo completar la acción",
                "Se cortó la conexión. Vuelve a tocar «Sí llegó»: si ya se había corregido, no se repite.",
                warning = true,
            ),
            vm.undo.ui.value.notice,
        )
    }

    // MARK: - El aviso de la corrección automática

    private val dao = FakeReservationDao()
    private val kioskApi = mockk<KioskCheckInApi>()

    private fun retrier(d: PendingReservationActionDao = dao) =
        ReservationActionsRetrier(d, mockk(), mockk(relaxed = true), mockk(relaxed = true), kioskApi, secureStorage)

    private suspend fun llegada(): Long = dao.insert(
        PendingReservationActionEntity(
            reservationId = "r1",
            action = KioskTap.ACTION,
            payloadJson = KioskTap.payload("v1", C, 720_000, name = "Karla R."), // tocó 12 min antes del cierre: 08:49
        ),
    )

    private fun avisos() = dao.rows.filter { it.action == KioskTap.CORRECTION }

    @Test
    fun `exito del kiosco con pendiente - entra un aviso a la franja y la llegada sale de la cola`() = runTest(scheduler) {
        llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns
            Result.success(KioskTap.Correction("c1", listOf("CLASS_UNPAID")))

        retrier().drain(KioskTap.ACTION)

        assertTrue("la llegada salió de la cola", dao.rows.none { it.action == KioskTap.ACTION })
        val aviso = avisos().single()
        assertEquals("r1", aviso.reservationId)
        assertEquals("guarda la sucursal de la llegada", "v1", KioskTap.parseCorrection(aviso.payloadJson)?.venueId)
        assertEquals("ya está en la franja (no se vuelve a mandar)", MAX, aviso.attemptCount)
        val vm = vm()
        assertEquals(
            listOf(
                // Neutro: la `correction` no dice si la hizo el kiosco o una persona antes de que saliera la fila.
                "Karla R. llegó a las 08:49 sin conexión; su «No se presentó» ya quedó como «Llegó».",
                "Pendiente: Esta clase no estaba pagada: cóbrala en caja",
            ),
            vm.correctionNoticeLines(aviso),
        )
    }

    @Test
    fun `el aviso se guarda ANTES de borrar la fila - y el reintento con la misma correccion no crea otro`() = runTest(scheduler) {
        llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns
            Result.success(KioskTap.Correction("c1", listOf("CLASS_UNPAID")))
        // El proceso muere justo al borrar la fila: el aviso ya tiene que estar guardado.
        val muere = object : PendingReservationActionDao by dao {
            var vivo = false
            override suspend fun delete(rowId: Long) {
                if (!vivo) throw IllegalStateException("el proceso murió")
                dao.delete(rowId)
            }
        }

        runCatching { retrier(muere).drain(KioskTap.ACTION) }
        assertEquals(1, avisos().size)
        assertEquals("la fila sigue para reintentar", 1, dao.rows.count { it.action == KioskTap.ACTION })

        muere.vivo = true
        retrier(muere).drain(KioskTap.ACTION) // respuesta repetida: la misma corrección

        assertEquals(1, avisos().size)
        assertTrue(dao.rows.none { it.action == KioskTap.ACTION })
    }

    @Test
    fun `sin pendiente no hay aviso`() = runTest(scheduler) {
        llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.success(KioskTap.Correction("c1", emptyList()))

        retrier().drain(KioskTap.ACTION)

        assertTrue(avisos().isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `el aviso no se vuelve a mandar en el siguiente drain`() = runTest(scheduler) {
        llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns
            Result.success(KioskTap.Correction("c1", listOf("CLASS_UNPAID")))
        retrier().drain(KioskTap.ACTION)

        retrier().drain()

        coVerify(exactly = 1) { kioskApi.checkInReservation(any(), any(), any(), any()) }
        assertEquals(1, avisos().size)
    }

    @Test
    fun `Marcar resuelta en el aviso lo resuelve en el servidor y lo quita`() = runTest(scheduler) {
        val aviso = fila(action = KioskTap.CORRECTION, lastError = null, payload = KioskTap.correctionPayload(KioskTap.Correction("c1", listOf("CLASS_UNPAID"))))
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null) } returns Result.success("2026-10-28T16:00:00.000Z")
        val vm = vm()

        vm.resolveCorrectionNotice(aviso)
        advanceUntilIdle()

        coVerify(exactly = 1) { reservationRepository.resolveNoShowCorrection("r1", null) }
        coVerify(exactly = 1) { reservationRepository.dismissQuarantined(7) }
        assertEquals("Pendiente resuelto", vm.successMessage.value)
    }

    @Test
    fun `sin red Marcar resuelta no borra el aviso`() = runTest(scheduler) {
        val aviso = fila(action = KioskTap.CORRECTION, lastError = null, payload = KioskTap.correctionPayload(KioskTap.Correction("c1", listOf("CLASS_UNPAID"))))
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null) } returns
            Result.failure(ReservationRepository.OfflineRequiredException("Sin conexión"))
        val vm = vm()

        vm.resolveCorrectionNotice(aviso)
        advanceUntilIdle()

        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
        assertEquals(UndoNoShowFlow.Notice("Sin conexión", "Necesita conexión para marcarlo resuelto", warning = true), vm.resolveNotice.value)
        assertNull("sin red no va en rojo", vm.errorMessage.value)
        assertNull(vm.successMessage.value)
    }

    private fun aviso() = fila(action = KioskTap.CORRECTION, lastError = null, payload = KioskTap.correctionPayload(KioskTap.Correction("c1", listOf("CLASS_UNPAID"))))

    @Test
    fun `la red se corta al resolver - aviso en ambar, nunca el texto crudo en rojo, y el aviso se queda`() = runTest(scheduler) {
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null) } returns Result.failure(java.net.SocketException("Connection reset"))
        val vm = vm()

        vm.resolveCorrectionNotice(aviso())
        advanceUntilIdle()

        assertEquals(UndoNoShowFlow.Notice("Sin conexión", "Necesita conexión para marcarlo resuelto", warning = true), vm.resolveNotice.value)
        assertNull(vm.errorMessage.value)
        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
    }

    @Test
    fun `otro rechazo al resolver - titulo y texto humanizado`() = runTest(scheduler) {
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null) } returns
            Result.failure(com.avoqado.pos.reservations.data.ReservationApiException(403, "Forbidden"))
        val vm = vm()

        vm.resolveCorrectionNotice(aviso())
        advanceUntilIdle()

        assertEquals(UndoNoShowFlow.Notice("No se pudo completar la acción", "No tienes permiso para hacer esto."), vm.resolveNotice.value)
        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
    }

    private fun avisoDe(venueId: String) = fila(
        action = KioskTap.CORRECTION,
        lastError = null,
        payload = KioskTap.correctionPayload(KioskTap.Correction("c1", listOf("CLASS_UNPAID"), venueId = venueId)),
    )

    @Test
    fun `aviso de la sucursal A con la app en la B - se resuelve contra A`() = runTest(scheduler) {
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null, "vA") } returns Result.success("2026-10-28T16:00:00.000Z")
        val vm = vm() // la sesión está en v1

        vm.resolveCorrectionNotice(avisoDe("vA"))
        advanceUntilIdle()

        coVerify(exactly = 1) { reservationRepository.resolveNoShowCorrection("r1", null, "vA") }
        coVerify(exactly = 1) { reservationRepository.dismissQuarantined(7) }
        assertEquals("Pendiente resuelto", vm.successMessage.value)
    }

    @Test
    fun `404 en SU sucursal - ya no estaba pendiente, se quita sin decir que se resolvio`() = runTest(scheduler) {
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null, "vA") } returns
            Result.failure(com.avoqado.pos.reservations.data.ReservationApiException(404, "No se encontró el recurso"))
        val vm = vm()

        vm.resolveCorrectionNotice(avisoDe("vA"))
        advanceUntilIdle()

        coVerify(exactly = 1) { reservationRepository.dismissQuarantined(7) }
        assertEquals("Ya no estaba pendiente", vm.successMessage.value)
        assertNull(vm.resolveNotice.value)
    }

    @Test
    fun `el cajero no resuelve el aviso`() = runTest(scheduler) {
        val aviso = fila(action = KioskTap.CORRECTION, lastError = null, payload = KioskTap.correctionPayload(KioskTap.Correction("c1", listOf("CLASS_UNPAID"))))

        vm("CASHIER", PermisosRealesDelServer.CASHIER).resolveCorrectionNotice(aviso)
        advanceUntilIdle()

        coVerify(exactly = 0) { reservationRepository.resolveNoShowCorrection(any(), any(), any()) }
    }

    // MARK: - Codex r2 #2 · todo lo que nace del aviso va contra SU sucursal

    /** La llegada rechazada de la sucursal A (su toque guardado la trae), con la sesión en v1. */
    private fun filaDe(venueId: String) = fila(payload = KioskTap.payload(venueId, C, 720_000, name = "Karla R."))

    @Test
    fun `Si llego de una llegada de la sucursal A con la sesion en B - vista previa, titulo y POST van contra A`() = runTest(scheduler) {
        coEvery { reservationRepository.previewUndoNoShow("r1", "vA") } returns Result.success(UndoNoShowPlan(creditToReconsume = 1))
        coEvery { reservationRepository.fetchOne("r1", "vA") } returns Result.success(reserva())
        coEvery { reservationRepository.undoNoShow("r1", null, "vA") } returns Result.success(
            UndoNoShowResult("UNDONE", reserva(), UndoNoShowPlan(), NoShowCorrectionRef("c1", emptyList())),
        )
        val vm = vm() // la sesión está en v1

        vm.undoNoShow(filaDe("vA")); advanceUntilIdle()
        assertTrue("la vista previa de A abrió la confirmación", vm.undo.ui.value.prompt != null)
        vm.undo.confirm(); advanceUntilIdle()

        coVerify(exactly = 1) { reservationRepository.previewUndoNoShow("r1", "vA") }
        coVerify(exactly = 1) { reservationRepository.undoNoShow("r1", null, "vA") }
        coVerify(exactly = 0) { reservationRepository.previewUndoNoShow(any(), neq("vA")) }
        coVerify(exactly = 0) { reservationRepository.undoNoShow(any(), any(), neq("vA")) }
        coVerify(exactly = 0) { reservationRepository.fetchOne(any(), neq("vA")) }
        coVerify(exactly = 1) { reservationRepository.dismissQuarantined(7) }
        assertEquals("Llegada corregida", vm.undo.ui.value.done?.message)
    }

    @Test
    fun `Si llego de la sucursal A - si la respuesta se pierde, la relectura tambien va contra A`() = runTest(scheduler) {
        val corregida = reserva().copy(noShowCorrection = NoShowCorrection(pending = emptyList()))
        coEvery { reservationRepository.previewUndoNoShow("r1", "vA") } returns Result.success(UndoNoShowPlan())
        coEvery { reservationRepository.fetchOne("r1", "vA") } returns Result.success(corregida)
        coEvery { reservationRepository.undoNoShow("r1", null, "vA") } returns Result.failure(IOException("unexpected end of stream"))
        val vm = vm()

        vm.undoNoShow(filaDe("vA")); advanceUntilIdle()
        vm.undo.confirm(); advanceUntilIdle()

        assertEquals("Ya estaba corregida", vm.undo.ui.value.done?.message)
        coVerify(exactly = 0) { reservationRepository.fetchOne(any(), neq("vA")) }
    }

    // MARK: - Codex r2 #3 · «Sí llegó» y «Marcar resuelta» del aviso piden `reservations:update`, como el detalle

    @Test
    fun `recepcion HOST - ve y usa Si llego y Marcar resuelta del aviso, sin ser gerente`() = runTest(scheduler) {
        coEvery { reservationRepository.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan())
        coEvery { reservationRepository.fetchOne("r1") } returns Result.success(reserva())
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null, "vA") } returns Result.success("2026-10-28T16:00:00.000Z")
        val vm = vm("HOST", PermisosRealesDelServer.HOST)

        assertTrue("premisa: HOST tiene reservations:update", "reservations:update" in PermisosRealesDelServer.HOST)
        assertFalse("premisa: HOST no resuelve la cuarentena", vm.canResolve)
        assertTrue(vm.canFixArrival)
        assertTrue(vm.canUndoNoShow(fila()))

        vm.undoNoShow(fila()); advanceUntilIdle()
        assertTrue("abrió la confirmación", vm.undo.ui.value.prompt != null)

        vm.resolveCorrectionNotice(avisoDe("vA")); advanceUntilIdle()
        coVerify(exactly = 1) { reservationRepository.resolveNoShowCorrection("r1", null, "vA") }
        assertEquals("Pendiente resuelto", vm.successMessage.value)
    }

    @Test
    fun `sin reservations update - ni Si llego ni Marcar resuelta del aviso`() = runTest(scheduler) {
        val vm = vm("CASHIER", PermisosRealesDelServer.CASHIER)

        assertFalse("premisa: el cajero no tiene reservations:update", "reservations:update" in PermisosRealesDelServer.CASHIER)
        assertFalse(vm.canFixArrival)
        assertFalse(vm.canUndoNoShow(fila()))
        vm.undoNoShow(fila())
        vm.resolveCorrectionNotice(avisoDe("vA"))
        advanceUntilIdle()

        coVerify(exactly = 0) { reservationRepository.previewUndoNoShow(any(), any()) }
        coVerify(exactly = 0) { reservationRepository.resolveNoShowCorrection(any(), any(), any()) }
    }

    @Test
    fun `HOST - las demas acciones de la cuarentena siguen con el candado de gerente`() = runTest(scheduler) {
        val vm = vm("HOST", PermisosRealesDelServer.HOST)

        assertFalse(vm.canResolve)
        vm.dismissReservationAction(7L) // «Marcar resuelta» de una acción de reserva: borra del aparato, sigue de gerente
        advanceUntilIdle()

        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
    }

    // MARK: - Detalle de la ronda anterior · un aviso viejo (sin sucursal) con 404 no se quita

    @Test
    fun `aviso viejo sin sucursal - un 404 de la sucursal activa no lo quita`() = runTest(scheduler) {
        coEvery { reservationRepository.resolveNoShowCorrection("r1", null, null) } returns
            Result.failure(com.avoqado.pos.reservations.data.ReservationApiException(404, "No se encontró el recurso"))
        val vm = vm()

        vm.resolveCorrectionNotice(aviso()); advanceUntilIdle()

        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
        assertNull(vm.successMessage.value)
        assertEquals("se dice que no salió, el aviso se queda", "No se pudo completar la acción", vm.resolveNotice.value?.title)
    }

    // MARK: - Codex código r3 · la hora de un aviso de OTRA sucursal, en la zona de ESA sucursal
    // (la sesión anda en CDMX; la llegada fue en Cancún, que va una hora adelante: 14:49Z = 08:49 CDMX = 09:49 Cancún)

    private val cdmx = StoredVenue(id = "v1", name = "Centro", timezone = "America/Mexico_City")
    private val cancun = StoredVenue(id = "vC", name = "Cancún", timezone = "America/Cancun")

    private fun avisoConHoraDe(venueId: String) = fila(
        action = KioskTap.CORRECTION, lastError = null,
        payload = KioskTap.correctionPayload(KioskTap.Correction("c1", name = "Karla R.", tappedAt = "2026-10-28T14:49:00Z", venueId = venueId)),
    )

    @Test
    fun `aviso del kiosco de Cancun visto desde CDMX - dice la hora de Cancun`() = runTest(scheduler) {
        every { secureStorage.venuesList } returns listOf(cdmx, cancun)

        assertEquals(
            "Karla R. llegó a las 09:49 sin conexión; su «No se presentó» ya quedó como «Llegó».",
            vm().correctionNoticeLines(avisoConHoraDe("vC")).first(),
        )
    }

    @Test
    fun `Si llego de una llegada de Cancun visto desde CDMX - el titulo dice la hora de Cancun`() = runTest(scheduler) {
        every { secureStorage.venuesList } returns listOf(cdmx, cancun)
        coEvery { reservationRepository.previewUndoNoShow("r1", "vC") } returns Result.success(UndoNoShowPlan())
        coEvery { reservationRepository.fetchOne("r1", "vC") } returns Result.success(reserva()) // empieza 14:51Z
        val vm = vm()

        vm.undoNoShow(fila(payload = KioskTap.payload("vC", C, 720_000, name = "Karla R."))); advanceUntilIdle()

        val titulo = vm.undo.ui.value.prompt?.title
        assertEquals(UndoNoShowTexts.title(reserva(), "America/Cancun"), titulo)
        assertTrue(titulo.orEmpty(), titulo.orEmpty().contains("de las 09:51"))
    }

    @Test
    fun `sucursal que la app no conoce - se queda la hora de la activa, no se inventa zona`() = runTest(scheduler) {
        every { secureStorage.venuesList } returns listOf(cdmx)

        assertEquals(
            "Karla R. llegó a las 08:49 sin conexión; su «No se presentó» ya quedó como «Llegó».",
            vm().correctionNoticeLines(avisoConHoraDe("vDesconocida")).first(),
        )
    }
}
