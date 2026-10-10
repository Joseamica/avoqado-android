package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `tables:configure` espejado por nombre EXACTO (spec 2026-10-09 §4.2, aviso «Arma el plano…»). Espejo: iOS `ConfigurarPlanoPermisoTests`. */
class ConfigurarPlanoPermisoTest {

    private val secureStorage = mockk<SecureStorage>()
    private val roleManager = RoleManager(secureStorage)

    @Test
    fun `configurar el plano espeja tables configure`() {
        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.venuePermissions } returns listOf("tables:read", "tables:configure")
        assertTrue(roleManager.canConfigureTables)
        every { secureStorage.venuePermissions } returns listOf("tables:read")
        assertFalse(roleManager.canConfigureTables)
        every { secureStorage.venuePermissions } returns listOf("tables:*")
        assertTrue(roleManager.canConfigureTables)
    }

    @Test
    fun `configurar el plano respaldo por rol cae a gerente`() {
        every { secureStorage.venuePermissions } returns emptyList()
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canConfigureTables)
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canConfigureTables)
    }
}
