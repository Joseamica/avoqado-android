package com.avoqado.pos.scale

import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.data.ScaleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleUsageContextTest {

    private val profile = ScaleProfile(
        id = "cedis",
        name = "Justa CEDIS",
        location = "CEDIS",
        model = "LP7516",
        allowedContexts = listOf("STOCK_COUNT"),
        transport = "ANDROID_USB_SERIAL",
    )

    @Test
    fun `selects active USB profile only for requested context`() {
        val settings = ScaleIntegrationSettings(
            entitled = true,
            enabled = true,
            profile = profile,
        )

        assertEquals(profile, settings.configuredProfileFor(ScaleUsageContext.STOCK_COUNT))
        assertNull(settings.configuredProfileFor(ScaleUsageContext.AREA_TICKET_LINE))
    }

    @Test
    fun `manual fallback remains when entitlement or venue setting is off`() {
        assertNull(
            ScaleIntegrationSettings(
                entitled = false,
                enabled = true,
                profile = profile,
            ).configuredProfileFor(ScaleUsageContext.STOCK_COUNT),
        )
        assertNull(
            ScaleIntegrationSettings(
                entitled = true,
                enabled = false,
                profile = profile,
            ).configuredProfileFor(ScaleUsageContext.STOCK_COUNT),
        )
    }
}
