package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.data.ImpresionDePedidoEnLinea
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderItem
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.ReclamoDeImpresion
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.GuardaDelPrimerByte
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D661 · Ronda 3 · Familia 3 (Ruling 50): un reclamo es el permiso de imprimir AHORA. Si la comanda esperó su impresora (el turno de la
 * impresora, el permiso USB, la conexión) hasta que su reclamo tiene 75 s (90 s de vida − 15 s de margen), no sale ni un byte: se suelta
 * y la siguiente vuelta la reclama otra vez. Ya empezada, termina y se confirma como siempre.
 */
class ReclamoVigenteTest {

    private val repo = mockk<OnlineOrdersRepository>()

    /** Reloj monotónico de la prueba (ms) y reloj de pared (para el descanso de 3 min). */
    private var monotono = 1_000_000L
    private var pared = 5_000_000L

    /**
     * El papel: espera [esperas] (una por pedazo que manda), y antes de cada pedazo pregunta el permiso del primer byte que viaja en el
     * contexto, como `PrinterService` justo antes de escribir. Sin permiso no manda nada de ese pedazo (como una estación que tronó).
     */
    private class Papel(var esperas: List<Long> = listOf(0L)) {
        var pedazos = 0
        var llamadas = 0
    }

    private fun armar(papel: Papel) = ImpresionDePedidoEnLinea(
        repositorio = repo,
        hayImpresoraDeCocina = { true },
        venueId = { "v1" },
        despachar = { _, _, _, _, _, _ ->
            papel.llamadas++
            var fallo = false
            for (espera in papel.esperas) {
                monotono += espera
                val guarda = currentCoroutineContext()[GuardaDelPrimerByte]
                if (guarda == null || guarda.puedeEscribir()) papel.pedazos++ else fallo = true
            }
            if (fallo) EstadoDeComanda.NoSalio(listOf("Cocina"), "La impresora no respondió.", "T-p1") else EstadoDeComanda.Salio
        },
        reloj = { pared },
        relojMonotono = { monotono },
    )

    private fun pedido() = OnlineOrder(
        orderId = "p1", onlineOrderId = "o-p1", orderNumber = "T-p1", status = OnlineOrderStatus.ACCEPTED,
        paymentMode = "CARD_ONLINE", paymentStatus = "PAID", customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-21T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 25050, remainingCents = 0, needsPrint = true, items = listOf(OnlineOrderItem("p", null, "Flautas", 2, null, emptyList())),
    )

    @Test
    fun `R3 Family 3 a ticket whose claim is 75 s old when its printer frees sends zero bytes and is claimed again by the next walk`() = runTest {
        coEvery { repo.reclamarComanda("p1", "dev-1") } returns ReclamoDeImpresion(true)
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        // A tiene la impresora USB esperando un permiso: B espera su turno 80 s.
        val papel = Papel(esperas = listOf(80_000L))
        val impresion = armar(papel)
        assertEquals(ResultadoImpresion.ReclamoVencido, impresion.imprimirSiToca(pedido(), "dev-1"))
        assertEquals("ni un byte de B", 0, papel.pedazos)
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
        // Se soltó: sin marca de «en curso» y sin el descanso de 3 min; la siguiente vuelta la reclama otra vez (el SERVIDOR decide).
        assertTrue(impresion.puedeIntentar("p1"))
        papel.esperas = listOf(0L)
        assertEquals(ResultadoImpresion.Impresa, impresion.imprimirSiToca(pedido(), "dev-1"))
        coVerify(exactly = 2) { repo.reclamarComanda("p1", "dev-1") }
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
        assertEquals(1, papel.pedazos)
    }

    @Test
    fun `R3 Family 3 a ticket younger than 75 s prints and confirms as today`() = runTest {
        coEvery { repo.reclamarComanda("p1", "dev-1") } returns ReclamoDeImpresion(true)
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        val papel = Papel(esperas = listOf(74_000L))
        assertEquals(ResultadoImpresion.Impresa, armar(papel).imprimirSiToca(pedido(), "dev-1"))
        assertEquals(1, papel.pedazos)
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
    }

    @Test
    fun `R3 Family 3 a ticket whose bytes started before 75 s finishes and confirms`() = runTest {
        coEvery { repo.reclamarComanda("p1", "dev-1") } returns ReclamoDeImpresion(true)
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        // Cocina sale a los 10 s; la barra espera su impresora 80 s más: ya empezó, así que termina.
        val papel = Papel(esperas = listOf(10_000L, 80_000L))
        assertEquals(ResultadoImpresion.Impresa, armar(papel).imprimirSiToca(pedido(), "dev-1"))
        assertEquals(2, papel.pedazos)
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
    }

    @Test
    fun `R3 Family 3 a person's reprint is not limited by any claim`() = runTest {
        val papel = Papel(esperas = listOf(500_000L))
        assertEquals(ResultadoImpresion.Impresa, armar(papel).reimprimir(pedido()))
        assertEquals(1, papel.pedazos)
        coVerify(exactly = 0) { repo.reclamarComanda(any(), any()) }
    }
}
