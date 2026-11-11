package com.avoqado.pos.payment

import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.SolicitudesDeTarjetaTest
import com.avoqado.pos.giftcards.data.CobroConSaldoCoordinator
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.giftcards.data.DesenlaceDeCobro
import com.avoqado.pos.giftcards.data.GiftCardsRepository
import com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.orders.data.CuentasCobradasAqui
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.payment.data.PayLaterRow
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.DecisionSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed.Cobrar
import com.avoqado.pos.payment.domain.DecisionSinRed.Gris
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D12, final fix ronda 2 (R2-1, Codex r2): un cobro NO efectivo que ESTE aparato confirmó sobre una cuenta guardada nunca la deja
 * cobrable en efectivo sin red, salga por donde salga el positivo: «Revisar» de un cobro anterior, desenlace tardío, conciliación,
 * «Entendido», vencimiento de su ventana o el resolutor del saldo de regalo, que no pasan por el `Success` de la pantalla.
 *
 * Piezas REALES: `SecureStorage` sobre preferencias de memoria (la lista durable de tarjetas y las marcas, con sus commits),
 * `TerminalPaymentService` contra un MockWebServer, `CuentasCobradasAqui`, la copia de «Pedidos», el coordinador del saldo de regalo y
 * la decisión de `CobroDeCuentaViewModel`. `runBlocking`: las llamadas HTTP son de verdad.
 */
class CobroNoEfectivoDeEstaCajaTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: SolicitudesDeTarjetaTest.Preferencias
    private lateinit var storage: SecureStorage
    private lateinit var servicio: TerminalPaymentService
    private lateinit var copia: CopiaDeCuentas

    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private val cache = mockk<PayloadCache> {
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private val dao = mockk<PendingPaymentDao> { coEvery { forVenue(any()) } returns emptyList() }

    @Before fun armar() {
        server = MockWebServer().also { it.start() }
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString())
        prefs = SolicitudesDeTarjetaTest.Preferencias().apply { values["venueId"] = "v1"; values["accessToken"] = "t" }
        reiniciar()
    }

    @After fun cerrar() {
        server.shutdown()
        System.clearProperty("avoqado.test.baseUrl")
    }

    /** Un proceso nuevo sobre el mismo disco (preferencias y copia). */
    private fun reiniciar() {
        storage = prefs.almacen()
        val marcas = CuentasCobradasAqui(storage)
        servicio = TerminalPaymentService(storage, OkHttpClient(), marcas).also { it.baseUrl = server.url("/api/v1").toString().trimEnd('/') }
        copia = CopiaDeCuentas(cache, marcas).also { it.io = kotlinx.coroutines.Dispatchers.Unconfined }
    }

    private fun cuenta(id: String, saldo: Int = 10000) = OrderSummary(id = id, esDeMesa = false, source = "AVOQADO_ANDROID",
        status = "PENDING", paymentStatus = "PENDING", createdAt = "2026-11-10T18:00:00Z", saldoPendienteCents = saldo, pagosCompletados = 0)

    private fun contexto(requestId: String, orderId: String, venue: String? = "v1", creadoEn: Long = System.currentTimeMillis()) =
        JSONObject().put("requestId", requestId).put("orderId", orderId).put("amountCents", 10000).put("creadoEn", creadoEn)
            .apply { if (venue != null) put("venueId", venue) }.toString()

    private fun decision(orderId: String): DecisionSinRed? = runBlocking {
        val store = SolicitudesDeTarjetaStore(storage)
        val vm = CobroDeCuentaViewModel(
            mockk<TableServiceRepository>(), storage, mockk<ConnectivityMonitor>(),
            mockk<RoleManager>(relaxed = true) { every { hasVenuePermission("payments:create") } returns true }, store,
            CobrosLocalesDeLaCuenta(dao, storage, store),
            copiaDeCuentas = copia,
            cuentasAbiertasAqui = mockk<CuentasAbiertasAqui> { coEvery { laAbrioAqui(any(), any()) } returns true },
            cashPaymentRepository = mockk<CashPaymentRepository> { coEvery { huboEfectivoSubido(any()) } returns false },
            planManager = mockk<PlanManager> { every { tieneAcreditada(any()) } returns false },
            lanHub = mockk<LanHubService>(relaxed = true) { every { enabled } returns MutableStateFlow(false) },
            payLaterQueue = mockk<PayLaterQueue> { every { filas } returns MutableStateFlow(emptyList<PayLaterRow>()) },
            terminalPaymentService = servicio,
        )
        vm.consultarSinRed(orderId)
        vm.sinRed.value
    }

    private fun guardadaEnLaCopia(vararg ids: String) = runBlocking { copia.alLeer("v1", ids.map { cuenta(it) }, Lectura.SUELTA) }

    private fun noSeOfreceEfectivo(orderId: String) {
        val d = decision(orderId)
        assertTrue("$orderId no debe ofrecer «Cobrar en efectivo»: $d", d is Gris)
    }

    private fun completado(paymentId: String) =
        MockResponse().setResponseCode(200).setBody("""{"status":"COMPLETED","inProgress":false,"paymentId":"$paymentId"}""")

    // MARK: - Tarjeta

    /** El caso de Codex r2: «Revisar» de un cobro anterior confirma que sí pasó → «Entendido» → se cae la red. */
    @Test fun `Revisar confirma el cobro anterior y Entendido no la deja cobrable en efectivo`() = runBlocking {
        guardadaEnLaCopia("A")
        assertEquals("montaje: sin tarjeta, la cuenta se ofrece", Cobrar(10000, false), decision("A"))
        storage.persistPendingCardCharge("req-1", "A", contexto("req-1", "A"))
        assertEquals("en duda", Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), decision("A"))

        server.enqueue(completado("pay-1"))
        assertTrue(servicio.resolveOutcome("req-1") is TerminalPaymentResult.Success) // «Revisar»
        assertEquals("cobrada, sigue en la lista", Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), decision("A"))

        servicio.reconocerCobro("req-1") // «Entendido»
        noSeOfreceEfectivo("A")

        reiniciar() // y tras reiniciar la app
        noSeOfreceEfectivo("A")
        assertEquals("la copia ya la aplicó: la marca se soltó", emptyList<Any>(), CuentasCobradasAqui.leer(storage.cuentasCobradasAquiJson))
    }

    @Test fun `un desenlace tardio que si cobro y luego su Entendido no la dejan cobrable`() = runBlocking {
        guardadaEnLaCopia("B")
        storage.persistPendingCardCharge("req-2", "B", contexto("req-2", "B"))
        assertTrue(servicio.aplicarDesenlaceTardio("req-2", CardChargeOutcome.Charged("pay-2")))
        noSeOfreceEfectivo("B")
        servicio.reconocerCobro("req-2")
        noSeOfreceEfectivo("B")
    }

    /** El pariente de la re-revisión: el positivo sale de la lista al vencer su ventana de 10 min, sin que nadie toque nada. */
    @Test fun `un positivo que vence su ventana deja su marca y la cuenta no queda cobrable`() = runBlocking {
        guardadaEnLaCopia("C")
        val hace11Min = System.currentTimeMillis() - 11 * 60_000L
        assertTrue(storage.persistCobroQueSiPaso("req-3", "C", contexto("req-3", "C", creadoEn = hace11Min), hace11Min))
        noSeOfreceEfectivo("C")
        assertEquals("ya salió de la lista", emptyList<String>(), servicio.cobrosConTarjetaDelNegocio("v1").map { it.requestId })
        noSeOfreceEfectivo("C")
    }

    /** Una tarjeta en duda cuyo contexto no dice de qué negocio es también frena (falla cerrado). */
    @Test fun `una tarjeta en duda sin negocio en su contexto tambien deja la cuenta en gris`() = runBlocking {
        guardadaEnLaCopia("D")
        storage.persistPendingCardCharge("req-4", "D", contexto("req-4", "D", venue = null))
        assertEquals(Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), decision("D"))
    }

    /** Lo que ya funcionaba no cambia: una duda que se declara sin cobro sale sin marca y la cuenta se vuelve a ofrecer. */
    @Test fun `una duda declarada sin cobro no deja marca`() = runBlocking {
        guardadaEnLaCopia("E")
        storage.persistPendingCardCharge("req-5", "E", contexto("req-5", "E"))
        servicio.soltarLlaveSiEs("req-5")
        assertEquals(Cobrar(10000, false), decision("E"))
        assertEquals(emptyList<Any>(), CuentasCobradasAqui.leer(storage.cuentasCobradasAquiJson))
    }

    // MARK: - Saldo de regalo

    /** El resolutor del saldo de regalo confirma un cobro en duda sin pasar por la pantalla: la cuenta queda gris «de regalo». */
    @Test fun `el resolutor del saldo de regalo confirma un cobro y la cuenta no queda cobrable`() = runBlocking {
        guardadaEnLaCopia("F")
        val store = SolicitudesDeTarjetaStore(storage)
        val coordinador = CobroConSaldoCoordinator(GiftCardsRepository(OkHttpClient(), storage), store, storage,
            CobrosLocalesDeLaCuenta(dao, storage, store), CuentasCobradasAqui(storage))
        assertTrue(store.guardar(SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "k", venueId = "v1", creadaEn = 1, orderId = "F",
            cuerpo = """{ "idempotencyKey": "k", "amount": 40.00 }""", mandado = true)))
        assertEquals("en duda", Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), decision("F"))
        val pago = """{"paymentId":"p","orderId":"F","orderNumber":"7","amount":40,"tipAmount":0,"redeemed":[],"orderRemaining":60,"orderPaymentStatus":"PARTIAL"}"""
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"success":true,"data":{"found":true,"payment":$pago}}"""))
        assertTrue(coordinador.resolver("k") is DesenlaceDeCobro.Cobrado)
        assertEquals("la solicitud ya no está", emptyList<Any>(), store.delNegocio("v1"))
        assertEquals(Gris(TextosDeTarjetas.SIN_RED), decision("F"))
        assertNotNull(copia.cuenta("v1", "F"))
    }
}
