package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.presentation.botonDeVentaGuardada
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.PreBillBalance
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * D19: en «Vender tarjeta», la venta guardada dice «Consultando…» (apagado) mientras su cuenta se consulta,
 * y «Reintentar» sólo cuando la consulta ya falló o no hay red. Se maneja el ViewModel real de la cuenta,
 * igual que la pantalla, y se lee el botón en cada momento.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VentaGuardadaConsultandoTest {
    private val cuenta = OrderDetail("o", esDeMesa = false, cobrosConSaldo = 1, total = 100.0,
        preBillBalance = PreBillBalance(10000, 2000, 8000, 0))
    private val storage = mockk<SecureStorage> { every { venueId } returns "a" }
    private val repo = mockk<TableServiceRepository>()
    private val locales = mockk<CobrosLocalesDeLaCuenta> { coEvery { hayPendiente("a", "o") } returns false }
    private val m = CobroDeCuentaViewModel(repo, storage, mockk<ConnectivityMonitor>(), mockk(relaxed = true), mockk(relaxed = true), locales)

    private val consultando = TextosDeTarjetas.CONSULTANDO to false
    private val reintentar = TextosDeTarjetas.REINTENTAR to true

    /** Lo que pinta la pantalla con la red puesta y la venta disponible. */
    private fun boton(consultaPedida: Boolean = true, enLinea: Boolean = true, disponible: Boolean = true) =
        botonDeVentaGuardada(m.detalle.value, m.consultasEnCurso.value, enLinea, consultaPedida, disponible)

    private fun consultaRetenida(): CompletableDeferred<Result<OrderDetail>> {
        val respuesta = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repo.getOrderDetail("a", "o") } coAnswers { runCatching { respuesta.await().getOrThrow() } }
        return respuesta
    }

    @Test fun `el primer instante, antes de que arranque la consulta, ya dice Consultando`() {
        assertEquals(consultando, boton(consultaPedida = false))
    }

    @Test fun `mientras la consulta va en camino dice Consultando apagado y al volver bien dice Cobrar`() = runTest {
        val respuesta = consultaRetenida()
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(consultando, boton())
        respuesta.complete(Result.success(cuenta))
        consulta.join()
        assertEquals(TextosDeTarjetas.cobrar(8000) to true, boton())
    }

    @Test fun `si la consulta falla dice Reintentar y se puede tocar`() = runTest {
        val respuesta = consultaRetenida()
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(consultando, boton())
        respuesta.complete(Result.failure(IOException("sin red")))
        consulta.join()
        assertEquals(reintentar, boton())
    }

    @Test fun `si se cancela la consulta no se queda en Consultando`() = runTest {
        consultaRetenida()
        val consulta = launch { m.cargar("o") }
        runCurrent()
        assertEquals(consultando, boton())
        consulta.cancelAndJoin()
        assertEquals(reintentar, boton())
    }

    @Test fun `sin red no se consulta y Reintentar sale en gris como hoy`() {
        assertEquals(TextosDeTarjetas.REINTENTAR to false, boton(consultaPedida = false, enLinea = false, disponible = false))
        assertEquals(TextosDeTarjetas.REINTENTAR to false, boton(consultaPedida = true, enLinea = false, disponible = false))
    }

    @Test fun `una venta guardada sin cuenta que consultar dice Reintentar en cuanto se pidio`() {
        assertEquals(reintentar, boton(consultaPedida = true))
    }

    @Test fun `con la cuenta ya pagada o en cero dice Reintentar, como hoy`() = runTest {
        coEvery { repo.getOrderDetail("a", "o") } returns Result.success(cuenta.copy(preBillBalance = PreBillBalance(10000, 10000, 0, 0)))
        m.cargar("o")
        assertEquals(reintentar, boton())
    }
}
