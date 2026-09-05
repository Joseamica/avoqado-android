package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        return PaymentSyncService(dao, secureStorage, client, connectivityMonitor, cajon)
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

        coVerify(exactly = 0) { dao.getPendingPayments(any()) }
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
}
