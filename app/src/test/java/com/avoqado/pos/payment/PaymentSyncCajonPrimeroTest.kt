package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * F1, medido en una Samsung SM-X133 el 5-sep-2026: al volver el WiFi, ESTE servicio reintentaba sus
 * cobros («No pending payments to sync», 11:24:00) y la cola del CAJÓN no la reproducía nadie — su
 * único disparador era entrar a la pantalla de Caja, que es donde el cajero no está. Resultado: los
 * cobros en efectivo llegan al servidor ANTES que el `POST /open` y nacen con `shiftId = null`,
 * sin `CASH_SALE` en el cajón y sin forma de reatribuirlos después.
 *
 * Aquí se prueban las dos mitades que viven en el sincronizador: el DISPARADOR al reconectar y el
 * ORDEN entre las dos colas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentSyncCajonPrimeroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val cajon = mockk<CashDrawerRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)

    private var service: PaymentSyncService? = null

    /**
     * 🔴 El servicio corre en el MISMO scheduler del test (P3-2): sin eso, sus corrutinas vivían en
     * `Dispatchers.IO` y la única forma de esperarlas era un `Thread.sleep(500)` de tiempo REAL —
     * una prueba que en esta Mac, con ~20 sesiones encima, falla por CARGA y no por código.
     */
    private fun TestScope.servicio(): PaymentSyncService {
        every { secureStorage.venueId } returns "venue-123"
        every { secureStorage.userId } returns "user-456"
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        return PaymentSyncService(dao, secureStorage, client, connectivityMonitor, cajon, outbox, io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true))
            .also {
                it.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
                service = it
            }
    }

    @After
    fun tearDown() {
        service?.stop()
    }

    // MARK: - El disparador al reconectar

    /**
     * 🔴 P1 — al pasar de desconectado a conectado se actúa UNA vez, y sólo DESPUÉS de la espera de
     * estabilización. Los tres defectos posibles se ven aquí: actuar de más, actuar sin haber
     * estado caído, y mandar antes de que la ruta suba (que es exactamente lo que se midió: el
     * `POST /open` salió con el WiFi a medio subir, falló, y nadie volvió a intentarlo).
     */
    @Test
    fun `P1 al volver la red se reproduce una sola vez y tras la espera`() = runTest {
        val red = MutableStateFlow(true)
        var vueltas = 0

        val trabajo = launch {
            PaymentSyncService.alReconectar(red, esperaMs = 2_000) { vueltas++ }
        }
        runCurrent()
        assertEquals("Arrancar ya conectado no es una reconexión", 0, vueltas)

        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
        assertEquals("No se manda nada antes de que la ruta suba", 0, vueltas)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals("La reconexión dispara exactamente una vez", 1, vueltas)

        // Seguir conectado no vuelve a disparar; hace falta otra caída.
        red.value = true
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, vueltas)

        red.value = false
        runCurrent()
        red.value = true
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals("Una segunda caída sí vuelve a disparar", 2, vueltas)

        trabajo.cancel()
    }

    // MARK: - El orden entre las dos colas

    /** 🔴 P1 — en `syncNow()` el cajón va PRIMERO y los cobros después. */
    @Test
    fun `P1 el replay del cajon precede al de los cobros`() = runTest {
        val orden = mutableListOf<String>()
        coEvery { cajon.sincronizarCajonPrimero() } coAnswers { orden += "cajon"; true }
        coEvery { dao.getPendingPayments(any()) } answers { orden += "cobros"; emptyList() }

        // `start()` y no `syncNow()` a secas: el drenado se corta con `if (!isStarted) return`, así
        // que arrancar el servicio es el ÚNICO camino que ejercita el orden real.
        servicio().start()
        // `runCurrent()` y NO `advanceUntilIdle()`: el temporizador de seguridad del servicio es un
        // `while (isActive) { delay(15 min) }`, así que siempre hay una tarea futura agendada y
        // avanzar "hasta que no quede nada" no terminaría nunca. Aquí basta drenar lo que está
        // agendado para ESTE instante, que es todo lo que dispara `start()`.
        runCurrent()
        // 🔴 Se DETIENE dentro del cuerpo del test, no en el `@After`: el temporizador de seguridad
        // del servicio es un `while (isActive) { delay(15 min) }` sobre ESTE scheduler, y `runTest`
        // termina avanzando el tiempo virtual «hasta que no quede nada». Con el temporizador vivo,
        // eso son millones de vueltas de 15 minutos virtuales y el worker muere por memoria — un
        // fallo que se lee como OOM de la máquina y en realidad es de la prueba.
        service?.stop()

        assertEquals("El cajón tiene que ir primero", listOf("cajon", "cobros"), orden.take(2))
    }

    /**
     * 🔴 P1 — si la apertura vuelve REINTENTAR, los cobros de ESE ciclo no se mandan.
     *
     * Es la mitad que de verdad protege el dinero: un cobro que aterriza sin caja abierta queda
     * huérfano para siempre, mientras que esperar un ciclo no cuesta nada (la cola es durable).
     */
    @Test
    fun `P1 con la apertura sin confirmar los cobros de ese ciclo no salen`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro("sin-caja", caja = null))

        servicio().start()
        // `runCurrent()` y NO `advanceUntilIdle()`: el temporizador de seguridad del servicio es un
        // `while (isActive) { delay(15 min) }`, así que siempre hay una tarea futura agendada y
        // avanzar "hasta que no quede nada" no terminaría nunca. Aquí basta drenar lo que está
        // agendado para ESTE instante, que es todo lo que dispara `start()`.
        runCurrent()
        // 🔴 Se DETIENE dentro del cuerpo del test, no en el `@After`: el temporizador de seguridad
        // del servicio es un `while (isActive) { delay(15 min) }` sobre ESTE scheduler, y `runTest`
        // termina avanzando el tiempo virtual «hasta que no quede nada». Con el temporizador vivo,
        // eso son millones de vueltas de 15 minutos virtuales y el worker muere por memoria — un
        // fallo que se lee como OOM de la máquina y en realidad es de la prueba.
        service?.stop()

        // Desde la barrera por caja (efectivo sin red en el turno correcto) la cola SÍ se lee —cada fila se evalúa con su
        // caja—, pero un cobro sin caja conserva la regla de hoy: con la apertura sin confirmar no sale.
        coVerify(exactly = 0) { dao.updateStatus(any(), PaymentSyncStatus.SYNCING.name) }
        coVerify(atLeast = 1) { cajon.sincronizarCajonPrimero() }
    }

    /** Sin nada pendiente en el cajón, el camino feliz de los cobros no cambia. */
    @Test
    fun `P1 sin apertura pendiente los cobros siguen saliendo`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        servicio().start()
        // `runCurrent()` y NO `advanceUntilIdle()`: el temporizador de seguridad del servicio es un
        // `while (isActive) { delay(15 min) }`, así que siempre hay una tarea futura agendada y
        // avanzar "hasta que no quede nada" no terminaría nunca. Aquí basta drenar lo que está
        // agendado para ESTE instante, que es todo lo que dispara `start()`.
        runCurrent()
        // 🔴 Se DETIENE dentro del cuerpo del test, no en el `@After`: el temporizador de seguridad
        // del servicio es un `while (isActive) { delay(15 min) }` sobre ESTE scheduler, y `runTest`
        // termina avanzando el tiempo virtual «hasta que no quede nada». Con el temporizador vivo,
        // eso son millones de vueltas de 15 minutos virtuales y el worker muere por memoria — un
        // fallo que se lee como OOM de la máquina y en realidad es de la prueba.
        service?.stop()

        coVerify(atLeast = 1) { dao.getPendingPayments(any()) }
    }

    // MARK: - Barrera 1 por caja (efectivo sin red en el turno correcto, 21-oct-2026)

    private fun cobro(id: String, caja: String?) = PendingPaymentEntity(
        id = id, venueId = "venue-123", staffId = "user-456", amountCents = 10_000, tipCents = 0, method = "CASH",
        paymentType = "FAST", createdAt = 1L, cashDrawerSessionId = caja,
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            venueId = "venue-123", key = id, orderId = null, amountCents = 10_000, tipCents = 0, method = "CASH", splitType = "FULLPAYMENT",
        )),
    )

    private fun TestScope.drenar() {
        servicio().start()
        runCurrent()
        service?.stop()
    }

    /**
     * P1 — un cobro cuya caja ya está confirmada SALE aunque haya otra apertura pendiente (la de la caja siguiente). Con la
     * barrera global de antes esperaba, y con el cierre esperando a sus cobros eso cerraba un ciclo (Codex r1 #8).
     */
    @Test
    fun `P1 un cobro de una caja confirmada sale aunque otra apertura siga pendiente`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        every { cajon.cajasQueEsperanSuApertura() } returns setOf("caja-B-local")
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro("pA", caja = "srv-A"))

        drenar()

        coVerify(exactly = 1) { dao.updateStatus("pA", PaymentSyncStatus.SYNCING.name) }
    }

    /** P1 — FIFO: la fila se detiene en el primer cobro cuya caja todavía espera su apertura; lo de detrás no se adelanta. */
    @Test
    fun `P1 la fila se detiene en el primer cobro cuya caja espera su apertura`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        every { cajon.cajasQueEsperanSuApertura() } returns setOf("caja-B-local")
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro("pB", caja = "caja-B-local"), cobro("pA", caja = "srv-A"))

        drenar()

        coVerify(exactly = 0) { dao.updateStatus(any(), PaymentSyncStatus.SYNCING.name) }
    }

    /**
     * 🔴 Codex apps r1 #5 (como iOS): una fila SIN caja (de una versión anterior) con la barrera global cerrada ESPERA, pero no
     * detiene a las de una caja ya confirmada: si las detuviera, el cierre de esa caja esperaría a una fila que espera a la
     * apertura siguiente, que espera a ese cierre. Antes ANDROID la dejaba SALIR mientras un cierre esperaba (y caía en la caja
     * vieja) e iOS la dejaba esperar: la misma cola terminaba en cajas distintas según la app.
     */
    @Test
    fun `P1 una fila sin caja espera sin detener a los cobros de una caja confirmada`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        every { cajon.cajasQueEsperanSuApertura() } returns setOf("caja-B-local")
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro("vieja", caja = null), cobro("pA", caja = "srv-A"))

        drenar()

        coVerify(exactly = 0) { dao.updateStatus("vieja", PaymentSyncStatus.SYNCING.name) }
        coVerify(exactly = 1) { dao.updateStatus("pA", PaymentSyncStatus.SYNCING.name) }
    }

    // MARK: - El tope del cierre cuenta pases con el servidor, no minutos (founder, 24-oct-2026)

    private val cierreX = PendingDrawerOp("CLOSE", "srv-X", 25_000, localId = "cierre-X", at = 5)

    /** Corre una sincronización con la consulta final al servidor fija; devuelve cuántas veces se consultó. */
    private fun TestScope.sincronizarConElServidor(contesta: Boolean, antes: () -> Unit = {}): Int {
        var consultas = 0
        val s = servicio()
        s.servidorContesta = { consultas++; contesta }
        antes()
        s.start()
        runCurrent()
        service?.stop()
        return consultas
    }

    @Test
    fun `P1 un cierre retenido suma un pase si la subida corrio y el servidor contesta`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        val consultas = sincronizarConElServidor(contesta = true)

        assertEquals(1, consultas)
        verify(exactly = 1) { cajon.solicitarPaseConServidor(listOf(cierreX)) }
    }

    @Test
    fun `P1 si al final el servidor no contesta el cierre no suma pase`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        val consultas = sincronizarConElServidor(contesta = false)

        assertEquals(1, consultas)
        verify(exactly = 0) { cajon.solicitarPaseConServidor(any()) }
    }

    @Test
    fun `P1 sin cierre retenido no se consulta ni se cuenta nada`() = runTest {
        every { cajon.cierreQueEspera() } returns null
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        assertEquals(0, sincronizarConElServidor(contesta = true))
        verify(exactly = 0) { cajon.solicitarPaseConServidor(any()) }
    }

    /**
     * La pausa entre reintentos ya no la clasifica el sincronizador (Codex apps r5–r6: anotar cada salida de dos colas no se puede
     * hacer completo): la sincronización corrió y el intento llega al cajón, que sólo lo cuenta con 5 min desde el anterior.
     */
    @Test
    fun `P1 con la cola en pausa entre reintentos el intento llega al cajon`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        coEvery { dao.getPendingPayments(any()) } returns
            listOf(cobro("p1", caja = "srv-X").copy(retryCount = 3, lastRetryAt = System.currentTimeMillis()))

        assertEquals(1, sincronizarConElServidor(contesta = true))
        verify(exactly = 1) { cajon.solicitarPaseConServidor(listOf(cierreX)) }
    }

    /** Con el servidor marcado caído (sin red, 5xx o intermediario) la subida no corre: no es un intento. */
    @Test
    fun `P1 con el servidor caido no cuenta pase`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro("p1", caja = "srv-X"))

        val consultas = sincronizarConElServidor(contesta = false) {
            every { connectivityMonitor.isServerReachable } returns MutableStateFlow(false)
        }

        assertEquals("con la subida sin empezar se pregunta la salud", 1, consultas)
        verify(exactly = 0) { cajon.solicitarPaseConServidor(any()) }
    }

    /**
     * 🔴 Codex apps r7 #1: un 500 de OTRA ruta (el outbox de Mesas) apaga la bandera del servidor y la subida de cobros no empieza;
     * sin más, el cierre no sumaba nunca. Si la salud contesta (y vuelve a encender la bandera), se da una pasada real y cuenta.
     */
    @Test
    fun `P1 si la subida no empezo por la bandera y la salud contesta, corre una pasada y cuenta`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        coEvery { dao.getPendingPayments(any()) } returns emptyList()
        val bandera = MutableStateFlow(false)
        val s = servicio()
        every { connectivityMonitor.isServerReachable } returns bandera
        s.servidorContesta = { bandera.value = true; true }
        s.start()
        runCurrent()
        service?.stop()

        // La pasada real leyó la cola (antes, con la bandera apagada, ni la leía) y el intento llegó al cajón.
        coVerify(atLeast = 1) { dao.getPendingPayments(any()) }
        verify(exactly = 1) { cajon.solicitarPaseConServidor(listOf(cierreX)) }
    }

    /**
     * 🔴 Codex apps r8 #1: con red del APARATO el temporizador sincroniza aunque la bandera del servidor esté apagada (como iOS). Si
     * no, una falla parcial que deja la bandera apagada para siempre impedía volver a contar un intento del cierre.
     */
    @Test
    fun `P1 el temporizador sincroniza con red del aparato aunque la bandera del servidor este apagada`() = runTest {
        coEvery { dao.getPendingPayments(any()) } returns emptyList()
        val s = servicio()
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(false)
        s.start()
        runCurrent()
        coVerify(exactly = 1) { cajon.sincronizarCajonPrimero() }

        advanceTimeBy(15 * 60_000L + 1)
        runCurrent()
        service?.stop()

        coVerify(exactly = 2) { cajon.sincronizarCajonPrimero() }
    }

    /** 🔴 Codex apps r7 #2: se corrigió la hora del aparato y el último intento quedó EN EL FUTURO: no es una espera, se reintenta. */
    @Test
    fun `P1 un ultimo intento en el futuro se reintenta`() = runTest {
        coEvery { dao.getPendingPayments(any()) } returns
            listOf(cobro("p1", caja = "srv-X").copy(retryCount = 5, lastRetryAt = System.currentTimeMillis() + 60 * 60_000L))

        drenar()

        coVerify(exactly = 1) { dao.updateStatus("p1", PaymentSyncStatus.SYNCING.name) }
    }

    /** 🔴 Codex apps r7 #4: la salud vale con o sin espacios en el JSON; no vale HTML, otro código ni un JSON sin `status: ok`. */
    @Test
    fun `la salud del servidor solo cuenta como nuestra API`() {
        val ok = """{"status":"ok","checks":{"database":"connected"}}"""
        assertEquals(true, ConnectivityMonitor.saludContesta(200, "application/json; charset=utf-8", ok))
        assertEquals(true, ConnectivityMonitor.saludContesta(200, "application/json", """{ "status": "ok" }"""))
        assertEquals(false, ConnectivityMonitor.saludContesta(200, "text/html", "<html>portal</html>"))
        assertEquals(false, ConnectivityMonitor.saludContesta(503, "application/json", """{"status":"error"}"""))
        assertEquals(false, ConnectivityMonitor.saludContesta(200, "application/json", """{"status":"error"}"""))
        assertEquals(false, ConnectivityMonitor.saludContesta(200, "application/json", "no es json"))
        assertEquals(false, ConnectivityMonitor.saludContesta(200, null, ok))
    }

    /**
     * 🔴 Codex apps r5 #1: la reserva sin confirmar de A retiene su cierre, y un cobro de B (cuya apertura va detrás del cierre de A)
     * detiene la fila. Medido por TODA la pasada, A nunca sumaba un pase: trabado para siempre. Ahora sólo cuenta la caja del cierre.
     */
    @Test
    fun `P1 un cobro de otra caja detenido por su apertura no le quita el pase al cierre`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        every { cajon.cajasQueEsperanSuApertura() } returns setOf("caja-B-local")
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro("pB", caja = "caja-B-local"))

        assertEquals(1, sincronizarConElServidor(contesta = true))
        verify(exactly = 1) { cajon.solicitarPaseConServidor(listOf(cierreX)) }
    }

    /** Retenido en dos vueltas de la MISMA sincronización: una sola llamada (el cajón suma uno por cierre y por llamada). */
    @Test
    fun `P1 una sincronizacion cuenta a lo mas un pase`() = runTest {
        every { cajon.cierreQueEspera() } returns cierreX
        every { cajon.operacionesVivasDelCajon() } returnsMany listOf(2, 1, 1, 1)
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        assertEquals(1, sincronizarConElServidor(contesta = true))
        verify(exactly = 1) { cajon.solicitarPaseConServidor(any()) }
        verify(exactly = 1) { cajon.solicitarPaseConServidor(listOf(cierreX, cierreX)) }
    }

    @Test
    fun `la decision por fila, suelta`() {
        assertEquals(true, PaymentSyncService.elCobroPuedeSalir("srv-A", sinCajaPuedenSalir = false, cajasEsperando = setOf("B")))
        assertEquals(false, PaymentSyncService.elCobroPuedeSalir("B", sinCajaPuedenSalir = true, cajasEsperando = setOf("B")))
        assertEquals(false, PaymentSyncService.elCobroPuedeSalir(null, sinCajaPuedenSalir = false, cajasEsperando = emptySet()))
        assertEquals(true, PaymentSyncService.elCobroPuedeSalir(null, sinCajaPuedenSalir = true, cajasEsperando = setOf("B")))
    }
}
