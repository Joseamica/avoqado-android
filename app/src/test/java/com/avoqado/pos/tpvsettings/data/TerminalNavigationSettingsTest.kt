package com.avoqado.pos.tpvsettings.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalNavigationSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** La pantalla de impresoras dice cómo se llama ESTE aparato: sólo por el id exacto, nunca el de otra terminal. */
    @Test
    fun `P1 el nombre del aparato sale de su propia terminal y nunca de otra`() {
        val response = json.decodeFromString<VenueSettingsResponse>(
            """
            {
              "success": true,
              "data": {
                "terminals": [
                  { "id": "t1", "name": "Avoqado Windows 10", "status": "ACTIVE", "type": "POS_ANDROID" },
                  { "id": "t2", "name": "  Avoqado Windows 10 (2) ", "status": "ACTIVE" }
                ],
                "deviceTerminal": { "id": "t2" }
              }
            }
            """.trimIndent(),
        )
        assertEquals("Avoqado Windows 10 (2)", response.data.toTerminalNavigationSettings().terminalName)

        val sinSuNombre = json.decodeFromString<VenueSettingsResponse>(
            """{ "data": { "terminals": [ { "id": "t1", "name": "Avoqado Windows 10" } ], "deviceTerminal": { "id": "t9" } } }""",
        )
        assertEquals(null, sinSuNombre.data.toTerminalNavigationSettings().terminalName)

        val serverViejo = json.decodeFromString<VenueSettingsResponse>("""{ "data": { "deviceTerminal": { "id": "t2" } } }""")
        assertEquals(null, serverViejo.data.toTerminalNavigationSettings().terminalName)
    }

    @Test
    fun `maps device terminal workspace and capabilities from venue settings`() {
        val response = json.decodeFromString<VenueSettingsResponse>(
            """
            {
              "success": true,
              "data": {
                "activeTerminalId": "terminal-checkout",
                "deviceTerminal": {
                  "id": "terminal-checkout",
                  "defaultWorkspace": "AREA_OPERATIONS",
                  "canIssueAreaTickets": false,
                  "canCheckoutAreaTickets": true,
                  "canDeliverAreaTickets": false,
                  "fulfillmentAreaId": null
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            TerminalNavigationSettings(
                terminalId = "terminal-checkout",
                defaultWorkspace = TerminalNavigationSettings.AREA_OPERATIONS,
                canCheckoutAreaTickets = true,
            ),
            response.data.toTerminalNavigationSettings(),
        )
    }

    @Test
    fun `old server payload defaults to standard pos`() {
        val response = json.decodeFromString<VenueSettingsResponse>(
            """{"success":true,"data":{"activeTerminalId":"legacy-terminal"}}""",
        )

        assertEquals(
            TerminalNavigationSettings.DEFAULT,
            response.data.toTerminalNavigationSettings(),
        )
    }
}
