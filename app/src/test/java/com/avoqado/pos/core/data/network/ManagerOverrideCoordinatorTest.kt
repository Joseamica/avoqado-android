package com.avoqado.pos.core.data.network

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Carreras del teclado de autorización.
 *
 * 🔴 Estas son las que la auditoría (2026-08-16) señaló como no cubiertas: el
 * flujo feliz de UNA acción pasaba todos los tests, pero los defectos vivían en
 * la FILA — dos acciones bloqueadas a la vez, con un teclado que se cierra por
 * un camino distinto a "autorizado".
 *
 * `awaitToken` BLOQUEA su hilo a propósito (es lo que permite que el reintento
 * llegue al ViewModel que hizo la llamada), y su timeout corre en tiempo real.
 * Por eso aquí se usan hilos de verdad y un tope de milisegundos, no `runTest`
 * con tiempo virtual: con el scheduler de pruebas esto se deadlockea.
 */
class ManagerOverrideCoordinatorTest {

    /** Tope diminuto: el de producción son 2 minutos. */
    private class TestCoordinator(
        repository: PermissionOverrideRepository,
        override val promptTimeoutMs: Long = 30_000L,
    ) : ManagerOverrideCoordinator(repository)

    private fun repoReturning(vararg results: OverrideResult): PermissionOverrideRepository {
        val repo = mockk<PermissionOverrideRepository>()
        var i = 0
        coEvery { repo.requestToken(any(), any(), any(), any()) } answers {
            results[minOf(i++, results.size - 1)]
        }
        return repo
    }

    /** Espera a que el teclado aparezca; falla rápido si no llega. */
    private fun ManagerOverrideCoordinator.esperarTeclado(): ManagerOverrideCoordinator.Prompt {
        val limite = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < limite) {
            prompt.value?.let { return it }
            Thread.sleep(5)
        }
        error("El teclado nunca apareció")
    }

    /**
     * El defecto original: `submitPin` leía el deferred y el permiso por
     * separado, así que el token pedido para una acción podía completar la
     * espera de otra. Con la captura única, el permiso que viaja al server es
     * SIEMPRE el del teclado que se está mostrando.
     */
    @Test
    fun `el permiso que se pide es el del teclado vivo`() {
        val repo = mockk<PermissionOverrideRepository>()
        val pedidos = mutableListOf<String>()
        coEvery { repo.requestToken(any(), any(), any()) } answers {
            pedidos.add(thirdArg())
            OverrideResult.Granted("tok", "Laura")
        }
        val coordinator = TestCoordinator(repo)

        var token: String? = null
        val listo = CountDownLatch(1)
        thread { token = coordinator.awaitToken("orders:merge"); listo.countDown() }

        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }

        assertTrue(listo.await(3, TimeUnit.SECONDS))
        assertEquals("tok", token)
        assertEquals(listOf("orders:merge"), pedidos)
    }

    /**
     * Una cancelación que llega TARDE —su teclado ya se cerró— no puede tumbar
     * la espera de la acción que venía en la fila.
     */
    @Test
    fun `cancelar con un id viejo no afecta al teclado siguiente`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok_a", "Laura")))

        val listoA = CountDownLatch(1)
        thread { coordinator.awaitToken("orders:merge"); listoA.countDown() }
        val idA = coordinator.esperarTeclado().id

        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listoA.await(3, TimeUnit.SECONDS))

        // B toma el turno.
        var tokenB: String? = "sin-resolver"
        val listoB = CountDownLatch(1)
        thread { tokenB = coordinator.awaitToken("payments:refund"); listoB.countDown() }
        val idB = coordinator.esperarTeclado().id
        assertNotEquals(idA, idB)

        // Llega la cancelación tardía de A: B no se entera.
        coordinator.cancel(idA)
        Thread.sleep(100)
        assertEquals(1, listoB.count)
        assertEquals(idB, coordinator.prompt.value?.id)

        // Y la cancelación de B sí la resuelve.
        coordinator.cancel(idB)
        assertTrue(listoB.await(3, TimeUnit.SECONDS))
        assertNull(tokenB)
    }

    /**
     * Sin tope, un teclado que nadie contesta dejaba el hilo de red bloqueado
     * PARA SIEMPRE con el Mutex tomado, y todo 403 posterior se encolaba detrás.
     */
    @Test
    fun `la espera vence sola y libera la fila`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok_b", "Laura")), promptTimeoutMs = 150L)

        var tokenA: String? = "sin-resolver"
        val listoA = CountDownLatch(1)
        thread { tokenA = coordinator.awaitToken("orders:merge"); listoA.countDown() }

        // Nadie contesta.
        assertTrue("la espera debió vencer sola", listoA.await(3, TimeUnit.SECONDS))
        assertNull(tokenA)
        assertNull(coordinator.prompt.value)

        // La fila quedó libre: la siguiente acción SÍ consigue su teclado.
        var tokenB: String? = null
        val listoB = CountDownLatch(1)
        thread { tokenB = coordinator.awaitToken("payments:refund"); listoB.countDown() }
        assertEquals("payments:refund", coordinator.esperarTeclado().permission)

        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listoB.await(3, TimeUnit.SECONDS))
        assertEquals("tok_b", tokenB)
    }

    // MARK: - El PIN pedido POR ADELANTADO (al TOCAR el botón con candado)
    //
    // 🔴 Antes el teclado sólo salía cuando el server rechazaba: o sea después
    // de que el cajero llenó importe, motivo y propina. Si el encargado no
    // estaba cerca, todo ese trabajo se perdía.

    @Test
    fun `preauthorize muestra el teclado y devuelve true al autorizar`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok", "Laura")))

        var autorizado: Boolean? = null
        val listo = CountDownLatch(1)
        thread {
            autorizado = runBlocking { coordinator.preauthorize("payments:refund") }
            listo.countDown()
        }

        assertEquals("payments:refund", coordinator.esperarTeclado().permission)
        runBlocking { coordinator.submitPin("venue_1", "1234") }

        assertTrue(listo.await(3, TimeUnit.SECONDS))
        assertEquals(true, autorizado)
    }

    /** Autoriza el teclado que esté a la vista y devuelve lo que preauthorize respondió. */
    private fun preautorizar(
        coordinator: ManagerOverrideCoordinator,
        permiso: String,
        llave: String? = null,
        contexto: String? = null,
    ): Boolean? {
        var autorizado: Boolean? = null
        val listo = CountDownLatch(1)
        thread { autorizado = runBlocking { coordinator.preauthorize(permiso, llave, contexto) }; listo.countDown() }
        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listo.await(3, TimeUnit.SECONDS))
        return autorizado
    }

    @Test
    fun `5b el operationKey viaja al servidor y la preautorizacion se guarda con su llave`() {
        val repo = mockk<PermissionOverrideRepository>()
        val llaves = mutableListOf<String?>()
        coEvery { repo.requestToken(any(), any(), any(), any()) } answers {
            llaves.add(arg<String?>(3))
            OverrideResult.Granted("tok${llaves.size}", "Laura")
        }
        val coordinator = TestCoordinator(repo)

        assertEquals(true, preautorizar(coordinator, "discounts:apply", "descuento-cliente:c:d:1000"))
        assertEquals(listOf<String?>("descuento-cliente:c:d:1000"), llaves)

        // Otra llave (otro monto) no reusa la anterior: pide su propio PIN y su propio token.
        assertNull(coordinator.tomarPreautorizado("discounts:apply", "descuento-cliente:c:d:1200"))
        assertEquals(true, preautorizar(coordinator, "discounts:apply", "descuento-cliente:c:d:1200"))
        assertEquals(2, llaves.size)

        // La de :1000 fue reemplazada por la de :1200 (una sola preautorización viva), y se entrega una vez.
        assertEquals("tok2", coordinator.tomarPreautorizado("discounts:apply", "descuento-cliente:c:d:1200"))
        assertNull(coordinator.tomarPreautorizado("discounts:apply", "descuento-cliente:c:d:1200"))
    }

    @Test
    fun `5b preauthorize con la misma llave viva no vuelve a pedir PIN`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok", "Laura")))
        preautorizar(coordinator, "discounts:apply", "k1")

        assertEquals(true, runBlocking { coordinator.preauthorize("discounts:apply", "k1") })
        assertNull(coordinator.prompt.value)
    }

    @Test
    fun `5c cortesia sin operationKey funciona igual`() {
        val repo = mockk<PermissionOverrideRepository>()
        val llaves = mutableListOf<String?>()
        coEvery { repo.requestToken(any(), any(), any(), any()) } answers {
            llaves.add(arg<String?>(3))
            OverrideResult.Granted("tok", "Laura")
        }
        val coordinator = TestCoordinator(repo)

        assertEquals(true, preautorizar(coordinator, "orders:comp"))
        assertEquals(listOf<String?>(null), llaves)
        assertEquals("tok", coordinator.tomarPreautorizado("orders:comp"))
    }

    @Test
    fun `5d el contexto llega al teclado, y sin contexto queda el texto de hoy`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok", "Laura")))
        var visto: ManagerOverrideCoordinator.Prompt? = null
        val listo = CountDownLatch(1)
        thread {
            runBlocking { coordinator.preauthorize("discounts:apply", "k", "Descuento de Cecilia −\$10.00") }
            listo.countDown()
        }
        visto = coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listo.await(3, TimeUnit.SECONDS))
        assertEquals("Descuento de Cecilia −\$10.00", visto?.contexto)

        val otro = TestCoordinator(repoReturning(OverrideResult.Granted("tok", "Laura")))
        val l2 = CountDownLatch(1)
        thread { runBlocking { otro.preauthorize("payments:refund") }; l2.countDown() }
        assertNull(otro.esperarTeclado().contexto)
        runBlocking { otro.submitPin("venue_1", "1234") }
        assertTrue(l2.await(3, TimeUnit.SECONDS))
    }

    /** Si canceló, quien llamó tiene que poder NO abrir el formulario. */
    @Test
    fun `preauthorize devuelve false si el usuario cancela`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok", "Laura")))

        var autorizado: Boolean? = null
        val listo = CountDownLatch(1)
        thread {
            autorizado = runBlocking { coordinator.preauthorize("payments:refund") }
            listo.countDown()
        }

        coordinator.cancel(coordinator.esperarTeclado().id)

        assertTrue(listo.await(3, TimeUnit.SECONDS))
        assertEquals(false, autorizado)
    }

    /**
     * 🔴 El PIN se pide UNA vez por acción. Sin reusar el token adelantado, el
     * 403 de la petición real sacaría un SEGUNDO teclado por el mismo reembolso.
     */
    @Test
    fun `el token adelantado lo consume el 403 sin sacar otro teclado`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok_pre", "Laura")))

        val listo = CountDownLatch(1)
        thread { runBlocking { coordinator.preauthorize("payments:refund") }; listo.countDown() }
        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listo.await(3, TimeUnit.SECONDS))

        // El interceptor pide el token: sale el MISMO, y sin teclado.
        val token = coordinator.awaitToken("payments:refund")

        assertEquals("tok_pre", token)
        assertNull("no debió aparecer un segundo teclado", coordinator.prompt.value)
    }

    /** Y de un solo uso: el segundo 403 vuelve a pedir PIN. */
    @Test
    fun `el token adelantado sirve UNA vez`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok_pre", "Laura")))

        val listo = CountDownLatch(1)
        thread { runBlocking { coordinator.preauthorize("payments:refund") }; listo.countDown() }
        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listo.await(3, TimeUnit.SECONDS))

        assertEquals("tok_pre", coordinator.awaitToken("payments:refund"))

        // El segundo intento ya no tiene token guardado: teclado.
        val segundo = CountDownLatch(1)
        thread { coordinator.awaitToken("payments:refund"); segundo.countDown() }
        assertEquals("payments:refund", coordinator.esperarTeclado().permission)
        coordinator.cancel()
        assertTrue(segundo.await(3, TimeUnit.SECONDS))
    }

    /**
     * 🔴 Un token de UN permiso no puede saldar el 403 de OTRO. En el server es
     * de un solo uso y de un solo permiso: reusarlo cruzado sólo lo quemaría y
     * dejaría la acción real sin autorizar.
     */
    @Test
    fun `el token adelantado no sirve para otro permiso`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok_pre", "Laura")))

        val listo = CountDownLatch(1)
        thread { runBlocking { coordinator.preauthorize("payments:refund") }; listo.countDown() }
        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listo.await(3, TimeUnit.SECONDS))

        val otro = CountDownLatch(1)
        thread { coordinator.awaitToken("orders:merge"); otro.countDown() }

        assertEquals("orders:merge", coordinator.esperarTeclado().permission)
        coordinator.cancel()
        assertTrue(otro.await(3, TimeUnit.SECONDS))
    }

    /**
     * 🔴 El token del server vive 60 s. Mandar uno vencido es PEOR que no mandar
     * ninguno: la petición saldría CON el header y el guard del interceptor
     * apagaría el teclado justo cuando hacía falta.
     */
    @Test
    fun `un token adelantado vencido se descarta y el teclado vuelve a salir`() {
        val coordinator = object : ManagerOverrideCoordinator(
            repoReturning(OverrideResult.Granted("tok_viejo", "Laura")),
        ) {
            override val promptTimeoutMs: Long = 30_000L
            override val preauthTtlMs: Long = 1L
        }

        val listo = CountDownLatch(1)
        thread { runBlocking { coordinator.preauthorize("payments:refund") }; listo.countDown() }
        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234") }
        assertTrue(listo.await(3, TimeUnit.SECONDS))

        Thread.sleep(20) // se pasa del TTL

        val otro = CountDownLatch(1)
        thread { coordinator.awaitToken("payments:refund"); otro.countDown() }
        assertEquals("payments:refund", coordinator.esperarTeclado().permission)
        coordinator.cancel()
        assertTrue(otro.await(3, TimeUnit.SECONDS))
    }

    /**
     * Codex D24 r1 #2: el teclado A vence y la acción B de la fila instala el suyo; antes de que la pantalla se redibuje,
     * el encargado toca «Autorizar» en A. Sin el id, ese PIN sacaba un token para B (quizá un reembolso) que nadie vio.
     */
    @Test
    fun `un envio con el id de un teclado ya cerrado no autoriza al siguiente`() {
        val repo = mockk<PermissionOverrideRepository>()
        val pedidos = mutableListOf<String>()
        coEvery { repo.requestToken(any(), any(), any()) } answers {
            pedidos.add(thirdArg())
            OverrideResult.Granted("tok", "Laura")
        }
        val coordinator = TestCoordinator(repo, promptTimeoutMs = 150L)

        val listoA = CountDownLatch(1)
        thread { coordinator.awaitToken("orders:merge"); listoA.countDown() }
        val idA = coordinator.esperarTeclado().id
        assertTrue("A vence solo", listoA.await(3, TimeUnit.SECONDS))

        var tokenB: String? = "sin-resolver"
        val listoB = CountDownLatch(1)
        thread { tokenB = coordinator.awaitToken("payments:refund"); listoB.countDown() }
        val limite = System.currentTimeMillis() + 3_000
        while (coordinator.prompt.value?.permission != "payments:refund" && System.currentTimeMillis() < limite) Thread.sleep(5)
        val idB = coordinator.prompt.value?.id
        assertNotEquals(idA, idB)

        val tardio = runBlocking { coordinator.submitPin("venue_1", "1234", idA) }

        assertEquals(OverrideResult.Failed("La acción ya no está esperando autorización."), tardio)
        assertEquals("no se pidió ningún token", emptyList<String>(), pedidos)
        assertEquals(1, listoB.count)
        assertEquals(idB, coordinator.prompt.value?.id)

        runBlocking { coordinator.submitPin("venue_1", "1234", idB) }
        assertTrue(listoB.await(3, TimeUnit.SECONDS))
        assertEquals("tok", tokenB)
        assertEquals(listOf("payments:refund"), pedidos)
    }

    /** Sin teclado vivo, teclear no puede resolverle a nadie. */
    @Test
    fun `enviar sin teclado vivo falla con mensaje, sin tocar la red`() {
        val coordinator = TestCoordinator(repoReturning(OverrideResult.Granted("tok", "Laura")))

        val result = runBlocking { coordinator.submitPin("venue_1", "1234") }

        assertEquals(
            OverrideResult.Failed("La acción ya no está esperando autorización."),
            result,
        )
    }
}
