package com.avoqado.pos.areatickets.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * 🔴 DINERO. Pasada exhaustiva de la vida del apartado de vales (Codex r2, 20-oct): reglas 2, 3, 4 y 6.
 */
class AreaTicketVidaDelApartadoTest {

    private val api = mockk<ApiService>()
    private val storage: SecureStorage = mockk(relaxed = true) { every { venueId } returns VENUE }
    private val session = AreaTicketSession(storage)
    private val repo = AreaTicketRepository(api, storage, session, mockk<PayloadCache>(relaxed = true))

    @Test
    fun `regla 3 - la confirmacion tardia de soltar A no borra la sesion B`() = runTest {
        session.update(VENUE, checkout("A"))
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.cancelAreaTicketCheckout(VENUE, "A", any()) } coAnswers { respuesta.await() }

        val soltando = async { repo.cancel() }
        testScheduler.advanceUntilIdle()
        session.clear()
        session.update(VENUE, checkout("B"))
        respuesta.complete(envelope(checkout("A", status = "CANCELLED")))
        soltando.await()

        assertEquals("B", session.current()?.id)
    }

    @Test
    fun `regla 3 - un latido tardio de A no pisa la sesion B`() = runTest {
        session.update(VENUE, checkout("A"))
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "A", any()) } coAnswers { respuesta.await() }

        val latiendo = async { repo.heartbeat() }
        testScheduler.advanceUntilIdle()
        session.clear()
        session.update(VENUE, checkout("B"))
        respuesta.complete(envelope(checkout("A")))

        assertNull(latiendo.await())
        assertEquals("B", session.current()?.id)
    }

    @Test
    fun `regla 2 - vencida por reloj CON orden armada se suelta en el servidor, no se olvida a ciegas`() = runTest {
        session.update(VENUE, checkout("A", status = "MATERIALIZED", order = order(), expiresAt = VENCIDA))
        coEvery { api.cancelAreaTicketCheckout(VENUE, "A", any()) } returns envelope(checkout("A", status = "CANCELLED"))

        assertNull(repo.restore())

        coVerify(exactly = 1) { api.cancelAreaTicketCheckout(VENUE, "A", any()) }
        assertNull(session.current())
    }

    @Test
    fun `regla 2 - vencida con orden armada y sin red se conserva para reintentar`() = runTest {
        session.update(VENUE, checkout("A", status = "MATERIALIZED", order = order(), expiresAt = VENCIDA))
        coEvery { api.cancelAreaTicketCheckout(VENUE, "A", any()) } throws IOException("sin red")

        assertNull(repo.restore())

        assertEquals("A", session.current()?.id)
    }

    @Test
    fun `regla 2 - vencida SIN orden la libera el servidor solo y se olvida`() = runTest {
        session.update(VENUE, checkout("A", expiresAt = VENCIDA))

        assertNull(repo.restore())

        coVerify(exactly = 0) { api.cancelAreaTicketCheckout(any(), any(), any()) }
        assertNull(session.current())
    }

    @Test
    fun `regla 4 - la orden armada en OTRO cobro se suelta y se pide volver a escanear`() = runTest {
        session.update(VENUE, checkout("A"))
        coEvery { api.materializeAreaTicketCheckout(VENUE, "A", any()) } returns
            envelope(checkout("A", status = "MATERIALIZED", order = order()))
        repo.materialize(listOf(NormalCheckoutItem("cafe", 1)), null, null, cobroId = "cobro-1")
        coEvery { api.cancelAreaTicketCheckout(VENUE, "A", any()) } returns envelope(checkout("A", status = "CANCELLED"))

        // Codex r3 #1: un importe libre no viaja en la cuenta normal; por eso decide el cobro, no lo que se manda.
        try {
            repo.materialize(listOf(NormalCheckoutItem("cafe", 1)), null, null, cobroId = "cobro-2")
            fail("Debió detenerse: la cuenta cambió")
        } catch (e: AreaTicketException) {
            assertEquals("CHECKOUT_CART_CHANGED", e.code)
        }
        coVerify(exactly = 1) { api.cancelAreaTicketCheckout(VENUE, "A", any()) }
        assertNull(session.current())
    }

    @Test
    fun `regla 4 - en el MISMO cobro se reusa la orden armada (reintento tras una tarjeta rechazada)`() = runTest {
        session.update(VENUE, checkout("A"))
        coEvery { api.materializeAreaTicketCheckout(VENUE, "A", any()) } returns
            envelope(checkout("A", status = "MATERIALIZED", order = order()))
        val cuenta = listOf(NormalCheckoutItem("cafe", 1))
        repo.materialize(cuenta, null, "nota", cobroId = "cobro-1")

        assertEquals("orden-A", repo.materialize(cuenta, null, "nota", cobroId = "cobro-1").order?.id)
        coVerify(exactly = 1) { api.materializeAreaTicketCheckout(VENUE, "A", any()) }
        coVerify(exactly = 0) { api.cancelAreaTicketCheckout(any(), any(), any()) }
    }

    @Test
    fun `regla 4 - tras reiniciar no se sabe con que se armo y se suelta`() = runTest {
        session.update(VENUE, checkout("A", status = "MATERIALIZED", order = order()))
        coEvery { api.cancelAreaTicketCheckout(VENUE, "A", any()) } returns envelope(checkout("A", status = "CANCELLED"))

        val error = runCatching { repo.materialize(emptyList(), null, null, cobroId = "cobro-1") }.exceptionOrNull()

        assertEquals("CHECKOUT_CART_CHANGED", (error as? AreaTicketException)?.code)
    }

    @Test
    fun `regla 4 - con abono la venta sigue por su saldo aunque no se sepa la cuenta`() = runTest {
        session.update(VENUE, checkout("A", status = "PARTIALLY_PAID", order = order(paid = "50.00")))

        assertEquals("orden-A", repo.materialize(emptyList(), null, null, cobroId = "cobro-1").order?.id)
        coVerify(exactly = 0) { api.cancelAreaTicketCheckout(any(), any(), any()) }
    }

    @Test
    fun `regla 2 - armada con la respuesta perdida y vencida se suelta en vez de olvidarse`() = runTest {
        session.update(VENUE, checkout("A"))
        coEvery { api.materializeAreaTicketCheckout(VENUE, "A", any()) } throws IOException("se perdió la respuesta")
        runCatching { repo.materialize(emptyList(), null, null, cobroId = "cobro-1") }
        session.update(VENUE, checkout("A", expiresAt = VENCIDA))
        coEvery { api.cancelAreaTicketCheckout(VENUE, "A", any()) } returns envelope(checkout("A", status = "CANCELLED"))

        assertNull(repo.restore())

        coVerify(exactly = 1) { api.cancelAreaTicketCheckout(VENUE, "A", any()) }
    }

    // F1 (vales-ciclo, Codex r3): tras reiniciar, la restauración que pidió la venta A tarda; mientras, la venta B pide un vale.
    // B no hereda la llave de A (crearía «su» sesión y recibiría el apartado de A con sus vales) y la respuesta de A, al llegar,
    // no se instala encima de B.
    @Test
    fun `F1 - la restauracion tardia de A no le pasa su apartado a la venta B`() = runTest {
        every { storage.areaTicketCheckoutVenueId } returns VENUE
        every { storage.areaTicketCheckoutId } returns "A"
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.getAreaTicketCheckout(VENUE, "A") } coAnswers { respuesta.await() }
        val llaveDeA = session.createIdempotencyKey()

        val restaurando = async { repo.restore(venta = "venta-A") }
        testScheduler.advanceUntilIdle()
        session.prepararCreacionPara("venta-B")
        assertTrue(session.createIdempotencyKey() != llaveDeA)
        respuesta.complete(envelope(checkout("A")))

        assertNull(restaurando.await())
        assertNull(session.current())
        assertEquals("venta-B", session.ventaDuena)
    }

    // F1 (vales-ciclo, Codex r4): la variante con DOS restauraciones. B ya preparó su creación (dueña B, llave nueva), pero el disco
    // todavía apunta al apartado de A. El cajero vuelve a Cobrar y la restauración que pide B no adopta ese apartado como suyo.
    @Test
    fun `F1 - una segunda restauracion de B no adopta el apartado guardado de A`() = runTest {
        every { storage.areaTicketCheckoutVenueId } returns VENUE
        every { storage.areaTicketCheckoutId } returns "A"
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.getAreaTicketCheckout(VENUE, "A") } coAnswers { respuesta.await() }

        val primera = async { repo.restore(venta = "venta-A") }
        testScheduler.advanceUntilIdle()
        session.prepararCreacionPara("venta-B")
        val segunda = async { repo.restore(venta = "venta-B") }
        testScheduler.advanceUntilIdle()
        respuesta.complete(envelope(checkout("A")))

        assertNull(segunda.await())
        assertNull(primera.await())
        assertNull(session.current())
        assertEquals("venta-B", session.ventaDuena)
    }

    @Test
    fun `regla 3 - la restauracion tardia de A no pisa la sesion B`() = runTest {
        every { storage.areaTicketCheckoutVenueId } returns VENUE
        every { storage.areaTicketCheckoutId } returns "A"
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.getAreaTicketCheckout(VENUE, "A") } coAnswers { respuesta.await() }

        val restaurando = async { repo.restore() }
        testScheduler.advanceUntilIdle()
        session.update(VENUE, checkout("B"))
        respuesta.complete(envelope(checkout("A")))

        assertEquals("B", restaurando.await()?.id)
        assertEquals("B", session.current()?.id)
    }

    @Test
    fun `regla 3 - una creacion que llega tarde no reemplaza a la sesion vigente`() = runTest {
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.createAreaTicketCheckout(VENUE, any()) } coAnswers { respuesta.await() }

        val escaneando = async { runCatching { repo.addTicket("VALE-1") } }
        testScheduler.advanceUntilIdle()
        session.clear()
        session.update(VENUE, checkout("B"))
        respuesta.complete(envelope(checkout("A")))

        assertEquals("CHECKOUT_SUPERSEDED", (escaneando.await().exceptionOrNull() as? AreaTicketException)?.code)
        assertEquals("B", session.current()?.id)
    }

    // J9 (vales-ciclo r0): dos escaneos de la misma venta piden crear con la misma llave. El primero agrega V1 (versión 2);
    // la segunda respuesta de crear, fotografiada antes de V1, no puede regresar la copia a la versión 1.
    @Test
    fun `J9 - la segunda respuesta de crear no retrocede la version del mismo apartado ya instalado`() = runTest {
        val primera = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        val segunda = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        var creaciones = 0
        coEvery { api.createAreaTicketCheckout(VENUE, any()) } coAnswers { if (creaciones++ == 0) primera.await() else segunda.await() }
        var agregados = 0
        coEvery { api.addAreaTicketToCheckout(VENUE, "A", any()) } coAnswers {
            if (agregados++ == 0) envelope(checkout("A", version = 2)) else throw IOException("sin red")
        }

        val uno = async { runCatching { repo.addTicket("VALE-1") } }
        val dos = async { runCatching { repo.addTicket("VALE-2") } }
        testScheduler.advanceUntilIdle()
        primera.complete(envelope(checkout("A", version = 1)))
        uno.await()
        assertEquals(2, session.current()?.version)
        segunda.complete(envelope(checkout("A", version = 1)))
        dos.await()

        assertEquals(2, session.current()?.version)
    }

    @Test
    fun `regla 6 - un latido que dice CANCELLED olvida la sesion y no la proyecta`() = runTest {
        session.update(VENUE, checkout("A"))
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "A", any()) } returns envelope(checkout("A", status = "CANCELLED"))

        assertNull(repo.heartbeat())
        assertNull(session.current())
    }

    @Test
    fun `D5 - escanear con una venta con abono dice que se cobre su saldo`() = runTest {
        session.update(VENUE, checkout("A", status = "PARTIALLY_PAID", order = order(paid = "50.00")))

        val error = runCatching { repo.addTicket("VALE-1") }.exceptionOrNull() as AreaTicketException

        assertEquals("CHECKOUT_SESSION_FROZEN", error.code)
        assertTrue(error.message.contains("saldo pendiente"))
    }

    // MARK: - Rediseño r7 (Codex r6, 21-oct)

    @Test
    fun `regla 7 - un latido con version vieja no pisa la copia mas nueva`() = runTest {
        session.update(VENUE, checkout("A", version = 3))
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "A", any()) } returns envelope(checkout("A", version = 2))

        assertNull(repo.heartbeat())
        assertEquals(3, session.current()?.version)
    }

    @Test
    fun `regla 7 - una respuesta de antes del abono no le quita el abono a la copia (Codex r6 #6)`() = runTest {
        session.update(VENUE, checkout("A", status = "MATERIALIZED", order = order(), version = 4))
        session.marcarConAbono("orden-A", saldoCents = 7500)
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "A", any()) } returns
            envelope(checkout("A", status = "MATERIALIZED", order = order(), version = 4))

        assertNull(repo.heartbeat())
        assertTrue(session.current()!!.tieneAbono())
        assertEquals("75.00", session.current()?.order?.remainingBalance)
    }

    @Test
    fun `regla 7 - el estado final si reemplaza a la copia con abono`() = runTest {
        session.update(VENUE, checkout("A", status = "PARTIALLY_PAID", order = order(paid = "25.00"), version = 4))
        coEvery { api.getAreaTicketCheckout(VENUE, "A") } returns envelope(checkout("A", status = "PAID", order = order(), version = 6))

        assertEquals("PAID", repo.refresh()?.status)
        assertNull(session.current())
    }

    @Test
    fun `regla 9 - soltar el apartado de un cobro nunca suelta el de otra venta`() = runTest {
        session.update(VENUE, checkout("B"))

        assertNull(repo.cancel(soloSi = "A"))

        coVerify(exactly = 0) { api.cancelAreaTicketCheckout(any(), any(), any()) }
        assertEquals("B", session.current()?.id)
    }

    @Test
    fun `r7 7 con la misma version no se acepta una copia con menos pagado (abono manual del panel)`() = runTest {
        session.update(VENUE, checkout("A", status = "PARTIALLY_PAID", order = order(paid = "50.00"), version = 5))
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "A", any()) } returns
            envelope(checkout("A", status = "PARTIALLY_PAID", order = order(paid = "20.00"), version = 5))

        assertNull(repo.heartbeat())
        assertEquals("50.00", session.current()?.order?.paidAmount)
    }

    @Test
    fun `r8 2 la respuesta tardia de un abono no sube un saldo que ya se sabia menor`() {
        session.update(
            VENUE,
            checkout("A", status = "PARTIALLY_PAID", order = order(paid = "60.00").copy(remainingBalance = "40.00"), version = 6),
        )

        session.marcarConAbono("orden-A", saldoCents = 6000)

        assertEquals("40.00", session.current()?.order?.remainingBalance)
        assertEquals("60.00", session.current()?.order?.paidAmount)
    }

    @Test
    fun `r7 2 entrar a otra sucursal no borra la referencia guardada del apartado de la primera`() {
        session.update(VENUE, checkout("A", status = "PARTIALLY_PAID", order = order(paid = "50.00")))
        io.mockk.clearMocks(storage, answers = false)

        session.ensureVenue("otra-sucursal")

        assertNull(session.current())
        verify(exactly = 0) { storage.areaTicketCheckoutId = null }
    }

    @Test
    fun `r7 3 una venta nueva sin apartado pide con llave nueva si la ultima que pidio fue otra`() {
        session.prepararCreacionPara("venta-A")
        val llaveDeA = session.createIdempotencyKey()

        session.prepararCreacionPara("venta-A")
        assertEquals("la misma venta reintenta con su llave", llaveDeA, session.createIdempotencyKey())

        session.prepararCreacionPara("venta-B")
        assertTrue(llaveDeA != session.createIdempotencyKey())
        assertEquals("venta-B", session.ventaDuena)
    }

    private fun checkout(
        id: String,
        status: String = "OPEN",
        order: AreaTicketCheckoutOrder? = null,
        expiresAt: String = VIGENTE,
        version: Int = 1,
    ) = AreaTicketCheckout(
        id = id,
        status = status,
        order = order?.copy(id = "orden-$id"),
        version = version,
        expiresAt = expiresAt,
        createdAt = "2026-10-20T10:00:00Z",
        totals = AreaTicketCheckoutTotals("100.00", "0.00", "100.00"),
    )

    private fun order(paid: String = "0.00") = AreaTicketCheckoutOrder(
        id = "",
        orderNumber = "V-1",
        paymentStatus = if (paid == "0.00") "PENDING" else "PARTIAL",
        status = "PENDING",
        paidAmount = paid,
        total = "100.00",
    )

    private fun envelope(checkout: AreaTicketCheckout) = AreaTicketEnvelope(success = true, data = CheckoutData(checkout))

    private companion object {
        const val VENUE = "venue-1"
        const val VIGENTE = "2099-01-01T00:00:00Z"
        const val VENCIDA = "2020-01-01T00:00:00Z"
    }
}
