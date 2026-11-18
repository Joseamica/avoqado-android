package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.domain.TenderTypeOption
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tarjeta de la nube na300d63 (encargo 0199df): un cobro «Ya pagó de otra forma» rechazado y cancelado se SUELTA sólo en memoria
 * (`olvidarReservaEnMemoria`, sin tocar la tabla). La hoja «Cobros interrumpidos» y el contador de `AppState` leen
 * [CashPaymentRepository.observarSinConfirmar], que sólo se recalculaba cuando cambiaba la tabla: el cobro no salía en la hoja
 * mientras el freno de «Cerrar sesión» (`enVueloPorRevisar`, calculado al momento) sí lo contaba.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroSoltadoSeVeEnLaHojaTest {

    private val tabla = MutableStateFlow<List<PendingPaymentEntity>>(emptyList())
    private val dao = mockk<PendingPaymentDao>(relaxed = true).also { d ->
        every { d.observarEnVuelo() } returns tabla
        coEvery { d.findForVenue(any(), any()) } answers { tabla.value.firstOrNull { it.id == secondArg<String>() } }
        coEvery { d.enVueloDelVenue(any()) } answers { tabla.value.filter { it.venueId == firstArg<String>() } }
        coEvery { d.insert(any(), any()) } answers {
            val fila = firstArg<PendingPaymentEntity>()
            tabla.value = tabla.value.filterNot { it.id == fila.id } + fila
        }
    }
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns VENUE }
    private val repo = CashPaymentRepository(storage, dao)

    /** «Ya pagó de otra forma › Transferencia» (tipo del negocio), como en la prueba de D63 del 17-nov. */
    private val transferencia = TenderTypeOption("tender-transferencia", 1, "Transferencia", false, "OTHER", false, "PRIMARY", 1, false)

    private suspend fun reservar(llave: String, tipo: TenderTypeOption? = transferencia) = repo.reservarCobro(
        CreateOrderRequest(emptyList(), subtotal = 3_000, total = 3_000, paymentMethod = "CASH"),
        "staff-1", 3_000, 0, null, idempotencyKey = llave, tenderType = tipo, venueId = VENUE,
    )

    @Test
    fun `P1 el cobro soltado solo en memoria aparece en la hoja sin que cambie la tabla`() = runTest(UnconfinedTestDispatcher()) {
        val hoja = repo.observarSinConfirmar().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        reservar("llave-1")
        assertEquals("mientras el flujo es su dueño no sale en la hoja", emptyList<String>(), hoja.value.map { it.id })

        repo.olvidarReservaEnMemoria("llave-1")

        assertEquals("soltado: la hoja lo muestra ya, como lo cuenta el freno de Cerrar sesión", listOf("llave-1"), hoja.value.map { it.id })
        assertEquals("y lo que calcula al momento coincide", listOf("llave-1"), repo.cobrosSinConfirmar().map { it.id })
        assertEquals("el freno de Cerrar sesión lo cuenta por revisar, igual que la hoja", 1, repo.enVueloPorRevisar())
    }

    @Test
    fun `P2 reservar otro cobro no lo saca de la hoja`() = runTest(UnconfinedTestDispatcher()) {
        val hoja = repo.observarSinConfirmar().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        reservar("llave-1")
        repo.olvidarReservaEnMemoria("llave-1")
        reservar("llave-2")
        assertEquals(listOf("llave-1"), hoja.value.map { it.id })
    }

    private companion object { const val VENUE = "venue-1" }
}
