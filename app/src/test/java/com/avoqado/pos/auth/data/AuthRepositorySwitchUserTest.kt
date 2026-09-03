package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.LoginResponse
import com.avoqado.pos.auth.data.model.SwitchUserRequest
import com.avoqado.pos.auth.data.model.UserData
import com.avoqado.pos.auth.data.model.VenueData
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.notifications.data.NotificationsRepository
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.PromotionsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cambiar de usuario con PIN — «es como un logout login pero con pin» (founder, 2026-08-29).
 *
 * Lo que estas pruebas protegen: que el relevo REEMPLACE la sesión guardada. Si el rol o los
 * permisos del anterior sobreviven, la app sigue pintando su menú y sus botones para la persona
 * nueva — que es exactamente el defecto que esta función viene a evitar.
 */
class AuthRepositorySwitchUserTest {

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val repository = AuthRepository(
        apiService = apiService,
        secureStorage = secureStorage,
        productsRepository = mockk<ProductsRepository>(relaxed = true),
        discountsRepository = mockk<DiscountsRepository>(relaxed = true),
        promotionsRepository = mockk<PromotionsRepository>(relaxed = true),
        tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true),
        savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true),
        inventoryRepository = mockk<InventoryRepository>(relaxed = true),
        transactionRepository = mockk<TransactionRepository>(relaxed = true),
        notificationsRepository = mockk<NotificationsRepository>(relaxed = true),
        refrescoExclusivo = RefrescoExclusivo(),
    )

    private fun respuestaDeGerente() = LoginResponse(
        success = true,
        accessToken = "access-gerente",
        refreshToken = "refresh-gerente",
        user = UserData(
            id = "staff-ana",
            email = "ana@x.com",
            firstName = "Ana",
            lastName = "Ruiz",
            venues = listOf(
                VenueData(
                    id = "venue-1",
                    name = "Amaena",
                    slug = "amaena",
                    role = "MANAGER",
                    permissions = listOf("orders:read", "shifts:close"),
                ),
            ),
        ),
    )

    @Test
    fun `el PIN correcto REEMPLAZA la sesion guardada con el rol y los permisos de quien entra`() = runTest {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { apiService.switchUser("venue-1", SwitchUserRequest("9335")) } returns respuestaDeGerente()

        val r = repository.switchUser("9335")

        assertTrue(r is SwitchUserResult.Success)
        verify(exactly = 1) {
            secureStorage.saveLogin(
                userId = "staff-ana",
                email = "ana@x.com",
                firstName = "Ana",
                lastName = "Ruiz",
                venueId = "venue-1",
                venueName = "Amaena",
                venueSlug = "amaena",
                role = "MANAGER",
                accessToken = "access-gerente",
                refreshToken = "refresh-gerente",
                venueTimezone = any(),
                venuePermissions = listOf("orders:read", "shifts:close"),
            )
        }
    }

    @Test
    fun `un PIN incorrecto NO toca la sesion guardada — el anterior sigue trabajando`() = runTest {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { apiService.switchUser("venue-1", SwitchUserRequest("0000")) } throws
            retrofit2.HttpException(retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))

        val r = repository.switchUser("0000")

        assertTrue(r is SwitchUserResult.Error)
        verify(exactly = 0) { secureStorage.saveLogin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 sin red NO se cambia de usuario, y el mensaje lo dice — no se guarda nada a medias`() = runTest {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { apiService.switchUser(any(), any()) } throws java.io.IOException("sin red")

        val r = repository.switchUser("9335")

        assertTrue(r is SwitchUserResult.Error)
        assertTrue((r as SwitchUserResult.Error).message.contains("conexión", ignoreCase = true))
        verify(exactly = 0) { secureStorage.saveLogin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sin venue local no se llama al servidor — no hay sobre qué relevar`() = runTest {
        every { secureStorage.venueId } returns ""

        val r = repository.switchUser("9335")

        assertTrue(r is SwitchUserResult.Error)
    }

    @Test
    fun `P1 el rol nuevo queda accesible de inmediato para que la UI se repinte`() = runTest {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { apiService.switchUser(any(), any()) } returns respuestaDeGerente()

        val r = repository.switchUser("9335") as SwitchUserResult.Success

        assertEquals("MANAGER", r.role)
        assertEquals("Ana", r.firstName)
    }
}
