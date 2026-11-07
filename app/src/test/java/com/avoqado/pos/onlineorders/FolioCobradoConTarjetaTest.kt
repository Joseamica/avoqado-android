package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.SaldoDelPedido
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.conLoCobradoEnElAparato
import com.avoqado.pos.onlineorders.presentation.tarjetasConfirmadas
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 🔴 R100 (Codex fase 3-4 r2 #1, #2 y #4): el dinero que ESTE aparato tiene de una orden de la bandeja y que el SERVIDOR todavía no
 * confirma. Una prueba por fuente de `dineroSinConfirmar`, con las piezas REALES: `TerminalPaymentService` (la lista durable de cobros
 * con tarjeta y el único punto donde un positivo sale de ella), `FoliosCobradosConTarjeta` y `CashPaymentRepository`, sobre un
 * «disco» de texto que imita `SecureStorage` (lo de verdad en disco lo prueba `FolioPorPagarEnLaEscenaTest`, de escritorio).
 * «Reiniciar» = servicio, marcas y bandeja nuevos sobre el mismo disco.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioCobradoConTarjetaTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)

    // El disco: la lista durable de cobros con tarjeta y las marcas de la bandeja.
    private var pendientes: String? = null
    private var marcasEnDisco: String? = null
    private var filas: List<PendingPaymentEntity> = emptyList()
    private var venue = "venue-1"

    private var respuesta: Result<ListaPorPagar> = Result.failure(IOException("sin red"))

    /**
     * La copia guardada de «Por pagar», por negocio, como la deja `OnlineOrdersRepository`: sólo una página 1 disponible que sí se
     * guardó (`copiaGuardada`) y que no es más vieja que la copia (R102). Es lo que «Cobrar» usa sin red (R104).
     */
    private val copias = mutableMapOf<String, ListaPorPagar>()
    private var guardada: ListaPorPagar?
        get() = copias[venue]
        set(v) { if (v == null) copias.remove(venue) else copias[venue] = v }

    private fun leerComoElRepositorio(seccion: String?, cursor: String?, r: Result<ListaPorPagar>): Result<ListaPorPagar> {
        val lista = r.getOrNull()
        if (seccion == null && cursor == null && lista != null && lista.disponible && lista.copiaGuardada && !lista.masViejaQueLaCopia) {
            copias[venue] = lista
        }
        return r
    }
    private var inbox: Result<Inbox> = Result.failure(IOException("sin red"))

    /** Lo que contesta una página extra («Cargar más»). */
    private var extra: Result<ListaPorPagar> = Result.failure(IOException("sin red"))

    private lateinit var servicio: TerminalPaymentService
    private lateinit var marcas: FoliosCobradosConTarjeta

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { secureStorage.venueId } answers { venue }
        every { secureStorage.pendingCardChargesJson } answers { pendientes }
        every { secureStorage.persistCobroQueSiPaso(any(), any(), any(), any()) } answers {
            pendientes = PendientesDeTarjeta.agregar(pendientes, firstArg(), secondArg(), thirdArg(), cobrado = true, cobradoEn = arg(3)); true
        }
        every { secureStorage.removePendingCardCharge(any()) } answers { pendientes = PendientesDeTarjeta.quitar(pendientes, firstArg()) }
        every { secureStorage.foliosCobradosConTarjetaJson } answers { marcasEnDisco }
        every { secureStorage.escribirFoliosCobradosConTarjeta(any()) } answers {
            marcasEnDisco = firstArg<(String?) -> String>()(marcasEnDisco); true
        }
        // El MISMO commit: sale el positivo y entra su marca.
        every { secureStorage.quitarCobroYAnotarFolio(any(), any()) } answers {
            pendientes = PendientesDeTarjeta.quitar(pendientes, firstArg())
            marcasEnDisco = secondArg<(String?) -> String>()(marcasEnDisco); true
        }
        coEvery { dao.forVenue(any()) } answers { filas }
        coEvery { repo.inbox(any(), any(), any()) } coAnswers { inbox }
        coEvery { repo.saldoDelPedido(any()) } returns Result.failure(IOException("sin red"))
        reiniciar()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    /** Lo que vive en memoria se pierde; el disco se queda. */
    private fun reiniciar() {
        servicio = TerminalPaymentService(secureStorage, OkHttpClient())
        marcas = FoliosCobradosConTarjeta(secureStorage)
    }

    private fun armar() = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = MutableStateFlow(true),
        efectivoDelPedido = CashPaymentRepository(secureStorage, dao)::efectivoDelPedido,
        leerPorPagar = { _, seccion, cursor -> leerComoElRepositorio(seccion, cursor, if (cursor == null) respuesta else extra) },
        foliosGuardados = { guardada },
        foliosConTarjeta = marcas,
        cobrosConTarjeta = servicio::cobrosConTarjetaDelNegocio,
        venueActual = { venue },
    )

    private fun folio(id: String = "f1", total: Long = 13400, falta: Long = 13400, status: OnlineOrderStatus = OnlineOrderStatus.PENDING_ACCEPTANCE, requiere: Boolean = false) = OnlineOrder(
        orderId = id, onlineOrderId = "oo-$id", orderNumber = "W7K3P", status = status, paymentMode = "PAY_AT_PICKUP",
        paymentStatus = if (falta == 0L) "PAID" else "PENDING", customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-06T18:00:00Z"), acceptDeadlineAt = Instant.parse("2099-11-06T18:30:00Z"), estimatedReadyAt = null,
        totalCents = total, remainingCents = falta, needsPrint = false, items = emptyList(), requiresAcceptance = requiere, channel = "QR",
    )

    private fun lista(
        porPagar: List<OnlineOrder> = emptyList(), porEntregar: List<OnlineOrder> = emptyList(), cursorPagar: String? = null,
        disponible: Boolean = true, cursorEntregar: String? = null,
    ) = ListaPorPagar(disponible, true, 30, ConteosPorPagar(porPagar.size, porEntregar.size), porPagar, porEntregar, CursoresPorPagar(cursorPagar, cursorEntregar))

    private fun bandeja(vararg pedidos: OnlineOrder) =
        Result.success(Inbox(Instant.parse("2026-11-06T18:10:00Z"), 0, pedidos.size, 0, pedidos.toList(), storeOff = false, truncated = false, nextCursor = null))

    /** Un cobro con tarjeta de la bandeja tal como lo deja `sendPaymentToTerminal` ANTES del POST (todavía en duda). */
    private fun cobroEnviado(requestId: String, orderId: String, monto: Int = 13400, esFolio: Boolean = true, venueDelCobro: String = venue) {
        val contexto = """{"requestId":"$requestId","venueId":"$venueDelCobro","terminalId":"t1","orderId":"$orderId","amountCents":$monto,""" +
            """"tipCents":0,"creadoEn":${System.currentTimeMillis()},"bandejaSaldoAntesCents":$monto,"bandejaEsFolio":$esFolio}"""
        pendientes = PendientesDeTarjeta.agregar(pendientes, requestId, orderId, contexto)
    }

    private fun fila(orderId: String, estado: String) = PendingPaymentEntity(
        id = "fila-$orderId", venueId = venue, staffId = "staff-1", amountCents = 13400, tipCents = 0,
        method = "CASH", paymentType = "ORDER", orderId = orderId, syncStatus = estado,
    )

    // MARK: - (a) la cola de efectivo

    @Test
    fun `a - a cash row of this device in the queue blocks Cobrar`() = runTest(dispatcher) {
        filas = listOf(fila("f1", "PENDING"))
        val vm = armar()
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO, vm.aviso.value)

        filas = listOf(fila("f1", "FAILED"))
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.COBRO_EN_EFECTIVO_POR_REVISAR, vm.aviso.value)
    }

    // MARK: - (b) el positivo en la lista durable, por cualquier camino

    @Test
    fun `P1 b - a late card positive from the stale path blocks cash, also after a restart`() = runTest(dispatcher) {
        // La cajera salió del cobro; el éxito llegó tarde (`handleStaleCardResult` → `aplicarDesenlaceTardio`).
        cobroEnviado("req-1", "f1")
        assertTrue(servicio.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("pay-1")))
        guardada = lista(porPagar = listOf(folio())).copy(guardadoHaceMin = 3)

        val vm = armar()
        vm.descubrirPorPagar() // sin red: la lista vieja
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull("no se cobra efectivo encima de la tarjeta", vm.cobroAbierto.value)
        assertEquals("Este folio ya se cobró con tarjeta en esta caja.", vm.aviso.value)
        coVerify(exactly = 0) { repo.saldoDelPedido(any()) }
        assertEquals("se pinta en «Por entregar»", mapOf("f1" to 13400L), vm.cobradosConTarjeta.value)

        reiniciar()
        val tras = armar()
        tras.descubrirPorPagar()
        tras.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(tras.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, tras.aviso.value)
    }

    @Test
    fun `P1 b to c - releasing the positive writes the marker in the same commit, never leaving a gap`() {
        cobroEnviado("req-1", "f1")
        servicio.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("pay-1"))
        assertTrue(servicio.cobrosConTarjetaDelNegocio(venue).single().cobrado)

        servicio.reconocerCobro("req-1") // «Entendido» (o la adopción del flujo que lo mandó)

        assertTrue("salió de la lista durable", PendientesDeTarjeta.leer(pendientes).isEmpty())
        val marca = marcas.marcas().single()
        assertEquals(FolioCobradoConTarjeta("f1", 13400, 13400, marca.enMs, venueId = venue, requestId = "req-1", esFolio = true), marca)
        // Idempotente por requestId: el mismo cobro nunca cuenta dos veces.
        marcas.anotar(marca.copy(enMs = 1))
        assertEquals(1, marcas.marcas().size)
    }

    @Test
    fun `a card charge that did not come from the tray leaves no marker`() {
        pendientes = PendientesDeTarjeta.agregar(
            null, "req-9", "venta-9", """{"requestId":"req-9","venueId":"$venue","orderId":"venta-9","amountCents":500,"creadoEn":${System.currentTimeMillis()}}""",
        )
        servicio.aplicarDesenlaceTardio("req-9", CardChargeOutcome.Charged("pay-9"))
        servicio.reconocerCobro("req-9")
        assertTrue(PendientesDeTarjeta.leer(pendientes).isEmpty())
        assertTrue(marcas.marcas().isEmpty())
    }

    // MARK: - (c) la marca

    @Test
    fun `P1 c - the marker survives a restart and only a complete same-venue read that counts the payment releases it`() = runTest(dispatcher) {
        cobroEnviado("req-1", "f1")
        servicio.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("pay-1"))
        servicio.reconocerCobro("req-1")
        reiniciar()

        // Otro negocio: su lista completa sin f1 no suelta nada.
        venue = "venue-2"
        respuesta = Result.success(lista(porPagar = listOf(folio("g1"))))
        armar().refrescar()
        assertEquals("la lectura de otro negocio no la suelta", 1, marcas.marcas().size)

        venue = "venue-1"
        // 403/404: «no disponible» no prueba ningún pago.
        respuesta = Result.success(lista(disponible = false))
        val vm = armar()
        vm.refrescar()
        assertEquals("un 403/404 no la suelta", 1, marcas.marcas().size)
        // Lectura que todavía no lo cuenta (salió antes del cobro).
        respuesta = Result.success(lista(porPagar = listOf(folio())))
        vm.refrescar()
        assertEquals(1, marcas.marcas().size)
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)
        // Sin f1 en «Por pagar» pero con páginas por leer: pudo quedar en otra.
        respuesta = Result.success(lista(porPagar = listOf(folio("f0")), cursorPagar = "c1"))
        vm.refrescar()
        assertEquals(1, marcas.marcas().size)

        // Completa, del mismo negocio y ya lo cuenta (está en «Por entregar»): se suelta.
        respuesta = Result.success(lista(porEntregar = listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED))))
        vm.refrescar()
        assertTrue(marcas.marcas().isEmpty())
        assertTrue(vm.cobradosConTarjeta.value.isEmpty())
    }

    /** Lo que deja un cobro con tarjeta de folio ya reconocido: el positivo salió de la lista y quedó su marca. */
    private fun folioCobradoConTarjeta() {
        cobroEnviado("req-1", "f1")
        servicio.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("pay-1"))
        servicio.reconocerCobro("req-1")
        assertEquals(1, marcas.marcas().size)
    }

    /**
     * R101 (Codex r3 #1, regresión de A6): la Bandeja ya ve el pago, pero lo que alimenta «Cobrar» de un folio es «Por pagar», y esa
     * lectura falló: conserva «$134». La Bandeja nunca suelta la marca de un folio.
     */
    @Test
    fun `P1 the Inbox seeing the payment never releases a folio marker - with the folio read failing Cobrar stays blocked, also after a restart`() = runTest(dispatcher) {
        folioCobradoConTarjeta()
        respuesta = Result.success(lista(porPagar = listOf(folio())))
        val vm = armar()
        vm.refrescar()

        inbox = bandeja(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED)) // el folio también sale en la Bandeja, ya pagado
        respuesta = Result.failure(IllegalStateException("500"))
        vm.refrescar()
        assertEquals("la Bandeja no suelta la marca de un folio", 1, marcas.marcas().size)
        assertEquals("«Por pagar» sigue diciendo $134", 13400L, vm.porPagar.value!!.porPagar.single().remainingCents)
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)

        reiniciar()
        guardada = lista(porPagar = listOf(folio())).copy(guardadoHaceMin = 1)
        val tras = armar()
        tras.descubrirPorPagar()
        tras.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull("tras reiniciar con la lista guardada, tampoco", tras.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, tras.aviso.value)
    }

    /** R101: se suelta en el MISMO paso en que esa lectura queda como copia guardada; si la copia no se escribió, no se suelta. */
    @Test
    fun `P1 a complete to-pay read releases a folio marker only when its saved copy was written`() = runTest(dispatcher) {
        folioCobradoConTarjeta()
        val pagado = lista(porEntregar = listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED)))
        respuesta = Result.success(pagado.copy(copiaGuardada = false))
        val vm = armar()
        vm.refrescar()
        assertEquals("la copia vieja seguiría detrás: no se suelta", 1, marcas.marcas().size)

        respuesta = Result.success(pagado)
        vm.refrescar()
        assertTrue(marcas.marcas().isEmpty())
    }

    /** R102: una respuesta más vieja que la copia guardada no reemplaza la lista en memoria ni suelta ninguna marca. */
    @Test
    fun `an old to-pay response never replaces a newer list nor releases a marker`() = runTest(dispatcher) {
        folioCobradoConTarjeta()
        respuesta = Result.success(lista(porPagar = listOf(folio())))
        val vm = armar()
        vm.refrescar()

        respuesta = Result.success(
            lista(porEntregar = listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED))).copy(masViejaQueLaCopia = true, copiaGuardada = false),
        )
        vm.refrescar()
        assertEquals("la lista de memoria no retrocede", listOf("f1"), vm.porPagar.value!!.porPagar.map { it.orderId })
        assertEquals("y la marca se queda", 1, marcas.marcas().size)
    }

    // MARK: - R104: la ÚNICA regla para soltar la marca de un folio es la copia guardada en disco

    /**
     * R104 (Codex r6 #1): una página extra de «Por entregar» trae el folio ya pagado, pero la copia guardada (sólo la página 1) sigue
     * diciendo «Por pagar $134». La marca NO se suelta: si se soltara, al reiniciar sin red se cobraría otra vez.
     */
    @Test
    fun `P1 an extra page bringing the paid folio does not release the marker - a restart offline still blocks`() = runTest(dispatcher) {
        folioCobradoConTarjeta()
        respuesta = Result.success(lista(porPagar = listOf(folio()), cursorEntregar = "d1"))
        val vm = armar()
        vm.refrescar()
        extra = Result.success(lista(porEntregar = listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED))))
        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_ENTREGAR)
        advanceUntilIdle()
        assertTrue("la página extra sí lo trae pagado", vm.porPagar.value!!.porEntregar.any { it.orderId == "f1" })
        assertEquals("pero la copia guardada sigue con $134: la marca se queda", 1, marcas.marcas().size)

        reiniciar()
        respuesta = Result.failure(IOException("sin red"))
        val tras = armar()
        tras.descubrirPorPagar()
        tras.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(tras.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, tras.aviso.value)
    }

    /** R104: la marca se suelta cuando la copia guardada YA cuenta el pago, también al abrir la pantalla. */
    @Test
    fun `a saved copy that already counts the payment releases the marker, also on screen open`() = runTest(dispatcher) {
        folioCobradoConTarjeta()
        guardada = lista(porEntregar = listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED)))
        respuesta = Result.failure(IOException("sin red"))
        armar().descubrirPorPagar()
        assertTrue("al abrir, la copia en disco ya lo cuenta", marcas.marcas().isEmpty())
    }

    @Test
    fun `a saved copy with more unread Por pagar pages proves nothing by absence`() = runTest(dispatcher) {
        folioCobradoConTarjeta()
        guardada = lista(porPagar = listOf(folio("f0")), cursorPagar = "c1")
        respuesta = Result.failure(IOException("sin red"))
        armar().descubrirPorPagar()
        assertEquals(1, marcas.marcas().size)
    }

    /** R104: sin red, el saldo de un folio sale SÓLO de la copia guardada; si no está ahí (vino de una página extra), no se cobra. */
    @Test
    fun `P1 a folio only in an extra page cannot be charged offline`() = runTest(dispatcher) {
        respuesta = Result.success(lista(porPagar = listOf(folio("f0")), cursorPagar = "c1"))
        val vm = armar()
        vm.refrescar()
        extra = Result.success(lista(porPagar = listOf(folio("f2", total = 5000, falta = 5000))))
        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_PAGAR)
        advanceUntilIdle()
        assertTrue(vm.porPagar.value!!.porPagar.any { it.orderId == "f2" })

        vm.cobrarFolio(folio("f2", total = 5000, falta = 5000)) // saldoDelPedido: sin red
        advanceUntilIdle()
        assertNull("no inventa ni reusa un saldo de memoria", vm.cobroAbierto.value)
        assertEquals("Sin conexión: no se puede confirmar el saldo de este folio.", vm.aviso.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_SIN_SALDO_GUARDADO, vm.aviso.value)
    }

    @Test
    fun `an A5 marker without venue blocks everywhere and only the folio in sight releases it`() {
        val vieja = FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0) // A5: sin negocio ni solicitud
        assertTrue("su ausencia no prueba nada", tarjetasConfirmadas(listOf(vieja), venue, emptyList(), porPagarCompleto = emptySet()).isEmpty())
        assertEquals(listOf(vieja), tarjetasConfirmadas(listOf(vieja), venue, listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED))))
        marcas.anotar(vieja)
        assertEquals(listOf(vieja), marcas.marcasDe("venue-2"))
    }

    // MARK: - (d) la tarjeta en duda

    @Test
    fun `d - a card still in doubt lets cash through with the warning`() = runTest(dispatcher) {
        cobroEnviado("req-1", "f1") // sin desenlace
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(13400L))
        val vm = armar()
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNotNull("el efectivo no se frena (founder, 2-nov «B»)", vm.cobroAbierto.value)
        assertEquals(CardChargeDecision.UNDETERMINED_MESSAGE, vm.aviso.value)
    }

    // MARK: - «Mi equipo» (Codex r2 #4)

    @Test
    fun `P1 a Mi equipo order charged by card and then offline with the stale list is blocked`() = runTest(dispatcher) {
        val pedido = folio("p1", requiere = true, status = OnlineOrderStatus.ACCEPTED)
        cobroEnviado("req-7", "p1", esFolio = false)
        servicio.aplicarDesenlaceTardio("req-7", CardChargeOutcome.Charged("pay-7"))
        servicio.reconocerCobro("req-7")
        assertFalse(marcas.marcas().single().esFolio)

        inbox = Result.failure(IOException("sin red"))
        val vm = armar()
        vm.cobrar(pedido)
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        assertEquals("Este pedido ya se cobró con tarjeta en esta caja.", vm.aviso.value)

        // La Bandeja del mismo negocio ya lo trae pagado: se suelta.
        inbox = bandeja(pedido.copy(remainingCents = 0, paymentStatus = "PAID"))
        respuesta = Result.success(lista())
        vm.refrescar()
        assertTrue(marcas.marcas().isEmpty())
    }

    // MARK: - lo que se pinta

    @Test
    fun `the card folio moves to Por entregar and a re-read whose second page fails keeps it`() = runTest(dispatcher) {
        var segunda: Result<ListaPorPagar> = Result.success(lista(porPagar = listOf(folio("f1"))))
        val vm = OnlineOrdersViewModel(
            repositorio = repo, permisoActualizar = { true }, permisoCancelar = { true }, tienePlan = { true }, hayPedidosAbiertos = { true },
            ventanaALaVista = MutableStateFlow(true),
            leerPorPagar = { _, _, cursor -> if (cursor == null) respuesta else segunda },
            foliosConTarjeta = marcas, cobrosConTarjeta = servicio::cobrosConTarjetaDelNegocio, venueActual = { venue },
        )
        respuesta = Result.success(lista(porPagar = listOf(folio("f0")), cursorPagar = "c1"))
        vm.refrescar()
        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_PAGAR)
        advanceUntilIdle()
        marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0, venueId = venue, requestId = "req-1"))
        segunda = Result.failure(IllegalStateException("500"))
        vm.refrescar()

        assertEquals("la lectura incompleta no lo borra", listOf("f0", "f1"), vm.porPagar.value!!.porPagar.map { it.orderId })
        assertEquals("ni suelta su marca", 1, marcas.marcas().size)
        assertEquals(mapOf("f1" to 13400L), vm.cobradosConTarjeta.value)
        val pantalla = conLoCobradoEnElAparato(vm.porPagar.value!!, vm.cobradosConTarjeta.value.keys)
        assertEquals(listOf("f1"), pantalla.porEntregar.map { it.orderId })
    }
}
