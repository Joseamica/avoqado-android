package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.every
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The confirmation uses the real service, two HTTP reads and the actual CAS in owned SQLite. */
class CanceladoCeroConfirmacionTest {
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)
    private val key = "folio-cancelado"
    private val body = """{"externalId":"venta-original","items":[{"productId":"bebida-A","quantity":2}],"total":0,"tip":0}"""
    private fun proof(payments: Boolean = false, verifiedAt: String = "2026-10-24T12:00:00.000Z") =
        """{"data":{"orders":[{"externalId":"venta-original","orderId":"orden-original","cancelledPaymentEvidence":{"version":1,"venueId":"${Q16PrecioCapturadoTest.VENUE}","orderId":"orden-original","externalId":"venta-original","orderStatus":"CANCELLED","hasPayments":$payments,"hasTerminalRequests":false,"verifiedAt":"$verifiedAt"}}]}}"""
    private fun Q16PrecioCapturadoTest.Fixture.prepare() {
        every { storage.userRole } returns "MANAGER"; every { storage.userId } returns "gerente-actual"
        insertCaptured(PendingPaymentEntity(key,Q16PrecioCapturadoTest.VENUE,"staff-original",0,0,"CASH","ORDER",
            orderId="orden-original",orderRequestJson=body,syncStatus="FAILED",lastError="rechazo-original"))
    }
    private suspend fun Q16PrecioCapturadoTest.Fixture.proposal(id: String = key): PaymentSyncService.PropuestaRevisionCancelada {
        val outcome = service.retryFailedPayment(id)
        assertTrue("Debe ofrecer la revisión, nunca registrarla automáticamente", outcome is PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview)
        return (outcome as PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview).propuesta
    }
    private fun Q16PrecioCapturadoTest.Fixture.respond(external: String = "venta-original", callback: (Int) -> String): AtomicInteger {
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertTrue(request.path.orEmpty().endsWith("/sync/recovery"))
                val sent = JSONObject(request.body.readUtf8())
                assertEquals(true,sent.opt("includeCancelledPaymentEvidence"))
                assertEquals(external,sent.getJSONArray("externalOrderIds").getString(0))
                return MockResponse().setResponseCode(200).setBody(callback(calls.incrementAndGet()))
            }
        }
        return calls
    }

    @Test fun `confirmar vuelve a consultar y conserva FAILED cuerpo error y llave con prueba nueva`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare(); val calls=f.respond { proof(verifiedAt=if(it==1) "2026-10-24T12:00:00.000Z" else "2026-10-24T12:01:00.000Z") }
            val before=f.row(key);val proposal=f.proposal()
            assertTrue(f.service.confirmarRevisionCancelada(proposal));assertEquals(2,calls.get())
            val after=f.row(key)
            assertEquals("FAILED",after["syncStatus"]);assertEquals("CANCELLED_ZERO_PAYMENT",after["q15Reason"])
            assertEquals("gerente-actual",after["q15ReviewedBy"]);assertEquals("MANAGER",after["q15ReviewRole"])
            assertTrue((after["q15ReviewedAt"] as Number).toLong()>0)
            assertTrue((after["q15ResultJson"] as String).contains("12:01:00"))
            for(k in listOf("id","venueId","orderId","original","orderRequestJson","lastError","q15CaptureJson","retryCount")) assertEquals(k,before[k],after[k])
        }
    }

    @Test fun `un pago aparecido entre propuesta y confirmacion conserva el intento`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare();val calls=f.respond { proof(payments=it>1) };val proposal=f.proposal();val before=f.row(key)
            assertFalse(f.service.confirmarRevisionCancelada(proposal));assertEquals(2,calls.get());assertEquals(before,f.row(key))
        }
    }

    @Test fun `cambio de actor rol sucursal o epoch durante segunda consulta impide CAS`() = runBlocking {
        for(kind in listOf("actor","role","venue","epoch")) Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare();val calls=f.respond { n ->
                if(n==2) when(kind) {
                    "actor" -> every { f.storage.userId } returns "otro-actor"
                    "role" -> every { f.storage.userRole } returns "CASHIER"
                    "venue" -> every { f.storage.venueId } returns "otro-negocio"
                    else -> every { f.outbox.contextoDeSesionQ15() } returns 42L
                }
                proof()
            }
            val proposal=f.proposal();val before=f.row(key)
            assertFalse(kind,f.service.confirmarRevisionCancelada(proposal));assertEquals(kind,2,calls.get());assertEquals(kind,before,f.row(key))
        }
    }

    @Test fun `cambio de contexto antes de confirmar ni consulta ni revisa`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare();val calls=f.respond { proof() };val proposal=f.proposal();val before=f.row(key)
            every { f.storage.userId } returns "otro-actor"
            assertFalse(f.service.confirmarRevisionCancelada(proposal));assertEquals(1,calls.get());assertEquals(before,f.row(key))
        }
    }

    @Test fun `CAS no archiva fila cuyo cuerpo original cambio durante recovery`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare();val calls=f.respond { n ->
                if(n==2) f.sql("UPDATE pending_payments SET orderRequestJson='otro-cuerpo' WHERE id=:id",mapOf("id" to key))
                proof()
            }
            val proposal=f.proposal()
            assertFalse(f.service.confirmarRevisionCancelada(proposal));assertEquals(2,calls.get())
            assertNull(f.row(key)["q15ReviewedAt"]);assertEquals("otro-cuerpo",f.row(key)["orderRequestJson"])
        }
    }

    @Test fun `doble toque tiene una segunda consulta y una sola escritura de revision`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val calls=f.respond { n -> if(n==2) {entered.countDown();release.await(5,TimeUnit.SECONDS)};proof() }
            val proposal=f.proposal()
            val first=async(Dispatchers.Default) { f.service.confirmarRevisionCancelada(proposal) }
            try {
                assertTrue("Debe alcanzar la segunda lectura antes de escribir",entered.await(5,TimeUnit.SECONDS))
                assertFalse(f.service.confirmarRevisionCancelada(proposal))
            } finally { release.countDown() }
            assertTrue(first.await());assertEquals(2,calls.get());assertNotNull(f.row(key)["q15ReviewedAt"])
        }
    }

    @Test fun `cajero no puede proponer ni confirmar revision de cero`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare();val calls=f.respond { proof() };every { f.storage.userRole } returns "CASHIER"
            assertEquals(PaymentSyncService.RetryOutcome.StillQueued,f.service.retryFailedPayment(key))
            val original=requireNotNull(f.dao.findForVenue(Q16PrecioCapturadoTest.VENUE,key))
            assertFalse(f.service.confirmarRevisionCancelada(PaymentSyncService.PropuestaRevisionCancelada(original,"gerente-actual","MANAGER",0)))
            assertEquals(0,calls.get());assertNull(f.row(key)["q15ReviewedAt"])
        }
    }

    // Exact durable writer shape read from the naturally produced D3 row; no physical DB edits.
    private fun legacy() = PendingPaymentEntity(
        "f4d74c91-b0ab-43b5-93d9-88810c4caba6", "cmvl9gzfi0002wai0411khtia", "cmvl9gzha0003wai0rzpoukyg", 0, 0, "CASH", "ORDER",
        orderId = null, cashTenderedCents = 0, changeCents = 0,
        orderRequestJson = """{"items":[{"productId":"cmvlmf4ri000bwaih26syx0o7","quantity":1,"displayedUnitPrice":9000,"catalogAt":"2026-10-24T14:57:31.970Z","externalId":"04051f8d-e4c4-43e8-9e7a-5172921f2525","serviceCourse":{"id":"immediate","label":"Inmediato","kind":"IMMEDIATE","preparationVersion":1,"sortOrder":0}}],"staffId":"cmvl9gzha0003wai0rzpoukyg","orderType":"TAKEOUT","source":"AVOQADO_ANDROID","externalId":"f4d74c91-b0ab-43b5-93d9-88810c4caba6","subtotal":0,"tip":0,"total":0,"splitType":"FULLPAYMENT","isOfflineReplay":true,"createdAtLocal":1792854040934}""", syncStatus = "FAILED",
        lastError = """Error 400: {"message":"Esta cuenta debe 90.00. Un cobro en $0 sólo cierra cuentas cortesiadas al 100%."}""", createdAt = 1792854040934L, splitType = "FULLPAYMENT",
        q15CaptureJson = """{"version":1,"venueId":"cmvl9gzfi0002wai0411khtia","key":"f4d74c91-b0ab-43b5-93d9-88810c4caba6","amountCents":0,"tipCents":0,"splitType":"FULLPAYMENT","method":"CASH","cashTenderedCents":0,"changeCents":0,"orderRequestJson":"{\"items\":[{\"productId\":\"cmvlmf4ri000bwaih26syx0o7\",\"quantity\":1,\"displayedUnitPrice\":9000,\"catalogAt\":\"2026-10-24T14:57:31.970Z\",\"externalId\":\"04051f8d-e4c4-43e8-9e7a-5172921f2525\",\"serviceCourse\":{\"id\":\"immediate\",\"label\":\"Inmediato\",\"kind\":\"IMMEDIATE\",\"preparationVersion\":1,\"sortOrder\":0}}],\"staffId\":\"cmvl9gzha0003wai0rzpoukyg\",\"orderType\":\"TAKEOUT\",\"source\":\"AVOQADO_ANDROID\",\"externalId\":\"f4d74c91-b0ab-43b5-93d9-88810c4caba6\",\"subtotal\":0,\"tip\":0,\"total\":0,\"splitType\":\"FULLPAYMENT\",\"isOfflineReplay\":true,\"createdAtLocal\":1792854040934}"}""", capturedOrderRef = "f4d74c91-b0ab-43b5-93d9-88810c4caba6",
    )
    private fun legacyProof(order: String = "cmvmj0q7a0029wa5mkzasemeg", payments: Boolean = false, terminal: Boolean = false): String {
        val original = legacy()
        return JSONObject(proof(payments)).apply {
            getJSONObject("data").getJSONArray("orders").getJSONObject(0).apply {
                put("orderId",order); put("externalId",original.id)
                getJSONObject("cancelledPaymentEvidence").apply {
                    put("orderId",order); put("externalId",original.id); put("venueId",original.venueId)
                    put("hasTerminalRequests",terminal)
                }
            }
        }.toString()
    }
    private fun Q16PrecioCapturadoTest.Fixture.prepareLegacy(row: PendingPaymentEntity = legacy()) {
        every { storage.venueId } returns row.venueId
        every { storage.userId } returns "gerente-actual"; every { storage.userRole } returns "MANAGER"
        insertCaptured(row)
    }

    @Test fun `F3 fila exacta legacy NULL propone confirma fresco y preserva cuerpo captura error llave y NULL`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val original=legacy();f.prepareLegacy(original)
            val calls=f.respond(original.id) { legacyProof() };val before=f.row(original.id)
            val proposal=f.proposal(original.id)
            assertTrue(f.service.confirmarRevisionCancelada(proposal));assertEquals(2,calls.get())
            val after=f.row(original.id)
            assertEquals("CANCELLED_ZERO_PAYMENT",after["q15Reason"])
            for(k in listOf("id","venueId","orderId","original","orderRequestJson","lastError","q15CaptureJson","capturedOrderRef","syncStatus","retryCount"))
                assertEquals(k,before[k],after[k])
            assertNull(after["orderId"])
        }
    }

    @Test fun `F3 mapping canonical cambia entre primera y segunda consulta conserva NULL sin revisar`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val original=legacy();f.prepareLegacy(original)
            val calls=f.respond(original.id) { legacyProof(order=if(it==1) "cmvmj0q7a0029wa5mkzasemeg" else "otra-orden") }
            val proposal=f.proposal(original.id);val before=f.row(original.id)
            assertFalse(f.service.confirmarRevisionCancelada(proposal));assertEquals(2,calls.get());assertEquals(before,f.row(original.id))
        }
    }

    @Test fun `F3 cobro TPR o cambio contexto durante segunda consulta mantiene intento NULL`() = runBlocking {
        for(kind in listOf("payment","terminal","actor","role","venue","epoch")) Q16PrecioCapturadoTest.Fixture().use { f ->
            val original=legacy();f.prepareLegacy(original)
            val calls=f.respond(original.id) { n ->
                if(n==2) when(kind) {
                    "actor" -> every { f.storage.userId } returns "otro-actor"
                    "role" -> every { f.storage.userRole } returns "CASHIER"
                    "venue" -> every { f.storage.venueId } returns "otro-venue"
                    "epoch" -> every { f.outbox.contextoDeSesionQ15() } returns 42L
                }
                legacyProof(payments=n==2 && kind=="payment",terminal=n==2 && kind=="terminal")
            }
            val proposal=f.proposal(original.id);val before=f.row(original.id)
            assertFalse(kind,f.service.confirmarRevisionCancelada(proposal));assertEquals(kind,2,calls.get());assertEquals(kind,before,f.row(original.id))
        }
    }

    @Test fun `F3 CAS rechaza cambio de orderId NULL o llave durante segunda consulta`() = runBlocking {
        for(column in listOf("orderId","id")) Q16PrecioCapturadoTest.Fixture().use { f ->
            val original=legacy();f.prepareLegacy(original)
            val calls=f.respond(original.id) { n ->
                if(n==2) f.sql("UPDATE pending_payments SET $column='identidad-cambiada' WHERE id=:id",mapOf("id" to original.id))
                legacyProof()
            }
            val proposal=f.proposal(original.id)
            assertFalse(column,f.service.confirmarRevisionCancelada(proposal));assertEquals(2,calls.get())
            val saved=f.row(if(column=="id") "identidad-cambiada" else original.id)
            assertNull(saved["q15ReviewedAt"]);assertEquals("FAILED",saved["syncStatus"])
        }
    }

    @Test fun `F3 no propone NULL sin external original ni orden conocida vacia equivocada o envelope cruzado`() = runBlocking {
        for(kind in listOf("missing","numeric","blank","known-blank","known-wrong","envelope")) Q16PrecioCapturadoTest.Fixture().use { f ->
            val row=when(kind) {
                "missing" -> legacy().copy(orderRequestJson="{}")
                "numeric" -> legacy().copy(orderRequestJson="""{"externalId":123}""")
                "blank" -> legacy().copy(orderRequestJson="""{"externalId":" "}""")
                "known-blank" -> legacy().copy(orderId=" ")
                "known-wrong" -> legacy().copy(orderId="otra-orden")
                else -> legacy()
            }
            f.prepareLegacy(row)
            val calls=f.respond(row.id) { JSONObject(legacyProof()).apply {
                if(kind=="envelope") getJSONObject("data").getJSONArray("orders").getJSONObject(0).put("orderId","otra-orden")
            }.toString() }
            val before=f.row(row.id)
            assertEquals(kind,PaymentSyncService.RetryOutcome.ConsultaSinPrueba,f.service.retryFailedPayment(row.id))
            assertEquals(kind,if(kind in listOf("known-wrong","envelope")) 1 else 0,calls.get());assertEquals(before,f.row(row.id))
        }
    }
}
