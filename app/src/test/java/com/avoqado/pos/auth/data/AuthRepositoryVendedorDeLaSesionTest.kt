package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.LoginResponse
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.RefreshResponse
import com.avoqado.pos.auth.data.model.SwitchUserRequest
import com.avoqado.pos.auth.data.model.UserData
import com.avoqado.pos.auth.data.model.VenueData
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.notifications.data.NotificationsRepository
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.PromotionsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D66 (13-nov, D3): «Vendiendo: <persona>» es de la SESIÓN. Se cerró la sesión del gerente, entró la cajera con su
 * contraseña y el carrito seguía diciendo «Vendiendo: Manager Venue 1»: el cobro con tarjeta salió a nombre del gerente.
 * Sólo el cambio por PIN ponía a quien entra; la contraseña y la huella dejaban la elección del anterior.
 */
class AuthRepositoryVendedorDeLaSesionTest {

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true) {
        every { instalarSiSigue(any(), any()) } answers { secondArg<() -> Unit>().invoke(); 1L }
    }
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

    private fun respuestaDeLaCajera() = LoginResponse(
        success = true,
        accessToken = "access-cajera",
        refreshToken = "refresh-cajera",
        user = UserData(
            id = "staff-cajera",
            email = "cashier@cashier.com",
            firstName = "Cajera",
            lastName = "Venue 1",
            venues = listOf(VenueData(id = "venue-1", name = "Avoqado Full", slug = "full", role = "CASHIER")),
        ),
    )

    @Test
    fun `entrar con contrasena deja Vendiendo con quien entro`() = runTest {
        coEvery { apiService.login(any()) } returns respuestaDeLaCajera()

        assertTrue(repository.loginWithEmail("cashier@cashier.com", "contraseña") is LoginResult.Success)

        verify(exactly = 1) { secureStorage.venderComoQuienEntra("staff-cajera", "Cajera Venue 1") }
    }

    @Test
    fun `entrar con contrasena sin nombre usa el correo`() = runTest {
        val r = respuestaDeLaCajera()
        coEvery { apiService.login(any()) } returns r.copy(user = r.user!!.copy(firstName = null, lastName = null))

        repository.loginWithEmail("cashier@cashier.com", "contraseña")

        verify(exactly = 1) { secureStorage.venderComoQuienEntra("staff-cajera", "cashier@cashier.com") }
    }

    @Test
    fun `un login tardio no cambia el vendedor de quien ya entro`() = runTest {
        every { secureStorage.generacion } returns 7L
        every { secureStorage.instalarSiSigue(7L, any()) } returns null
        coEvery { apiService.login(any()) } returns respuestaDeLaCajera()

        assertEquals(LoginResult.Error(AuthRepository.OTRA_PERSONA_ENTRO), repository.loginWithEmail("cashier@cashier.com", "x"))

        verify(exactly = 0) { secureStorage.venderComoQuienEntra(any(), any()) }
    }

    @Test
    fun `el cambio por PIN sigue dejando Vendiendo con quien entro`() = runTest {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { apiService.switchUser("venue-1", SwitchUserRequest("1234")) } returns respuestaDeLaCajera()

        assertTrue(repository.switchUser("1234") is SwitchUserResult.Success)

        verify(exactly = 1) { secureStorage.venderComoQuienEntra("staff-cajera", "Cajera Venue 1") }
    }

    @Test
    fun `entrar con huella deja Vendiendo con quien entro`() = runTest {
        val venue = StoredVenue(id = "venue-1", name = "Avoqado Full")
        every { secureStorage.biometricUserId } returns "staff-cajera"
        every { secureStorage.siSigueLaGeneracion(9L, any()) } returns true
        every { secureStorage.restoreBiometricSession(venue) } returns true
        every { secureStorage.userId } returns "staff-cajera"
        every { secureStorage.userEmail } returns "cashier@cashier.com"
        coEvery { apiService.refreshToken(RefreshRequest("huella-1")) } returns
            RefreshResponse(accessToken = "access-2", refreshToken = "huella-2")

        assertTrue(repository.entrarConHuella("huella-1", venue, 9L))

        verify(exactly = 1) { secureStorage.venderComoQuienEntra("staff-cajera", "cashier@cashier.com") }
    }

    @Test
    fun `se olvidan las elecciones de todas las sucursales y nada mas`() {
        val llaves = setOf(
            "selectedStaffId_venue-1", "selectedStaffName_venue-1",
            "selectedStaffId_venue-2", "selectedStaffName_global",
            "floorAreaTab_venue-1", "calendarView_venue-1", "selectedStaffIdX",
        )

        assertEquals(
            setOf("selectedStaffId_venue-1", "selectedStaffName_venue-1", "selectedStaffId_venue-2", "selectedStaffName_global"),
            SecureStorage.llavesDelVendedorElegido(llaves).toSet(),
        )
    }
}
