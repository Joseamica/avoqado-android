package com.avoqado.pos.core.data.sync

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Agregar el cliente a una venta de MESA cobrada sin red (18-oct): el id del pago que el servidor dio al intent `PAY_CASH`
 * ACKED. Calca `CashPaymentRepository.pagoDelServidor`: sin ese id no se liga, nunca «el último pago de $X».
 */
class SyncOutboxPagoDelServidorTest {

    private val dao = mockk<SyncIntentDao>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    private val outbox = SyncOutbox(
        dao,
        mockk(relaxed = true),
        mockk(relaxed = true),
        secureStorage,
        mockk(relaxed = true),
        mockk<Context>(relaxed = true),
        mockk(relaxed = true),
    )

    private fun intent(status: String, resultJson: String? = null, q15ResultJson: String? = null) = SyncIntentEntity(
        id = "intent-1", venueId = "venue-1", seq = 1, type = SyncIntentTypes.PAY_CASH, payloadJson = "{}",
        status = status, resultJson = resultJson, q15ResultJson = q15ResultJson,
    )

    init {
        every { secureStorage.venueId } returns "venue-1"
    }

    @Test
    fun `P1 un intent ACKED da el paymentId de su ack`() = runTest {
        coEvery { dao.findForVenue("venue-1", "intent-1") } returns
            intent(SyncIntentEntity.STATUS_ACKED, resultJson = """{"orderId":"orden-7","paymentId":"pago-servidor-7"}""")

        assertEquals("pago-servidor-7", outbox.pagoDelServidor("intent-1"))
    }

    @Test
    fun `sin paymentId en el ack lo toma de la conciliacion q15`() = runTest {
        coEvery { dao.findForVenue("venue-1", "intent-1") } returns intent(
            SyncIntentEntity.STATUS_ACKED,
            resultJson = """{"orderId":"orden-7"}""",
            q15ResultJson = """{"paymentId":"pago-q15","orderId":"orden-7"}""",
        )

        assertEquals("pago-q15", outbox.pagoDelServidor("intent-1"))
    }

    @Test
    fun `P1 un intent PENDING o REJECTED no da id aunque traiga resultado`() = runTest {
        for (status in listOf(SyncIntentEntity.STATUS_PENDING, SyncIntentEntity.STATUS_REJECTED, SyncIntentEntity.STATUS_HELD)) {
            coEvery { dao.findForVenue("venue-1", "intent-1") } returns
                intent(status, resultJson = """{"paymentId":"pago-servidor-7"}""")
            assertNull(status, outbox.pagoDelServidor("intent-1"))
        }
    }

    @Test
    fun `fila ausente, de otra sucursal o sin sucursal en sesion no da id`() = runTest {
        coEvery { dao.findForVenue(any(), any()) } returns null
        assertNull(outbox.pagoDelServidor("intent-1"))

        every { secureStorage.venueId } returns null
        coEvery { dao.findForVenue(any(), any()) } returns
            intent(SyncIntentEntity.STATUS_ACKED, resultJson = """{"paymentId":"pago-servidor-7"}""")
        assertNull(outbox.pagoDelServidor("intent-1"))
    }

    @Test
    fun `un resultado ilegible o con id vacio no da id`() = runTest {
        for (resultado in listOf("no-es-json", """{"paymentId":""}""", """{"paymentId":7}""")) {
            coEvery { dao.findForVenue("venue-1", "intent-1") } returns intent(SyncIntentEntity.STATUS_ACKED, resultJson = resultado)
            assertNull(resultado, outbox.pagoDelServidor("intent-1"))
        }
    }
}
