package com.avoqado.pos.auth.data

import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.GoogleTokenRequest
import com.avoqado.pos.auth.data.model.LoginResponse
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.auth.data.model.PaseDeGoogle
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
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Entrar con Google en el POS: mismo guardado que el correo, sucursal que elige el SERVIDOR (hz:6bfcd3a1:D1). */
class AuthRepositoryGoogleTest {

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true) {
        every { generacion } returns 7L
        every { instalarSiSigue(7L, any()) } answers { secondArg<() -> Unit>().invoke(); 8L }
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

    private fun venue(id: String) = VenueData(id = id, name = "Sucursal $id", slug = "s-$id", role = "ADMIN")
    private fun respuesta(selected: String?) = LoginResponse(
        success = true,
        accessToken = "acc",
        refreshToken = "ref",
        user = UserData(id = "u1", email = "a@b.com", venues = listOf(venue("v1"), venue("v2"))),
        selectedVenueId = selected,
    )

    private fun http(code: Int, cuerpo: String) = HttpException(Response.error<Any>(code, cuerpo.toResponseBody(null)))

    @Test
    fun `token - guarda igual que el correo`() = runTest {
        val pedido = slot<GoogleTokenRequest>()
        coEvery { apiService.loginWithGoogleToken(capture(pedido)) } returns respuesta(null)

        val r = repository.loginWithGoogle(PaseDeGoogle.Token("tok"), 7L)

        assertTrue(r is LoginResult.Success)
        assertEquals("tok", pedido.captured.idToken)
        verify { secureStorage.saveLogin("u1", "a@b.com", any(), any(), "v1", any(), any(), any(), "acc", "ref", any(), any(), any()) }
    }

    @Test
    fun `con selectedVenueId de la segunda sucursal guarda la segunda en los dos caminos`() = runTest {
        coEvery { apiService.loginWithGoogleToken(any()) } returns respuesta("v2")
        coEvery { apiService.loginWithGoogleCode(any()) } returns respuesta("v2")
        coEvery { apiService.login(any()) } returns respuesta("v2")

        repository.loginWithGoogle(PaseDeGoogle.Token("t"), 7L)
        repository.loginWithGoogle(PaseDeGoogle.Codigo("c", "v", "http://127.0.0.1:1/cb"), 7L)
        repository.loginWithEmail("a@b.com", "x")

        verify(exactly = 3) { secureStorage.saveLogin("u1", "a@b.com", any(), any(), "v2", any(), any(), any(), "acc", "ref", any(), any(), any()) }
    }

    @Test
    fun `sin selectedVenueId guarda la primera (SERVIDOR viejo)`() = runTest {
        coEvery { apiService.loginWithGoogleToken(any()) } returns respuesta(null)
        repository.loginWithGoogle(PaseDeGoogle.Token("t"), 7L)
        verify { secureStorage.saveLogin("u1", "a@b.com", any(), any(), "v1", any(), any(), any(), "acc", "ref", any(), any(), any()) }
    }

    @Test
    fun `404 muestra el texto del servidor`() = runTest {
        val texto = "No encontramos una cuenta de Avoqado con ese correo."
        coEvery { apiService.loginWithGoogleToken(any()) } throws http(404, """{"message":"$texto"}""")
        assertEquals(texto, (repository.loginWithGoogle(PaseDeGoogle.Token("t"), 7L) as LoginResult.Error).message)
    }

    @Test
    fun `cuerpo ilegible - Error del servidor con el codigo`() = runTest {
        coEvery { apiService.loginWithGoogleToken(any()) } throws http(500, "<html>")
        assertEquals("Error del servidor (500)", (repository.loginWithGoogle(PaseDeGoogle.Token("t"), 7L) as LoginResult.Error).message)
    }

    @Test
    fun `sin red - texto sin red`() = runTest {
        coEvery { apiService.loginWithGoogleToken(any()) } throws IOException("sin red")
        assertEquals(
            "Sin conexión: conéctate a internet para entrar con Google.",
            (repository.loginWithGoogle(PaseDeGoogle.Token("t"), 7L) as LoginResult.Error).message,
        )
    }

    @Test
    fun `sello viejo - OTRA_PERSONA_ENTRO y no instala`() = runTest {
        every { secureStorage.instalarSiSigue(3L, any()) } returns null
        coEvery { apiService.loginWithGoogleToken(any()) } returns respuesta(null)

        val r = repository.loginWithGoogle(PaseDeGoogle.Token("t"), 3L)

        assertEquals(AuthRepository.OTRA_PERSONA_ENTRO, (r as LoginResult.Error).message)
        verify(exactly = 0) { secureStorage.saveLogin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Fallo y Cancelado no llegan a la red y no truenan`() = runTest {
        assertEquals("nada", (repository.loginWithGoogle(PaseDeGoogle.Fallo("nada", "x"), 7L) as LoginResult.Error).message)
        assertTrue(repository.loginWithGoogle(PaseDeGoogle.Cancelado, 7L) is LoginResult.Error)
    }

    @Test
    fun `config sin red - SinRed`() = runTest {
        coEvery { apiService.configDeGoogle() } throws IOException()
        assertEquals(ConfigDeGoogle.SinRed, repository.configDeGoogle())
    }

    @Test
    fun `config 404 - NoDisponible`() = runTest {
        coEvery { apiService.configDeGoogle() } throws http(404, "")
        assertEquals(ConfigDeGoogle.NoDisponible, repository.configDeGoogle())
    }

    @Test
    fun `config con android y windows en false - NoDisponible, y con uno - Lista`() = runTest {
        coEvery { apiService.configDeGoogle() } returns GoogleConfigResponse(success = true)
        assertEquals(ConfigDeGoogle.NoDisponible, repository.configDeGoogle())
        val c = GoogleConfigResponse(success = true, android = true)
        coEvery { apiService.configDeGoogle() } returns c
        assertEquals(ConfigDeGoogle.Lista(c), repository.configDeGoogle())
    }
}
