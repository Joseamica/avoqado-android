package com.avoqado.pos.core.data.network

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * D24 (4-nov-2026): un solo PIN de gerente por RÁFAGA marcada.
 *
 * Informes hace 4 GET a la vez con `reports:read`. Con el rol sin ese permiso y el PIN de gerente prendido, cada 403
 * abría su propio teclado en fila: 4 teclados seguidos, y cancelar uno sólo dejaba pasar al siguiente (D3, 4-nov 00:15).
 * Ahora Informes marca sus 4 consultas con la misma ráfaga y comparten un teclado. Sin marca, todo sigue como antes.
 *
 * Igual que [ManagerOverrideCoordinatorTest]: hilos de verdad, porque `awaitToken` bloquea su hilo.
 */
class UnPinPorRafagaTest {

    private class TestCoordinator(
        repository: PermissionOverrideRepository,
        override val promptTimeoutMs: Long = 30_000L,
    ) : ManagerOverrideCoordinator(repository)

    /** Cada token sale distinto (tok1, tok2…), como en el servidor; cuenta cuántos se pidieron y para qué permiso. */
    private class RepoQueCuenta(
        private val falla: (Int) -> OverrideResult? = { null },
    ) {
        val pedidos: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val repo: PermissionOverrideRepository = mockk<PermissionOverrideRepository>().also { repo ->
            coEvery { repo.requestToken(any(), any(), any()) } answers {
                pedidos.add(thirdArg())
                val n = pedidos.size
                falla(n) ?: OverrideResult.Granted("tok$n", "Laura")
            }
        }
    }

    private fun ManagerOverrideCoordinator.esperarTeclado(): ManagerOverrideCoordinator.Prompt {
        val limite = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < limite) {
            prompt.value?.let { return it }
            Thread.sleep(5)
        }
        error("El teclado nunca apareció")
    }

    /** El teclado se cierra un instante después de entregar el token: espera al SIGUIENTE, no al que se está cerrando. */
    private fun ManagerOverrideCoordinator.esperarOtroTeclado(anterior: Long): ManagerOverrideCoordinator.Prompt {
        val limite = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < limite) {
            prompt.value?.takeIf { it.id != anterior }?.let { return it }
            Thread.sleep(5)
        }
        error("El siguiente teclado nunca apareció")
    }

    /** Espera a que [n] consultas estén unidas al grupo de [rafaga] (sin contar a la que abrió el teclado). */
    private fun ManagerOverrideCoordinator.esperarUnidas(rafaga: String, permiso: String, n: Int) {
        val limite = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < limite) {
            if (unidasAlGrupo(rafaga, permiso) == n) return
            Thread.sleep(5)
        }
        error("Se esperaban $n consultas unidas a $rafaga y hay ${unidasAlGrupo(rafaga, permiso)}")
    }

    /**
     * Une [n] consultas de [rafaga] UNA POR UNA, esperando que cada una entre antes de lanzar la siguiente (Codex código
     * r1 #4: crear hilos en orden no garantiza que entren en orden).
     */
    private fun ManagerOverrideCoordinator.unirEnOrden(n: Int, rafaga: String = "carga-1"): List<Consulta> =
        (1..n).map { i ->
            informe(this, rafaga).also { esperarUnidas(rafaga, "reports:read", i) }
        }

    /** Una consulta que recibió un 403 y espera su token en su propio hilo, como el interceptor. */
    private class Consulta(
        val coordinator: ManagerOverrideCoordinator,
        permiso: String,
        rafaga: String?,
        vivo: () -> Boolean = { true },
    ) {
        @Volatile var token: String? = "sin-resolver"
        val listo = CountDownLatch(1)

        init {
            thread {
                token = coordinator.awaitToken(permiso, rafaga = rafaga, vivo = vivo)
                listo.countDown()
            }
        }
    }

    private fun informe(coordinator: ManagerOverrideCoordinator, rafaga: String = "carga-1", vivo: () -> Boolean = { true }) =
        Consulta(coordinator, "reports:read", rafaga, vivo)

    @Test
    fun `cuatro consultas de la misma rafaga comparten UN teclado y cada una recibe su propio token`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)
        val antes = coordinator.tecladosPublicados

        val primera = informe(coordinator)
        coordinator.esperarTeclado()
        val otras = coordinator.unirEnOrden(3)

        val resultado = runBlocking { coordinator.submitPin("venue_1", "1234", coordinator.prompt.value?.id) }

        assertTrue(resultado is OverrideResult.Granted)
        (otras + primera).forEach { assertTrue(it.listo.await(3, TimeUnit.SECONDS)) }
        val tokens = (listOf(primera) + otras).map { it.token }
        assertEquals("cada consulta con su token de un solo uso", 4, tokens.toSet().size)
        assertTrue(tokens.all { it != null && it.startsWith("tok") })
        // Cada una recibe el suyo en cuanto sale, por orden de llegada (Codex r1 #3: ningún token espera a los demás).
        assertEquals("tok1", primera.token)
        assertEquals(listOf("tok2", "tok3", "tok4"), otras.map { it.token })
        assertEquals(List(4) { "reports:read" }, red.pedidos.toList())
        Thread.sleep(150)
        assertNull("no debe salir otro teclado", coordinator.prompt.value)
        assertEquals("UN solo teclado para la ráfaga", antes + 1, coordinator.tecladosPublicados)
    }

    @Test
    fun `cancelar suelta a TODA la rafaga sin abrir otro teclado ni pedir tokens`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)
        val antes = coordinator.tecladosPublicados

        val primera = informe(coordinator)
        val id = coordinator.esperarTeclado().id
        val otras = coordinator.unirEnOrden(3)

        coordinator.cancel(id)

        (otras + primera).forEach {
            assertTrue(it.listo.await(3, TimeUnit.SECONDS))
            assertNull(it.token)
        }
        Thread.sleep(150)
        assertNull(coordinator.prompt.value)
        assertEquals(antes + 1, coordinator.tecladosPublicados)
        assertEquals(0, red.pedidos.size)
    }

    /** Codex r1 #1: una respuesta de la misma carga que llega DESPUÉS de cancelar no vuelve a preguntar. */
    @Test
    fun `una respuesta tardia de una carga cancelada no abre otro teclado, y Reintentar si pregunta`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator)
        coordinator.cancel(coordinator.esperarTeclado().id)
        assertTrue(primera.listo.await(3, TimeUnit.SECONDS))

        val tarde = informe(coordinator)
        assertTrue("la tardía se resuelve sola", tarde.listo.await(3, TimeUnit.SECONDS))
        assertNull(tarde.token)
        Thread.sleep(100)
        assertNull("sin teclado para la tardía", coordinator.prompt.value)

        // «Reintentar» es otra carga: pregunta otra vez, una vez.
        val reintento = informe(coordinator, rafaga = "carga-2")
        coordinator.esperarTeclado()
        runBlocking { coordinator.submitPin("venue_1", "1234", coordinator.prompt.value?.id) }
        assertTrue(reintento.listo.await(3, TimeUnit.SECONDS))
        assertEquals("tok1", reintento.token)
    }

    @Test
    fun `si vence el teclado, la rafaga entera recibe null y la tardia tampoco pregunta`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo, promptTimeoutMs = 200L)

        val primera = informe(coordinator)
        coordinator.esperarTeclado()
        val otras = coordinator.unirEnOrden(3)

        (otras + primera).forEach {
            assertTrue("la espera debió vencer sola", it.listo.await(3, TimeUnit.SECONDS))
            assertNull(it.token)
        }
        assertNull(coordinator.prompt.value)
        assertEquals(0, coordinator.unidasAlGrupo("carga-1", "reports:read"))

        val tarde = informe(coordinator)
        assertTrue(tarde.listo.await(3, TimeUnit.SECONDS))
        assertNull(tarde.token)
    }

    /** 🔴 Sin marca, cada petición pide su PIN como hoy: un doble toque en «Reembolsar» no pasa con un solo PIN. */
    @Test
    fun `sin marca de rafaga cada peticion sigue pidiendo su propio teclado`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)

        val a = Consulta(coordinator, "payments:refund", rafaga = null)
        val idA = coordinator.esperarTeclado().id
        val b = Consulta(coordinator, "payments:refund", rafaga = null)
        Thread.sleep(100)

        runBlocking { coordinator.submitPin("venue_1", "1234", idA) }
        assertTrue(a.listo.await(3, TimeUnit.SECONDS))
        assertEquals("tok1", a.token)
        assertEquals("un solo token por PIN tecleado", 1, red.pedidos.size)

        // La segunda saca SU teclado.
        val idB = coordinator.esperarOtroTeclado(idA).id
        assertNotEquals(idA, idB)
        coordinator.cancel(idB)
        assertTrue(b.listo.await(3, TimeUnit.SECONDS))
        assertNull(b.token)
    }

    @Test
    fun `otra rafaga u otro permiso no se unen al grupo`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)

        val reportes = informe(coordinator)
        val idReportes = coordinator.esperarTeclado().id
        val otraCarga = informe(coordinator, rafaga = "carga-2")
        val otroPermiso = Consulta(coordinator, "orders:read", rafaga = "carga-1")
        Thread.sleep(100)
        assertEquals(0, coordinator.unidasAlGrupo("carga-1", "reports:read"))

        coordinator.cancel(idReportes)
        assertTrue(reportes.listo.await(3, TimeUnit.SECONDS))
        assertNull(reportes.token)

        // Las otras dos tienen su propio teclado y no las tumbó la cancelación de la primera.
        var anterior = idReportes
        repeat(2) {
            val limite = System.currentTimeMillis() + 3_000
            var teclado = coordinator.prompt.value
            while ((teclado == null || teclado.id == anterior) && System.currentTimeMillis() < limite) {
                Thread.sleep(5)
                teclado = coordinator.prompt.value
            }
            val vivo = requireNotNull(teclado) { "El siguiente teclado nunca apareció" }
            runBlocking { coordinator.submitPin("venue_1", "1234", vivo.id) }
            anterior = vivo.id
        }
        assertTrue(otraCarga.listo.await(3, TimeUnit.SECONDS))
        assertTrue(otroPermiso.listo.await(3, TimeUnit.SECONDS))
        assertEquals(setOf("tok1", "tok2"), setOf(otraCarga.token, otroPermiso.token))
    }

    @Test
    fun `si falla un token posterior, ese y los que faltan reciben null`() {
        // 1.º (la que abrió) y 2.º salen; el 3.º es 429 → se deja de pedir.
        val red = RepoQueCuenta(falla = { n -> if (n == 3) OverrideResult.TooManyAttempts else null })
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator)
        coordinator.esperarTeclado()
        val otras = coordinator.unirEnOrden(3)

        val resultado = runBlocking { coordinator.submitPin("venue_1", "1234", coordinator.prompt.value?.id) }

        assertTrue("el PIN era bueno", resultado is OverrideResult.Granted)
        (otras + primera).forEach { assertTrue(it.listo.await(3, TimeUnit.SECONDS)) }
        assertEquals("tok1", primera.token)
        assertEquals(listOf("tok2", null, null), otras.map { it.token })
        assertEquals("tras el 429 no se insiste", 3, red.pedidos.size)
        assertNull(coordinator.prompt.value)
    }

    /** El primer token es el que valida el PIN: si sale mal, el teclado sigue y nadie se suelta (como hoy). */
    @Test
    fun `un PIN malo deja el teclado abierto y a la rafaga esperando`() {
        val red = RepoQueCuenta(falla = { n -> if (n == 1) OverrideResult.WrongPin else null })
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator)
        val id = coordinator.esperarTeclado().id
        val otra = informe(coordinator)
        coordinator.esperarUnidas("carga-1", "reports:read", 1)

        assertEquals(OverrideResult.WrongPin, runBlocking { coordinator.submitPin("venue_1", "0000", id) })
        Thread.sleep(100)
        assertEquals(id, coordinator.prompt.value?.id)
        assertEquals(1, primera.listo.count)
        assertEquals(1, otra.listo.count)

        // El segundo intento, bueno, reparte.
        assertTrue(runBlocking { coordinator.submitPin("venue_1", "1234", id) } is OverrideResult.Granted)
        assertTrue(primera.listo.await(3, TimeUnit.SECONDS))
        assertTrue(otra.listo.await(3, TimeUnit.SECONDS))
        assertEquals("tok2", primera.token)
        assertEquals("tok3", otra.token)
    }

    /** D22 corta la consulta cuando la pantalla deja de verse: a esa ya no se le gasta un intento del límite. */
    @Test
    fun `una consulta ya cancelada no gasta un token`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator)
        coordinator.esperarTeclado()
        val viva = informe(coordinator)
        coordinator.esperarUnidas("carga-1", "reports:read", 1)
        val cancelada = informe(coordinator, vivo = { false })
        coordinator.esperarUnidas("carga-1", "reports:read", 2)

        runBlocking { coordinator.submitPin("venue_1", "1234", coordinator.prompt.value?.id) }

        listOf(primera, viva, cancelada).forEach { assertTrue(it.listo.await(3, TimeUnit.SECONDS)) }
        assertEquals(2, red.pedidos.size)
        assertNull(cancelada.token)
        assertEquals("tok1", primera.token)
        assertEquals("tok2", viva.token)
    }

    /** Codex r1 #4: también la que abrió el teclado puede estar cancelada; si ninguna sigue viva, el PIN no se manda. */
    @Test
    fun `si la que abrio esta cancelada solo reciben las vivas, y si ninguna vive no se manda el PIN`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator, vivo = { false })
        coordinator.esperarTeclado()
        val viva = informe(coordinator)
        coordinator.esperarUnidas("carga-1", "reports:read", 1)
        runBlocking { coordinator.submitPin("venue_1", "1234", coordinator.prompt.value?.id) }
        assertTrue(primera.listo.await(3, TimeUnit.SECONDS))
        assertTrue(viva.listo.await(3, TimeUnit.SECONDS))
        assertNull(primera.token)
        assertEquals("tok1", viva.token)
        assertEquals(1, red.pedidos.size)

        val sola = informe(coordinator, rafaga = "carga-2", vivo = { false })
        val id = coordinator.esperarTeclado().id
        val resultado = runBlocking { coordinator.submitPin("venue_1", "1234", id) }
        assertTrue(resultado is OverrideResult.Failed)
        assertTrue(sola.listo.await(3, TimeUnit.SECONDS))
        assertNull(sola.token)
        assertEquals("no se mandó el PIN", 1, red.pedidos.size)
        Thread.sleep(100)
        assertNull(coordinator.prompt.value)
    }

    /** Si el envío se cancela a media repartición, nadie se queda esperando para siempre. */
    @Test
    fun `si se cancela el envio a media reparticion nadie queda colgado`() {
        val segundoEnCamino = CountDownLatch(1)
        val nunca = CompletableDeferred<OverrideResult>()
        val pedidos = AtomicInteger(0)
        val repo = mockk<PermissionOverrideRepository>()
        coEvery { repo.requestToken(any(), any(), any()) } coAnswers {
            if (pedidos.incrementAndGet() == 1) {
                OverrideResult.Granted("tok1", "Laura")
            } else {
                segundoEnCamino.countDown()
                nunca.await()
            }
        }
        val coordinator = TestCoordinator(repo)

        val primera = informe(coordinator)
        val id = coordinator.esperarTeclado().id
        val otras = coordinator.unirEnOrden(2)

        runBlocking {
            val envio = launch(Dispatchers.Default) { coordinator.submitPin("venue_1", "1234", id) }
            assertTrue(segundoEnCamino.await(3, TimeUnit.SECONDS))
            envio.cancel()
        }

        (otras + primera).forEach { assertTrue("nadie se queda colgado", it.listo.await(3, TimeUnit.SECONDS)) }
        // La que abrió ya tenía el suyo (salió primero); las que faltaban se sueltan.
        assertEquals("tok1", primera.token)
        assertEquals(listOf(null, null), otras.map { it.token })
        Thread.sleep(100)
        assertNull(coordinator.prompt.value)
    }

    /** Un repositorio cuyo token número [frenado] se queda en camino hasta que la prueba lo suelta. */
    private class RepoConFreno(private val frenado: Int) {
        val enCamino = CountDownLatch(1)
        val suelta = CountDownLatch(1)
        val pedidos = AtomicInteger(0)
        val repo: PermissionOverrideRepository = mockk<PermissionOverrideRepository>().also { repo ->
            coEvery { repo.requestToken(any(), any(), any()) } coAnswers {
                val n = pedidos.incrementAndGet()
                if (n == frenado) {
                    enCamino.countDown()
                    withContext(Dispatchers.IO) { suelta.await(5, TimeUnit.SECONDS) }
                }
                OverrideResult.Granted("tok$n", "Laura")
            }
        }
    }

    /** Codex código r1 #1: cancelar mientras sale un token. Gana cancelar: el token se tira y nadie lo usa. */
    @Test
    fun `cancelar mientras sale el token gana cancelar y el token se tira`() {
        val red = RepoConFreno(frenado = 1)
        val coordinator = TestCoordinator(red.repo)

        val sola = Consulta(coordinator, "orders:merge", rafaga = null)
        val id = coordinator.esperarTeclado().id
        runBlocking {
            val envio = launch(Dispatchers.Default) { coordinator.submitPin("venue_1", "1234", id) }
            assertTrue(red.enCamino.await(3, TimeUnit.SECONDS))
            coordinator.cancel(id)
            assertTrue(sola.listo.await(3, TimeUnit.SECONDS))
            red.suelta.countDown()
            envio.join()
        }
        assertNull("el token que llegó tarde no se entrega", sola.token)
        assertNull(coordinator.prompt.value)
    }

    /** Codex código r1 #1: lo mismo si el teclado VENCE mientras sale el token. */
    @Test
    fun `si el teclado vence mientras sale el token, el token se tira`() {
        val red = RepoConFreno(frenado = 1)
        val coordinator = TestCoordinator(red.repo, promptTimeoutMs = 400L)

        val sola = Consulta(coordinator, "orders:merge", rafaga = null)
        val id = coordinator.esperarTeclado().id
        runBlocking {
            val envio = launch(Dispatchers.Default) { coordinator.submitPin("venue_1", "1234", id) }
            assertTrue(red.enCamino.await(3, TimeUnit.SECONDS))
            assertTrue("vence solo", sola.listo.await(3, TimeUnit.SECONDS))
            red.suelta.countDown()
            envio.join()
        }
        assertNull(sola.token)
    }

    /** Codex código r1 #2: cancelar a MEDIA repartición también anota la carga; su respuesta tardía no pregunta. */
    @Test
    fun `cancelar a media reparticion anota la carga y su respuesta tardia no pregunta`() {
        val red = RepoConFreno(frenado = 2)
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator)
        val id = coordinator.esperarTeclado().id
        val otra = coordinator.unirEnOrden(1).single()
        runBlocking {
            val envio = launch(Dispatchers.Default) { coordinator.submitPin("venue_1", "1234", id) }
            assertTrue(red.enCamino.await(3, TimeUnit.SECONDS))
            coordinator.cancel(id)
            red.suelta.countDown()
            envio.join()
        }
        assertTrue(primera.listo.await(3, TimeUnit.SECONDS))
        assertTrue(otra.listo.await(3, TimeUnit.SECONDS))
        assertEquals("tok1", primera.token)
        assertNull(otra.token)

        val publicados = coordinator.tecladosPublicados
        val tarde = informe(coordinator)
        assertTrue(tarde.listo.await(3, TimeUnit.SECONDS))
        assertNull(tarde.token)
        assertEquals("sin teclado nuevo", publicados, coordinator.tecladosPublicados)
    }

    /** Una consulta que llega cuando su grupo ya repartió abre el suyo: nunca se queda sin respuesta. */
    @Test
    fun `una respuesta tardia de una carga ya autorizada abre su propio teclado`() {
        val red = RepoQueCuenta()
        val coordinator = TestCoordinator(red.repo)

        val primera = informe(coordinator)
        val idPrimero = coordinator.esperarTeclado().id
        runBlocking { coordinator.submitPin("venue_1", "1234", idPrimero) }
        assertTrue(primera.listo.await(3, TimeUnit.SECONDS))
        assertEquals("tok1", primera.token)

        val tarde = informe(coordinator)
        val idTarde = coordinator.esperarOtroTeclado(idPrimero).id
        assertNotEquals(idPrimero, idTarde)
        coordinator.cancel(idTarde)
        assertTrue(tarde.listo.await(3, TimeUnit.SECONDS))
        assertNull(tarde.token)
    }
}
