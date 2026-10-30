package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.RefreshResponse
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryVenueBindingTest {

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true) {
        every { siSigueLaGeneracion(any(), any()) } answers { secondArg<() -> Unit>().invoke(); true }
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

    @Test
    fun `repairCurrentVenueBinding reissues the token for the locally selected venue`() = runTest {
        every { secureStorage.venueId } returns "venue-atole"
        every { secureStorage.refreshSiSigue(any(), "venue-atole") } returns "refresh-old"
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns true
        coEvery {
            apiService.refreshToken(RefreshRequest("refresh-old", venueId = "venue-atole"))
        } returns RefreshResponse(
            accessToken = "access-atole",
            refreshToken = "refresh-atole",
        )

        val repaired = repository.repairCurrentVenueBinding()

        assertTrue(repaired)
        coVerify(exactly = 1) {
            apiService.refreshToken(RefreshRequest("refresh-old", venueId = "venue-atole"))
        }
        verify(exactly = 1) {
            secureStorage.updateTokensSiSigue("refresh-old", "access-atole", "refresh-atole")
        }
    }

    @Test
    fun `si la sesion cambio mientras se ligaba al venue, no se pisa y no cuenta como reparada`() = runTest {
        every { secureStorage.venueId } returns "venue-atole"
        every { secureStorage.refreshSiSigue(any(), "venue-atole") } returns "refresh-old"
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns false
        coEvery {
            apiService.refreshToken(RefreshRequest("refresh-old", venueId = "venue-atole"))
        } returns RefreshResponse(accessToken = "access-atole", refreshToken = "refresh-atole")

        assertFalse(repository.repairCurrentVenueBinding())
        verify(exactly = 0) { secureStorage.updateTokens(any(), any()) }
    }

    @Test
    fun `D4 H3-a si mientras espera turno entra otra persona o cambia la sucursal, no se pide nada`() = runTest {
        // Sesión, sucursal y llave se leen juntas dentro del candado: si ya no son las del inicio, no hay llave.
        every { secureStorage.venueId } returns "venue-atole"
        every { secureStorage.refreshSiSigue(any(), any()) } returns null

        assertFalse(repository.repairCurrentVenueBinding())
        coVerify(exactly = 0) { apiService.refreshToken(any()) }
    }

    @Test
    fun `D4 H3-b si la sucursal cambia con la renovacion en vuelo, se guardan las llaves y se liga a la vigente`() = runTest {
        // Vuelta 1: empieza en V1 y, al guardar, ya es V2. Vuelta 2: todo V2.
        every { secureStorage.generacion } returns 4L
        every { secureStorage.venueId } returnsMany listOf("venue-atole", "venue-otra", "venue-otra", "venue-otra")
        every { secureStorage.refreshSiSigue(4L, "venue-atole") } returns "refresh-old"
        every { secureStorage.refreshSiSigue(4L, "venue-otra") } returns "refresh-v1"
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns true
        coEvery { apiService.refreshToken(RefreshRequest("refresh-old", venueId = "venue-atole")) } returns
            RefreshResponse(accessToken = "access-v1", refreshToken = "refresh-v1")
        coEvery { apiService.refreshToken(RefreshRequest("refresh-v1", venueId = "venue-otra")) } returns
            RefreshResponse(accessToken = "access-v2", refreshToken = "refresh-v2")

        assertTrue(repository.repairCurrentVenueBinding())
        // Las llaves de V1 NO se tiraron (el servidor ya las rotó) y la segunda vuelta ligó la sucursal vigente.
        verify(exactly = 1) { secureStorage.updateTokensSiSigue("refresh-old", "access-v1", "refresh-v1") }
        verify(exactly = 1) { secureStorage.updateTokensSiSigue("refresh-v1", "access-v2", "refresh-v2") }
    }

    @Test
    fun `D4 la segunda vuelta no adopta a quien entro mientras tanto`() = runTest {
        // Codex código r1 #4: la vuelta 1 ve el cambio de sucursal; antes de la 2 entra B. La 2 es de la sesión con que
        // empezó (sello 4): con B ya adentro no hay llave que leer y no se renueva a B.
        every { secureStorage.generacion } returns 4L
        every { secureStorage.venueId } returnsMany listOf("venue-atole", "venue-otra", "venue-otra")
        every { secureStorage.refreshSiSigue(4L, "venue-atole") } returns "refresh-old"
        every { secureStorage.refreshSiSigue(4L, "venue-otra") } returns null // ya es la sesión de B
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns true
        coEvery { apiService.refreshToken(RefreshRequest("refresh-old", venueId = "venue-atole")) } returns
            RefreshResponse(accessToken = "access-v1", refreshToken = "refresh-v1")

        assertFalse(repository.repairCurrentVenueBinding())
        coVerify(exactly = 1) { apiService.refreshToken(any()) }
    }
}
