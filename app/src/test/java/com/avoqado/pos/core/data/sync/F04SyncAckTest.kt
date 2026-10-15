package com.avoqado.pos.core.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class F04SyncAckTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun ack(value: String, status: String = "REJECTED", code: String = "STALE_DEVICE_SEQUENCE", id: String = "cash") =
        json.decodeFromString<SyncAck>("""{"id":"$id","status":"$status","errorCode":"$code","details":{"latestSeq":$value}}""")
    private fun floor(ack: SyncAck, id: String = "cash", rowVenue: String = "v1", sentVenue: String = "v1", sentDevice: String = "d1", currentDevice: String = "d1") =
        ack.latestSeqFor(id, 1, rowVenue, sentVenue, sentDevice, currentDevice)

    @Test fun additiveDetailsSurviveTheWireAndRaiseOnlyAStaleRejectedFloor() {
        assertEquals(99L, floor(ack("99")))
        assertEquals(99L, floor(ack("99.0")))
        assertEquals(99L, floor(ack("9.9e1")))
        for (status in listOf("ACKED", "RETRY", "unknown")) assertNull(floor(ack("99", status = status)))
        assertNull(floor(ack("99", code = "BUSINESS_RULE")))
        assertNull(floor(ack("99", id = "other")))
    }

    @Test fun stringsBooleansFractionsNegativeNullAndOverflowAreNotFloors() {
        for (value in listOf("\"99\"", "true", "false", "99.5", "-1", "null", "{}", "[]", "2147483648", "9007199254740992", "0")) {
            assertNull("Rejected raw $value", floor(ack(value)))
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(floor(ack("99").copy(details = JsonObject(mapOf("latestSeq" to JsonPrimitive(value))))))
        }
        assertEquals(2147483647L, floor(ack("2147483647")))
        assertNull(floor(json.decodeFromString<SyncAck>("""{"id":"cash","status":"REJECTED","errorCode":"STALE_DEVICE_SEQUENCE"}""")))
    }

    @Test fun batchVenueAndDeviceContextCannotBeSubstitutedByTheVisibleScreen() {
        val ack = ack("99")
        assertNull(floor(ack, id = "unrelated"))
        assertNull(floor(ack, sentVenue = "v2"))
        assertNull(floor(ack, currentDevice = "new-device"))
        assertNull(floor(ack, sentDevice = "", currentDevice = ""))
        assertNull(floor(ack, sentDevice = " ", currentDevice = " "))
        // El contexto válido es el enviado, aunque la pantalla ahora esté en otra sucursal.
        assertEquals(99L, floor(ack, rowVenue = "previous", sentVenue = "previous"))
    }
}
