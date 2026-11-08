package com.avoqado.pos.articles

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.data.model.PriceType
import com.avoqado.pos.articles.data.model.ProductType
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * D2: las hojas de producto, categoría, grupo de modificadores y paquete de créditos enseñan lo que tenga
 * `errorMessage` del repositorio. Tiene que ser el motivo real del rechazo del SERVIDOR; sin red, decirlo; y una página
 * que no es nuestra API (502 de Render) nunca llega a la pantalla.
 */
class ArticulosMotivoDelRechazoRepositorioTest {
    /** Cada guardado con el texto de respaldo que tiene hoy. */
    private val guardados: List<Pair<String, suspend (ArticlesRepository) -> Boolean>> = listOf(
        "Error al crear producto" to { it.createProduct("{}") },
        "Error al actualizar producto" to { it.updateProduct("producto-1", "{}") },
        "Error al crear categoría" to { it.createCategory("{}") },
        "Error al actualizar categoría" to { it.updateCategory("categoria-1", "{}") },
        "Error al crear grupo de modificadores" to { it.createModifierGroup("{}") },
        "Error al actualizar grupo de modificadores" to { it.updateModifierGroup("grupo-1", "{}") },
        "Error al agregar modificador" to { it.addModifierToGroup("grupo-1", "{}") != null },
        "Error al actualizar modificador" to { it.updateModifier("grupo-1", "modificador-1", "{}") },
        "Error al eliminar modificador" to { it.deleteModifier("grupo-1", "modificador-1") },
        "Error al crear paquete de crédito" to { it.createCreditPack("{}") },
        "Error al actualizar paquete de crédito" to { it.updateCreditPack("paquete-1", "{}") },
    )

    @Test
    fun `un 400 deja el motivo que mando el servidor`() = runTest {
        val motivo = "Ya existe un registro con ese nombre en este negocio"
        guardados.forEach { (respaldo, guardar) ->
            val repository = repositorio(clientQueResponde(400, """{"success":false,"message":"$motivo"}"""))

            assertFalse(respaldo, guardar(repository))
            assertEquals(respaldo, motivo, repository.errorMessage.value)
        }
    }

    @Test
    fun `sin red dice que no hay conexion`() = runTest {
        guardados.forEach { (respaldo, guardar) ->
            val call = mockk<Call>()
            every { call.execute() } throws java.net.UnknownHostException("Unable to resolve host")
            val repository = repositorio(mockk { every { newCall(any()) } returns call })

            assertFalse(respaldo, guardar(repository))
            assertEquals(respaldo, "Sin conexión. Revisa la red e inténtalo de nuevo.", repository.errorMessage.value)
        }
    }

    @Test
    fun `una respuesta que no es de la API cae al texto propio`() = runTest {
        guardados.forEach { (respaldo, guardar) ->
            val repository = repositorio(clientQueResponde(502, "<html>Bad Gateway</html>"))

            assertFalse(respaldo, guardar(repository))
            assertEquals(respaldo, respaldo, repository.errorMessage.value)
        }
    }

    private fun repositorio(client: OkHttpClient) = ArticlesRepository(
        mockk<SecureStorage> { every { venueId } returns "venue-1" },
        client,
        mockk(relaxed = true),
    )

    private fun clientQueResponde(status: Int, body: String): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://api.avoqado.io/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Rechazo")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        return mockk { every { newCall(any()) } returns call }
    }
}

/**
 * D2: la hoja lee el motivo al terminar el guardado, así que el ViewModel tiene que limpiar el de un intento anterior
 * antes de guardar, y el grupo de modificadores (que hace varias peticiones) no puede pisar el motivo del servidor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticulosMotivoAlGuardarViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ArticlesRepository>(relaxed = true)
    private val repositoryError = MutableStateFlow<String?>(null)

    private val respaldoDelGrupo = "No se pudo guardar el grupo de modificadores. Intenta de nuevo."

    private fun viewModel(): ArticlesViewModel {
        every { repository.products } returns MutableStateFlow(emptyList())
        every { repository.categories } returns MutableStateFlow(emptyList())
        every { repository.modifierGroups } returns MutableStateFlow(emptyList())
        every { repository.discounts } returns MutableStateFlow(emptyList())
        every { repository.coupons } returns MutableStateFlow(emptyList())
        every { repository.creditPacks } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.errorMessage } returns repositoryError
        // El repositorio de verdad: setError y clearError mueven el mismo flujo que lee la hoja.
        every { repository.setError(any()) } answers { repositoryError.value = firstArg() }
        every { repository.clearError() } answers { repositoryError.value = null }
        val storage = mockk<SecureStorage>()
        every { storage.planSnapshot } returns null
        every { storage.planTier } returns null
        every { storage.planExempt } returns false
        val refreshGateFactory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns
            com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return ArticlesViewModel(repository, PlanManager(storage), refreshGateFactory)
    }

    @Test
    fun `guardar limpia el motivo anterior antes de llamar al servidor`() = runTest {
        val vm = viewModel()

        vm.createProduct(name = "A", type = ProductType.FOOD_AND_BEV, priceType = PriceType.FIXED, price = 1.0, isActive = true, trackInventory = false)
        vm.updateProduct(productId = "p", name = "A", type = ProductType.FOOD_AND_BEV, priceType = PriceType.FIXED, price = 1.0, isActive = true, trackInventory = false)
        vm.createCategory(name = "C")
        vm.updateCategory(categoryId = "c", name = "C")
        vm.createModifierGroup(name = "G", required = false, allowMultiple = false, modifiers = emptyList())
        vm.updateModifierGroup(groupId = "g", name = "G", required = false, allowMultiple = false, originalModifiers = emptyList(), currentModifiers = emptyList())
        vm.createCreditPack(name = "P", price = 1.0, active = true, items = emptyList())
        vm.updateCreditPack(packId = "k", name = "P", price = 1.0, active = true, items = emptyList())
        advanceUntilIdle()

        coVerifyOrder {
            repository.clearError()
            repository.createProduct(any())
            repository.clearError()
            repository.updateProduct("p", any())
            repository.clearError()
            repository.createCategory(any())
            repository.clearError()
            repository.updateCategory("c", any())
            repository.clearError()
            repository.createModifierGroup(any())
            repository.clearError()
            repository.updateModifierGroup("g", any())
            repository.clearError()
            repository.createCreditPack(any())
            repository.clearError()
            repository.updateCreditPack("k", any())
        }
    }

    @Test
    fun `una ficha abierta sin conexion no deja a la hoja el motivo de otro intento`() = runTest {
        repositoryError.value = "Error al eliminar grupo de modificadores"
        var queLeeLaHoja: String? = "sin llamar"

        viewModel().updateProduct(
            productId = "p",
            name = "A",
            type = ProductType.FOOD_AND_BEV,
            priceType = PriceType.FIXED,
            price = 1.0,
            isActive = true,
            trackInventory = false,
            abiertaSinConexion = true,
            onResult = { queLeeLaHoja = repositoryError.value },
        )
        advanceUntilIdle()

        assertEquals(null, queLeeLaHoja)
        coVerify(exactly = 0) { repository.updateProduct(any(), any()) }
    }

    @Test
    fun `grupo rechazado deja el motivo del servidor, no toca los modificadores y avisa a la hoja`() = runTest {
        val motivo = "Ya existe un grupo con ese nombre"
        coEvery { repository.updateModifierGroup("g", any()) } answers { repositoryError.value = motivo; false }
        var resultado: Boolean? = null
        var queLeeLaHoja: String? = null

        viewModel().updateModifierGroup(
            groupId = "g",
            name = "G",
            required = false,
            allowMultiple = false,
            originalModifiers = listOf(ArticleModifier(id = "m-viejo")),
            currentModifiers = listOf(
                ArticlesViewModel.InlineModifier(id = "m-uno", name = "Uno", price = 1.0),
                ArticlesViewModel.InlineModifier(name = "Nuevo", price = 2.0),
            ),
            onResult = { resultado = it; queLeeLaHoja = repositoryError.value },
        )
        advanceUntilIdle()

        assertEquals(false, resultado)
        assertEquals(motivo, queLeeLaHoja)
        // Nada a medias: reintentar no duplica el modificador nuevo ni borra dos veces.
        coVerify(exactly = 0) { repository.deleteModifier(any(), any()) }
        coVerify(exactly = 0) { repository.updateModifier(any(), any(), any()) }
        coVerify(exactly = 0) { repository.addModifierToGroup(any(), any()) }
        verify(exactly = 0) { repository.setError(any()) }
    }

    @Test
    fun `grupo rechazado sin motivo de ningun lado cae al texto propio y no a uno de otro intento`() = runTest {
        repositoryError.value = "motivo de un intento anterior"
        coEvery { repository.updateModifierGroup("g", any()) } returns false
        var queLeeLaHoja: String? = null

        viewModel().updateModifierGroup(
            groupId = "g",
            name = "G",
            required = false,
            allowMultiple = false,
            originalModifiers = emptyList(),
            currentModifiers = emptyList(),
            onResult = { queLeeLaHoja = repositoryError.value },
        )
        advanceUntilIdle()

        assertEquals(respaldoDelGrupo, queLeeLaHoja)
    }

    @Test
    fun `una excepcion en el guardado del grupo no pisa el motivo que ya hay`() = runTest {
        val motivo = "El máximo no puede ser menor que el mínimo"
        coEvery { repository.updateModifierGroup("g", any()) } answers {
            repositoryError.value = motivo
            throw IllegalStateException("boom")
        }
        var resultado: Boolean? = null

        viewModel().updateModifierGroup(
            groupId = "g",
            name = "G",
            required = false,
            allowMultiple = false,
            originalModifiers = emptyList(),
            currentModifiers = emptyList(),
            onResult = { resultado = it },
        )
        advanceUntilIdle()

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
    }

    @Test
    fun `grupo aceptado sigue con sus modificadores como hasta hoy`() = runTest {
        coEvery { repository.updateModifierGroup("g", any()) } returns true
        coEvery { repository.deleteModifier("g", any()) } returns true
        coEvery { repository.updateModifier("g", any(), any()) } returns true
        coEvery { repository.addModifierToGroup("g", any()) } returns "m-nuevo"
        var resultado: Boolean? = null

        viewModel().updateModifierGroup(
            groupId = "g",
            name = "G",
            required = false,
            allowMultiple = false,
            originalModifiers = listOf(ArticleModifier(id = "m-viejo"), ArticleModifier(id = "m-uno")),
            currentModifiers = listOf(
                ArticlesViewModel.InlineModifier(id = "m-uno", name = "Uno", price = 1.0),
                ArticlesViewModel.InlineModifier(name = "Nuevo", price = 2.0),
            ),
            onResult = { resultado = it },
        )
        advanceUntilIdle()

        assertEquals(true, resultado)
        coVerify(exactly = 1) { repository.deleteModifier("g", "m-viejo") }
        coVerify(exactly = 1) { repository.updateModifier("g", "m-uno", any()) }
        coVerify(exactly = 1) { repository.addModifierToGroup("g", any()) }
    }
}
