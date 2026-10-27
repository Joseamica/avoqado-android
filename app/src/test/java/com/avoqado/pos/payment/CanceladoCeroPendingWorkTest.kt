package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import io.mockk.coEvery
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** The logout/venue guard must verify the proof, rather than trust a reason or a review timestamp. */
class CanceladoCeroPendingWorkTest {
    private val proof = """{"cancelledPaymentEvidence":{"version":1,"venueId":"${Q16PrecioCapturadoTest.VENUE}","orderId":"orden-original","externalId":"venta-original","orderStatus":"CANCELLED","hasPayments":false,"hasTerminalRequests":false,"verifiedAt":"2026-10-23T20:00:00.000Z"}}"""
    private fun reviewed() = PendingPaymentEntity(
        "folio-original", Q16PrecioCapturadoTest.VENUE, "staff-original", 0, 0, "CASH", "ORDER",
        orderId = "orden-original", orderRequestJson = """{"externalId":"venta-original","total":0,"tip":0}""",
        syncStatus = "FAILED", q15Reason = "CANCELLED_ZERO_PAYMENT", q15ResultJson = proof,
        q15ReviewedAt = 10L, q15ReviewedBy = "gerente-actual", q15ReviewRole = "MANAGER",
    )

    @Test fun `cancelacion cero comprobada y revisada deja historial sin bloquear cambio de sesion`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            coEvery { f.dao.getUnsyncedPayments() } returns emptyList()
            coEvery { f.dao.getFailedPayments() } returns listOf(reviewed(),reviewed().copy(id="legacy-null",orderId=null))
            coEvery { f.dao.enVueloCount() } returns 0
            assertEquals(0, f.service.blockingWorkCount())
        }
    }

    @Test fun `una marca sin prueba completa o sin gerente sigue reteniendo`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            coEvery { f.dao.getUnsyncedPayments() } returns emptyList()
            coEvery { f.dao.enVueloCount() } returns 0
            for (row in listOf(
                reviewed().copy(q15ResultJson = null), reviewed().copy(q15ResultJson = "malformed"),
                reviewed().copy(q15ResultJson = "{}"), reviewed().copy(q15ResultJson = """{"cancelledPaymentEvidence":{"version":1}}"""),
                reviewed().copy(q15ReviewedAt = null), reviewed().copy(q15ReviewedBy = null),
                reviewed().copy(q15ReviewedBy = " "), reviewed().copy(q15ReviewRole = "CASHIER"),
                reviewed().copy(amountCents = 1), reviewed().copy(tipCents = 1),
                reviewed().copy(orderId = "otra-orden"), reviewed().copy(venueId = "otro-venue"),
                reviewed().copy(orderRequestJson = """{"externalId":"otra-venta"}"""),
                reviewed().copy(q15Reason = "DIFERENCIA_DE_COBRO"), reviewed().copy(paymentType = "FAST"),
            )) {
                coEvery { f.dao.getFailedPayments() } returns listOf(row)
                assertEquals(row.toString(), 1, f.service.blockingWorkCount())
            }
        }
    }

    @Test fun `un FAILED comun y uno retenido sin prueba no se esconden por otro revisado`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            coEvery { f.dao.getUnsyncedPayments() } returns emptyList()
            coEvery { f.dao.enVueloCount() } returns 0
            coEvery { f.dao.getFailedPayments() } returns listOf(
                reviewed(), reviewed().copy(id = "fallido-comun", amountCents = 22000, q15Reason = null),
                reviewed().copy(id = "retenido-sin-prueba", q15Reason = "CAPTURA_SIN_EVIDENCIA", q15ResultJson = null),
            )
            assertEquals(2, f.service.blockingWorkCount())
        }
    }

    @Test fun `lista fallidos conserva q15Reason null y solo omite revision acreditada o tarjeta Q15 pendiente`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            coEvery { f.dao.getFailedPayments() } returns listOf(
                reviewed(),reviewed().copy(id="comun",q15Reason=null,q15ReviewedAt=null),
                reviewed().copy(id="revision-invalida",q15ResultJson="{}"),
                reviewed().copy(id="tarjeta-Q15",q15Reason="CAPTURA_SIN_EVIDENCIA",q15ReviewedAt=null),
            )
            assertEquals(listOf("comun","revision-invalida"),f.service.failedPayments().map {it.id})
        }
    }
}
