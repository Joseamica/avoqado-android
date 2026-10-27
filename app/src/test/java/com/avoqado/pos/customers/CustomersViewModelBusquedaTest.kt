package com.avoqado.pos.customers

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.PaginaDeClientes
import com.avoqado.pos.customers.domain.AVISO_SERVIDOR_FALLO_CLIENTES
import com.avoqado.pos.customers.domain.AVISO_SIN_RED_CLIENTES
import com.avoqado.pos.customers.presentation.ContenidoDeLaLista
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.customers.presentation.estadoDeLaListaDeClientes
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
    private val zulema = Customer(id = "c3", firstName = "Zulema", phone = "+528133334444")

    /** Lo que contesta el servidor: la lista, cuántos cumplen en TODO el negocio y si topó. */
    private fun pagina(vararg c: Customer, total: Int? = c.size, topada: Boolean = false) =
        Result.success(PaginaDeClientes(c.toList(), total, topada))

    @Before
    fun setup() {
        coEvery { repository.buscarClientes(any()) } returns pagina()
        every { repository.clientesGuardados() } returns emptyList()
        viewModel = CustomersViewModel(repository, roleManager, mockk(relaxed = true), mockk(relaxed = true))
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
        assertEquals(AVISO_SIN_RED_CLIENTES, viewModel.avisoDeGuardados.value)
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
        assertNull(viewModel.avisoDeGuardados.value)
        assertEquals(listOf(dany), viewModel.customers.value)
    }

    // Revisión A2 (Important): un fallo del SERVIDOR (5xx, 401, «No venue selected») caía en silencio a lo guardado y su
    // error iba a `error`, que `CreateCustomerView` (mismo ViewModel) pintaba como «No se pudo crear el cliente».
    @Test
    fun `P1 si el servidor falla la lista sale de lo guardado y lo dice, sin dialogo de error`() = runTest {
        every { repository.clientesGuardados() } returns listOf(dany)
        coEvery { repository.buscarClientes("Dany") } returnsMany listOf(
            Result.failure(Exception("Error al cargar clientes (500)")),
            pagina(dany),
        )

        viewModel.onQueryChange("Dany")
        advanceUntilIdle()

        assertFalse("una respuesta del servidor no es «sin conexión»", viewModel.offline.value)
        assertEquals(AVISO_SERVIDOR_FALLO_CLIENTES, viewModel.avisoDeGuardados.value)
        assertNull("el error de una búsqueda no abre el diálogo de crear cliente", viewModel.error.value)
        assertEquals("la caja no se queda sin lista", listOf(dany), viewModel.customers.value)

        viewModel.fetchCustomers()
        advanceUntilIdle()
        assertNull("cuando el servidor vuelve a contestar, el aviso se quita", viewModel.avisoDeGuardados.value)
    }

    @Test
    fun `el aviso de fallo del servidor dice exactamente lo que pasa`() {
        assertEquals(
            "No se pudo buscar en el servidor: mostrando sólo los clientes guardados en la tablet",
            AVISO_SERVIDOR_FALLO_CLIENTES,
        )
    }

    @Test
    fun `un error viejo se limpia al abrir la lista y con una busqueda que si contesta`() = runTest {
        coEvery { repository.createCustomer(any()) } returns Result.failure(Exception("Este número de teléfono ya está registrado."))
        coEvery { repository.buscarClientes("Dany") } returns pagina(dany)

        viewModel.createCustomer("Ana", null, "+528112345678", null) {}
        advanceUntilIdle()
        assertNotNull(viewModel.error.value)
        viewModel.abrirLista()
        assertNull("al volver a abrir no se arrastra un error de antes", viewModel.error.value)

        viewModel.createCustomer("Ana", null, "+528112345678", null) {}
        advanceUntilIdle()
        assertNotNull(viewModel.error.value)
        viewModel.onQueryChange("Dany")
        advanceUntilIdle()
        assertNull(viewModel.error.value)
    }

    // Revisión A2 (Minor): el ViewModel vive con la pantalla de navegación; al reabrir se veían los resultados de la
    // búsqueda anterior —quizá de OTRA sucursal— bajo «Creados recientemente» hasta que llegaban los recientes.
    @Test
    fun `P2 al reabrir la lista no se ve la busqueda anterior, se ve el cargador hasta que llegan los recientes`() = runTest {
        coEvery { repository.buscarClientes("a") } returns pagina(dany, pedro, total = 120)
        viewModel.onQueryChange("a")
        advanceUntilIdle()
        assertEquals(listOf(dany, pedro), viewModel.customers.value)
        coEvery { repository.buscarClientes(null) } coAnswers {
            delay(1_000)
            pagina(pedro)
        }

        viewModel.abrirLista()

        assertEquals(emptyList<Customer>(), viewModel.customers.value)
        assertNull(viewModel.avisoDeMasResultados.value)
        assertTrue("lista vacía y cargando = el cargador, nunca «No se encontraron»", viewModel.isLoading.value)
        advanceUntilIdle()
        assertEquals(listOf(pedro), viewModel.customers.value)
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

    // MARK: - Revisión final #6: al reabrir no se arrastra el aviso de la vez anterior

    @Test
    fun `al reabrir la lista no se arrastra el aviso de guardados ni el sin red de la vez anterior`() = runTest {
        coEvery { repository.buscarClientes("Dany") } returns Result.failure(java.net.UnknownHostException("sin red"))
        viewModel.onQueryChange("Dany")
        advanceUntilIdle()
        assertTrue(viewModel.offline.value)
        assertEquals(AVISO_SIN_RED_CLIENTES, viewModel.avisoDeGuardados.value)
        coEvery { repository.buscarClientes(null) } coAnswers {
            delay(1_000)
            pagina(pedro)
        }

        viewModel.abrirLista()

        assertFalse("la red pudo volver: no se dice «sin conexión» antes de preguntar", viewModel.offline.value)
        assertNull(viewModel.avisoDeGuardados.value)
        advanceUntilIdle()
        assertEquals(listOf(pedro), viewModel.customers.value)
        assertNull(viewModel.avisoDeGuardados.value)
    }

    // MARK: - Revisión final #1: mientras el servidor contesta (WiFi malo: hasta 30 s)

    @Test
    fun `P1 mientras el servidor contesta se ve al momento lo guardado que cumple el texto nuevo, no la lista del texto anterior`() = runTest {
        every { repository.clientesGuardados() } returns listOf(dany, pedro)
        coEvery { repository.buscarClientes("a") } returns pagina(dany, pedro, total = 120)
        viewModel.onQueryChange("a")
        advanceUntilIdle()
        assertEquals("2 de 120: escribe más para afinar", viewModel.avisoDeMasResultados.value)
        coEvery { repository.buscarClientes("ped") } coAnswers {
            delay(5_000)
            pagina(pedro)
        }

        viewModel.onQueryChange("ped")

        assertEquals("al instante, sin esperar los 300 ms ni al servidor", listOf(pedro), viewModel.customers.value)
        assertTrue(viewModel.isLoading.value)
        assertNull("el «2 de 120» era del texto anterior", viewModel.avisoDeMasResultados.value)
        advanceUntilIdle()
        assertEquals(listOf(pedro), viewModel.customers.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `P1 si lo guardado no tiene a nadie con el texto nuevo, mientras carga se ve el cargador y nunca Crear cliente`() = runTest {
        every { repository.clientesGuardados() } returns listOf(dany, pedro)
        coEvery { repository.buscarClientes("a") } returns pagina(dany, pedro)
        viewModel.onQueryChange("a")
        advanceUntilIdle()
        coEvery { repository.buscarClientes("zulema") } coAnswers {
            delay(5_000)
            pagina(zulema)
        }

        viewModel.onQueryChange("zulema")

        assertEquals(emptyList<Customer>(), viewModel.customers.value)
        assertTrue(viewModel.isLoading.value)
        val estado = estadoDeLaListaDeClientes(viewModel.isLoading.value, viewModel.customers.value.isNotEmpty(), "zulema")
        assertEquals("lista vacía y cargando = el cargador, nunca «No se encontraron» + «Crear cliente»", ContenidoDeLaLista.CARGANDO, estado.contenido)
        advanceUntilIdle()
        assertEquals(listOf(zulema), viewModel.customers.value)
    }

    @Test
    fun `sin texto no hay lista provisional, al reabrir se ve el cargador aunque haya clientes guardados`() = runTest {
        // «Todo lo guardado» no son los recientes: bajo «Creados recientemente» mentiría el orden (revisión A2, P2).
        every { repository.clientesGuardados() } returns listOf(dany, pedro)
        coEvery { repository.buscarClientes(null) } coAnswers {
            delay(1_000)
            pagina(pedro)
        }

        viewModel.abrirLista()

        assertEquals(emptyList<Customer>(), viewModel.customers.value)
        assertTrue(viewModel.isLoading.value)
        advanceUntilIdle()
        assertEquals(listOf(pedro), viewModel.customers.value)
    }

    @Test
    fun `la barra delgada de carga se ve siempre que el servidor va en camino, tambien encima de una lista`() {
        assertTrue(estadoDeLaListaDeClientes(cargando = true, hayClientes = true, texto = "ped").barraDeCarga)
        assertTrue(estadoDeLaListaDeClientes(cargando = true, hayClientes = false, texto = "").barraDeCarga)
        assertFalse(estadoDeLaListaDeClientes(cargando = false, hayClientes = true, texto = "ped").barraDeCarga)
    }

    @Test
    fun `que se pinta bajo el buscador en los tres layouts`() {
        assertEquals(ContenidoDeLaLista.CARGANDO, estadoDeLaListaDeClientes(true, false, "zulema").contenido)
        assertEquals(ContenidoDeLaLista.CARGANDO, estadoDeLaListaDeClientes(true, false, "").contenido)
        assertEquals("con lista y cargando: la lista y la barra", ContenidoDeLaLista.LISTA, estadoDeLaListaDeClientes(true, true, "ped").contenido)
        assertEquals(ContenidoDeLaLista.SIN_RESULTADOS, estadoDeLaListaDeClientes(false, false, "zulema").contenido)
        assertEquals(ContenidoDeLaLista.LISTA, estadoDeLaListaDeClientes(false, false, "").contenido)
        assertEquals(ContenidoDeLaLista.LISTA, estadoDeLaListaDeClientes(false, true, "ped").contenido)
    }
}
