package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintJobDto
import com.avoqado.pos.printing.routing.SyncPrintJobsRequest
import com.avoqado.pos.printing.routing.SyncPrintJobsResponse
import com.avoqado.pos.printing.routing.SyncPrintJobsResult
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * «La libreta» (Task 16): la app reporta al servidor lo que de verdad pasó con cada comanda —
 * `ApiService.syncPrintJobs` ya estaba declarado y el servidor ya lo procesa, pero nadie lo
 * llamaba. Sin esto, el fallo de una impresora ocurre entero en la LAN del local y nunca llega
 * al servidor (el caso real: diagnosticar «la impresora de cocina se desconecta» costó una
 * sesión entera sin encontrar la causa).
 */
class ReporteDeComandasTest {

    private val apiService = mockk<ApiService>()
    private val syncOutbox = mockk<SyncOutbox>()
    private lateinit var sut: ReporteDeComandas

    private val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)

    @Before
    fun setup() {
        every { syncOutbox.deviceId } returns "device-abc-123"
        coEvery { apiService.syncPrintJobs(any(), any()) } returns
            SyncPrintJobsResponse(success = true, data = SyncPrintJobsResult(upserted = 1, registered = true))

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        sut = ReporteDeComandas(apiService, syncOutbox)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // MARK: - (a) una comanda que salió reporta DONE con el terminalId de SyncOutbox

    @Test
    fun `una comanda que salio reporta DONE con el terminalId de SyncOutbox`() = runTest {
        val requestSlot = slot<SyncPrintJobsRequest>()
        coEvery { apiService.syncPrintJobs(eq("venue-1"), capture(requestSlot)) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = true))

        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.Salio,
            intentos = 1,
        )

        val job: PrintJobDto = requestSlot.captured.jobs.single()
        assertEquals("device-abc-123", requestSlot.captured.terminalId)
        assertEquals("DONE", job.status)
        assertEquals("KITCHEN_TICKET", job.type)
        assertEquals("ORIGINAL", job.reason)
        assertEquals(1, job.seq)
        assertNull(job.error)
        assertEquals(1, job.attempts)
        assertEquals("order-1", job.orderId)
    }

    // MARK: - (b) una que no salió reporta FAILED con la causa

    @Test
    fun `una comanda que no salio reporta FAILED con la causa real`() = runTest {
        val requestSlot = slot<SyncPrintJobsRequest>()
        coEvery { apiService.syncPrintJobs(eq("venue-1"), capture(requestSlot)) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = true))

        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout 192.168.1.50:9100", "1234"),
            intentos = 6,
            stationId = "st_cocina",
        )

        val job = requestSlot.captured.jobs.single()
        assertEquals("FAILED", job.status)
        assertEquals("timeout 192.168.1.50:9100", job.error)
        assertEquals(6, job.attempts)
        assertEquals("st_cocina", job.stationId)
    }

    @Test
    fun `el error se trunca a 500 caracteres — el server lo rechaza mas largo`() = runTest {
        val requestSlot = slot<SyncPrintJobsRequest>()
        coEvery { apiService.syncPrintJobs(any(), capture(requestSlot)) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = true))
        val causaLarga = "x".repeat(900)

        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.NoSalio(listOf("Cocina"), causaLarga, "1234"),
            intentos = 6,
        )

        assertEquals(500, requestSlot.captured.jobs.single().error?.length)
    }

    // MARK: - (c) el mismo eventId para el mismo pedido+estación en reintentos

    @Test
    fun `el mismo pedido y estacion producen SIEMPRE el mismo eventId, aunque cambien los intentos`() = runTest {
        val requests = mutableListOf<SyncPrintJobsRequest>()
        coEvery { apiService.syncPrintJobs(any(), capture(requests)) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = true))

        // Dos reportes de LA MISMA comanda/estación en distintos reintentos (3 y luego 6 intentos).
        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout", "1234"),
            intentos = 3,
            stationId = "st_cocina",
        )
        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout otra vez", "1234"),
            intentos = 6,
            stationId = "st_cocina",
        )

        assertEquals(2, requests.size)
        assertEquals(requests[0].jobs.single().eventId, requests[1].jobs.single().eventId)
    }

    @Test
    fun `una estacion DISTINTA de la MISMA orden produce un eventId distinto`() = runTest {
        val requests = mutableListOf<SyncPrintJobsRequest>()
        coEvery { apiService.syncPrintJobs(any(), capture(requests)) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = true))

        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout", "1234"),
            intentos = 6,
            stationId = "st_cocina",
        )
        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.NoSalio(listOf("Barra"), "timeout", "1234"),
            intentos = 6,
            stationId = "st_barra",
        )

        assertEquals(2, requests.size)
        assert(requests[0].jobs.single().eventId != requests[1].jobs.single().eventId)
    }

    // MARK: - (d) si la llamada de red truena, reportar NO propaga la excepción

    @Test
    fun `si la red truena reportar no propaga la excepcion`() = runTest {
        coEvery { apiService.syncPrintJobs(any(), any()) } throws java.io.IOException("sin red")

        // No debe lanzar — si lo hace, el test truena aquí mismo.
        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.Salio,
            intentos = 1,
        )
    }

    // MARK: - (e) registered:false deja un Log.w y una llave de Crashlytics

    @Test
    fun `registered false se grita con Log w y una llave de Crashlytics — nunca en silencio`() = runTest {
        coEvery { apiService.syncPrintJobs(any(), any()) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = false))

        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.Salio,
            intentos = 1,
        )

        verify(atLeast = 1) { Log.w(any(), any<String>()) }
        verify(atLeast = 1) { crashlytics.setCustomKey("print_job_sync_registered", false) }
    }

    @Test
    fun `registered true NO dispara ningun aviso de descarte`() = runTest {
        coEvery { apiService.syncPrintJobs(any(), any()) } returns
            SyncPrintJobsResponse(data = SyncPrintJobsResult(registered = true))

        sut.reportar(
            venueId = "venue-1",
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.Salio,
            intentos = 1,
        )

        verify(exactly = 0) { crashlytics.setCustomKey("print_job_sync_registered", any<Boolean>()) }
    }

    // MARK: - Sin venueId no se inventa un reporte

    @Test
    fun `sin venueId no se llama al server — nunca inventa un venue`() = runTest {
        sut.reportar(
            venueId = null,
            orderId = "order-1",
            orderNumber = "1234",
            estado = EstadoDeComanda.Salio,
            intentos = 1,
        )

        coVerify(exactly = 0) { apiService.syncPrintJobs(any(), any()) }
    }
}
