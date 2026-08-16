package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.RefreshResponse
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryVenueBindingTest {

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
    )

    @Test
    fun `repairCurrentVenueBinding reissues the token for the locally selected venue`() = runTest {
        every { secureStorage.venueId } returns "venue-atole"
        every { secureStorage.refreshToken } returns "refresh-old"
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
            secureStorage.updateTokens("access-atole", "refresh-atole")
        }
    }
}
