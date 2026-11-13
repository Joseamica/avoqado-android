package com.avoqado.pos.payment

import com.avoqado.pos.core.data.lan.CuentaEnElRegistro
import com.avoqado.pos.core.data.lan.CuentasEntreCajas
import com.avoqado.pos.core.data.lan.DuenoDeCuentas
import com.avoqado.pos.core.data.lan.EstadoDeCuenta
import com.avoqado.pos.core.data.lan.Instante
import com.avoqado.pos.core.data.lan.Respuesta
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
import com.avoqado.pos.payment.domain.DecisionSinRed.NoConfirmo
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.payment.presentation.LoQueSePinta
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
 * planes; otra caja sólo en PREMIUM, con permiso de la caja que la abrió (Opción D). Todo lo demás, gris con su razón.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroDeCuentaSinRedTest {
    // El «disco» de memoria de la Tarea 4 (CopiaDeCuentasTest / PedidoSinRedTest).
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var falloAlGuardar = false
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private var reloj = 1_000_000_000L
    private var copia = CopiaDeCuentas(cache).also { it.ahora = { reloj }; it.io = kotlinx.coroutines.Dispatchers.Unconfined }
    private var venue: String? = "v1"
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } answers { venue } }

    private var laAbrioAqui = true
    private val marcas = mockk<CuentasAbiertasAqui> { coEvery { laAbrioAqui(any(), any()) } answers { laAbrioAqui } }
    private val locales = mockk<CobrosLocalesDeLaCuenta> { coEvery { hayPendiente(any(), any()) } returns false }
    private val efectivo = mockk<CashPaymentRepository> { coEvery { huboEfectivoSubido(any()) } returns false }
    private var premium = false
    private val plan = mockk<PlanManager> { every { tieneAcreditada("OFFLINE_LAN_HUB") } answers { premium } }
    private var otraCaja = false
    private val concedido = CuentasEntreCajas.DelDueno(Respuesta.Concedida, "caja-1", "Caja 1", 0L)
    private var respuestaDelDueno: suspend () -> CuentasEntreCajas.DelDueno = { concedido }
    private val entre = mockk<CuentasEntreCajas>(relaxed = true) {
        every { hayOtraCajaConD() } answers { otraCaja }
        coEvery { cajaQueLaAbrio(any(), any()) } returns null
        coEvery { apartar(any(), any(), any(), any(), any()) } coAnswers { respuestaDelDueno() }
    }
    private var registro: CuentaEnElRegistro? = null
    private var puedeDeclarar = false
    private val dueno = mockk<DuenoDeCuentas>(relaxed = true) {
        coEvery { estadoDe(any(), any()) } answers { registro }
        every { puedeDeclararNoSeCobro(any()) } answers { puedeDeclarar }
        every { deviceId } returns "caja-yo"
        coEvery { noSeCobro(any(), any(), any()) } answers { registro = null; puedeDeclarar = false; true }
    }
    private val filasDePagarDespues = MutableStateFlow<List<PayLaterRow>>(emptyList())
    private val pagarDespues = mockk<PayLaterQueue> { every { filas } returns filasDePagarDespues }
    private val repo = mockk<TableServiceRepository>()
    private val roles = mockk<RoleManager>(relaxed = true) { every { hasVenuePermission("payments:create") } returns true }

    private fun vm(efectivoReal: CashPaymentRepository = efectivo) = CobroDeCuentaViewModel(
        repo, storage, mockk<ConnectivityMonitor>(), roles, mockk(relaxed = true), locales,
        copiaDeCuentas = copia, cuentasAbiertasAqui = marcas, cashPaymentRepository = efectivoReal,
        planManager = plan, cuentasEntreCajas = entre, duenoDeCuentas = dueno, payLaterQueue = pagarDespues,
    )

    private fun cuenta(id: String = "o1", saldo: Int? = 6000, pagos: Int = 0, pago: String = "PENDING") = OrderSummary(
        id = id, esDeMesa = false, source = "AVOQADO_ANDROID", status = "PENDING", paymentStatus = pago,
        createdAt = "2026-11-10T18:00:00Z", saldoPendienteCents = saldo, pagosCompletados = pagos,
    )

    private suspend fun enLaCopia(vararg c: OrderSummary, en: String = "v1") = copia.alLeer(en, c.toList(), Lectura.SUELTA)

    /** Otra caja de la sucursal, PREMIUM acreditado, con otra caja que anuncia `d=1`. */
    private fun otraCajaPremium() {
        laAbrioAqui = false
        premium = true
        otraCaja = true
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
        copia = CopiaDeCuentas(cache).also { it.ahora = { reloj }; it.io = kotlinx.coroutines.Dispatchers.Unconfined } // el proceso se reinició: misma copia en disco
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
        assertEquals(Cobrar(6000), recorridoReal("PENDING", 0))
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

    @Test fun `la abrio aqui se ofrece cobrar y al tocar la aparta en su propio registro`() = runTest {
        enLaCopia(cuenta())
        val m = vm()
        m.consultarSinRed("o1")
        assertEquals(Cobrar(6000), m.sinRed.value)
        coVerify(exactly = 0) { entre.apartar(any(), any(), any(), any(), any()) }
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        coVerify(exactly = 1) { entre.apartar("v1", "o1", any(), any(), enLinea = false) }
        assertNull("sin «Avisándole…»: la pregunta es a sí misma", m.avisandoA.value)
    }

    @Test fun `otra caja PREMIUM le pide permiso a la que la abrió al tocar y lo suelta al salir`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val m = vm()
        m.consultarSinRed("o1")
        assertEquals("antes del toque no pregunta nada", Cobrar(6000), m.sinRed.value)
        coVerify(exactly = 0) { entre.apartar(any(), any(), any(), any(), any()) }
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        m.soltarCobroSinRed()
        io.mockk.verify(exactly = 1) { entre.soltarApartado("o1") }
    }

    @Test fun `otra caja PREMIUM - si otra la está cobrando, gris con quién y dónde, y no hay nada que soltar`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(Respuesta.Ocupada("Ana", "Caja 2"), "caja-1", "Caja 1", 0L) }
        val m = vm()
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."), m.pedirCobroSinRed("o1"))
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."), m.sinRed.value)
        m.soltarCobroSinRed()
        io.mockk.verify(exactly = 0) { entre.soltarApartado(any()) }
    }

    @Test fun `otra caja PREMIUM - la que la abrió no contesta, gris con su nombre`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(null, "caja-1", "Caja 1", 0L) }
        assertEquals(Gris(TextosDeCuentaSinRed.noLaEncuentro("Caja 1")), vm().pedirCobroSinRed("o1"))
    }

    @Test fun `mientras pregunta dice Avisándole a la caja que la abrió`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { entre.cajaQueLaAbrio("v1", "o1") } returns "Caja 1"
        val respuesta = CompletableDeferred<CuentasEntreCajas.DelDueno>()
        respuestaDelDueno = { respuesta.await() }
        val m = vm()
        val toque = async { m.pedirCobroSinRed("o1") }
        runCurrent()
        assertEquals("Caja 1", m.avisandoA.value)
        respuesta.complete(concedido)
        assertEquals(Cobrar(6000), toque.await())
        assertNull(m.avisandoA.value)
    }

    /** Review Focus 4. */
    @Test fun `doble toque pregunta una sola vez`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val respuesta = CompletableDeferred<CuentasEntreCajas.DelDueno>()
        respuestaDelDueno = { respuesta.await() }
        val m = vm()
        val primero = async { m.pedirCobroSinRed("o1") }
        val segundo = async { m.pedirCobroSinRed("o1") }
        runCurrent()
        respuesta.complete(concedido)
        assertEquals(Cobrar(6000), primero.await())
        assertEquals(Cobrar(6000), segundo.await())
        coVerify(exactly = 1) { entre.apartar(any(), any(), any(), any(), any()) }
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
        assertEquals("la de otra sucursal no cuenta", Cobrar(6000), m.sinRed.value)
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
        coVerify(exactly = 0) { entre.apartar(any(), any(), any(), any(), any()) }
    }

    /**
     * Final fix (M-a): el cobro con la copia que quedó en la cola vuelve a la cuenta sin «Pagada» (el anfitrión no lee un cierre: vuelve
     * a decidir con la copia y recarga). Lo que se ve es «sin sincronizar», nunca otro «Cobrar».
     */
    @Test fun `al volver de un cobro encolado con la copia la cuenta queda sin sincronizar`() = runTest {
        enLaCopia(cuenta())
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        assertNull(m.revalidarCobroSinRed("o1", 6000))
        coEvery { locales.hayPendiente("v1", "o1") } returns true // el efectivo quedó en la cola
        assertEquals("se vuelve a decidir y se recarga", true, m.alVolverDelCobroSinRed("o1"))
        assertEquals(Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), m.sinRed.value)
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

    /**
     * Caso 4 en aparatos (SAMSUNG + D3, 12-nov): con la cuenta ya apartada con la caja que la abrió, se apaga el WiFi de ESTA caja justo
     * antes del cajón. La revalidación ya no la ve y corta antes de la puerta: el texto es el de «No recibas el efectivo», no el de «no la
     * encuentro» (la cajera ya tocó «$180»).
     */
    @Test fun `la duena deja de verse con la cuenta apartada y la revalidacion dice que se perdio el contacto`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { entre.cajaQueLaAbrio("v1", "o1") } returns "Sunmi D3"
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(Respuesta.Concedida, "d3", "Sunmi D3", 0L) }
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        otraCaja = false // se apagó el WiFi de esta caja: la dueña ya no se ve
        assertEquals(TextosDeCuentaSinRed.sePerdioElContacto("Sunmi D3"), m.revalidarCobroSinRed("o1", 6000))
        assertEquals(Gris(TextosDeCuentaSinRed.sePerdioElContacto("Sunmi D3")), m.sinRed.value)
    }

    /**
     * D5 (hz:619db22d, medido en la SAMSUNG el 12-nov a las 17:11): la revalidación sí dice «Se perdió el contacto…», pero al volver a la
     * cuenta su vuelta de cada 10 s (`CobrarCuentaSinRed` → [CobroDeCuentaViewModel.consultarSinRed]) lo tapaba con «no la encuentro».
     * El gris de la puerta se queda mientras la cuenta siga sin poderse cobrar; si la dueña vuelve a verse, se ofrece otra vez.
     */
    @Test fun `el no recibas el efectivo se queda al volver aunque la cuenta se vuelva a consultar`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { entre.cajaQueLaAbrio("v1", "o1") } returns "Sunmi D3"
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(Respuesta.Concedida, "d3", "Sunmi D3", 0L) }
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        otraCaja = false
        val texto = TextosDeCuentaSinRed.sePerdioElContacto("Sunmi D3")
        assertEquals(texto, m.revalidarCobroSinRed("o1", 6000))
        assertEquals(false, m.alVolverDelCobroSinRed("o1"))
        m.consultarSinRed("o1") // la vuelta de cada 10 s
        assertEquals(Gris(texto), m.sinRed.value)

        otraCaja = true // la dueña vuelve a verse: se ofrece otra vez
        m.consultarSinRed("o1")
        assertEquals(Cobrar(6000), m.sinRed.value)
        otraCaja = false // y un gris nuevo ya es el de siempre
        m.consultarSinRed("o1")
        assertEquals(Gris(TextosDeCuentaSinRed.noLaEncuentro("Sunmi D3")), m.sinRed.value)
    }

    /**
     * Codex sobre la D5 (12-nov): el aviso guardado sólo tapa el «no la encuentro» (la MISMA causa). Un motivo nuevo y más importante
     * (aquí, un cobro de esta cuenta que quedó en la cola; igual «Cobrada en <caja>») se ve en cuanto aparece.
     */
    @Test fun `el aviso guardado no tapa un motivo nuevo`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { entre.cajaQueLaAbrio("v1", "o1") } returns "Sunmi D3"
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(Respuesta.Concedida, "d3", "Sunmi D3", 0L) }
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        otraCaja = false
        assertEquals(TextosDeCuentaSinRed.sePerdioElContacto("Sunmi D3"), m.revalidarCobroSinRed("o1", 6000))
        assertEquals(false, m.alVolverDelCobroSinRed("o1"))

        coEvery { locales.hayPendiente(any(), any()) } returns true
        m.consultarSinRed("o1")
        assertEquals(Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), m.sinRed.value)
    }

    /** Codex r2 sobre la D5: un aviso guardado por OTRA causa (el saldo cambió) no tapa el «no la encuentro» que llega después. */
    @Test fun `el aviso de saldo cambiado no tapa que despues se perdio el contacto`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { entre.cajaQueLaAbrio("v1", "o1") } returns "Sunmi D3"
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(Respuesta.Concedida, "d3", "Sunmi D3", 0L) }
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        assertEquals(TextosDeCuentaSinRed.saldoCambio(6000), m.revalidarCobroSinRed("o1", 5000))
        assertEquals(false, m.alVolverDelCobroSinRed("o1"))

        otraCaja = false
        m.consultarSinRed("o1")
        assertEquals(Gris(TextosDeCuentaSinRed.noLaEncuentro("Sunmi D3")), m.sinRed.value)
    }

    /** Caso 4, el otro lado: sin apartado vivo con la dueña (nunca se pidió, o ya se soltó) sigue el texto de «no la encuentro». */
    @Test fun `sin apartado con la duena la revalidacion sigue diciendo que no la encuentra`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        coEvery { entre.cajaQueLaAbrio("v1", "o1") } returns "Sunmi D3"
        respuestaDelDueno = { CuentasEntreCajas.DelDueno(Respuesta.Concedida, "d3", "Sunmi D3", 0L) }
        val m = vm()
        otraCaja = false
        assertEquals(TextosDeCuentaSinRed.noLaEncuentro("Sunmi D3"), m.revalidarCobroSinRed("o1", 6000))

        otraCaja = true
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        m.soltarCobroSinRed() // salió del cobro: ya no hay apartado
        otraCaja = false
        assertEquals(TextosDeCuentaSinRed.noLaEncuentro("Sunmi D3"), m.revalidarCobroSinRed("o1", 6000))
        assertEquals(Gris(TextosDeCuentaSinRed.noLaEncuentro("Sunmi D3")), m.sinRed.value)
    }

    /** Codex r3 H3. */
    /** Opción D: la puerta del cobro (en `PaymentFlowViewModel`) dice que no justo antes del cajón: la cuenta enseña ese gris. */
    @Test fun `la puerta que dice no justo antes del cajón deja la cuenta en gris`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        assertNull("la revalidación de la copia ya no pregunta por la red", m.revalidarCobroSinRed("o1", 6000))
        m.alNoPasarLaPuerta(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1"))
        assertEquals(Gris(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1")), m.sinRed.value)
    }

    /** Ruling 5: tras el rechazo de la revalidación, volver a la cuenta deja su gris (no se vuelve a ofrecer en el acto). */
    /** Ruling 5: tras un «no» de la puerta, volver a la cuenta deja su gris (no se vuelve a ofrecer en el acto). */
    @Test fun `tras el rechazo antes del efectivo la cuenta se queda en gris al volver`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val m = vm()
        m.pedirCobroSinRed("o1")
        m.alNoPasarLaPuerta(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1"))
        assertEquals("no se refresca la cuenta", false, m.alVolverDelCobroSinRed("o1"))
        assertEquals(Gris(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1")), m.sinRed.value)

        // Un cobro que sí siguió: se vuelve a decidir y se refresca.
        m.pedirCobroSinRed("o1")
        assertNull(m.revalidarCobroSinRed("o1", 6000))
        assertEquals(true, m.alVolverDelCobroSinRed("o1"))
        assertEquals(Cobrar(6000), m.sinRed.value)
    }

    @Test fun `en la caja que la abrió, su registro manda - otra la tiene, ya se cobró o no confirmó`() = runTest {
        enLaCopia(cuenta())
        val m = vm()
        registro = CuentaEnElRegistro(EstadoDeCuenta.COMPROMETIDA, "caja-2", "Caja 2", "Ana", "i", Instante("b", 0))
        m.consultarSinRed("o1")
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."), m.sinRed.value)
        puedeDeclarar = true
        m.consultarSinRed("o1")
        assertEquals(NoConfirmo("Ana empezó a cobrarla en Caja 2 y no confirmó. Pregúntale antes de cobrar.", 6000), m.sinRed.value)
        // «No se cobró: cobrar aquí»: lo declara una persona y la cuenta se aparta aquí.
        assertEquals(Cobrar(6000), m.noSeCobroYCobrarAqui("o1"))
        coVerify(exactly = 1) { dueno.noSeCobro("v1", "o1", any()) }
        registro = CuentaEnElRegistro(EstadoDeCuenta.CERRADA, "caja-2", "Caja 2", "Ana", "i", Instante("b", 0), faltaSubirla = true)
        m.consultarSinRed("o1")
        assertEquals(Gris("Cobrada en Caja 2 · falta subirla"), m.sinRed.value)
    }

    @Test fun `si la copia vence entre preparar y recibir el efectivo no se cobra`() = runTest {
        enLaCopia(cuenta())
        val m = vm()
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
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
        assertEquals("la caja que la abrió", Cobrar(6000), m.sinRed.value)

        otraCajaPremium()
        m.consultarSinRed("A")
        assertEquals("otra caja PREMIUM con otra caja de la sucursal", Cobrar(6000), m.sinRed.value)

        premium = false
        m.consultarSinRed("A")
        assertEquals(Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), m.sinRed.value)
    }

    // MARK: - Ronda 1 de la revisión

    /** I2: la cajera sale durante «Revisando…» (o vuelve la red y el botón sale de pantalla): el candado concedido tarde se suelta. */
    /** I2: la cajera sale durante «Avisándole…» (o vuelve la red y el botón sale de pantalla): el apartado concedido tarde se suelta. */
    @Test fun `salir durante Revisando suelta el apartado concedido y no lo guarda`() = runTest {
        enLaCopia(cuenta())
        otraCajaPremium()
        val respuesta = CompletableDeferred<CuentasEntreCajas.DelDueno>()
        respuestaDelDueno = { respuesta.await() }
        val m = vm()
        val toque = launch { m.pedirCobroSinRed("o1") }
        runCurrent()
        toque.cancel()
        respuesta.complete(concedido)
        toque.join()
        io.mockk.verify(exactly = 1) { entre.soltarApartado("o1") }
        m.soltarCobroSinRed() // no quedó guardado: no hay nada más que soltar
        io.mockk.verify(exactly = 1) { entre.soltarApartado("o1") }
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
        assertEquals(Cobrar(6000), m.pedirCobroSinRed("o1"))
        assertNull("mismo saldo: sigue", m.revalidarCobroSinRed("o1", 6000))
        enLaCopia(cuenta(saldo = 8000))
        assertEquals(TextosDeCuentaSinRed.saldoCambio(8000), m.revalidarCobroSinRed("o1", 6000))
        assertEquals(Gris(TextosDeCuentaSinRed.saldoCambio(8000)), m.sinRed.value)
        assertEquals("al volver se queda el gris", false, m.alVolverDelCobroSinRed("o1"))
        m.consultarSinRed("o1") // vuelve a abrir la cuenta: el botón trae el monto nuevo
        assertEquals(Cobrar(8000), m.sinRed.value)
    }

    /**
     * Final fix (F1): una respuesta de detalle superada por una consulta posterior que NO trajo evidencia (sin red) igual pone la copia
     * al día: es lo más nuevo que este aparato sabe de la cuenta. La copia ordena por sus propios turnos (antes M7 la tiraba y la copia
     * vieja de $100 sin abono seguía ofreciendo «Cobrar»).
     */
    @Test fun `una respuesta de detalle superada igual deja la copia al dia si es lo mas nuevo`() = runTest {
        enLaCopia(cuenta("A", saldo = 10000))
        val vieja = CompletableDeferred<Result<OrderDetail>>()
        val respuestas = ArrayDeque(listOf(vieja, CompletableDeferred(Result.failure<OrderDetail>(java.io.IOException("sin red")))))
        coEvery { repo.getOrderDetail("v1", "A") } coAnswers { respuestas.removeFirst().await() }
        val m = vm()
        val primera = launch { m.cargar("A") }
        runCurrent()
        m.cargar("A") // la que manda en pantalla: no trae evidencia
        vieja.complete(Result.success(OrderDetail("A", esDeMesa = false, status = "PENDING", paymentStatus = "PARTIAL",
            payments = listOf(OrderDetailPayment("p1", 40.0, status = "COMPLETED")), preBillBalance = PreBillBalance(10000, 4000, 6000))))
        primera.join()
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
        m.consultarSinRed("A")
        assertEquals(Gris(TextosDeCuentaSinRed.CON_ABONO), m.sinRed.value)
        assertNull("la foto de la pantalla sigue siendo la de la consulta que manda", m.detalle.value)
    }

    /** Final fix (F1, Codex #2): el detalle trae el saldo de regalo que cobró otra caja y el disco no lo pudo guardar: igual, gris. */
    @Test fun `el detalle nuevo que no se pudo guardar igual deja la cuenta en gris`() = runTest {
        enLaCopia(cuenta("A", saldo = 10000))
        falloAlGuardar = true
        coEvery { repo.getOrderDetail("v1", "A") } returns Result.success(OrderDetail("A", esDeMesa = false, status = "PENDING",
            paymentStatus = "PARTIAL", cobrosConSaldo = 1, preBillBalance = PreBillBalance(10000, 4000, 6000)))
        val m = vm()
        m.cargar("A")
        m.consultarSinRed("A") // se fue la red: decide con la copia
        assertEquals(Gris(TextosDeTarjetas.SIN_RED), m.sinRed.value)
        assertEquals(TextosDeTarjetas.SIN_RED, m.revalidarCobroSinRed("A", 10000))
    }

    @Test fun `I2 - con internet la dueña ve el no confirmó a los 2 min y lo declara`() = runTest {
        val m = vm()
        registro = CuentaEnElRegistro(EstadoDeCuenta.COMPROMETIDA, "caja-2", "Caja 2", "Ana", "i", Instante("b", 0))
        assertNull(m.noConfirmoEnLinea("o1"))
        puedeDeclarar = true
        assertEquals("Ana empezó a cobrarla en Caja 2 y no confirmó. Pregúntale antes de cobrar.", m.noConfirmoEnLinea("o1"))
        assertTrue(m.declararNoSeCobro("o1"))
        coVerify(exactly = 1) { dueno.noSeCobro("v1", "o1", any()) }
        assertNull(m.noConfirmoEnLinea("o1"))
    }
}
