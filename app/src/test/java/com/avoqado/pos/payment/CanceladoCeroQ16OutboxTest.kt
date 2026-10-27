package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real outbox/SQLite paths, including the venue fallback used when the table history is unknown. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanceladoCeroQ16OutboxTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private fun Q16OutboxPrecioTest.Fixture.seedReviewed(orderId: String = "L", proofOverride: String? = null, key: String = "folio-cancelado", originalOrderId: String? = orderId) {
        val body = """{"externalId":"venta-cancelada","items":[{"productId":"bebida-A","quantity":2}],"total":0,"tip":0}"""
        disk.insertCaptured(PendingPaymentEntity(
            key, Q16PrecioCapturadoTest.VENUE, "staff-A", 0, 0, "CASH", "ORDER",
            orderId = originalOrderId, orderRequestJson = body, syncStatus = "FAILED", capturedOrderRef = originalOrderId ?: "venta-cancelada",
        ))
        val evidence = """{"cancelledPaymentEvidence":{"version":1,"venueId":"${Q16PrecioCapturadoTest.VENUE}","orderId":"$orderId","externalId":"venta-cancelada","orderStatus":"CANCELLED","hasPayments":false,"hasTerminalRequests":false,"verifiedAt":"2026-10-23T20:00:00.000Z"}}"""
        disk.sql("""UPDATE pending_payments SET q15Reason='CANCELLED_ZERO_PAYMENT',q15ResultJson=:proof,
            q15ReviewedAt=10,q15ReviewedBy='gerente-actual',q15ReviewRole='MANAGER' WHERE id=:key""",
            mapOf("key" to key,"proof" to (proofOverride ?: evidence)))
    }

    @Test fun `pagina Q16 posterior a 50 valida prueba real y metadata incompleta no se esconde`() = runTest {
        Q16OutboxPrecioTest.Fixture().use { f ->
            repeat(55) { f.seedReviewed(key="folio-${it.toString().padStart(3,'0')}") }
            f.disk.sql("UPDATE pending_payments SET q15ResultJson='{}' WHERE id='folio-054'")
            assertTrue(f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertEquals(listOf("L"),f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE,listOf("L")))
            f.seedReviewed(key="folio-054")
            assertFalse(f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertTrue(f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE,listOf("L")).isEmpty())
            f.disk.sql("UPDATE pending_payments SET q15ReviewedBy=NULL WHERE id='folio-054'")
            assertTrue(f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertEquals(listOf("L"),f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE,listOf("L")))
            f.seedReviewed(key="folio-054")
            f.disk.sql("UPDATE pending_payments SET id='' WHERE id='folio-054'")
            assertTrue("Folio vacío debe retener por SQL ordinario, nunca escapar el cursor",f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertEquals(listOf("L"),f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE,listOf("L")))
        }
    }

    @Test fun `cancelado cero con prueba y gerente no retiene OPEN cuando falta historial de mesa`() = runTest {
        Q16OutboxPrecioTest.Fixture().use { f ->
            f.seedReviewed(); f.add("apertura-nueva", 1, type = SyncIntentTypes.OPEN_TABLE)
            f.drain()
            assertTrue("La revisión acreditada debe liberar la consulta global Q16", f.disk.server.requestCount > 0)
            assertNull(f.row("apertura-nueva")["q15Reason"])
            assertEquals("FAILED", f.disk.row("folio-cancelado")["syncStatus"])
            assertNotNull(f.disk.row("folio-cancelado")["q15ResultJson"])
        }
    }

    @Test fun `revision acreditada libera sus alias pero otro FAILED conserva su propia retencion`() = runTest {
        Q16OutboxPrecioTest.Fixture().use { f ->
            f.seedReviewed(orderId = "R")
            f.drafts.promote(Q16PrecioCapturadoTest.VENUE, "L", "R")
            f.disk.seed("otro-fallido", order = "S")
            f.disk.sql("UPDATE pending_payments SET syncStatus='FAILED' WHERE id='otro-fallido'")
            f.add("ronda-libre", 1, ref = "L")
            f.drain()
            assertTrue("La consulta por referencias debe excluir sólo el revisado de R", f.disk.server.requestCount > 0)
            assertNull(f.row("ronda-libre")["q15Reason"])
            assertEquals("FAILED", f.disk.row("otro-fallido")["syncStatus"])
        }
    }

    @Test fun `marca de revision sin prueba valida sigue reteniendo OPEN y ADD`() = runTest {
        for (bad in listOf("malformed", "{}", """{"cancelledPaymentEvidence":{"version":1}}""")) {
            Q16OutboxPrecioTest.Fixture().use { f ->
                f.seedReviewed(proofOverride = bad)
                f.add("apertura-retenida", 1, type = SyncIntentTypes.OPEN_TABLE)
                f.add("ronda-retenida", 2)
                f.drain()
                assertEquals(bad, 0, f.disk.server.requestCount)
                assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING, f.row("apertura-retenida")["q15Reason"])
                assertEquals("PENDING", f.row("ronda-retenida")["status"])
            }
        }
    }

    @Test fun `prueba cruzada a otra orden no libera la captura original`() = runTest {
        Q16OutboxPrecioTest.Fixture().use { f ->
            f.seedReviewed()
            val original = requireNotNull(f.disk.row("folio-cancelado")["q15ResultJson"] as String?)
            val crossed = JSONObject(original).apply { getJSONObject("cancelledPaymentEvidence").put("orderId", "otra-orden") }.toString()
            f.disk.sql("UPDATE pending_payments SET q15ResultJson=:proof WHERE id='folio-cancelado'", mapOf("proof" to crossed))
            f.add("apertura-retenida", 1, type = SyncIntentTypes.OPEN_TABLE)
            f.drain()
            assertEquals(0, f.disk.server.requestCount)
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING, f.row("apertura-retenida")["q15Reason"])
        }
    }

    @Test fun `F3 revisado NULL libera Q16 global referencias y OPEN conservando FAILED y NULL`() = runTest {
        Q16OutboxPrecioTest.Fixture().use { f ->
            f.seedReviewed(originalOrderId=null)
            assertFalse(f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertTrue(f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE,listOf("venta-cancelada")).isEmpty())
            f.add("apertura-nueva",1,type=SyncIntentTypes.OPEN_TABLE);f.drain()
            assertTrue(f.disk.server.requestCount>0)
            assertEquals("FAILED",f.disk.row("folio-cancelado")["syncStatus"]);assertNull(f.disk.row("folio-cancelado")["orderId"])
        }
    }

    @Test fun `F3 NULL con proof cruzado incompleto o tipos falsos retiene Q16`() = runTest {
        for(field in listOf("venueId","externalId","orderId","hasPayments","hasTerminalRequests","version")) Q16OutboxPrecioTest.Fixture().use { f ->
            f.seedReviewed(originalOrderId=null)
            val bad=JSONObject(f.disk.row("folio-cancelado")["q15ResultJson"] as String).apply {
                getJSONObject("cancelledPaymentEvidence").put(field,when(field) {
                    "orderId" -> " "
                    "hasPayments", "hasTerminalRequests" -> "false"
                    "version" -> "1"
                    else -> "otra-identidad"
                })
            }.toString()
            f.seedReviewed(originalOrderId=null,proofOverride=bad)
            assertTrue(field,f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertEquals(field,listOf("venta-cancelada"),f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE,listOf("venta-cancelada")))
        }
    }
}
