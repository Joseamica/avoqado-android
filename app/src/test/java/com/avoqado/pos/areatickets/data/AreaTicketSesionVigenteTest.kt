package com.avoqado.pos.areatickets.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 Codex r11 #3: la sesión de vales es UNA para todo el aparato. Si la operación de A espera su red, se abandona y B abre otra
 * sesión, la respuesta de A no puede sustituir ni borrar la de B. Cada escritura de la sesión tras la red pasa sólo si sigue vigente
 * la MISMA sesión que se capturó antes; si no, la respuesta vuelve a quien llamó sin tocar la sesión. Sin cruce, nada cambia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AreaTicketSesionVigenteTest {

    private val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns VENUE }
    private val api = mockk<ApiService>()
    private val session = AreaTicketSession(storage)
    private val repo = AreaTicketRepository(api = api, secureStorage = storage, session = session, payloadCache = mockk(relaxed = true))

    private fun checkout(id: String, status: String = "OPEN", orden: String? = null) = AreaTicketCheckout(
        id = id, status = status, version = 1, expiresAt = "2999-01-01T00:00:00Z", createdAt = "2026-10-25T10:00:00Z",
        order = orden?.let { AreaTicketCheckoutOrder(id = it, orderNumber = "1", paymentStatus = "PENDING", status = "PENDING", total = "100.00") },
        totals = AreaTicketCheckoutTotals("100.00", "0.00", "100.00"),
    )

    private fun responde(c: AreaTicketCheckout) = AreaTicketEnvelope(success = true, data = CheckoutData(c))

    /** A se abandona mientras espera y B abre su sesión. */
    private fun abreB() {
        session.clear()
        session.update(VENUE, checkout("sesion-B"))
    }

    @Test
    fun `materialize de A que vuelve con B instalada no sustituye la sesion de B`() = runTest {
        session.update(VENUE, checkout("sesion-A"))
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.materializeAreaTicketCheckout(VENUE, "sesion-A", any()) } coAnswers { respuesta.await() }

        val deA = async { repo.materialize(emptyList(), null, null, cobroId = "cobro-A") }
        runCurrent()
        abreB()
        respuesta.complete(responde(checkout("sesion-A", orden = "orden-A")))

        assertEquals("A recibe su respuesta", "orden-A", deA.await().order?.id)
        assertEquals("la sesión vigente sigue siendo B", "sesion-B", session.current()?.id)
    }

    @Test
    fun `refresh de A que vuelve PAID con B instalada no borra la sesion de B`() = runTest {
        session.update(VENUE, checkout("sesion-A"))
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.getAreaTicketCheckout(VENUE, "sesion-A") } coAnswers { respuesta.await() }

        val deA = async { repo.refresh() }
        runCurrent()
        abreB()
        respuesta.complete(responde(checkout("sesion-A", status = "PAID")))

        assertEquals("PAID", deA.await()?.status)
        assertEquals("sesion-B", session.current()?.id)
    }

    @Test
    fun `un STALE no borra A ni B sin comprobar y una respuesta EXPIRED si borra la vencida`() = runTest {
        session.update(VENUE, checkout("sesion-A"))
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.materializeAreaTicketCheckout(VENUE, "sesion-A", any()) } coAnswers { respuesta.await() }

        val deA = async { runCatching { repo.materialize(emptyList(), null, null, cobroId = "cobro-A") } }
        runCurrent()
        abreB()
        respuesta.complete(AreaTicketEnvelope(success = false, error = AreaTicketApiError(code = "CHECKOUT_SESSION_STALE")))

        assertEquals("CHECKOUT_SESSION_STALE", (deA.await().exceptionOrNull() as? AreaTicketException)?.code)
        assertEquals("el STALE era de A: B sigue", "sesion-B", session.current()?.id)

        // Ventas: STALE también significa una revisión vieja; sólo EXPIRED acredita que la sesión murió.
        coEvery { api.materializeAreaTicketCheckout(VENUE, "sesion-B", any()) } returns
            AreaTicketEnvelope(success = false, error = AreaTicketApiError(code = "CHECKOUT_SESSION_STALE"))
        runCatching { repo.materialize(emptyList(), null, null, cobroId = "cobro-A") }
        assertEquals("sesion-B", session.current()?.id)
        coEvery { api.getAreaTicketCheckout(VENUE, "sesion-B") } returns responde(checkout("sesion-B", status = "EXPIRED"))
        assertEquals("EXPIRED", repo.refresh()?.status)
        assertEquals(null, session.current())
    }

    @Test
    fun `crear la sesion de A no instala la suya si mientras tanto se instalo otra`() = runTest {
        val creada = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.createAreaTicketCheckout(VENUE, any()) } coAnswers { creada.await() }
        coEvery { api.addAreaTicketToCheckout(VENUE, "sesion-A", any()) } returns responde(checkout("sesion-A"))

        val deA = async { runCatching { repo.addTicket("vale-1") } }
        runCurrent()
        session.update(VENUE, checkout("sesion-B"))
        creada.complete(responde(checkout("sesion-A")))

        assertEquals("CHECKOUT_SUPERSEDED", (deA.await().exceptionOrNull() as? AreaTicketException)?.code)
        assertEquals("sesion-B", session.current()?.id)
    }

    @Test
    fun `sin cruce las escrituras de la sesion siguen como siempre`() = runTest {
        session.update(VENUE, checkout("sesion-A"))
        coEvery { api.materializeAreaTicketCheckout(VENUE, "sesion-A", any()) } returns responde(checkout("sesion-A", orden = "orden-A"))
        coEvery { api.getAreaTicketCheckout(VENUE, "sesion-A") } returns responde(checkout("sesion-A", status = "PAID", orden = "orden-A"))

        repo.materialize(emptyList(), null, null, cobroId = "cobro-A")
        assertEquals("orden-A", session.current()?.order?.id)
        repo.refresh()
        assertTrue("PAID se tira, como siempre", session.current() == null)
    }

    private companion object { const val VENUE = "venue-vales" }
}
