package com.avoqado.pos.customers

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.PaginaDeClientes
import com.avoqado.pos.customers.presentation.CustomersViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * El buscador de clientes: el servidor busca en TODO el negocio, después de 300 ms sin teclear; una respuesta vieja
 * nunca pisa la nueva; y sin red se busca entre lo guardado en la tablet, diciéndolo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomersViewModelBusquedaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<CustomersRepository>(relaxed = true)
    private val roleManager = mockk<RoleManager>(relaxed = true)
    private lateinit var viewModel: CustomersViewModel

    private val dany = Customer(id = "c1", firstName = "Dany", phone = "+528129440042")
    private val pedro = Customer(id = "c2", firstName = "Pedro", phone = "+525511112222")

    /** Lo que contesta el servidor: la lista, cuántos cumplen en TODO el negocio y si topó. */
    private fun pagina(vararg c: Customer, total: Int? = c.size, topada: Boolean = false) =
        Result.success(PaginaDeClientes(c.toList(), total, topada))

    @Before
    fun setup() {
        coEvery { repository.buscarClientes(any()) } returns pagina()
        every { repository.clientesGuardados() } returns emptyList()
        viewModel = CustomersViewModel(repository, roleManager)
    }

    @Test
    fun `escribir rapido pregunta una sola vez, 300 ms despues de la ultima tecla`() = runTest {
        viewModel.onQueryChange("D")
        advanceTimeBy(100)
        viewModel.onQueryChange("Da")
        advanceTimeBy(100)
        viewModel.onQueryChange("Dany")
        advanceTimeBy(299)
        coVerify(exactly = 0) { repository.buscarClientes(any()) }

        advanceTimeBy(2)

        coVerify(exactly = 1) { repository.buscarClientes("Dany") }
        coVerify(exactly = 1) { repository.buscarClientes(any()) }
    }

    @Test
    fun `al abrir la lista pide los recientes sin esperar`() = runTest {
        viewModel.abrirLista()
        runCurrent()

        coVerify(exactly = 1) { repository.buscarClientes(null) }
    }

    @Test
    fun `P1 una respuesta que llega tarde no pisa la lista del texto nuevo`() = runTest {
        // La llamada HTTP no se puede interrumpir: regresa aunque su corrutina ya se haya cancelado.
        coEvery { repository.buscarClientes("Dan") } coAnswers {
            withContext(NonCancellable) { delay(1_000) }
            pagina(pedro)
        }
        coEvery { repository.buscarClientes("Dany") } returns pagina(dany)

        viewModel.onQueryChange("Dan")
        advanceTimeBy(301) // la búsqueda de «Dan» ya salió y sigue en vuelo
        viewModel.onQueryChange("Dany")
        advanceUntilIdle()

        assertEquals(listOf(dany), viewModel.customers.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `P1 sin red busca entre los guardados de la tablet y lo avisa`() = runTest {
        every { repository.clientesGuardados() } returns listOf(dany, pedro)
        coEvery { repository.buscarClientes(any()) } returns Result.failure(java.net.UnknownHostException("Unable to resolve host"))

        viewModel.onQueryChange("81 29")
        advanceUntilIdle()

        assertEquals(listOf(dany), viewModel.customers.value)
        assertTrue(viewModel.offline.value)
        assertNull("sin red no es un error que haya que explicar en un diálogo", viewModel.error.value)
    }

    @Test
    fun `al volver la red el aviso se quita y manda el servidor`() = runTest {
        every { repository.clientesGuardados() } returns listOf(pedro)
        coEvery { repository.buscarClientes("Dany") } returnsMany listOf(
            Result.failure(java.net.SocketTimeoutException("timeout")),
            pagina(dany),
        )

        viewModel.onQueryChange("Dany")
        advanceUntilIdle()
        assertTrue(viewModel.offline.value)
        assertEquals(emptyList<Customer>(), viewModel.customers.value)

        viewModel.fetchCustomers()
        advanceUntilIdle()
        assertFalse(viewModel.offline.value)
        assertEquals(listOf(dany), viewModel.customers.value)
    }

    @Test
    fun `una respuesta de error del servidor no se llama sin conexion`() = runTest {
        every { repository.clientesGuardados() } returns listOf(dany)
        coEvery { repository.buscarClientes(any()) } returns Result.failure(Exception("Error al cargar clientes (500)"))

        viewModel.onQueryChange("Dany")
        advanceUntilIdle()

        assertFalse(viewModel.offline.value)
        assertNotNull(viewModel.error.value)
        assertEquals("la caja no se queda sin lista", listOf(dany), viewModel.customers.value)
    }

    // MARK: - Auditoría A #10: decir cuántos hay de verdad

    @Test
    fun `P1 con texto y mas clientes en el servidor de los que se ven dice cuantos hay y pide afinar`() = runTest {
        coEvery { repository.buscarClientes("a") } returns pagina(dany, pedro, total = 120)

        viewModel.onQueryChange("a")
        advanceUntilIdle()

        assertEquals("2 de 120: escribe más para afinar", viewModel.avisoDeMasResultados.value)
    }

    @Test
    fun `busqueda topada por el servidor tambien pide afinar`() = runTest {
        coEvery { repository.buscarClientes("a") } returns pagina(dany, total = 6200, topada = true)

        viewModel.onQueryChange("a")
        advanceUntilIdle()

        assertEquals("1 de 6200: escribe más para afinar", viewModel.avisoDeMasResultados.value)
    }

    @Test
    fun `sin texto, con todos a la vista o con un servidor viejo sin total no dice nada`() = runTest {
        coEvery { repository.buscarClientes(null) } returns pagina(dany, pedro, total = 41)
        viewModel.abrirLista()
        advanceUntilIdle()
        assertNull("sin texto la lista son los recientes, no resultados", viewModel.avisoDeMasResultados.value)

        coEvery { repository.buscarClientes("dany") } returns pagina(dany, total = 1)
        viewModel.onQueryChange("dany")
        advanceUntilIdle()
        assertNull(viewModel.avisoDeMasResultados.value)

        coEvery { repository.buscarClientes("pe") } returns pagina(pedro, total = null)
        viewModel.onQueryChange("pe")
        advanceUntilIdle()
        assertNull("sin total del servidor no se inventa", viewModel.avisoDeMasResultados.value)
    }

    @Test
    fun `sin red no se dice un total que no se conoce`() = runTest {
        coEvery { repository.buscarClientes("a") } returns pagina(dany, pedro, total = 120)
        viewModel.onQueryChange("a")
        advanceUntilIdle()
        coEvery { repository.buscarClientes("an") } returns Result.failure(java.net.UnknownHostException("sin red"))

        viewModel.onQueryChange("an")
        advanceUntilIdle()

        assertTrue(viewModel.offline.value)
        assertNull(viewModel.avisoDeMasResultados.value)
    }
}
