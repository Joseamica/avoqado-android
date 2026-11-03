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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val secureStorage = mockk<SecureStorage>(relaxed = true) {
        // D4: por defecto nadie entra ni sale mientras viaja el PIN.
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
    fun `D4 H2-c un PIN cuya respuesta llega despues de que entro o salio alguien no se instala`() = runTest {
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.generacion } returns 3L
        every { secureStorage.instalarSiSigue(3L, any()) } returns null // mientras viajaba, la sesión cambió
        coEvery { apiService.switchUser("venue-1", SwitchUserRequest("9335")) } returns respuestaDeGerente()

        val r = repository.switchUser("9335")

        assertEquals(SwitchUserResult.Error(AuthRepository.OTRA_PERSONA_ENTRO), r)
        verify(exactly = 0) { secureStorage.saveLogin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { secureStorage.saveSelectedStaffForCurrentVenue(any(), any()) }
    }

    @Test
    fun `D4 H2-a un login con contrasena que llega despues de que entro otra persona no la reinstala`() = runTest {
        every { secureStorage.generacion } returns 7L
        every { secureStorage.instalarSiSigue(7L, any()) } returns null
        coEvery { apiService.login(any()) } returns respuestaDeGerente()

        val r = repository.loginWithEmail("ana@x.com", "contraseña")

        assertEquals(LoginResult.Error(AuthRepository.OTRA_PERSONA_ENTRO), r)
        verify(exactly = 0) { secureStorage.saveLogin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `D4 un login con contrasena sin nadie en medio se instala con su lista de venues`() = runTest {
        coEvery { apiService.login(any()) } returns respuestaDeGerente()

        assertTrue(repository.loginWithEmail("ana@x.com", "contraseña") is LoginResult.Success)
        verify(exactly = 1) { secureStorage.saveLogin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { secureStorage.venuesList = any() }
    }

    @Test
    fun `D4 H2-d y H2-g la huella tardia guarda su llave rotada en la reserva y no instala nada`() = runTest {
        // Codex código r1 #6: la prueba del ViewModel sustituía todo; aquí corre la renovación y el guardado de verdad.
        val venue = StoredVenue(id = "venue-1", name = "Amaena")
        every { secureStorage.biometricUserId } returns "staff-ana"
        every { secureStorage.siSigueLaGeneracion(9L, any()) } returns true
        every { secureStorage.instalarSiSigue(9L, any()) } returns null // mientras viajaba, entró otra persona
        coEvery { apiService.refreshToken(RefreshRequest("huella-1")) } returns
            RefreshResponse(accessToken = "access-2", refreshToken = "huella-2")

        assertFalse(repository.entrarConHuella("huella-1", venue, 9L))

        verify(exactly = 1) { secureStorage.guardarRotadaDeLaHuellaSiSigue("huella-1", "huella-2") }
        verify(exactly = 0) { secureStorage.updateTokens(any(), any()) }
        verify(exactly = 0) { secureStorage.restoreBiometricSession(any()) }
    }

    @Test
    fun `D4 la huella sin nadie en medio instala llaves e identidad y guarda la rotada`() = runTest {
        val venue = StoredVenue(id = "venue-1", name = "Amaena")
        every { secureStorage.biometricUserId } returns "staff-ana"
        every { secureStorage.siSigueLaGeneracion(9L, any()) } returns true
        every { secureStorage.restoreBiometricSession(venue) } returns true
        coEvery { apiService.refreshToken(RefreshRequest("huella-1")) } returns
            RefreshResponse(accessToken = "access-2", refreshToken = "huella-2")

        assertTrue(repository.entrarConHuella("huella-1", venue, 9L))

        verify(exactly = 1) { secureStorage.guardarRotadaDeLaHuellaSiSigue("huella-1", "huella-2") }
        verify(exactly = 1) { secureStorage.updateTokens(accessToken = "access-2", refreshToken = "huella-2") }
        verify(exactly = 1) { secureStorage.restoreBiometricSession(venue) }
    }

    @Test
    fun `D4 una huella que espero turno mientras entro otra persona no manda su llave`() = runTest {
        // Codex código r4 #1: otro toque de huella terminó primero. Esta llave puede estar consumida: mandarla revocaría
        // la sesión que acaba de entrar. No se manda nada ni se toca la reserva.
        val venue = StoredVenue(id = "venue-1", name = "Amaena")
        every { secureStorage.biometricUserId } returns "staff-ana"
        every { secureStorage.siSigueLaGeneracion(9L, any()) } returns false

        assertFalse(repository.entrarConHuella("huella-1", venue, 9L))

        coVerify(exactly = 0) { apiService.refreshToken(any()) }
        verify(exactly = 0) { secureStorage.guardarRotadaDeLaHuellaSiSigue(any(), any()) }
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
