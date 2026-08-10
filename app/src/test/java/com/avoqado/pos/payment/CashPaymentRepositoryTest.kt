package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CashPaymentRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)

    private lateinit var repository: CashPaymentRepository

    @Before
    fun setup() {
        every { secureStorage.venueId } returns "venue-123"
        every { secureStorage.userId } returns "user-456"
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        repository = CashPaymentRepository(secureStorage, dao)
    }

    // MARK: - processCashPayment tests

    @Test
    fun `processCashPayment returns Success when enough cash`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 15000,
        )
        assertTrue(result is CashPaymentResult.Success)
    }

    @Test
    fun `processCashPayment returns Success with correct change`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 15000,
        ) as CashPaymentResult.Success
        assertEquals(5000, result.changeCents)
    }

    @Test
    fun `processCashPayment returns InsufficientFunds when not enough`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 5000,
        )
        assertTrue(result is CashPaymentResult.InsufficientFunds)
        val insufficient = result as CashPaymentResult.InsufficientFunds
        assertEquals(5000, insufficient.shortfall)
    }

    @Test
    fun `processCashPayment returns exact change for exact amount`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 10000,
        ) as CashPaymentResult.Success
        assertEquals(0, result.changeCents)
    }

    // MARK: - queueCashPayment tests

    @Test
    fun `cobro con terminal ajena se encola con su metodo, NO como efectivo`() = runTest {
        // Sin esto, un cobro con tarjeta hecho sin red se reproducía al
        // reconectar como CASH y el corte pedía dinero que nunca entró al
        // cajón (el arqueo filtra por method=CASH).
        val orderRequest = CreateOrderRequest(items = emptyList(), subtotal = 11900, total = 11900, paymentMethod = "CASH")

        repository.queueCashPayment(
            orderRequest,
            "user-456",
            null,
            null,
            null,
            manualMethod = com.avoqado.pos.payment.domain.ManualPaymentMethod.CARD_EXTERNAL,
        )

        coVerify { dao.insert(match { it.method == "CARD_EXTERNAL" }) }
    }

    @Test
    fun `transferencia se encola como transferencia`() = runTest {
        val orderRequest = CreateOrderRequest(items = emptyList(), subtotal = 5000, total = 5000, paymentMethod = "CASH")

        repository.queueCashPayment(
            orderRequest,
            "user-456",
            null,
            null,
            null,
            manualMethod = com.avoqado.pos.payment.domain.ManualPaymentMethod.TRANSFER,
        )

        coVerify { dao.insert(match { it.method == "TRANSFER" }) }
    }

    @Test
    fun `los metodos manuales mapean a nombres que el server conoce`() {
        // Espejo EXACTO del enum PaymentMethod del server y de iOS: un nombre
        // que el server no conozca se rechaza y el cobro cae en cuarentena.
        val validos = setOf("CASH", "CREDIT_CARD", "DEBIT_CARD", "BANK_TRANSFER", "OTHER")
        com.avoqado.pos.payment.domain.ManualPaymentMethod.entries.forEach { m ->
            assertTrue("${m.name} manda ${m.serverMethod}, que el server no acepta", m.serverMethod in validos)
        }
    }

    @Test
    fun `queueCashPayment inserts entity into DAO`() = runTest {
        val orderRequest = CreateOrderRequest(
            items = emptyList(),
            subtotal = 10000,
            total = 10000,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(orderRequest, "user-456", 10000, 0, null)

        coVerify {
            dao.insert(
                match {
                    it.venueId == "venue-123" &&
                        it.amountCents == 10000 &&
                        it.method == "CASH" &&
                        it.syncStatus == "PENDING"
                },
            )
        }
    }

    @Test
    fun `queueCashPayment returns unique local ID`() = runTest {
        val orderRequest = CreateOrderRequest(
            items = emptyList(),
            subtotal = 5000,
            total = 5000,
            paymentMethod = "CASH",
        )

        val id1 = repository.queueCashPayment(orderRequest, "user-456", 5000, 0, null)
        val id2 = repository.queueCashPayment(orderRequest, "user-456", 5000, 0, null)

        assertNotEquals(id1, id2)
    }

    @Test
    fun `queueCashPayment stores tip correctly`() = runTest {
        // total=11500, tip=1500 => amountCents = total - tip = 10000
        val orderRequest = CreateOrderRequest(
            items = emptyList(),
            subtotal = 10000,
            tip = 1500,
            total = 11500,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(orderRequest, "user-456", 12000, 500, 5)

        coVerify {
            dao.insert(
                match {
                    it.tipCents == 1500 &&
                        it.amountCents == 10000 &&
                        it.cashTenderedCents == 12000 &&
                        it.changeCents == 500 &&
                        it.rating == 5
                },
            )
        }
    }

    @Test
    fun `queued order reuses original externalId after lost create response`() = runTest {
        val orderRequest = CreateOrderRequest(
            items = listOf(
                com.avoqado.pos.payment.data.model.OrderItemRequest(
                    productId = "jamon",
                    name = "Jamón",
                    quantity = 1,
                    unitPrice = 10000,
                ),
            ),
            subtotal = 10000,
            total = 10000,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(
            orderRequest = orderRequest,
            staffId = "user-456",
            cashTenderedCents = 10000,
            changeCents = 0,
            rating = null,
            orderExternalId = "same-create-attempt",
        )

        coVerify {
            dao.insert(
                match {
                    it.orderRequestJson?.contains("\"externalId\":\"same-create-attempt\"") == true
                },
            )
        }
    }

    @Test
    fun `el cliente elegido en el carrito viaja en el pago encolado sin red`() = runTest {
        // 🔴 Sin esto, cobrar sin red borraba al cliente: el cajero eligió
        // "Juan Pérez", el ticket salió bien, y al reconectar la venta se
        // reproducía anónima — sin historial, sin lealtad y sin a quién
        // facturar. Nadie lo nota, porque en el local todo se vio correcto.
        val orderRequest = CreateOrderRequest(
            items = listOf(
                com.avoqado.pos.payment.data.model.OrderItemRequest(
                    productId = "prod_1",
                    name = "Hamburguesa",
                    quantity = 1,
                    unitPrice = 11900,
                ),
            ),
            subtotal = 11900,
            total = 11900,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(
            orderRequest = orderRequest,
            staffId = "user-456",
            cashTenderedCents = 15000,
            changeCents = 3100,
            rating = null,
            customerId = "cus_juan_perez",
        )

        coVerify {
            dao.insert(
                match {
                    it.orderRequestJson?.contains("\"customerId\":\"cus_juan_perez\"") == true
                },
            )
        }
    }

    @Test
    fun `una venta sin cliente no inventa uno`() = runTest {
        val orderRequest = CreateOrderRequest(
            items = listOf(
                com.avoqado.pos.payment.data.model.OrderItemRequest(
                    productId = "prod_1",
                    name = "Hamburguesa",
                    quantity = 1,
                    unitPrice = 11900,
                ),
            ),
            subtotal = 11900,
            total = 11900,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(
            orderRequest = orderRequest,
            staffId = "user-456",
            cashTenderedCents = 11900,
            changeCents = 0,
            rating = null,
        )

        coVerify { dao.insert(match { it.orderRequestJson?.contains("customerId") != true }) }
    }

    @Test
    fun `el cobro encolado conserva la llave del intento en linea`() = runTest {
        // 🔴 El caso real (2026-08-09, log del backend):
        //   12:28:31.884  el server GUARDA el pago con la llave K
        //   12:28:32.207  el server recibe SIGTERM — la respuesta nunca llega
        //   12:30 → 12:39  seis reintentos, todos 400 "Order is already paid"
        //
        // El dinero YA estaba cobrado. Los reintentos fallaban porque al
        // encolar se inventaba una llave nueva: el server buscaba una llave que
        // no conocía, se saltaba el atajo idempotente y chocaba con la orden ya
        // pagada. El cobro se quedaba en cuarentena para siempre y el gerente no
        // tenía forma de saber si el efectivo había entrado.
        //
        // El id de la fila ES la llave que manda PaymentSyncService.
        val orderRequest = CreateOrderRequest(items = emptyList(), subtotal = 12900, total = 12900, paymentMethod = "CASH")

        val localId = repository.queueCashPayment(
            orderRequest = orderRequest,
            staffId = "user-456",
            cashTenderedCents = 15000,
            changeCents = 2100,
            rating = null,
            orderId = "order-1",
            idempotencyKey = "65fb7769-3319-4c24-9d74-f017000c3fb1",
        )

        assertEquals("65fb7769-3319-4c24-9d74-f017000c3fb1", localId)
        coVerify { dao.insert(match { it.id == "65fb7769-3319-4c24-9d74-f017000c3fb1" }) }
    }

    @Test
    fun `sin llave previa se genera una, y dos cobros distintos no la comparten`() = runTest {
        val orderRequest = CreateOrderRequest(items = emptyList(), subtotal = 5000, total = 5000, paymentMethod = "CASH")

        val a = repository.queueCashPayment(orderRequest, "user-456", null, null, null)
        val b = repository.queueCashPayment(orderRequest, "user-456", null, null, null)

        assertNotEquals(a, b)
    }
}
