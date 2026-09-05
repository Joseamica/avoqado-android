package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.cashDrawerSyncEventJson
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The batch wire contract must preserve the drawer identity stored on the exact local row. */
class CashDrawerSyncSessionIdTest {
    @Test
    fun `el payload conserva el sessionId historico del evento local`() {
        val local = CashDrawerEventEntity(
            id = "local-mov-1",
            sessionId = "drawer-cerrada-del-martes",
            venueId = "venue-1",
            type = "PAY_OUT",
            amountCents = 5_000,
            note = "Retiro",
            staffId = "staff-1",
            staffName = "Cajero",
            orderId = null,
            createdAt = 1_788_270_400_000,
        )

        val payload = Json.parseToJsonElement(cashDrawerSyncEventJson(local)).jsonObject
        val wireEvent = payload.getValue("events").jsonArray.single().jsonObject

        assertEquals("drawer-cerrada-del-martes", wireEvent.getValue("sessionId").jsonPrimitive.content)
        assertEquals("local-mov-1", wireEvent.getValue("localId").jsonPrimitive.content)
    }
}
