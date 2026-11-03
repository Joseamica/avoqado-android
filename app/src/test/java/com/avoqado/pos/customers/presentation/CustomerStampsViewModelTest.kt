package com.avoqado.pos.customers.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.ResultadoDeAjuste
import com.avoqado.pos.customers.data.SellosRepository
import com.avoqado.pos.customers.data.TarjetaDeSellos
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class CustomerStampsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = mockk<SellosRepository>()
    private val roles = mockk<RoleManager>()
    private val tarjeta = TarjetaDeSellos(
        stampsEarned = 3, stampsRequired = 8, rewardLabel = "Cafe gratis", pendingRewards = 0,
        stampCardId = "card-1", version = 4, stampsEnabled = true,
    )

    private fun vm(puede: Boolean = true): CustomerStampsViewModel {
        every { roles.canAdjustLoyalty } returns puede
        return CustomerStampsViewModel(repo, roles)
    }

    @Test
    fun `P1 sin loyalty-adjust se ve la tarjeta pero no el boton`() {
        coEvery { repo.obtener("c1") } returns Result.success(tarjeta)
        val v = vm(puede = false)
        v.cargar("c1")
        assertEquals(EstadoDeSellos.Listo(tarjeta, puedeAjustar = false), v.estado.value)
    }

    @Test
    fun `P1 sin plan o sin sellos la seccion se oculta`() {
        coEvery { repo.obtener("c1") } returns Result.success(null)
        val v = vm()
        v.cargar("c1")
        assertEquals(EstadoDeSellos.Oculto, v.estado.value)
    }

    @Test
    fun `P1 sin red dice que se ajusta con internet y no manda nada`() {
        coEvery { repo.obtener("c1") } returns Result.failure(IOException("sin red"))
        val v = vm()
        v.cargar("c1")
        assertEquals(EstadoDeSellos.SinRed, v.estado.value)
        v.abrirAjuste() // sin lectura vigente no abre
        assertNull(v.ajusteAbierto.value)
        v.guardarAjuste(5, "Migracion de sellos")
        coVerify(exactly = 0) { repo.ajustar(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 guardar manda la foto de la apertura, recarga y cierra`() {
        coEvery { repo.obtener("c1") } returns Result.success(tarjeta)
        coEvery { repo.ajustar("c1", 5, "Migracion de sellos", tarjeta) } returns ResultadoDeAjuste.Hecho(5, false)
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        assertEquals("c1", v.ajusteAbierto.value?.customerId)
        assertEquals(tarjeta, v.ajusteAbierto.value?.foto)
        v.guardarAjuste(5, "Migracion de sellos")
        coVerify(exactly = 1) { repo.ajustar("c1", 5, "Migracion de sellos", tarjeta) }
        coVerify(exactly = 2) { repo.obtener("c1") }
        assertEquals(ResultadoDeAjuste.Hecho(5, false), v.resultado.value)
        assertNull(v.ajusteAbierto.value)
    }

    @Test
    fun `P1 un 409 STAMPS_CHANGED avisa el numero nuevo, recarga y cierra el dialogo`() {
        coEvery { repo.obtener("c1") } returns Result.success(tarjeta)
        coEvery { repo.ajustar(any(), any(), any(), any()) } returns ResultadoDeAjuste.Cambio(6)
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        v.guardarAjuste(5, "Migracion de sellos")
        assertEquals(ResultadoDeAjuste.Cambio(6), v.resultado.value)
        coVerify(exactly = 2) { repo.obtener("c1") }
        assertTrue(v.estado.value is EstadoDeSellos.Listo)
        assertFalse(v.estado.value is EstadoDeSellos.Cargando)
        // Decidido: un 409 propio cierra el diálogo; reabrir es un acto nuevo con la lectura nueva.
        assertNull(v.ajusteAbierto.value)
    }

    @Test
    fun `P1 la respuesta tardia de otro cliente no pisa la tarjeta del cliente actual`() {
        val tardiaA = CompletableDeferred<Result<TarjetaDeSellos?>>()
        val tarjetaB = tarjeta.copy(stampsEarned = 7, stampCardId = "card-B")
        coEvery { repo.obtener("A") } coAnswers { tardiaA.await() }
        coEvery { repo.obtener("B") } returns Result.success(tarjetaB)
        val v = vm()
        v.cargar("A")
        v.cargar("B")
        tardiaA.complete(Result.success(tarjeta))
        assertEquals(EstadoDeSellos.Listo(tarjetaB, true), v.estado.value)
    }

    @Test
    fun `P1 dos guardar seguidos mandan un solo POST`() {
        val enVuelo = CompletableDeferred<ResultadoDeAjuste>()
        coEvery { repo.obtener("c1") } returns Result.success(tarjeta)
        coEvery { repo.ajustar(any(), any(), any(), any()) } coAnswers { enVuelo.await() }
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        v.guardarAjuste(5, "Migracion de sellos")
        assertTrue(v.guardando.value)
        v.guardarAjuste(5, "Migracion de sellos")
        enVuelo.complete(ResultadoDeAjuste.Hecho(5, false))
        coVerify(exactly = 1) { repo.ajustar(any(), any(), any(), any()) }
        assertFalse(v.guardando.value)
    }

    // ── Pasada de la familia (24-oct): el ViewModel es dueño de la apertura ─────────────────────────────────────────────

    /** F6: la prueba NO pasa la tarjeta; la foto es la que el ViewModel capturó al abrir. */
    @Test
    fun `P1 abrir, recargar otra tarjeta y guardar manda la foto de la apertura`() {
        val nueva = tarjeta.copy(stampsEarned = 0, stampCardId = "card-2", version = 0)
        coEvery { repo.obtener("c1") } returnsMany listOf(Result.success(tarjeta), Result.success(nueva))
        coEvery { repo.ajustar(any(), any(), any(), any()) } returns ResultadoDeAjuste.Hecho(8, true)
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        v.cargar("c1") // una venta completó la tarjeta y la recarga trae otra en 0
        assertEquals(EstadoDeSellos.Listo(nueva, true), v.estado.value)
        v.guardarAjuste(8, "Migracion de sellos")
        coVerify(exactly = 1) { repo.ajustar("c1", 8, "Migracion de sellos", tarjeta) }
    }

    @Test
    fun `P1 abrir en A, cambiar a B y guardar no manda nada`() {
        val tarjetaB = tarjeta.copy(stampsEarned = 7, stampCardId = "card-B")
        coEvery { repo.obtener("A") } returns Result.success(tarjeta)
        coEvery { repo.obtener("B") } returns Result.success(tarjetaB)
        coEvery { repo.ajustar(any(), any(), any(), any()) } returns ResultadoDeAjuste.Hecho(5, false)
        val v = vm()
        v.cargar("A")
        v.abrirAjuste()
        v.cargar("B")
        v.guardarAjuste(5, "Migracion de sellos")
        coVerify(exactly = 0) { repo.ajustar(any(), any(), any(), any()) }
    }

    /**
     * F6: Guardar en la apertura 1, cerrarla, abrir la 2 y guardar en ella. Cuando por fin llega el desenlace de la 1, la 2
     * sigue abierta con su identidad y su foto, sin resultado, y el `finally` de la 1 no le quita el «guardando».
     */
    private fun cerrarYReabrirIgnora(desenlace: ResultadoDeAjuste) {
        val primera = CompletableDeferred<ResultadoDeAjuste>()
        val segunda = CompletableDeferred<ResultadoDeAjuste>()
        val respuestas = ArrayDeque(listOf(primera, segunda))
        coEvery { repo.obtener("c1") } returns Result.success(tarjeta)
        coEvery { repo.ajustar(any(), any(), any(), any()) } coAnswers { respuestas.removeFirst().await() }
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        val apertura1 = v.ajusteAbierto.value
        v.guardarAjuste(5, "Migracion de sellos")
        v.cerrarAjuste()
        v.abrirAjuste()
        val apertura2 = v.ajusteAbierto.value
        assertNotNull(apertura2)
        assertNotEquals(apertura1?.id, apertura2?.id)
        v.guardarAjuste(6, "Segunda apertura")
        assertTrue(v.guardando.value)

        primera.complete(desenlace)

        assertEquals("$desenlace: la apertura 2 sigue intacta y abierta", apertura2, v.ajusteAbierto.value)
        assertNull("$desenlace: sin resultado de la apertura 1", v.resultado.value)
        assertTrue("$desenlace: el finally de la 1 no libera la 2", v.guardando.value)
        coVerify(exactly = 1) { repo.ajustar("c1", 6, "Segunda apertura", tarjeta) }

        segunda.complete(ResultadoDeAjuste.Hecho(6, false))
        assertFalse(v.guardando.value)
    }

    @Test fun `P1 cerrar y reabrir ignora el Hecho de la apertura anterior`() = cerrarYReabrirIgnora(ResultadoDeAjuste.Hecho(5, false))

    @Test fun `P1 cerrar y reabrir ignora el Cambio de la apertura anterior`() = cerrarYReabrirIgnora(ResultadoDeAjuste.Cambio(6))

    @Test fun `P1 cerrar y reabrir ignora el Apagado de la apertura anterior`() = cerrarYReabrirIgnora(ResultadoDeAjuste.Apagado)

    @Test fun `P1 cerrar y reabrir ignora el SinRed de la apertura anterior`() = cerrarYReabrirIgnora(ResultadoDeAjuste.SinRed)

    @Test fun `P1 cerrar y reabrir ignora el Error de la apertura anterior`() = cerrarYReabrirIgnora(ResultadoDeAjuste.Error("x"))

    /** F7 (mitad del ViewModel): el aviso del 409 no depende de que la recarga deje la lectura en `Listo`. */
    private fun avisoSigue(recarga: Result<TarjetaDeSellos?>, estadoTrasRecarga: EstadoDeSellos) {
        coEvery { repo.obtener("c1") } returnsMany listOf(Result.success(tarjeta), recarga)
        coEvery { repo.ajustar(any(), any(), any(), any()) } returns ResultadoDeAjuste.Cambio(6)
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        v.guardarAjuste(5, "Migracion de sellos")
        assertEquals(estadoTrasRecarga, v.estado.value)
        assertEquals(ResultadoDeAjuste.Cambio(6), v.resultado.value)
    }

    @Test fun `P1 el aviso de un 409 sigue si la recarga dice que los sellos se apagaron`() =
        avisoSigue(Result.success(null), EstadoDeSellos.Oculto)

    @Test fun `P1 el aviso de un 409 sigue si la recarga no tiene red`() =
        avisoSigue(Result.failure(IOException("sin red")), EstadoDeSellos.SinRed)

    // ── Full-testing en la D3 (24-oct), W4: al volver la red el aviso «Sin conexión» se quedaba pegado ──────────────────

    /** Volver a guardar es un acto nuevo: el aviso viejo se va en cuanto sale el reintento, y el reintento sí se manda. */
    @Test
    fun `W4 tras SinRed, volver a guardar limpia el aviso y manda otra vez`() {
        val enVuelo = CompletableDeferred<ResultadoDeAjuste>()
        coEvery { repo.obtener("c1") } returns Result.success(tarjeta)
        var envios = 0
        coEvery { repo.ajustar(any(), any(), any(), any()) } coAnswers { if (envios++ == 0) ResultadoDeAjuste.SinRed else enVuelo.await() }
        val v = vm()
        v.cargar("c1")
        v.abrirAjuste()
        v.guardarAjuste(5, "Migracion de sellos")
        assertEquals(ResultadoDeAjuste.SinRed, v.resultado.value)
        v.guardarAjuste(5, "Migracion de sellos")
        assertNull(v.resultado.value)
        enVuelo.complete(ResultadoDeAjuste.Hecho(5, false))
        coVerify(exactly = 2) { repo.ajustar(any(), any(), any(), any()) }
        assertEquals(ResultadoDeAjuste.Hecho(5, false), v.resultado.value)
    }
}
