package com.avoqado.pos.tpvsettings.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalNavigationSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

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
