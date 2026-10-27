package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL
import com.avoqado.pos.core.data.local.database.RevisionCanceladoCero
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager

class CanceladoCeroProofContractTest {
    private val body = """{"externalId":"venta-original","items":[{"productId":"bebida-A","quantity":2}],"total":0,"tip":0}"""
    private val proof = """{"version":1,"venueId":"venue-A","orderId":"orden-original","externalId":"venta-original","orderStatus":"CANCELLED","hasPayments":false,"hasTerminalRequests":false,"verifiedAt":"2026-10-23T20:00:00.000Z"}"""
    private fun row() = PendingPaymentEntity(
        "folio-original", "venue-A", "staff-original", 0, 0, "CASH", "ORDER",
        orderId = "orden-original", orderRequestJson = body, syncStatus = "FAILED",
        lastError = "Error 400 original", q15CaptureJson = null,
    )
    private fun response(p: String = proof, outerOrder: String = "orden-original", outerExternal: String = "venta-original") =
        """{"data":{"orders":[{"orderId":"$outerOrder","externalId":"$outerExternal","cancelledPaymentEvidence":$p}]}}"""
    private fun reviewed() = row().copy(
        q15Reason = "CANCELLED_ZERO_PAYMENT", q15ReviewedAt = 10,
        q15ReviewedBy = "gerente-actual", q15ReviewRole = "MANAGER",
        q15ResultJson = """{"cancelledPaymentEvidence":$proof}""",
    )

    @Test fun `FAILED cero legacy se identifica sin inventar captura economica`() {
        val candidate = RevisionCanceladoCero.evidencia(row(), response())
        assertNotNull("La evidencia exacta debe reconocerse sin inventar una captura", candidate)
        val result = requireNotNull(candidate)
        assertEquals(JSONObject(proof).toString(), JSONObject(result).getJSONObject("cancelledPaymentEvidence").toString())
        assertNull(row().q15CaptureJson)
    }

    @Test fun `ausencia ambiguedad tipo incorrecto o identidad cruzada nunca son prueba`() {
        val bad = listOf(
            "{}", "[]", "null", "malformed", "{\"data\":{\"orders\":[]}}",
            response("{}"), response(JSONObject(proof).put("version", "1").toString()),
            response(JSONObject(proof).put("version", 2).toString()),
            response(JSONObject(proof).put("venueId", "otro-venue").toString()),
            response(JSONObject(proof).put("orderId", "otra-orden").toString()),
            response(JSONObject(proof).put("externalId", "otra-venta").toString()),
            response(JSONObject(proof).put("orderStatus", "PAID").toString()),
            response(JSONObject(proof).put("hasPayments", true).toString()),
            response(JSONObject(proof).put("hasPayments", "false").toString()),
            response(JSONObject(proof).apply { remove("hasPayments") }.toString()),
            response(JSONObject(proof).put("hasTerminalRequests", true).toString()),
            response(JSONObject(proof).put("hasTerminalRequests", "false").toString()),
            response(JSONObject(proof).apply { remove("hasTerminalRequests") }.toString()),
            response(JSONObject(proof).put("verifiedAt", "").toString()),
            response(JSONObject(proof).put("verifiedAt", "ayer").toString()),
            response(outerOrder = "otra-orden"), response(outerExternal = "otra-venta"),
            JSONObject(response()).apply {
                val orders = getJSONObject("data").getJSONArray("orders")
                orders.put(JSONObject(orders.getJSONObject(0).toString()))
            }.toString(),
        )
        for (candidate in bad) assertNull(candidate, RevisionCanceladoCero.evidencia(row(), candidate))
    }

    @Test fun `prueba buena no resuelve otra clase importe propina estado o cuerpo originales`() {
        val invalid = listOf(
            row().copy(amountCents = 1), row().copy(tipCents = 1), row().copy(paymentType = "FAST"),
            row().copy(syncStatus = "PENDING"), row().copy(syncStatus = "SYNCED"),
            row().copy(orderId = ""), row().copy(orderId = " "), row().copy(syncStatus = "EN_VUELO"), row().copy(orderId = "otra-orden"),
            row().copy(orderRequestJson = null), row().copy(orderRequestJson = "malformed"),
            row().copy(orderRequestJson = """{"externalId":123}"""),
            row().copy(orderRequestJson = """{"externalId":"otra-venta"}"""),
            row().copy(venueId = "otro-venue"),
        )
        for (candidate in invalid) assertNull(candidate.toString(), RevisionCanceladoCero.evidencia(candidate, response()))
    }

    @Test fun `sólo revisión completa con prueba estricta deja de retener Q16 y trabajo pendiente`() {
        assertTrue(RevisionCanceladoCero.revisado(reviewed()))
        for (candidate in listOf(
            reviewed().copy(q15ResultJson = null), reviewed().copy(q15ResultJson = "malformed"),
            reviewed().copy(q15ResultJson = "{}"), reviewed().copy(q15ResultJson = """{"cancelledPaymentEvidence":{"version":1}}"""),
            reviewed().copy(q15ReviewedAt = null), reviewed().copy(q15ReviewedBy = null),
            reviewed().copy(q15ReviewedBy = " "), reviewed().copy(q15ReviewRole = "CASHIER"),
            reviewed().copy(q15Reason = "DIFERENCIA_DE_COBRO"), reviewed().copy(amountCents = 1),
            reviewed().copy(tipCents = 1), reviewed().copy(paymentType = "FAST"),
            reviewed().copy(orderId = "otra-orden"), reviewed().copy(venueId = "otro-venue"),
        )) assertFalse(candidate.toString(), RevisionCanceladoCero.revisado(candidate))
    }

    @Test fun `CAS de revisar cambia una sola fila y conserva FAILED cuerpo error y llave`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { it.execute("""CREATE TABLE pending_payments(
                id TEXT PRIMARY KEY,venueId TEXT,syncStatus TEXT,paymentType TEXT,amountCents INTEGER,tipCents INTEGER,
                orderId TEXT,orderRequestJson TEXT,lastError TEXT,q15CaptureJson TEXT,q15InitialResultJson TEXT,
                q15ResultJson TEXT,q15Reason TEXT,q15DetectedAt INTEGER,q15ReviewedAt INTEGER,q15ReviewedBy TEXT,q15ReviewRole TEXT,
                createdAt INTEGER NOT NULL DEFAULT 1,capturedOrderRef TEXT,method TEXT NOT NULL DEFAULT 'CASH',tenderTypeId TEXT,tenderRevision INTEGER,
                splitType TEXT NOT NULL DEFAULT 'FULLPAYMENT',customerId TEXT,cashTenderedCents INTEGER,changeCents INTEGER)""") }
            fun execute(sql: String, parameters: Map<String, Any?> = emptyMap()): Int {
                val args = mutableListOf<Any?>()
                val bound = Regex(":([a-zA-Z][a-zA-Z0-9]*)").replace(sql) { args.add(parameters[it.groupValues[1]]); "?" }
                return db.prepareStatement(bound).use { statement ->
                    args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.executeUpdate()
                }
            }
            fun seed() {
                execute("DELETE FROM pending_payments")
                execute("""INSERT INTO pending_payments(id,venueId,syncStatus,paymentType,amountCents,tipCents,orderId,orderRequestJson,lastError)
                    VALUES('folio-original','venue-A','FAILED','ORDER',0,0,'orden-original',:body,'error-original')""", mapOf("body" to body))
            }
            val parameters = mapOf(
                "venueId" to "venue-A", "id" to "folio-original", "originalOrderId" to "orden-original",
                "originalOrderRequestJson" to body, "originalResultJson" to null,
                "originalSyncStatus" to "FAILED", "originalCaptureJson" to null, "originalCreatedAt" to 1L,
                "originalCapturedOrderRef" to null, "originalSplitType" to "FULLPAYMENT", "originalCustomerId" to null,
                "originalCashTenderedCents" to null, "originalChangeCents" to null, "originalReason" to null,
                "resultadoJson" to """{"cancelledPaymentEvidence":$proof}""",
                "actor" to "gerente-actual", "rol" to "MANAGER", "ahora" to 10L,
            )
            for (invalid in listOf(
                parameters + ("venueId" to "otro-venue"), parameters + ("id" to "otro-folio"),
                parameters + ("originalOrderId" to "otra-orden"), parameters + ("originalOrderRequestJson" to "otro-cuerpo"),
                parameters + ("originalResultJson" to "otro-resultado"), parameters + ("actor" to ""),
                parameters + ("actor" to " "), parameters + ("rol" to "CASHIER"),
            )) { seed(); assertEquals(invalid.toString(), 0, execute(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL, invalid)) }
            for (mutation in listOf(
                "syncStatus='PENDING'", "syncStatus='SYNCED'", "paymentType='FAST'", "amountCents=1", "tipCents=1",
                "q15ReviewedAt=5", "q15ResultJson='resultado-mas-nuevo'", "orderId='otra-orden'", "orderRequestJson='otro-cuerpo'",
            )) { seed(); execute("UPDATE pending_payments SET $mutation"); assertEquals(mutation, 0, execute(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL, parameters)) }
            seed()
            assertEquals(1, execute(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL, parameters))
            assertEquals(0, execute(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL, parameters))
            db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM pending_payments").use { rs ->
                assertTrue(rs.next()); assertEquals("FAILED", rs.getString("syncStatus"))
                assertEquals("folio-original", rs.getString("id")); assertEquals(body, rs.getString("orderRequestJson"))
                assertEquals("error-original", rs.getString("lastError")); assertEquals("CANCELLED_ZERO_PAYMENT", rs.getString("q15Reason"))
                assertEquals("gerente-actual", rs.getString("q15ReviewedBy")); assertEquals("MANAGER", rs.getString("q15ReviewRole"))
                assertEquals(10, rs.getLong("q15ReviewedAt")); assertNotNull(rs.getString("q15ResultJson")); assertFalse(rs.next())
            } }
        }
    }

    @Test fun `F3 NULL original acepta canonical estricto sin rellenar identidad y Q16 revisado valida misma prueba`() {
        val original=row().copy(orderId=null)
        val evidence=RevisionCanceladoCero.evidencia(original,response())
        assertNotNull("NULL original se resuelve por venue y external durable",evidence)
        assertNull(original.orderId)
        assertTrue(RevisionCanceladoCero.revisado(reviewed().copy(orderId=null,q15ResultJson=evidence)))
    }

    @Test fun `F3 NULL nunca acepta prueba sin canonical string ni envelope identidad tipos o external original cruzados`() {
        val original=row().copy(orderId=null)
        val invalid=listOf(
            response(outerOrder=""),response(outerOrder=" "),response(outerOrder="otra-orden"),
            response(JSONObject(proof).put("orderId","").toString()),
            response(JSONObject(proof).put("orderId",123).toString()),
            response(JSONObject(proof).apply { remove("orderId") }.toString()),
            response(JSONObject(proof).put("externalId","otra-venta").toString()),
            response(JSONObject(proof).put("venueId","otro-venue").toString()),
            response(JSONObject(proof).put("hasPayments","false").toString()),
            response(JSONObject(proof).put("hasTerminalRequests",true).toString()),
            response(JSONObject(proof).put("verifiedAt","ayer").toString()),
            JSONObject(response()).apply { getJSONObject("data").getJSONArray("orders").put(JSONObject()) }.toString(),
        )
        for(bad in invalid) assertNull(bad,RevisionCanceladoCero.evidencia(original,bad))
        for(bad in listOf("{}","""{"externalId":123}""","""{"externalId":" "}"""))
            assertNull(RevisionCanceladoCero.evidencia(original.copy(orderRequestJson=bad),response()))
        for(bad in listOf("", " ", "otra-orden")) assertFalse(RevisionCanceladoCero.revisado(reviewed().copy(orderId=bad)))
    }
}
