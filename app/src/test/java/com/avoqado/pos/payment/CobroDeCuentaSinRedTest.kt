package com.avoqado.pos.payment

import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.lan.TableLease
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.RazonSinRed
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.razonParaNoCobrar
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.payment.data.PayLaterRow
import com.avoqado.pos.payment.domain.DecisionSinRed.Cobrar
import com.avoqado.pos.payment.domain.DecisionSinRed.Gris
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import com.avoqado.pos.payment.presentation.CierreDeCuenta
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.payment.presentation.PaymentCompletion
import com.avoqado.pos.payment.presentation.LoQueSePinta
import com.avoqado.pos.payment.presentation.cierreDelCobroDesdeLaCopia
import com.avoqado.pos.payment.presentation.loQueSePinta
import com.avoqado.pos.payment.presentation.pideOfertaAlRecargar
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailPayment
import com.avoqado.pos.tables.data.PreBillBalance
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.UnknownHostException

/**
 * D12 (Tarea 12): «Cobrar $X en efectivo» sin red desde la copia de «Pedidos». La caja que abrió la cuenta cobra en todos los
 * planes; otra caja sólo en PREMIUM, con el candado coordinado del hub LAN. Todo lo demás, gris con su razón.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroDeCuentaSinRedTest {
    // El «disco» de memoria de la Tarea 4 (CopiaDeCuentasTest / PedidoSinRedTest).
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private var reloj = 1_000_000_000L
    private var copia = CopiaDeCuentas(cache).also { it.ahora = { reloj } }
    private var venue: String? = "v1"
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } answers { venue } }

    private var laAbrioAqui = true
    private val marcas = mockk<CuentasAbiertasAqui> { coEvery { laAbrioAqui(any(), any()) } answers { laAbrioAqui } }
    private val locales = mockk<CobrosLocalesDeLaCuenta> { coEvery { hayPendiente(any(), any()) } returns false }
    private val efectivo = mockk<CashPaymentRepository> { coEvery { huboEfectivoSubido(any()) } returns false }
    private var premium = false
    private val plan = mockk<PlanManager> { every { tieneAcreditada("OFFLINE_LAN_HUB") } answers { premium } }
    private val hubEncendido = MutableStateFlow(false)
    private var otraCaja = false
    private val hub = mockk<LanHubService>(relaxed = true) {
        every { enabled } returns hubEncendido
        every { hayOtraCajaDelNegocio() } answers { otraCaja }
    }
    private val filasDePagarDespues = MutableStateFlow<List<PayLaterRow>>(emptyList())
    private val pagarDespues = mockk<PayLaterQueue> { every { filas } returns filasDePagarDespues }
    private val repo = mockk<TableServiceRepository>()
    private val roles = mockk<RoleManager>(relaxed = true) { every { hasVenuePermission("payments:create") } returns true }

    private fun vm(efectivoReal: CashPaymentRepository = efectivo) = CobroDeCuentaViewModel(
        repo, storage, mockk<ConnectivityMonitor>(), roles, mockk(relaxed = true), locales,
        copiaDeCuentas = copia, cuentasAbiertasAqui = marcas, cashPaymentRepository = efectivoReal,
        planManager = plan, lanHub = hub, payLaterQueue = pagarDespues,
    )

    private fun cuenta(id: String = "o1", saldo: Int? = 6000, pagos: Int = 0, pago: String = "PENDING") = OrderSummary(
        id = id, esDeMesa = false, source = "AVOQADO_ANDROID", status = "PENDING", paymentStatus = pago,
        createdAt = "2026-11-10T18:00:00Z", saldoPendienteCents = saldo, pagosCompletados = pagos,
    )

    private suspend fun enLaCopia(vararg c: OrderSummary, en: String = "v1") = copia.alLeer(en, c.toList(), Lectura.SUELTA)

    private val concedido = LeaseOutcome.Granted(TableLease("cuenta:o1", "d1", "s1", "Yo", 1L, 99L))

    /** Otra caja de la sucursal, PREMIUM acreditado, con el hub encendido. */
    private fun otraCajaPremium() {
        laAbrioAqui = false
        premium = true
        otraCaja = true
        hubEncendido.value = true
    }

    // MARK: - La cadena real: JSON de la lista → disco → reinicio sin red → detalle de la copia → decisión

    private var respuestaDeLaLista: (Interceptor.Chain) -> Response = { throw UnknownHostException("sin red") }
    private var respuestaDelDetalle: (Interceptor.Chain) -> Response = { throw UnknownHostException("sin red") }
    private fun ordenes() = OrdersRepository(storage, OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        if (chain.request().url.encodedPath.endsWith("/orders")) respuestaDeLaLista(chain) else respuestaDelDetalle(chain)
    }).build(), copia)
    private fun http(chain: Interceptor.Chain, cuerpo: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x").body(cuerpo.toResponseBody()).build()
    private fun pagina(fila: String) = """{"data":[$fila],"meta":{"total":1,"page":1,"pageCount":1}}"""
    private fun filaA(pago: String, pagos: Int) =
        """{"id":"A","status":"PENDING","paymentStatus":"$pago","source":"AVOQADO_ANDROID","esDeMesa":false,"total":100,""" +
            """"createdAt":"2026-11-10T18:00:00Z","pagosCompletados":$pagos,"saldoPendienteCents":6000}"""

    private suspend fun recorridoReal(pago: String, pagos: Int): Any? {
        respuestaDeLaLista = { http(it, pagina(filaA(pago, pagos))) }
        assertEquals(true, ordenes().loadOrders(1, null, null).isSuccess)
        copia = CopiaDeCuentas(cache).also { it.ahora = { reloj } } // el proceso se reinició: misma copia en disco
        respuestaDeLaLista = { throw UnknownHostException("sin red") }
        val r = ordenes()
        r.loadOrderDetail("A")
        assertEquals("A", r.detalleDeLaCopia.value?.cuenta?.id)
        val m = vm()
        m.consultarSinRed("A")
        return m.sinRed.value
    }

    /** Codex r2 #1. */
    @Test fun `recorrido real 100 40 60 queda en gris por el abono`() = runTest {
        assertEquals(Gris(TextosDeCuentaSinRed.CON_ABONO), recorridoReal("PARTIAL", 1))
        disco.clear()
        assertEquals(Cobrar(6000, false), recorridoReal("PENDING", 0))
    }

    /** Codex r2 #2: `huboEfectivoSubido` del repositorio REAL, estricto. */
    @Test fun `copia de 100 y una fila SYNCED de esa cuenta quedan en gris`() = runTest {
        enLaCopia(cuenta("A", saldo = 10000))
        val dao = mockk<PendingPaymentDao> {
            coEvery { forVenue("v1") } returns listOf(PendingPaymentEntity(
                id = "p1", venueId = "v1", staffId = "s1", amountCents = 10000, tipCents = 0, method = "CASH",
                paymentType = "ORDER", orderId = "A", syncStatus = "SYNCED", q15ResultJson = """{"saldoCents":10000}""",
            ))
        }
        val m = vm(efectivoReal = CashPaymentRepository(storage, dao))
        m.consultarSinRed("A")
        assertEquals(Gris(TextosDeCuentaSinRed.YA_SUBIO_UN_COBRO), m.sinRed.value)
    }

    @Test fun `la abrio aqui sin hub se ofrece cobrar sin pedir candado`() = runTest {
        enLaCopia(cuenta())
        val m = vm()
        m.consultarSinRed("o1")
        assertEquals(Cobrar(6000, false), m.sinRed.value)
        assertEquals(Cobrar(6000, false), m.pedirCobroSinRed("o1"))
        coVerify(exactly = 0) { hub.acquire(any(), any(), any()) }
        coVerify(exactly = 0) { hub.acquireCoordinado(any(), any(), any()) }
    }

    @Test fun `otra caja PREMIUM pide candado coordinado al tocar y lo suelta al salir`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { hub.acquireCoordinado("cuenta:o1", any(), any()) } returns concedido
        val m = vm()
        m.consultarSinRed("o1")
        assertEquals("antes del toque no pide nada", Cobrar(6000, true), m.sinRed.value)
        coVerify(exactly = 0) { hub.acquireCoordinado(any(), any(), any()) }
        assertEquals(Cobrar(6000, true), m.pedirCobroSinRed("o1"))
        m.soltarCobroSinRed()
        coVerify { hub.release("cuenta:o1") }
    }

    @Test fun `otra caja PREMIUM con candado ajeno queda en gris con el nombre`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { hub.acquireCoordinado("cuenta:o1", any(), any()) } returns LeaseOutcome.Taken("Ana")
        val m = vm()
        assertEquals(Gris("La está cobrando Ana"), m.pedirCobroSinRed("o1"))
        assertEquals(Gris("La está cobrando Ana"), m.sinRed.value)
        m.soltarCobroSinRed()
        coVerify(exactly = 0) { hub.release(any()) }
    }

    /** Review Focus 4. */
    @Test fun `doble toque pide un solo candado`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val respuesta = CompletableDeferred<LeaseOutcome>()
        coEvery { hub.acquireCoordinado(any(), any(), any()) } coAnswers { respuesta.await() }
        val m = vm()
        val primero = async { m.pedirCobroSinRed("o1") }
        val segundo = async { m.pedirCobroSinRed("o1") }
        runCurrent()
        respuesta.complete(concedido)
        assertEquals(Cobrar(6000, true), primero.await())
        assertEquals(Cobrar(6000, true), segundo.await())
        coVerify(exactly = 1) { hub.acquireCoordinado(any(), any(), any()) }
    }

    @Test fun `si leer lo local falla queda en gris`() = runTest {
        enLaCopia(cuenta())
        coEvery { locales.hayPendiente(any(), any()) } throws IllegalStateException("disco")
        val m = vm()
        m.consultarSinRed("o1")
        assertEquals(Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), m.sinRed.value)

        coEvery { locales.hayPendiente(any(), any()) } returns false
        coEvery { efectivo.huboEfectivoSubido(any()) } throws IllegalStateException("disco")
        m.consultarSinRed("o1")
        assertEquals(Gris(TextosDeCuentaSinRed.YA_SUBIO_UN_COBRO), m.sinRed.value)
    }

    /** Ruling 4: una cortesía sin confirmar (fila REVISAR de «Pagar después») tampoco se cobra sin red. */
    @Test fun `una fila REVISAR de pagar despues de esa cuenta la deja en gris`() = runTest {
        enLaCopia(cuenta())
        filasDePagarDespues.value = listOf(PayLaterRow(
            id = "pay-later:1", venueId = "v1", staffId = "s1", customerId = "c1", customerName = "Ana", totalEsperadoCents = 6000,
            cuerpo = "{}", creadaEn = 1L, estado = PayLaterRow.Estado.REVISAR, orderId = "o1",
        ))
        val m = vm()
        m.consultarSinRed("o1")
        assertEquals(Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), m.sinRed.value)

        filasDePagarDespues.value = filasDePagarDespues.value.map { it.copy(venueId = "v2") }
        m.consultarSinRed("o1")
        assertEquals("la de otra sucursal no cuenta", Cobrar(6000, false), m.sinRed.value)
    }

    /** Codex r2 / Review Focus 2. */
    @Test fun `otra sucursal la cuenta de la copia de antes queda en gris`() = runTest {
        enLaCopia(cuenta("A"))
        venue = "v2"
        otraCajaPremium()
        val m = vm()
        m.consultarSinRed("A")
        assertEquals(Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED), m.sinRed.value)
        assertEquals(Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED), m.pedirCobroSinRed("A"))
        coVerify(exactly = 0) { hub.acquireCoordinado(any(), any(), any()) }
        coVerify(exactly = 0) { hub.acquire(any(), any(), any()) }
    }

    /** Codex r2: un cobro encolado nunca se da por pagado aunque el cálculo local diga 0. */
    @Test fun `un cobro encolado sale ABIERTA aunque el calculo local diga cero`() {
        val encolado = PaymentCompletion(splitType = "FULLPAYMENT", remainingBalanceCents = 0, saldoDelServidor = false, parteEnCola = "k1")
        assertEquals(CierreDeCuenta.ABIERTA, cierreDelCobroDesdeLaCopia(encolado))
        assertEquals(CierreDeCuenta.PAGADA, cierreDelCobroDesdeLaCopia(encolado.copy(saldoDelServidor = true, parteEnCola = null)))
        // La cuenta creció a $300 en otra caja y se pagaron $100.
        assertEquals(CierreDeCuenta.ABIERTA, cierreDelCobroDesdeLaCopia(
            encolado.copy(saldoDelServidor = true, remainingBalanceCents = 20000, parteEnCola = null)))
    }

    /** Codex r3 H1: la otra entrada del detalle también pone la copia al día. */
    @Test fun `el detalle que se consulta para cobrar tambien deja la copia al dia`() = runTest {
        enLaCopia(cuenta("A", saldo = 10000))
        coEvery { repo.getOrderDetail("v1", "A") } returns Result.success(OrderDetail("A", esDeMesa = false, status = "PENDING",
            paymentStatus = "PARTIAL", total = 100.0, payments = listOf(OrderDetailPayment("p1", 40.0, status = "COMPLETED")),
            preBillBalance = PreBillBalance(10000, 4000, 6000)))
        val m = vm()
        m.cargar("A")
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
        assertEquals(6000, copia.cuenta("v1", "A")!!.cuenta.saldoPendienteCents)

        coEvery { repo.getOrderDetail("v1", "A") } returns Result.failure(
            retrofit2.HttpException(retrofit2.Response.error<Any>(404, "".toResponseBody())))
        m.cargar("A")
        assertNull(copia.cuenta("v1", "A"))
    }

    /** Codex r3 H3. */
    @Test fun `caja ajena perder el candado antes de recibir el efectivo la deja en gris sin cobrar`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { hub.acquireCoordinado("cuenta:o1", any(), any()) } returns concedido
        coEvery { hub.candadoVigente("cuenta:o1") } returns false
        val m = vm()
        assertEquals(Cobrar(6000, true), m.pedirCobroSinRed("o1"))
        assertEquals(TextosDeCuentaSinRed.CANDADO_PERDIDO, m.revalidarCobroSinRed("o1", 6000))
        assertEquals(Gris(TextosDeCuentaSinRed.CANDADO_PERDIDO), m.sinRed.value)
        coVerify { hub.release("cuenta:o1") }

        coEvery { hub.candadoVigente("cuenta:o1") } returns true
        val otra = vm()
        assertEquals(Cobrar(6000, true), otra.pedirCobroSinRed("o1"))
        assertNull(otra.revalidarCobroSinRed("o1", 6000))
    }

    /** Ruling 5: tras el rechazo de la revalidación, volver a la cuenta deja su gris (no se vuelve a ofrecer en el acto). */
    @Test fun `tras el rechazo antes del efectivo la cuenta se queda en gris al volver`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { hub.acquireCoordinado("cuenta:o1", any(), any()) } returns concedido
        coEvery { hub.candadoVigente("cuenta:o1") } returns false
        val m = vm()
        m.pedirCobroSinRed("o1")
        m.revalidarCobroSinRed("o1", 6000)
        assertEquals("no se refresca la cuenta", false, m.alVolverDelCobroSinRed("o1"))
        assertEquals(Gris(TextosDeCuentaSinRed.CANDADO_PERDIDO), m.sinRed.value)

        // Un cobro que sí siguió: se vuelve a decidir y se refresca.
        coEvery { hub.candadoVigente("cuenta:o1") } returns true
        m.pedirCobroSinRed("o1")
        assertNull(m.revalidarCobroSinRed("o1", 6000))
        assertEquals(true, m.alVolverDelCobroSinRed("o1"))
        assertEquals(Cobrar(6000, true), m.sinRed.value)
    }

    @Test fun `la caja que abrio la cuenta no depende del candado para cobrar`() = runTest {
        enLaCopia(cuenta())
        hubEncendido.value = true
        coEvery { hub.acquire("cuenta:o1", any(), any()) } returns concedido
        coEvery { hub.candadoVigente(any()) } throws IllegalStateException("no se pregunta")
        val m = vm()
        assertEquals(Cobrar(6000, true), m.pedirCobroSinRed("o1"))
        assertNull(m.revalidarCobroSinRed("o1", 6000))
        coVerify(exactly = 0) { hub.candadoVigente(any()) }
    }

    @Test fun `si la copia vence entre preparar y recibir el efectivo no se cobra`() = runTest {
        enLaCopia(cuenta())
        val m = vm()
        assertEquals(Cobrar(6000, false), m.pedirCobroSinRed("o1"))
        reloj += 24 * 60 * 60 * 1000L + 1
        assertEquals(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED, m.revalidarCobroSinRed("o1", 6000))
    }

    /** Codex r3 H4. */
    @Test fun `cortar la red con la cuenta a la vista habilita el cobro en efectivo`() = runTest {
        respuestaDeLaLista = { http(it, pagina(filaA("PENDING", 0))) }
        respuestaDelDetalle = { http(it, """{"success":true,"order":{"id":"A","status":"PENDING","paymentStatus":"PENDING",""" +
            """"items":[],"preBillBalance":{"orderTotalCents":6000,"totalPaidCents":0,"remainingBalanceCents":6000}}}""") }
        val r = ordenes()
        r.loadOrders(1, null, null)
        r.loadOrderDetail("A")
        assertNull(r.detalleDeLaCopia.value)
        r.alPerderLaRed()
        assertEquals(copia.cuenta("v1", "A"), r.detalleDeLaCopia.value)

        val m = vm()
        m.consultarSinRed("A")
        assertEquals("la caja que la abrió", Cobrar(6000, false), m.sinRed.value)

        otraCajaPremium()
        m.consultarSinRed("A")
        assertEquals("otra caja PREMIUM con otra caja de la sucursal", Cobrar(6000, true), m.sinRed.value)

        premium = false
        m.consultarSinRed("A")
        assertEquals(Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), m.sinRed.value)
    }

    // MARK: - Ronda 1 de la revisión

    /** I2: la cajera sale durante «Revisando…» (o vuelve la red y el botón sale de pantalla): el candado concedido tarde se suelta. */
    @Test fun `salir durante Revisando suelta el candado concedido y no lo guarda`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val respuesta = CompletableDeferred<LeaseOutcome>()
        coEvery { hub.acquireCoordinado("cuenta:o1", any(), any()) } coAnswers { respuesta.await() }
        val m = vm()
        val toque = launch { m.pedirCobroSinRed("o1") }
        runCurrent()
        toque.cancel()
        respuesta.complete(concedido)
        toque.join()
        coVerify(exactly = 1) { hub.release("cuenta:o1") }
        m.soltarCobroSinRed() // no quedó guardada: no hay nada más que soltar
        coVerify(exactly = 1) { hub.release("cuenta:o1") }
    }

    /** I1: una recarga PAGADA o CANCELADA a medio cobro no desmonta el cobro abierto (la cajera ve su resultado y su cambio). */
    @Test fun `la cuenta pasa a PAGADA a medio cobro y el cobro sigue montado`() {
        val abierta = com.avoqado.pos.orders.data.model.OrderDetail(id = "o1", status = "PENDING", paymentStatus = "PENDING", esDeMesa = false)
        assertEquals(LoQueSePinta(acciones = true, cobroEnLinea = false, cobroSinRed = null), loQueSePinta(abierta, false, null))
        val pagada = abierta.copy(paymentStatus = "PAID")
        assertEquals(LoQueSePinta(acciones = false, cobroEnLinea = true, cobroSinRed = null), loQueSePinta(pagada, true, null))
        assertEquals(LoQueSePinta(acciones = false, cobroEnLinea = false, cobroSinRed = 6000), loQueSePinta(pagada, false, 6000))
        val cancelada = abierta.copy(status = "CANCELLED")
        assertEquals(LoQueSePinta(acciones = false, cobroEnLinea = false, cobroSinRed = 6000), loQueSePinta(cancelada, false, 6000))
        assertEquals("Mesas sigue sin acciones aquí", false, loQueSePinta(abierta.copy(esDeMesa = true), false, null).acciones)
    }

    /** I1: con un cobro abierto, recargar la cuenta no vuelve a pedir la oferta (vaciaría la foto del cobro en línea a medio cobro). */
    @Test fun `con un cobro en linea abierto una recarga no vuelve a pedir la oferta`() {
        assertTrue("sin cobro abierto, como siempre", pideOfertaAlRecargar(enLinea = true, cobroAbierto = false))
        assertFalse(pideOfertaAlRecargar(enLinea = true, cobroAbierto = true))
        assertFalse("sin red nunca se pide", pideOfertaAlRecargar(enLinea = false, cobroAbierto = false))
    }

    /** T12b (O3): vuelve la red a medio cobro y la recarga sube el saldo; el carrito trae el de antes: no se cobra, gris con el nuevo. */
    @Test fun `si la copia ya dice otro saldo que el del carrito no se cobra`() = runTest {
        enLaCopia(cuenta(saldo = 6000))
        val m = vm()
        assertEquals(Cobrar(6000, false), m.pedirCobroSinRed("o1"))
        assertNull("mismo saldo: sigue", m.revalidarCobroSinRed("o1", 6000))
        enLaCopia(cuenta(saldo = 8000))
        assertEquals(TextosDeCuentaSinRed.saldoCambio(8000), m.revalidarCobroSinRed("o1", 6000))
        assertEquals(Gris(TextosDeCuentaSinRed.saldoCambio(8000)), m.sinRed.value)
        assertEquals("al volver se queda el gris", false, m.alVolverDelCobroSinRed("o1"))
        m.consultarSinRed("o1") // vuelve a abrir la cuenta: el botón trae el monto nuevo
        assertEquals(Cobrar(8000, false), m.sinRed.value)
    }

    /** M7 (Codex r3 H1): una respuesta de detalle superada por una consulta posterior no escribe en la copia. */
    @Test fun `una respuesta de detalle superada por su turno no escribe en la copia`() = runTest {
        enLaCopia(cuenta("A", saldo = 10000))
        val vieja = CompletableDeferred<Result<OrderDetail>>()
        val respuestas = ArrayDeque(listOf(vieja, CompletableDeferred(Result.failure<OrderDetail>(java.io.IOException("sin red")))))
        coEvery { repo.getOrderDetail("v1", "A") } coAnswers { respuestas.removeFirst().await() }
        val m = vm()
        val primera = launch { m.cargar("A") }
        runCurrent()
        m.cargar("A") // la que manda: no trae evidencia
        vieja.complete(Result.success(OrderDetail("A", esDeMesa = false, status = "PENDING", paymentStatus = "PARTIAL",
            payments = listOf(OrderDetailPayment("p1", 40.0, status = "COMPLETED")), preBillBalance = PreBillBalance(10000, 4000, 6000))))
        primera.join()
        val fila = copia.cuenta("v1", "A")!!.cuenta
        assertEquals(10000, fila.saldoPendienteCents)
        assertNull(razonParaNoCobrar(fila))
    }
}
