package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * El cobro en efectivo se guarda en el aparato ANTES de mandarlo al servidor.
 *
 * 🔴 Defecto (30-sep-2026, prueba de avoqado-android en Windows + segunda opinión de Codex): la fila
 * de la cola nacía en el `onFailure` del POST, hasta 15 s después del toque. Si la app moría en esa
 * ventana, el cobro no quedaba en ningún lado, y si el POST sí había llegado, el cajero lo rehacía
 * con otra llave: dos ventas por un billete.
 *
 * La reserva vive como `EN_VUELO`: la cola NO la reproduce sola (la venta pudo no completarse) y el
 * cajón no la cuenta. Si el proceso muere, al reabrir aparece como «sin confirmar» y una persona
 * decide; «sí se cobró» la manda con la MISMA llave, que el servidor deduplica.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroEnVueloRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private lateinit var repository: CashPaymentRepository

    private val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")

    @Before
    fun setup() {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        repository = CashPaymentRepository(secureStorage, dao)
    }

    private fun fila(id: String) = PendingPaymentEntity(
        id = id,
        venueId = "venue-1",
        staffId = "staff-1",
        amountCents = 10_000,
        tipCents = 0,
        method = "CASH",
        paymentType = "FAST",
        orderId = null,
        orderNumber = null,
        cashTenderedCents = 10_000,
        changeCents = 0,
        rating = null,
        itemsJson = null,
        orderRequestJson = null,
        syncStatus = PaymentSyncStatus.EN_VUELO.name,
        retryCount = 0,
        createdAt = 1L,
    )

    @Test
    fun `la reserva se guarda EN_VUELO con la llave del cobro como id`() = runTest {
        repository.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "llave-A")

        coVerify { dao.insert(match { it.id == "llave-A" && it.syncStatus == PaymentSyncStatus.EN_VUELO.name }) }
    }

    @Test
    fun `soltar la reserva sólo borra la fila si sigue EN_VUELO`() = runTest {
        repository.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "llave-A")
        repository.soltarReserva("llave-A")

        // El DELETE va condicionado a EN_VUELO: si la cola ya la volvió PENDING (sin red), no se toca.
        coVerify { dao.borrarSiEnVuelo("llave-A") }
    }

    @Test
    fun `sin confirmar = EN_VUELO de un proceso anterior, nunca el cobro que va en vuelo ahora`() = runTest {
        coEvery { dao.enVueloDelVenue("venue-1") } returns listOf(fila("llave-vieja"), fila("llave-en-curso"))
        repository.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "llave-en-curso")

        val sinConfirmar = repository.cobrosSinConfirmar().map { it.id }

        assertEquals(listOf("llave-vieja"), sinConfirmar)
    }

    @Test
    fun `«sí se cobró» la manda a la cola con la MISMA llave`() = runTest {
        repository.confirmarQueSeCobro("llave-vieja")

        coVerify { dao.enVueloAPendiente("llave-vieja") }
    }

    @Test
    fun `«no se cobró» la borra, y sólo si seguía EN_VUELO`() = runTest {
        repository.descartarPorqueNoSeCobro("llave-vieja")

        coVerify { dao.borrarSiEnVuelo("llave-vieja") }
    }

    @Test
    fun `regresión - encolar sin red sobrescribe la reserva como PENDING con la misma llave`() = runTest {
        repository.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "llave-A")
        repository.queueCashPayment(venta, "staff-1", 10_000, 0, null, idempotencyKey = "llave-A")

        coVerifyOrder {
            dao.insert(match { it.id == "llave-A" && it.syncStatus == PaymentSyncStatus.EN_VUELO.name })
            dao.insert(match { it.id == "llave-A" && it.syncStatus == PaymentSyncStatus.PENDING.name })
        }
    }
}
