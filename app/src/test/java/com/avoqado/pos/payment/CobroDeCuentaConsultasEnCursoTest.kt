package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.payment.presentation.cuentaEnConsulta
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.PreBillBalance
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * D14: «Reintentar» sólo cuando la consulta ya falló. La pantalla decide con `consultasEnCurso`, así que
 * el contador tiene que subir al entrar y bajar por TODAS las salidas de `cargar`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroDeCuentaConsultasEnCursoTest {
    private val cuenta = OrderDetail("o", esDeMesa = false, cobrosConSaldo = 1, total = 100.0,
        preBillBalance = PreBillBalance(10000, 2000, 8000, 0))
    private var venue: String? = "a"
    private val storage = mockk<SecureStorage> { every { venueId } answers { venue } }
    private val repo = mockk<TableServiceRepository>()
    private val locales = mockk<CobrosLocalesDeLaCuenta> { coEvery { hayPendiente("a", "o") } returns false }
    private val m = CobroDeCuentaViewModel(repo, storage, mockk<ConnectivityMonitor>(), mockk(relaxed = true), mockk(relaxed = true), locales)

    /** Como el repositorio real, que envuelve la llamada en `runCatching` y se traga también la cancelación. */
    private fun consultaRetenida(): CompletableDeferred<Result<OrderDetail>> {
        val respuesta = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repo.getOrderDetail("a", "o") } coAnswers { runCatching { respuesta.await().getOrThrow() } }
        return respuesta
    }

    private fun TestScope.historial(): List<Int> {
        val valores = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.consultasEnCurso.toList(valores) }
        return valores
    }

    @Test fun `cuenta en vuelo mientras la consulta no vuelve y regresa a cero al terminar bien`() = runTest {
        val respuesta = consultaRetenida()
        assertEquals(0, m.consultasEnCurso.value)
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(1, m.consultasEnCurso.value)
        assertNull(m.detalle.value)
        respuesta.complete(Result.success(cuenta))
        consulta.join()
        assertEquals(0, m.consultasEnCurso.value)
        assertEquals(cuenta, m.detalle.value)
    }

    @Test fun `una consulta que falla apaga el contador y deja el aviso`() = runTest {
        val respuesta = consultaRetenida()
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(1, m.consultasEnCurso.value)
        respuesta.complete(Result.failure(IOException("sin red")))
        consulta.join()
        assertEquals(0, m.consultasEnCurso.value)
        assertNull(m.detalle.value)
        assertNotNull(m.mensaje.value)
    }

    @Test fun `sin sucursal el contador sube y baja`() = runTest {
        venue = null
        val valores = historial()
        runCurrent()
        m.cargar("o")
        assertEquals(listOf(0, 1, 0), valores)
        assertEquals(TextosDeTarjetas.CUENTA_NO_ENCONTRADA, m.mensaje.value)
    }

    @Test fun `si cambia de sucursal a media consulta el contador baja`() = runTest {
        val respuesta = consultaRetenida()
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(1, m.consultasEnCurso.value)
        venue = "otra"
        respuesta.complete(Result.success(cuenta))
        consulta.join()
        assertEquals(0, m.consultasEnCurso.value)
        assertEquals(TextosDeTarjetas.CUENTA_NO_ENCONTRADA, m.mensaje.value)
    }

    @Test fun `cancelar la corrutina apaga el contador sin dejar aviso`() = runTest {
        consultaRetenida()
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(1, m.consultasEnCurso.value)
        consulta.cancelAndJoin()
        assertEquals(0, m.consultasEnCurso.value)
        assertNull(m.mensaje.value)
    }

    @Test fun `dos consultas a la vez no bajan a cero hasta que acaba la ultima`() = runTest {
        val primera = CompletableDeferred<Result<OrderDetail>>()
        val segunda = CompletableDeferred<Result<OrderDetail>>()
        val respuestas = ArrayDeque(listOf(primera, segunda))
        coEvery { repo.getOrderDetail("a", "o") } coAnswers { respuestas.removeFirst().await() }
        launch { m.cargar("o") }
        launch { m.cargar("o") }
        runCurrent()
        assertEquals(2, m.consultasEnCurso.value)
        primera.complete(Result.success(cuenta))
        runCurrent()
        assertEquals(1, m.consultasEnCurso.value)
        segunda.complete(Result.failure(IOException("sin red")))
        runCurrent()
        assertEquals(0, m.consultasEnCurso.value)
    }

    private fun dosConsultasRetenidas(): Pair<CompletableDeferred<Result<OrderDetail>>, CompletableDeferred<Result<OrderDetail>>> {
        val vieja = CompletableDeferred<Result<OrderDetail>>()
        val nueva = CompletableDeferred<Result<OrderDetail>>()
        val respuestas = ArrayDeque(listOf(vieja, nueva))
        coEvery { repo.getOrderDetail("a", "o") } coAnswers { respuestas.removeFirst().await() }
        return vieja to nueva
    }

    @Test fun `una respuesta atrasada no pisa el saldo de la consulta mas nueva`() = runTest {
        val (vieja, nueva) = dosConsultasRetenidas()
        val saldoNuevo = cuenta.copy(preBillBalance = PreBillBalance(10000, 5000, 5000, 0))
        launch { m.cargar("o") }
        launch { m.cargar("o") }
        runCurrent()
        nueva.complete(Result.success(saldoNuevo))
        runCurrent()
        vieja.complete(Result.success(cuenta))
        runCurrent()
        assertEquals(saldoNuevo, m.detalle.value)
        assertEquals(0, m.consultasEnCurso.value)
    }

    @Test fun `una falla atrasada no deja aviso sobre una consulta que ya salio bien`() = runTest {
        val (vieja, nueva) = dosConsultasRetenidas()
        launch { m.cargar("o") }
        launch { m.cargar("o") }
        runCurrent()
        nueva.complete(Result.success(cuenta))
        runCurrent()
        vieja.complete(Result.failure(IOException("sin red")))
        runCurrent()
        assertEquals(cuenta, m.detalle.value)
        assertNull(m.mensaje.value)
    }

    @Test fun `una oferta superada no apaga Cobrar de la consulta mas nueva`() = runTest {
        val (vieja, nueva) = dosConsultasRetenidas()
        val pendienteDeLaVieja = CompletableDeferred<Boolean>()
        var llamadas = 0
        coEvery { locales.hayPendiente("a", "o") } coAnswers { if (llamadas++ == 0) pendienteDeLaVieja.await() else false }
        val a = async { m.ofertaParaCobrar("o") }
        val b = async { m.ofertaParaCobrar("o") }
        runCurrent()
        vieja.complete(Result.success(cuenta)) // A vuelve primero, ya superada, y se queda revisando pendientes.
        runCurrent()
        nueva.complete(Result.success(cuenta))
        assertEquals(OfertaDeLaCuenta.OFRECER, b.await())
        pendienteDeLaVieja.complete(false)
        assertEquals(OfertaDeLaCuenta.NADA, a.await())
        assertEquals(OfertaDeLaCuenta.OFRECER, m.oferta.value)
        assertEquals(cuenta, m.detalle.value)
    }

    @Test fun `antes de arrancar la primera consulta tambien se dice Consultando`() {
        assertEquals(true, cuentaEnConsulta(consultasEnCurso = 0, enLinea = true, consultaPedida = false))
        assertEquals(true, cuentaEnConsulta(consultasEnCurso = 1, enLinea = true, consultaPedida = true))
        assertEquals("Ya consulto y fallo", false, cuentaEnConsulta(consultasEnCurso = 0, enLinea = true, consultaPedida = true))
        assertEquals("Sin red nadie va a consultar", false, cuentaEnConsulta(consultasEnCurso = 0, enLinea = false, consultaPedida = false))
    }

    @Test fun `ofertaParaCobrar sigue decidiendo con la foto fresca`() = runTest {
        coEvery { repo.getOrderDetail("a", "o") } returns Result.success(cuenta)
        assertEquals(OfertaDeLaCuenta.OFRECER, m.ofertaParaCobrar("o"))
        assertEquals(0, m.consultasEnCurso.value)

        val respuesta = consultaRetenida()
        val oferta = async { m.ofertaParaCobrar("o") }
        runCurrent()
        assertNull("Mientras relee no queda a la vista la foto vieja", m.detalle.value)
        assertEquals(1, m.consultasEnCurso.value)
        respuesta.complete(Result.success(cuenta.copy(paymentStatus = "PAID")))
        assertEquals(OfertaDeLaCuenta.NADA, oferta.await())
        assertEquals(0, m.consultasEnCurso.value)
    }
}
