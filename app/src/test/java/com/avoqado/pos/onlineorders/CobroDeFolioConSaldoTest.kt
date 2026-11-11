package com.avoqado.pos.onlineorders

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.giftcards.data.DesenlaceDeCobro
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobroConSaldoCoordinator
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.giftcards.data.GiftCardsRepository
import com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta
import com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore
import com.avoqado.pos.giftcards.domain.FilaDeLaCaja
import com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.onlineorders.data.CobroDeLaBandeja
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.BeneficioDelFolio
import com.avoqado.pos.onlineorders.domain.ClienteDelFolio
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.SaldoDelPedido
import com.avoqado.pos.onlineorders.presentation.CobroDePedido
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.beneficiosDeMas
import com.avoqado.pos.onlineorders.presentation.carritoDelCobro
import com.avoqado.pos.onlineorders.presentation.cobradosConTarjetaEnLaLista
import com.avoqado.pos.onlineorders.presentation.lineaDeCuenta
import com.avoqado.pos.payment.PaymentFlowConSaldoTest
import com.avoqado.pos.payment.domain.OrigenDelCobro
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tienda híbrida, Fase A parte 2 (A1): cobrar un folio de «Por pagar» con saldo o tarjeta de regalo. El cobro es el de siempre
 * (`PaymentFlowViewModel` con el coordinador REAL y un servidor HTTP falso, el arnés de `PaymentFlowConSaldoTest`); lo nuevo es que un
 * folio ofrece la fila (T2, sobre su cuenta), que abierto sin red sale en gris y que un pedido de plataforma o de «Mi equipo» no la
 * ofrece. Y la bandeja: un cobro con saldo de esta caja que la lista guardada todavía no cuenta frena otro «Cobrar».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroDeFolioConSaldoTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var f: PaymentFlowConSaldoTest
    private lateinit var server: MockWebServer

    @Before fun preparar() {
        f = PaymentFlowConSaldoTest()
        f.setUp()
        server = PaymentFlowConSaldoTest::class.java.getDeclaredField("server").apply { isAccessible = true }.get(f) as MockWebServer
    }

    @After fun cerrar() = f.tearDown()

    /** El cobro que abre la bandeja: el carrito de un renglón «Folio W7K3P» por el saldo, sobre la cuenta `f1`. */
    private fun cobroDelFolio(
        saldo: Int = 13400,
        origen: OrigenDelCobro = OrigenDelCobro.PEDIDO_EN_LINEA,
        esFolio: Boolean = true,
    ): PaymentFlowViewModel {
        f.cart = carritoDelCobro(CobroDePedido(orderId = "f1", orderNumber = "W7K3P", saldoCents = saldo, origen = origen, esFolio = esFolio))
        return f.vm("f1").also {
            it.startPaymentFlow(f.cart, resumeOrderId = "f1", origen = origen, cobroDeLaBandeja = CobroDeLaBandeja(saldo.toLong(), esFolio))
        }
    }

    private fun cobrado(monto: String, resta: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"data":{"paymentId":"p","orderId":"f1","orderNumber":"W7K3P","amount":$monto,"tipAmount":0,"redeemed":[],"orderRemaining":$resta,"orderPaymentStatus":"${if (resta == "0") "PAID" else "PARTIAL"}"}}""")

    private fun solicitudes() = SolicitudesDeTarjeta.leer(f.storage.giftCardRequestsJson)

    /** La copia guardada de «Por pagar» que ve la bandeja; sin red, la bandeja sólo tiene ésta. */
    private var guardada: ListaPorPagar? = null
    private val repoBandeja = mockk<OnlineOrdersRepository>().also { repo ->
        coEvery { repo.inbox(any(), any(), any()) } returns
            Result.success(Inbox(Instant.parse("2026-11-06T18:10:00Z"), 0, 0, 0, emptyList(), storeOff = false, truncated = false, nextCursor = null))
        coEvery { repo.saldoDelPedido("f1") } returns Result.failure(IOException("sin red"))
    }

    /** La bandeja de verdad, con las mismas marcas y solicitudes en disco que el cobro (sin red al leer la lista). */
    private fun bandeja() = OnlineOrdersViewModel(
        repositorio = repoBandeja, permisoActualizar = { true }, permisoCancelar = { true }, tienePlan = { true },
        hayPedidosAbiertos = { true }, ventanaALaVista = MutableStateFlow(true),
        leerPorPagar = { _, _, _ -> Result.failure(IOException("sin red")) }, foliosGuardados = { guardada },
        ahoraMs = { Instant.parse("2026-11-06T18:10:00Z").toEpochMilli() },
        foliosConTarjeta = FoliosCobradosConTarjeta(f.storage), venueActual = { "a" },
        saldoEnDuda = { SolicitudesDeTarjeta.cuentaTrabada(f.storage.giftCardRequestsJson, "a", it) },
    )
    private fun marcas() = FoliosCobradosConTarjeta(f.storage).marcas()

    @Test
    fun `un folio con red ofrece la tarjeta de regalo y cobra por pay-gift-card de su cuenta`() = runTest {
        val vm = cobroDelFolio()

        val fila = vm.conductaDeSaldo()
        assertEquals("sobre la cuenta del folio (T2, giftcards/domain/TablaDeLaCaja.kt)", FilaDeLaCaja.T2, fila.fila)
        assertTrue(fila.visible && fila.habilitada)

        server.enqueue(cobrado("134", "0"))
        val r = vm.cobrarConSaldo(f.identified, 13400)

        assertTrue("$r", r is DesenlaceDeCobro.Cobrado)
        val pedido = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/a/orders/f1/pay-gift-card", pedido.path)
        val cuerpo = Json.parseToJsonElement(pedido.body.readUtf8()).jsonObject
        assertEquals(134.0, cuerpo.getValue("amount").jsonPrimitive.double, 0.0)
        assertEquals(0.0, cuerpo.getValue("tip").jsonPrimitive.double, 0.0)
        assertTrue("confirmado: la llave sale del aparato", solicitudes().isEmpty())
        assertFalse("el carrito de la bandeja no se vacía (no es una venta de mostrador)", f.cart.isEmpty)
        // La marca «aquí ya se cobró con saldo»: una lista vieja ya no ofrece efectivo encima.
        val marca = marcas().single()
        assertEquals("f1", marca.orderId)
        assertEquals(13400L, marca.montoCents)
        assertEquals("lo que debía antes = lo que quedó + lo cobrado", 13400L, marca.saldoAntesCents)
        assertEquals("a", marca.venueId)
        assertTrue(marca.esFolio && marca.conSaldo)
    }

    @Test
    fun `abierto sin red (PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO) la fila sale en gris T0 Sin red`() = runTest {
        // La red ya volvió con la pantalla abierta, pero el saldo que cobraría es el de la lista guardada: sigue en gris.
        val vm = cobroDelFolio(origen = OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO)

        val fila = vm.conductaDeSaldo()
        assertEquals(FilaDeLaCaja.T0, fila.fila)
        assertTrue("se ve, en gris", fila.visible)
        assertFalse(fila.habilitada)
        assertEquals(TextosDeTarjetas.SIN_RED, fila.motivo)

        val r = vm.cobrarConSaldo(f.identified, 13400)
        assertTrue("$r", r is DesenlaceDeCobro.NoSalio)
        assertEquals("no sale nada", 0, server.requestCount)
        assertTrue(solicitudes().isEmpty())
    }

    @Test
    fun `un pedido de plataforma no la ofrece, ni uno de Mi equipo`() = runTest {
        for ((origen, esFolio) in listOf(
            OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO to false,
            OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO_SALDO_GUARDADO to false,
            OrigenDelCobro.PEDIDO_EN_LINEA to false,
        )) {
            val vm = cobroDelFolio(origen = origen, esFolio = esFolio)
            val fila = vm.conductaDeSaldo()
            assertFalse("$origen: la fila no sale", fila.visible)
            assertTrue("$origen", vm.cobrarConSaldo(f.identified, 13400) is DesenlaceDeCobro.NoSalio)
        }
        assertEquals(0, server.requestCount)
        assertTrue(OrigenDelCobro.NORMAL.ofreceSaldo(esFolio = false))
        assertFalse(OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO.ofreceSaldo(esFolio = true))
    }

    @Test
    fun `saldo parcial y luego efectivo saldan el folio`() = runTest {
        // 1) $50 con saldo sobre un folio de $134: quedan $84 en su cuenta, sin «venta por terminar».
        val vm = cobroDelFolio()
        server.enqueue(cobrado("50", "84"))
        val r = vm.cobrarConSaldo(f.identified, 5000)
        assertTrue("$r", r is DesenlaceDeCobro.Cobrado)
        assertEquals(84.0, (r as DesenlaceDeCobro.Cobrado).cobro.orderRemaining, 0.0)
        assertTrue("un folio no deja «venta por terminar»", solicitudes().none { it.porTerminar })
        val marca = marcas().single()
        assertEquals(5000L, marca.montoCents)
        assertEquals(13400L, marca.saldoAntesCents)

        // 2) La red se cae y la bandeja sólo tiene la copia de antes del cobro ($134): «Cobrar» no abre otro cobro.
        guardada = lista(folio())
        val bandeja = bandeja()
        bandeja.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull("con la lista vieja se cobrarían $134 otra vez", bandeja.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_SALDO, bandeja.aviso.value)
        assertTrue(
            "un pago parcial NO pasa a «Por entregar»: todavía debe",
            cobradosConTarjetaEnLaLista(marcas(), emptyMap(), guardada).isEmpty(),
        )

        // 3) Codex r1 #4: con red manda el saldo del servidor aunque la marca siga (la copia aún no la suelta): se cobran los $84.
        coEvery { repoBandeja.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(8400L, estadoDeLaCuenta = "PENDING"))
        bandeja.cobrarFolio(folio(falta = 8400, pago = "PARTIAL"))
        advanceUntilIdle()
        val resto = bandeja.cobroAbierto.value!!
        assertEquals(OrigenDelCobro.PEDIDO_EN_LINEA, resto.origen)
        assertEquals("lo que falta según el servidor, nunca el total", 8400, resto.saldoCents)
        bandeja.cobroTerminado()
        advanceUntilIdle()

        // 4) La copia guardada ya cuenta el saldo ($84): suelta la marca.
        guardada = lista(folio(falta = 8400, pago = "PARTIAL"))
        bandeja.descubrirPorPagar()
        advanceUntilIdle()
        assertTrue("la copia ya cuenta el pago", marcas().isEmpty())
    }

    @Test
    fun `respuesta perdida - la bandeja no abre otro cobro y al volver el resolutor deja la marca sin ofrecer Continuar`() = runTest {
        // El POST salió y la respuesta se perdió (o la app murió): la llave queda guardada y mandada ANTES de la red.
        val vm = cobroDelFolio()
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(DesenlaceDeCobro.Incierto, vm.cobrarConSaldo(f.identified, 5000))
        val pendiente = solicitudes().single()
        assertTrue(pendiente.mandado && pendiente.deFolio && !pendiente.porTerminar)

        // Codex r1 #1: con un cobro con saldo sin confirmar, la bandeja no abre otro cobro (ni con red ni con la lista guardada).
        guardada = lista(folio())
        coEvery { repoBandeja.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(13400L, estadoDeLaCuenta = "PENDING"))
        val bandeja = bandeja()
        bandeja.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(bandeja.cobroAbierto.value)
        assertEquals(TextosDeTarjetas.COMPROBANDO_COBRO, bandeja.aviso.value)

        // Vuelve la red: el resolutor pregunta por la llave (nunca reenvía a ciegas), confirma, deja la marca y suelta la llave.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
            """{"success":true,"data":{"found":true,"payment":{"paymentId":"p","orderId":"f1","orderNumber":"W7K3P","amount":50,"tipAmount":0,"redeemed":[],"orderRemaining":84,"orderPaymentStatus":"PARTIAL"}}}"""))
        val red = mockk<ConnectivityMonitor> {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val store = SolicitudesDeTarjetaStore(f.storage)
        val dao = mockk<PendingPaymentDao>()
        coEvery { dao.forVenue(any()) } returns emptyList()
        val repo = GiftCardsRepository(OkHttpClient(), f.storage)
        val resolutor = ResolutorDeSolicitudesDeTarjeta(repo, store, f.storage, red,
            CobroConSaldoCoordinator(repo, store, f.storage, CobrosLocalesDeLaCuenta(dao, f.storage, store)))
        resolutor.resolverAhora()
        server.takeRequest(1, TimeUnit.SECONDS) // el POST perdido
        assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertTrue("la llave se suelta", solicitudes().isEmpty())
        val marca = marcas().single()
        assertTrue(marca.conSaldo)
        assertEquals(5000L, marca.montoCents)
        assertEquals(13400L, marca.saldoAntesCents)
        // Codex r1 #2: el aviso «Cobro con saldo recuperado» sabe que es un folio: lo que falta se cobra en «Por pagar», sin «Continuar».
        assertTrue(resolutor.recuperados.value.single().deFolio)
    }

    @Test
    fun `si el disco no acepta la marca la llave se queda y sigue frenando otro cobro`() = runTest {
        // Codex r1 #3: confirmado el saldo, si la marca no se guarda, soltar la llave dejaría el dinero sin protección.
        val store = SolicitudesDeTarjetaStore(f.storage)
        val dao = mockk<PendingPaymentDao>()
        coEvery { dao.forVenue(any()) } returns emptyList()
        val coordinador = CobroConSaldoCoordinator(GiftCardsRepository(OkHttpClient(), f.storage), store, f.storage,
            CobrosLocalesDeLaCuenta(dao, f.storage, store), FoliosCobradosConTarjeta({ null }, { false }))
        server.enqueue(cobrado("134", "0"))

        val r = coordinador.cobrar("a", "f1", f.identified.prueba, 13400, 0, deFolio = true)

        assertTrue("$r", r is DesenlaceDeCobro.Cobrado)
        assertTrue("la llave sigue: «Comprobando…» frena efectivo y tarjeta", coordinador.cuentaTrabada("f1"))
        assertTrue(SolicitudesDeTarjeta.cuentaTrabada(f.storage.giftCardRequestsJson, "a", "f1"))
    }

    @Test
    fun `un folio que vencio con la hoja abierta dice que se cobra como venta normal`() = runTest {
        val vm = cobroDelFolio()
        vm.startPaymentFlow(f.cart, resumeOrderId = "f1", origen = OrigenDelCobro.PEDIDO_EN_LINEA,
            cobroDeLaBandeja = CobroDeLaBandeja(13400, true), textoSiLaCuentaSeCancelo = { TextosPedidosEnLinea.FOLIO_VENCIDO })
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("""{"success":false,"code":"ORDER_CANCELLED_NO_NEW_CHARGE","message":"Esta cuenta está cancelada, abre una nueva."}"""))

        val r = vm.cobrarConSaldo(f.identified, 13400)

        assertTrue("$r", r is DesenlaceDeCobro.Rechazado)
        assertEquals(TextosPedidosEnLinea.FOLIO_VENCIDO, (r as DesenlaceDeCobro.Rechazado).texto)
        assertTrue("nada se cobró: la llave se suelta", solicitudes().isEmpty())
        assertTrue("sin cobro no hay marca", marcas().isEmpty())
    }

    @Test
    fun `la tarjeta del folio muestra cliente y beneficios cuando vienen y sin ellos como hoy`() {
        val conCuenta = folio().copy(
            cliente = ClienteDelFolio("c1", "Ana L."),
            beneficios = listOf(BeneficioDelFolio("Café gratis (premio de Ana)", 6500)),
            beneficiosTotal = 3,
        )
        assertEquals("Con cuenta: Ana L. · suma su sello al pagar", lineaDeCuenta(conCuenta, porEntregar = false, sellosActivos = true))
        assertEquals("sin sellos prendidos no se promete el sello", "Con cuenta: Ana L.", lineaDeCuenta(conCuenta, porEntregar = false, sellosActivos = false))
        assertEquals("ya pagado no se promete nada", "Con cuenta: Ana L.", lineaDeCuenta(conCuenta, porEntregar = true, sellosActivos = true))
        assertEquals("−$65.00", TextosPedidosEnLinea.montoDeBeneficio("$65.00"))
        assertEquals(2, beneficiosDeMas(conCuenta))
        assertEquals("y 2 más", TextosPedidosEnLinea.beneficiosDeMas(2))

        val deHoy = folio()
        assertNull(lineaDeCuenta(deHoy, porEntregar = false, sellosActivos = true))
        assertTrue(deHoy.beneficios.isEmpty())
        assertEquals(0, beneficiosDeMas(deHoy))
    }

    private fun folio(falta: Long = 13400, pago: String = "PENDING") = OnlineOrder(
        orderId = "f1", onlineOrderId = "oo-f1", orderNumber = "W7K3P", status = OnlineOrderStatus.PENDING_ACCEPTANCE,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = pago, customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-06T18:00:00Z"), acceptDeadlineAt = Instant.parse("2026-11-06T18:30:00Z"), estimatedReadyAt = null,
        totalCents = 13400, remainingCents = falta, needsPrint = false, items = emptyList(), requiresAcceptance = false, channel = "QR",
    )

    private fun lista(vararg porPagar: OnlineOrder) =
        ListaPorPagar(true, true, 30, ConteosPorPagar(porPagar.size, 0), porPagar.toList(), emptyList(), CursoresPorPagar(null, null))
}

/** El folio con su clienta y sus beneficios, tal como los manda el SERVIDOR (S14) en `to-pay`, contra un servidor HTTP de verdad. */
class FolioConCuentaRepositorioTest {
    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private val cache = mockk<PayloadCache>(relaxed = true)
    private lateinit var repo: OnlineOrdersRepository

    @Before fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), cache)
    }

    @After fun apagar() {
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    @Test
    fun `cliente, beneficios y beneficiosTotal son opcionales en cada folio`() = runTest {
        fun folio(id: String, extra: String) =
            """{"orderId":"$id","onlineOrderId":"oo-$id","orderNumber":"W7K3P","status":"PENDING_ACCEPTANCE","paymentMode":"PAY_AT_PICKUP",
            "customerName":"Ana","customerPhone":null,"note":null,"createdAt":"2026-11-06T18:00:00.000Z","acceptDeadlineAt":null,
            "estimatedReadyAt":null,"total":69,"remaining":69,"paymentStatus":"PENDING","requiresAcceptance":false,"needsPrint":false,"items":[]$extra}"""
        server.enqueue(MockResponse().setBody(
            """{"success":true,"data":{"showTile":true,"pollSeconds":30,"counts":{"toPay":3,"toDeliver":0},"toPay":[
            ${folio("f1", ""","cliente":{"id":"c1","nombre":"Ana L."},"beneficios":[{"nombre":"Café gratis (premio de Ana)","monto":65},{"nombre":"10 %","monto":6.9}],"beneficiosTotal":12""")},
            ${folio("f2", ""","cliente":null,"beneficios":[],"beneficiosTotal":0""")},
            ${folio("f3", "")}],
            "toDeliver":[],"nextCursors":{"toPay":null,"toDeliver":null}}}""",
        ))
        val porPagar = repo.porPagar(conLatido = false).getOrThrow().porPagar

        val ana = porPagar.single { it.orderId == "f1" }
        assertEquals(ClienteDelFolio("c1", "Ana L."), ana.cliente)
        assertEquals(listOf(BeneficioDelFolio("Café gratis (premio de Ana)", 6500), BeneficioDelFolio("10 %", 690)), ana.beneficios)
        assertEquals(12, ana.beneficiosTotal)
        for (id in listOf("f2", "f3")) {
            val viejo = porPagar.single { it.orderId == id }
            assertNull("$id: sin cuenta (o SERVIDOR viejo) no dice nada", viejo.cliente)
            assertTrue(viejo.beneficios.isEmpty())
            assertEquals(0, viejo.beneficiosTotal)
        }
    }
}
