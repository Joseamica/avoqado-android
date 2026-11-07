package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.HuellaDeFolio
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.sinLosYaAtendidos
import com.avoqado.pos.onlineorders.presentation.yaEstaListo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R101 (Codex fase 3-4 r3): la copia guardada de «Por pagar» es lo que «Cobrar» usa sin red y lo que la pantalla enseña al reiniciar.
 * Con el repositorio REAL (MockWebServer) y la copia en un «disco» de memoria que imita `PayloadCache`:
 * - (1) una marca de tarjeta de folio sólo se suelta si ESA lectura quedó como copia guardada: ninguna copia vieja queda detrás;
 * - (2) lo escondido tras «Listo»/«Entregado»/«Cancelar folio» se guarda en la MISMA copia y vuelve al reiniciar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CopiaGuardadaDeFoliosTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var falloAlGuardar = false
    private val cache = mockk<PayloadCache>().also { c ->
        coEvery { c.save(any(), any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), System.currentTimeMillis()) }
        coEvery { c.saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), System.currentTimeMillis())
        }
        coEvery { c.load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
    }
    private lateinit var repo: OnlineOrdersRepository
    private val marcas = FoliosCobradosConTarjeta.enMemoria()

    /** Lo que contesta `to-pay` ahora mismo. */
    private var toPay: MockResponse = MockResponse().setResponseCode(500)

    @Before fun arrancar() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("/online-orders/to-pay") -> toPay
                request.path!!.contains("/online-orders/inbox") -> MockResponse().setBody(
                    """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","counts":{"pending":0,"active":0,"done":0},"orders":[]}}""",
                )
                request.path!!.endsWith("/ready") || request.path!!.endsWith("/cancel") -> MockResponse().setBody("""{"success":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), cache)
    }

    @After fun apagar() {
        Dispatchers.resetMain()
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    /** Una pantalla nueva sobre el mismo disco (así se ve un reinicio de la app). */
    private fun pantalla() = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = MutableStateFlow(true),
        leerPorPagar = { latido, seccion, cursor -> repo.porPagar(latido, seccion, cursor) },
        foliosGuardados = { repo.porPagarGuardado() },
        guardarOcultos = { repo.guardarOcultos(it) },
        foliosConTarjeta = marcas,
        venueActual = { "v1" },
    )

    private fun folio(id: String, status: String, remaining: String, pago: String) =
        """{"orderId":"$id","onlineOrderId":"oo-$id","orderNumber":"W7K3P","status":"$status","paymentMode":"PAY_AT_PICKUP",
        "customerName":"Ana","customerPhone":null,"note":null,"createdAt":"2026-11-06T18:00:00.000Z",
        "acceptDeadlineAt":"2099-11-06T18:30:00.000Z","estimatedReadyAt":null,"total":134,"remaining":$remaining,"paymentStatus":"$pago",
        "cardStatus":null,"card":null,"rejectReason":null,"requiresAcceptance":false,"channel":"QR","needsPrint":false,"items":[]}"""

    private fun cuerpo(porPagar: String = "", porEntregar: String = "") = MockResponse().setBody(
        """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","enabled":true,"requiresAcceptance":false,"showTile":true,
        "pollSeconds":30,"counts":{"toPay":${if (porPagar.isEmpty()) 0 else 1},"toDeliver":${if (porEntregar.isEmpty()) 0 else 1}},
        "toPay":[$porPagar],"toDeliver":[$porEntregar],"nextCursors":{"toPay":null,"toDeliver":null},"store":null}}""",
    )

    private suspend fun esperarQueTermine(vm: OnlineOrdersViewModel, orderId: String) =
        withTimeout(5_000) { vm.ocupados.first { orderId !in it } }

    // MARK: - (2) lo escondido sobrevive al reinicio

    @Test
    fun `P2 Listo confirmed, the re-read fails and a restart with a 500 brings no live Listo - a READY read shows it with Entregado`() = runBlocking {
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val antes = pantalla()
        antes.descubrirPorPagar()

        toPay = MockResponse().setResponseCode(500)
        antes.marcarListo("f1")
        esperarQueTermine(antes, "f1")
        assertTrue("en este proceso, escondido", sinLosYaAtendidos(antes.porPagar.value!!, antes.ocultos.value).porEntregar.isEmpty())

        // Reinicio: la lista guardada es la de antes de «Listo» y la lectura vuelve a dar 500.
        val tras = pantalla()
        tras.descubrirPorPagar()
        assertEquals("la copia guardada sigue trayendo el folio como estaba", OnlineOrderStatus.ACCEPTED, tras.porPagar.value!!.porEntregar.single().status)
        assertTrue("pero sin su «Listo» vivo", sinLosYaAtendidos(tras.porPagar.value!!, tras.ocultos.value).porEntregar.isEmpty())

        // El SERVIDOR lo trae READY: vuelve, con la etiqueta «Listo» y sólo «Entregado»; la huella se suelta, también en la copia.
        toPay = cuerpo(porEntregar = folio("f1", "READY", "0", "PAID"))
        tras.refrescar()
        val vuelve = sinLosYaAtendidos(tras.porPagar.value!!, tras.ocultos.value).porEntregar.single()
        assertEquals(OnlineOrderStatus.READY, vuelve.status)
        assertTrue(yaEstaListo(vuelve))
        assertTrue(tras.ocultos.value.isEmpty())
        assertTrue(repo.porPagarGuardado()!!.ocultos.isEmpty())
    }

    @Test
    fun `saving page 1 keeps the hides that still match and prunes the rest`() = runBlocking {
        toPay = cuerpo(porPagar = folio("p1", "PENDING_ACCEPTANCE", "134", "PENDING"), porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        repo.porPagar(conLatido = false).getOrThrow()
        val ocultos = mapOf(
            "p1" to HuellaDeFolio("toPay", OnlineOrderStatus.PENDING_ACCEPTANCE, 13400),
            "f1" to HuellaDeFolio("toDeliver", OnlineOrderStatus.ACCEPTED, 0),
        )
        assertTrue(repo.guardarOcultos(ocultos))
        assertEquals(ocultos, repo.porPagarGuardado()!!.ocultos)

        // f1 cambió (READY): se suelta; p1 sigue igual: se queda.
        toPay = cuerpo(porPagar = folio("p1", "PENDING_ACCEPTANCE", "134", "PENDING"), porEntregar = folio("f1", "READY", "0", "PAID"))
        repo.porPagar(conLatido = false).getOrThrow()
        assertEquals(ocultos.filterKeys { it == "p1" }, repo.porPagarGuardado()!!.ocultos)

        // p1 ya no está en ninguna lista completa: se poda.
        toPay = cuerpo(porEntregar = folio("f1", "READY", "0", "PAID"))
        repo.porPagar(conLatido = false).getOrThrow()
        assertTrue(repo.porPagarGuardado()!!.ocultos.isEmpty())
    }

    // MARK: - (1) la marca se suelta en el mismo paso que la copia nueva

    @Test
    fun `P1 a complete to-pay read that counts the card payment releases the marker and the saved copy drops the old balance`() = runBlocking {
        toPay = cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING"))
        val vm = pantalla()
        vm.descubrirPorPagar()
        marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0, venueId = "v1", requestId = "req-1"))

        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        vm.refrescar()

        assertTrue("el SERVIDOR ya lo cuenta: se suelta", marcas.marcas().isEmpty())
        val copia = repo.porPagarGuardado()!!
        assertTrue("la copia guardada ya no dice «Por pagar $134»", copia.porPagar.none { it.orderId == "f1" })
        assertEquals(listOf("f1"), copia.porEntregar.map { it.orderId })
    }

    @Test
    fun `if the saved copy cannot be written the marker stays`() = runBlocking {
        toPay = cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING"))
        val vm = pantalla()
        vm.descubrirPorPagar()
        marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0, venueId = "v1", requestId = "req-1"))

        falloAlGuardar = true
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val leida = repo.porPagar(conLatido = false).getOrThrow()
        assertFalse("la lectura dice que su copia no quedó", leida.copiaGuardada)
        vm.refrescar()
        assertEquals("la copia vieja ($134) sigue en el disco: la marca se queda", 1, marcas.marcas().size)
        assertEquals(13400L, repo.porPagarGuardado()!!.porPagar.single().remainingCents)
    }
}
