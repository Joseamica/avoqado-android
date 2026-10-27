package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.SalidaDelApartado
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 D2, rediseño r9: lo que la cola da por COBRADO entra al cajón del aparato UNA vez, en la misma escritura que lo saca de
 * `EN_VUELO` (`SalidaDelApartado`). Antes la anotaba la cola desde afuera, y la respuesta tardía la podía volver a escribir después
 * de que la fusión con el servidor la había cambiado por la del servidor (Codex r9 #3).
 *
 * Sin base real (los DAOs de prueba no tienen transacción): la transacción y la carrera las fija `SalidaDelApartadoRoomTest`
 * (WINDOWS). Aquí va lo que cruza con la fusión del cajón, que necesita el servidor de mentira de este paquete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashDrawerVentaDadaPorCobradaTest {

    private val abrioHace = haceMinutos(60)

    /** Un cobro en efectivo que la app apartó (con la marca de v7) y que nadie resolvió: lo que el barrido da por cobrado. */
    private fun abandonada(id: String, amountCents: Int, tipCents: Int = 0, caja: String? = "srv-1") = PendingPaymentEntity(
        id = id, venueId = VENUE_ID, staffId = "staff-1", amountCents = amountCents, tipCents = tipCents, method = "CASH",
        paymentType = "FAST", syncStatus = "EN_VUELO", createdAt = haceMinutos(5), cashDrawerSessionId = caja,
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(
            CapturaDelCobro(VENUE_ID, id, amountCents = amountCents, tipCents = tipCents, method = "CASH", daPorCobradaAlAbandonar = true),
        ),
    )

    /** La tabla `pending_payments` del aparato, con la SQL que importa aquí. */
    private class Pagos(vararg iniciales: PendingPaymentEntity) {
        val filas = iniciales.associateBy { it.id }.toMutableMap()
        val dao = mockk<PendingPaymentDao>(relaxed = true).also { dao ->
            coEvery { dao.enVueloDelVenue(any()) } answers { filas.values.filter { it.venueId == firstArg<String>() && it.syncStatus == "EN_VUELO" } }
            coEvery { dao.enVueloAPendiente(any()) } answers {
                val fila = filas[firstArg()]
                if (fila?.syncStatus == "EN_VUELO" && fila.q15CaptureJson != null && fila.q15Reason == null) {
                    filas[fila.id] = fila.copy(syncStatus = "PENDING"); 1
                } else 0
            }
            coEvery { dao.registrarResultadoQ15(any(), any(), any(), any(), any(), any()) } answers {
                val fila = filas[secondArg()] ?: return@answers 0
                filas[fila.id] = fila.copy(syncStatus = if (fila.syncStatus == "SYNCED" || arg<Boolean>(2)) "SYNCED" else "PENDING"); 1
            }
            coEvery { dao.insert(any()) } answers { val f = firstArg<PendingPaymentEntity>(); filas[f.id] = filaAlReemplazar(filas[f.id], f) }
            coEvery { dao.findByIdQ15(any()) } answers { filas[firstArg()] }
            coEvery { dao.findForVenue(any(), any()) } answers { filas[secondArg()]?.takeIf { it.venueId == firstArg<String>() } }
            coEvery { dao.forVenue(any()) } answers { filas.values.filter { it.venueId == firstArg<String>() } }
            coEvery { dao.cobrosDeEfectivoVivos(any(), any(), any(), any(), any()) } answers { filas.values.toList().tandaDeVivos(arg(0), arg(1), arg(2), arg(3), arg(4)) }
            every { dao.getPendingCount() } returns flowOf(0)
            every { dao.getFailedCount() } returns flowOf(0)
        }
    }

    private fun salida(pagos: Pagos, dao: FakeCashDrawerDao) = SalidaDelApartado(pagos.dao, dao, cashDrawerSecureStorage())

    private fun TestScope.cola(pagos: Pagos, dao: FakeCashDrawerDao, cajon: CashDrawerRepository) =
        PaymentSyncService(
            pagos.dao, cashDrawerSecureStorage(), mockk(relaxed = true), conectividadDePrueba(), cajon, mockk(relaxed = true),
            CashPaymentRepository(cashDrawerSecureStorage(), pagos.dao, salida(pagos, dao)),
        ).also { it.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler)) }

    /** La cola del cajón real lee los cobros pendientes de la MISMA tabla (`PendingCashSales` de verdad). */
    private fun cajon(dao: FakeCashDrawerDao, pagos: Pagos, vararg rutas: Pair<String, String>) = cashDrawerRepo(
        dao,
        cashDrawerClient(*rutas),
        pendingCashSales = PendingCashSales(intentDao = mockk { coEvery { pendingPayloads(any(), any()) } returns emptyList() }, pendingPaymentDao = pagos.dao),
    )

    /** El `/fast` de nuestra API contestando 2xx que CONFIRMA el cobro (la respuesta que tardó). */
    private fun dosXxTardio(pagos: Pagos, dao: FakeCashDrawerDao, llave: String, pesos: String) = suspend {
        OrderRepository(
            cashDrawerSecureStorage(),
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(
                    """{"success":true,"data":{"id":"pago-srv","orderId":"orden-srv","amount":"$pesos","tipAmount":"0.00","status":"COMPLETED"}}"""
                        .toResponseBody("application/json".toMediaType()),
                ).build()
            }.build(),
            pagos.dao,
            salida(pagos, dao),
        ).recordFastCashPayment((pesos.toDouble() * 100).toInt(), "staff-1", idempotencyKey = llave, capturedVenueId = VENUE_ID)
    }

    @Test
    fun `P1 el barrido de una venta con su caja abierta la suma al esperado del aparato`() = runTest {
        val dao = FakeCashDrawerDao().also { it.insertSession(sesionLocal("srv-1", openedAt = abrioHace)) }
        val pagos = Pagos(abandonada("cobro-1", amountCents = 30_000, tipCents = 500))
        val cajon = cajon(dao, pagos)
        val antes = cajon.computeExpectedAmount("srv-1", FONDO_CENTS)

        cola(pagos, dao, cajon).darPorCobradasLasAbandonadasYa().join()

        assertEquals("PENDING", pagos.filas["cobro-1"]?.syncStatus)
        assertEquals("el corte cuenta la venta (con propina) que se dio por cobrada", antes + 30_500, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `P1 la venta dada por cobrada sobrevive a la fusion con el servidor hasta subir, y luego cuenta una vez`() = runTest {
        val dao = FakeCashDrawerDao().also { it.insertSession(sesionLocal("srv-1", openedAt = abrioHace)) }
        val pagos = Pagos(abandonada("cobro-1", amountCents = 30_000))
        // El servidor confirma OTRA venta de esta caja (así sí reconcilia ventas) y todavía no conoce la nuestra.
        val cajon = cajon(
            dao, pagos,
            "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, eventoJson("srv-ev-otra", "CASH_SALE", "100.00", orderId = "otra"), openedAt = abrioHace),
        )
        cola(pagos, dao, cajon).darPorCobradasLasAbandonadasYa().join()

        cajon.syncFromApi()

        val sesion = cajon.getOpenSession()!!
        assertEquals("fondo 5000 + otra 100 + la nuestra 300, sin perderla ni duplicarla", 540_000, cajon.computeExpectedAmount(sesion.id, sesion.startingAmountCents))
        assertNotNull("la venta dada por cobrada se borró antes de subir", dao.events.values.singleOrNull { it.type == "CASH_SALE" && it.amountCents == 30_000 })
    }

    /**
     * Prueba 5 (Codex r9 #3): el barrido la da por cobrada (y la anota); mientras su réplica espera, la fusión trae la venta DEL
     * SERVIDOR (el POST original sí llegó) y cambia la copia local por ella; luego llega el 2xx tardío del POST original. Ese 2xx
     * no la vuelve a escribir: la fila ya no estaba `EN_VUELO`. El esperado la cuenta una vez.
     */
    @Test
    fun `barrido, fusion con la venta del servidor y 2xx tardio no dejan una segunda anotacion`() = runTest {
        val dao = FakeCashDrawerDao().also { it.insertSession(sesionLocal("srv-1", openedAt = abrioHace)) }
        val pagos = Pagos(abandonada("cobro-1", amountCents = 30_000))
        val cajon = cajon(
            dao, pagos,
            "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, eventoJson("srv-ev-cobro-1", "CASH_SALE", "300.00", orderId = "orden-srv"), openedAt = abrioHace),
        )
        cola(pagos, dao, cajon).darPorCobradasLasAbandonadasYa().join()
        assertTrue("el barrido la anotó", dao.events.containsKey(CashDrawerRepository.idDeLaVentaEnEfectivo("cobro-1")))

        cajon.syncFromApi() // la del servidor llega por primera vez y reclama la copia local
        assertEquals(FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))

        assertTrue(dosXxTardio(pagos, dao, "cobro-1", "300.00")().isSuccess)

        assertEquals("SYNCED", pagos.filas["cobro-1"]?.syncStatus)
        assertEquals("cuenta UNA vez: la del servidor", FONDO_CENTS + 30_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
        assertEquals(1, dao.events.values.count { it.type == "CASH_SALE" })
    }

    /**
     * Codex r10 #1: con el 2xx comprobado se anota el resultado económico DEFINITIVO. Otra caja movió la orden mientras se cobraba
     * y el servidor recortó el efectivo a 350 + 50 de propina: el cajón cuenta 400 (lo que de verdad quedó), no los 500 de la fila.
     */
    @Test
    fun `el 2xx del pay anota en el cajon lo que el servidor registro, no el importe de la fila`() = runTest {
        val dao = FakeCashDrawerDao().also { it.insertSession(sesionLocal("srv-1", openedAt = abrioHace)) }
        val pagos = Pagos(abandonada("cobro-1", amountCents = 500).copy(orderId = "orden-1", paymentType = "ORDER"))
        val ordenes = OrderRepository(
            cashDrawerSecureStorage(),
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(
                    """{"success":true,"payment":{"paymentId":"pago-1","orderId":"orden-1","amount":350,"tipAmount":50,"status":"COMPLETED"}}"""
                        .toResponseBody("application/json".toMediaType()),
                ).build()
            }.build(),
            pagos.dao,
            salida(pagos, dao),
        )

        val cobro = ordenes.recordCashPayment("orden-1", 500, "staff-1", idempotencyKey = "cobro-1", capturedVenueId = VENUE_ID)

        assertTrue(cobro.isSuccess)
        assertEquals("SYNCED", pagos.filas["cobro-1"]?.syncStatus)
        assertEquals(listOf(400), dao.events.values.filter { it.type == "CASH_SALE" }.map { it.amountCents })
    }

    /**
     * Agregado 1 de la ronda corta: el apartado de una venta nueva lleva su orden (el ViewModel la escribe al nacer la orden). La venta
     * que anota el barrido la lleva, y la fusión la reclama POR ORDEN aunque el servidor haya registrado otro importe (otra caja movió la
     * orden): cuenta una vez, la del servidor.
     */
    @Test
    fun `la venta del apartado lleva su orden y la fusion la reclama por orden`() = runTest {
        val dao = FakeCashDrawerDao().also { it.insertSession(sesionLocal("srv-1", openedAt = abrioHace)) }
        val pagos = Pagos(abandonada("cobro-1", amountCents = 30_000).copy(orderId = "orden-A", paymentType = "ORDER"))
        val cajon = cajon(
            dao, pagos,
            "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, eventoJson("srv-ev-A", "CASH_SALE", "250.00", orderId = "orden-A"), openedAt = abrioHace),
        )
        cola(pagos, dao, cajon).darPorCobradasLasAbandonadasYa().join()
        assertEquals("orden-A", dao.events.getValue(CashDrawerRepository.idDeLaVentaEnEfectivo("cobro-1")).orderId)

        cajon.syncFromApi()

        assertTrue(dao.events.values.none { it.id == CashDrawerRepository.idDeLaVentaEnEfectivo("cobro-1") })
        assertEquals("cuenta UNA vez, lo que registró el servidor", FONDO_CENTS + 25_000, cajon.computeExpectedAmount("srv-1", FONDO_CENTS))
    }

    @Test
    fun `P1 una venta normal en linea sigue anotandose una vez, con el id de su llave, en la caja de la primera escritura`() = runTest {
        val dao = FakeCashDrawerDao().also { it.insertSession(sesionLocal("srv-1", openedAt = abrioHace)) }
        val cajon = cajon(dao, Pagos())
        cajon.addCashSale(12_000, "order-1", VENUE_ID, "staff-1", "Ana Ruiz", llaveDelCobro = "llave-1")
        dao.insertSession(sesionLocal("srv-2", openedAt = haceMinutos(1))) // otra caja abierta después
        cajon.addCashSale(12_000, "order-1", VENUE_ID, "staff-1", "Ana Ruiz", llaveDelCobro = "llave-1") // un reintento con la misma llave

        val ventas = dao.events.values.filter { it.type == "CASH_SALE" }
        assertEquals(1, ventas.size)
        assertEquals(CashDrawerRepository.idDeLaVentaEnEfectivo("llave-1"), ventas.single().id)
        assertEquals("INSERT OR IGNORE: el reintento no la muda de caja", "srv-1", ventas.single().sessionId)
        assertEquals("tiene forma de UUID, como los demás ids locales", ventas.single().id, java.util.UUID.fromString(ventas.single().id).toString())
    }

    @Test
    fun `P1 si la caja que capturo la venta ya no esta abierta, el barrido no la anota en otra`() = runTest {
        val dao = FakeCashDrawerDao().also {
            it.insertSession(sesionLocal("srv-vieja", openedAt = haceMinutos(120), status = "CLOSED"))
            it.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        }
        val pagos = Pagos(abandonada("cobro-1", amountCents = 30_000, caja = "srv-vieja"))
        val cajon = cajon(dao, pagos)

        cola(pagos, dao, cajon).darPorCobradasLasAbandonadasYa().join()

        assertEquals("la cola sí la sube", "PENDING", pagos.filas["cobro-1"]?.syncStatus)
        assertEquals("pero no la anota en la caja de otro turno", 0, dao.events.values.count { it.type == "CASH_SALE" })
    }
}
