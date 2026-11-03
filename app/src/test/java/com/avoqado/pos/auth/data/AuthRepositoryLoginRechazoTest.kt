package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.LoginResult
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * El login con un 403 que la app no conoce muestra el TEXTO del servidor (D7, 26-oct-2026).
 *
 * Antes decía «Acceso denegado.» a secas: el negocio suspendido, el cerrado y el que tiene el alta sin terminar se
 * veían igual, y el dueño no sabía qué hacer. El servidor ya manda en `message` qué pasa y qué hacer.
 */
class AuthRepositoryLoginRechazoTest {

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

    private fun rechazo403(cuerpo: String) =
        HttpException(Response.error<Any>(403, cuerpo.toResponseBody(null)))

    private suspend fun mensajeDeLogin(cuerpo: String): String {
        coEvery { apiService.login(any()) } throws rechazo403(cuerpo)
        return (repository.loginWithEmail("dueno@negocio.com", "la-contraseña") as LoginResult.Error).message
    }

    @Test
    fun `alta sin terminar - muestra lo que dice el servidor`() = runTest {
        val texto = "Termina de crear tu cuenta en dashboard.avoqado.io para empezar a usar Avoqado."
        val cuerpo = """{"success":false,"code":"ALTA_SIN_TERMINAR","message":"$texto"}"""

        assertEquals(texto, mensajeDeLogin(cuerpo))
    }

    @Test
    fun `negocio suspendido - tambien muestra el texto del servidor`() = runTest {
        val texto = "Este establecimiento está suspendido temporalmente. Contacta al administrador para más información."

        assertEquals(texto, mensajeDeLogin("""{"success":false,"code":"VENUE_NOT_OPERATIONAL","message":"$texto"}"""))
    }

    // REGRESIÓN — los textos propios que la app ya tenía no cambian.
    @Test
    fun `sin acceso a ningun negocio - conserva el texto de la app`() = runTest {
        assertEquals(
            "No tienes acceso a ningún establecimiento.",
            mensajeDeLogin("""{"success":false,"code":"NO_VENUE_ACCESS","message":"No tienes acceso a ningún establecimiento"}"""),
        )
    }

    @Test
    fun `sin cuerpo legible - Acceso denegado como antes`() = runTest {
        assertEquals("Acceso denegado.", mensajeDeLogin("<html>403</html>"))
        assertEquals("Acceso denegado.", mensajeDeLogin(""))
    }
}
