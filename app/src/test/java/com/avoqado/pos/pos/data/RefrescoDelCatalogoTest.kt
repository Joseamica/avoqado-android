package com.avoqado.pos.pos.data

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Se cobra el precio que vio el cliente» (Task 9): la caja baja el catálogo sola (primer plano, cada 5 min, al volver
 * la red) para que el precio que muestra no pase de las 72 h que el servidor respeta. Sin reloj real: tiempo virtual.
 * Todos los ciclos se detienen con `onStop` dentro del cuerpo (un `delay` infinito vivo hace girar a `runTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefrescoDelCatalogoTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    @Test
    fun `al volver a primer plano refresca y cada 5 minutos, y en segundo plano se detiene`() = runTest {
        var refrescos = 0
        val refresco = RefrescoDelCatalogo({ refrescos++ }, MutableStateFlow(true), backgroundScope)

        refresco.onStart(owner)
        runCurrent()
        assertEquals("al volver a primer plano refresca ya", 1, refrescos)

        advanceTimeBy(RefrescoDelCatalogo.INTERVALO_MS - 1)
        runCurrent()
        assertEquals("antes de los 5 minutos no refresca", 1, refrescos)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("a los 5 minutos refresca", 2, refrescos)

        refresco.onStop(owner)
        advanceTimeBy(3 * RefrescoDelCatalogo.INTERVALO_MS)
        runCurrent()
        assertEquals("en segundo plano no refresca", 2, refrescos)
    }

    @Test
    fun `al volver la red refresca una vez`() = runTest {
        var refrescos = 0
        val red = MutableStateFlow(true)
        val refresco = RefrescoDelCatalogo({ refrescos++ }, red, backgroundScope)

        refresco.onStart(owner)
        runCurrent()
        assertEquals(1, refrescos)

        red.value = false
        runCurrent()
        assertEquals("perder la red no refresca", 1, refrescos)

        red.value = true
        runCurrent()
        assertEquals("al volver la red refresca una vez", 2, refrescos)

        red.value = true
        runCurrent()
        assertEquals("seguir con red no vuelve a refrescar", 2, refrescos)

        refresco.onStop(owner)
        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
        assertEquals("en segundo plano tampoco (al volver, onStart refresca)", 2, refrescos)
    }

    @Test
    fun `abrir sin red refresca ya y al volver la red otra vez`() = runTest {
        var refrescos = 0
        val red = MutableStateFlow(false)
        val refresco = RefrescoDelCatalogo({ refrescos++ }, red, backgroundScope)

        refresco.onStart(owner)
        runCurrent()
        assertEquals("sin red el intento es silencioso, pero se hace", 1, refrescos)

        red.value = true
        runCurrent()
        assertEquals(2, refrescos)

        refresco.onStop(owner)
    }

    @Test
    fun `un refresco que truena no apaga el ciclo`() = runTest {
        var refrescos = 0
        val refresco = RefrescoDelCatalogo(
            {
                refrescos++
                if (refrescos <= 2) throw IllegalStateException("el servidor se cayó a media respuesta")
            },
            MutableStateFlow(true),
            backgroundScope,
        )

        refresco.onStart(owner)
        runCurrent()
        assertEquals("el primero truena", 1, refrescos)

        advanceTimeBy(RefrescoDelCatalogo.INTERVALO_MS)
        runCurrent()
        assertEquals("el ciclo sigue: el segundo también truena", 2, refrescos)

        advanceTimeBy(RefrescoDelCatalogo.INTERVALO_MS)
        runCurrent()
        assertEquals("y el tercero ya corre", 3, refrescos)

        refresco.onStop(owner)
    }

    @Test
    fun `refrescarAhora refresca en su propio scope, con o sin ciclo`() = runTest {
        var refrescos = 0
        val refresco = RefrescoDelCatalogo({ refrescos++ }, MutableStateFlow(true), backgroundScope)

        refresco.refrescarAhora()
        runCurrent()
        assertEquals("sin haber arrancado el ciclo", 1, refrescos)

        refresco.onStart(owner)
        runCurrent()
        refresco.refrescarAhora()
        runCurrent()
        assertEquals("el del ciclo + el de ahora", 3, refrescos)

        refresco.onStop(owner)
    }

    /**
     * Al volver a primer plano `onStart` y la reconexión pueden coincidir (y el reloj de 5 min): con una petición todavía
     * colgada esos disparos INTERNOS se descartan, no se encolan. Cuando la primera termina (o truena) se puede refrescar.
     */
    @Test
    fun `con un refresco en vuelo los disparos internos no lanzan otro ni dejan uno pendiente`() = runTest {
        var llamadas = 0
        val colgada = CompletableDeferred<Unit>()
        val red = MutableStateFlow(false)
        val refresco = RefrescoDelCatalogo({ llamadas++; colgada.await() }, red, backgroundScope)

        refresco.onStart(owner)
        runCurrent()
        assertEquals("onStart lanza el primero y queda colgado", 1, llamadas)

        red.value = true
        runCurrent()
        advanceTimeBy(RefrescoDelCatalogo.INTERVALO_MS)
        runCurrent()
        assertEquals("la reconexión y el reloj de 5 min no lanzan otro", 1, llamadas)

        colgada.complete(Unit)
        runCurrent()
        assertEquals("y al terminar el primero no queda nada pendiente", 1, llamadas)

        refresco.refrescarAhora()
        runCurrent()
        assertEquals("terminado el primero, el siguiente sí corre", 2, llamadas)

        refresco.onStop(owner)
    }

    /**
     * Regla del controlador: tras un 409 de precio la app pide la lista nueva con `refrescarAhora()`. Si ya había un
     * refresco en vuelo que arrancó ANTES del cambio, traería la lista vieja; por eso el explícito no se descarta: se
     * marca «pendiente» (uno solo, no se acumulan) y corre UNO más cuando termina el que iba en vuelo.
     */
    @Test
    fun `refrescarAhora con uno en vuelo queda pendiente y corre exactamente uno mas al terminar`() = runTest {
        var llamadas = 0
        val colgada = CompletableDeferred<Unit>()
        val refresco = RefrescoDelCatalogo({ llamadas++; colgada.await() }, MutableStateFlow(true), backgroundScope)

        refresco.refrescarAhora()
        runCurrent()
        refresco.refrescarAhora()
        refresco.refrescarAhora()
        runCurrent()
        assertEquals("mientras cuelga, tres disparos hacen una sola llamada", 1, llamadas)

        colgada.complete(Unit)
        runCurrent()
        assertEquals("al soltarla sale exactamente uno más, no dos", 2, llamadas)

        advanceTimeBy(RefrescoDelCatalogo.INTERVALO_MS)
        runCurrent()
        assertEquals("y no queda nada pendiente", 2, llamadas)

        refresco.refrescarAhora()
        runCurrent()
        assertEquals("el siguiente disparo vuelve a correr normal", 3, llamadas)
    }

    @Test
    fun `el pendiente tambien corre si el que iba en vuelo truena, y no se pierde con el segundo plano`() = runTest {
        var llamadas = 0
        val colgada = CompletableDeferred<Unit>()
        val refresco = RefrescoDelCatalogo(
            {
                llamadas++
                colgada.await()
                if (llamadas == 1) throw IllegalStateException("el servidor se cayó a media respuesta")
            },
            MutableStateFlow(true),
            backgroundScope,
        )

        refresco.onStart(owner)
        runCurrent()
        refresco.refrescarAhora()
        refresco.onStop(owner)
        runCurrent()
        assertEquals(1, llamadas)

        colgada.complete(Unit)
        runCurrent()
        assertEquals("truena el primero y aun así corre el pendiente", 2, llamadas)
    }

    @Test
    fun `pasar a segundo plano no cancela el refresco en vuelo ni deja trabado el siguiente`() = runTest {
        var empezados = 0
        var terminados = 0
        val colgada = CompletableDeferred<Unit>()
        val refresco = RefrescoDelCatalogo({ empezados++; colgada.await(); terminados++ }, MutableStateFlow(true), backgroundScope)

        refresco.onStart(owner)
        runCurrent()
        refresco.onStop(owner)
        runCurrent()
        colgada.complete(Unit)
        runCurrent()
        assertEquals("el refresco que ya iba en vuelo termina y actualiza el catálogo", 1, terminados)

        refresco.onStart(owner)
        runCurrent()
        assertEquals("al volver a primer plano refresca de nuevo", 2, empezados)
        refresco.onStop(owner)
    }

    /**
     * La carrera de la marca «pendiente» (con hilos de verdad): un `refrescarAhora()` que llega justo cuando el refresco
     * en vuelo termina de mirar si había pendiente no se puede perder. Invariante: después de CADA `refrescarAhora()`
     * arranca al menos un refresco nuevo (el que ya iba en vuelo pudo empezar antes del cambio que lo motivó).
     */
    @Test
    fun `un refrescarAhora nunca se pierde aunque llegue justo cuando termina el que iba en vuelo`() = runBlocking {
        val inicios = AtomicInteger(0)
        val propio = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val refresco = RefrescoDelCatalogo({ inicios.incrementAndGet(); yield() }, MutableStateFlow(true), propio)
        try {
            repeat(500) { vuelta ->
                val antes = inicios.get()
                refresco.refrescarAhora()
                withTimeout(5_000) { while (inicios.get() <= antes) delay(1) }
                assertTrue("vuelta $vuelta", inicios.get() > antes)
            }
        } finally {
            propio.cancel()
        }
    }
}
