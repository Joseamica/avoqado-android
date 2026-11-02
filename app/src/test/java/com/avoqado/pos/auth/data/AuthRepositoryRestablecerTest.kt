package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.RestablecerContrasenaRequest
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * «¿Olvidaste tu contraseña?» no hacía nada en ANDROID (1-nov-2026); en iOS pedía el correo a `mobile/auth/request-reset`.
 * Como en iOS, la respuesta del servidor no dice si la cuenta existe: sólo la falta de red se distingue.
 */
class AuthRepositoryRestablecerTest {

    private val apiService = mockk<ApiService>()
    private val repository = AuthRepository(
        apiService = apiService,
        secureStorage = mockk<SecureStorage>(relaxed = true),
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
    fun `pide el enlace con el correo limpio y en minusculas`() = runTest {
        coEvery { apiService.pedirRestablecerContrasena(any()) } returns Unit

        assertTrue(repository.pedirRestablecerContrasena("  Cajera@Avoqado.io "))
        coVerify(exactly = 1) { apiService.pedirRestablecerContrasena(RestablecerContrasenaRequest("cajera@avoqado.io")) }
    }

    @Test
    fun `un rechazo del servidor no se distingue del exito - no revela si la cuenta existe`() = runTest {
        coEvery { apiService.pedirRestablecerContrasena(any()) } throws
            HttpException(Response.error<Any>(429, "{}".toResponseBody(null)))

        assertTrue(repository.pedirRestablecerContrasena("nadie@avoqado.io"))
    }

    @Test
    fun `sin red dice que no salio`() = runTest {
        coEvery { apiService.pedirRestablecerContrasena(any()) } throws IOException("sin red")

        assertFalse(repository.pedirRestablecerContrasena("cajera@avoqado.io"))
    }
}
