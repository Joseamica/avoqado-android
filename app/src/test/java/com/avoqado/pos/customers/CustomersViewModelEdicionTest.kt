package com.avoqado.pos.customers

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.RechazoAlEditarCliente
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.PaginaDeClientes
import com.avoqado.pos.customers.data.model.UpdateCustomerRequest
import com.avoqado.pos.customers.domain.AVISO_FALTA_PIN_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.AVISO_SIN_PERMISO_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.AVISO_SIN_RED_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.ContactoEditado
import com.avoqado.pos.customers.domain.PERMISO_EDITAR_CLIENTES
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.UnknownHostException

/**
 * Editar cliente desde el POS (26-oct, founder): «Editar» siempre se ve; quien no es gerente+ teclea el PIN de un
 * gerente; sin red se dice y el formulario no se pierde.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomersViewModelEdicionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<CustomersRepository>(relaxed = true)
    private val roleManager = mockk<RoleManager>(relaxed = true)
    private val override = mockk<ManagerOverrideCoordinator>(relaxed = true)
    private val tpvSettings = mockk<TpvSettingsRepository>(relaxed = true)
    private val pinActivo = MutableStateFlow(false)
    private lateinit var viewModel: CustomersViewModel

    private val soloTelefono = Customer(id = "c1", phone = "+527351748538", totalVisits = 1, totalSpent = 65.0)

    @Before
    fun setup() {
        coEvery { repository.buscarClientes(any()) } returns Result.success(PaginaDeClientes(listOf(soloTelefono), 1))
        every { repository.clientesGuardados() } returns emptyList()
        every { tpvSettings.managerPinOverrideEnabled } returns pinActivo
        viewModel = CustomersViewModel(repository, roleManager, override, tpvSettings)
    }

    @Test
    fun `P1 gerente entra directo al formulario, sin PIN`() = runTest {
        every { roleManager.canEditCustomers } returns true
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()
        assertEquals(soloTelefono, viewModel.clienteEnEdicion.value)
        coVerify(exactly = 0) { override.preauthorize(any()) }
    }

    @Test
    fun `P1 cajero con PIN de gerente prendido teclea el PIN y luego ve el formulario`() = runTest {
        every { roleManager.canEditCustomers } returns false
        pinActivo.value = true
        coEvery { override.preauthorize(PERMISO_EDITAR_CLIENTES) } returns true
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()
        assertEquals(soloTelefono, viewModel.clienteEnEdicion.value)
    }

    @Test
    fun `cajero que cancela el PIN no abre nada`() = runTest {
        every { roleManager.canEditCustomers } returns false
        pinActivo.value = true
        coEvery { override.preauthorize(PERMISO_EDITAR_CLIENTES) } returns false
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()
        assertNull(viewModel.clienteEnEdicion.value)
        assertNull(viewModel.avisoSinPermiso.value)
    }

    @Test
    fun `P1 cajero con PIN apagado ve por que no y que hacer, sin teclado`() = runTest {
        every { roleManager.canEditCustomers } returns false
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()
        assertNull(viewModel.clienteEnEdicion.value)
        assertEquals(AVISO_SIN_PERMISO_EDITAR_CLIENTE, viewModel.avisoSinPermiso.value)
        coVerify(exactly = 0) { override.preauthorize(any()) }
    }

    @Test
    fun `P1 guardar manda solo lo cambiado, cierra el formulario y la lista ve el nombre nuevo`() = runTest {
        every { roleManager.canEditCustomers } returns true
        viewModel.abrirLista()
        advanceUntilIdle()
        val ana = soloTelefono.copy(firstName = "Ana")
        coEvery { repository.updateCustomer(soloTelefono, UpdateCustomerRequest(firstName = "Ana")) } returns Result.success(ana)
        // Después de guardar, la lista se vuelve a pedir: el servidor ya trae el nombre nuevo.
        coEvery { repository.buscarClientes(any()) } returns Result.success(PaginaDeClientes(listOf(ana), 1))
        viewModel.onEditarTocado(soloTelefono)
        var guardado: Customer? = null

        viewModel.guardarEdicion(ContactoEditado("Ana", "", "+527351748538", "")) { guardado = it }
        advanceUntilIdle()

        assertEquals(ana, guardado)
        assertNull(viewModel.clienteEnEdicion.value)
        assertEquals(listOf(ana), viewModel.customers.value)
    }

    @Test
    fun `P1 sin red se dice y el formulario sigue abierto`() = runTest {
        every { roleManager.canEditCustomers } returns true
        coEvery { repository.updateCustomer(any(), any()) } returns Result.failure(UnknownHostException("sin red"))
        viewModel.onEditarTocado(soloTelefono)

        viewModel.guardarEdicion(ContactoEditado("Ana", "", "+527351748538", "")) {}
        advanceUntilIdle()

        assertEquals(soloTelefono, viewModel.clienteEnEdicion.value)
        assertEquals(AVISO_SIN_RED_EDITAR_CLIENTE, viewModel.errorDeEdicion.value)
    }

    @Test
    fun `un rechazo del servidor se dice con su texto y el formulario sigue abierto`() = runTest {
        every { roleManager.canEditCustomers } returns true
        coEvery { repository.updateCustomer(any(), any()) } returns Result.failure(Exception("Ese teléfono ya es de otro cliente."))
        viewModel.onEditarTocado(soloTelefono)

        viewModel.guardarEdicion(ContactoEditado("", "", "+525511112222", "")) {}
        advanceUntilIdle()

        assertEquals(soloTelefono, viewModel.clienteEnEdicion.value)
        assertEquals("Ese teléfono ya es de otro cliente.", viewModel.errorDeEdicion.value)
    }

    @Test
    fun `sin cambios o con un dato vaciado no se llama al servidor`() = runTest {
        every { roleManager.canEditCustomers } returns true
        viewModel.onEditarTocado(soloTelefono)
        viewModel.guardarEdicion(ContactoEditado("", "", "+527351748538", "")) {}
        viewModel.guardarEdicion(ContactoEditado("Ana", "", "", "")) {}
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.updateCustomer(any(), any()) }
    }

    // MARK: - Ronda 2 de Codex

    @Test
    fun `P1 cancelar el formulario tira el PIN de gerente (no sirve para otro cliente)`() = runTest {
        every { roleManager.canEditCustomers } returns false
        pinActivo.value = true
        coEvery { override.preauthorize(PERMISO_EDITAR_CLIENTES) } returns true
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()

        viewModel.cerrarEdicion()

        io.mockk.verify { override.descartarPreautorizado(PERMISO_EDITAR_CLIENTES) }
    }

    @Test
    fun `P1 una respuesta tardia no cierra ni ensucia el formulario de otro cliente`() = runTest {
        every { roleManager.canEditCustomers } returns true
        val respuestaDeA = CompletableDeferred<Result<Customer>>()
        coEvery { repository.updateCustomer(soloTelefono, any()) } coAnswers { respuestaDeA.await() }
        val otro = Customer(id = "c2", firstName = "Beto", phone = "+525511112222")
        var guardadoA: Customer? = null

        viewModel.onEditarTocado(soloTelefono)
        viewModel.guardarEdicion(ContactoEditado("Ana", "", "+527351748538", "")) { guardadoA = it }
        advanceUntilIdle()
        viewModel.cerrarEdicion()
        viewModel.onEditarTocado(otro)
        respuestaDeA.complete(Result.success(soloTelefono.copy(firstName = "Ana")))
        advanceUntilIdle()

        assertEquals(otro, viewModel.clienteEnEdicion.value)
        assertNull(guardadoA)
        assertNull(viewModel.errorDeEdicion.value)
    }

    @Test
    fun `dos toques en Editar piden un solo PIN`() = runTest {
        every { roleManager.canEditCustomers } returns false
        pinActivo.value = true
        val pin = CompletableDeferred<Boolean>()
        coEvery { override.preauthorize(PERMISO_EDITAR_CLIENTES) } coAnswers { pin.await() }

        viewModel.onEditarTocado(soloTelefono)
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()
        pin.complete(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { override.preauthorize(PERMISO_EDITAR_CLIENTES) }
    }

    @Test
    fun `un corte de conexion cualquiera se dice como sin conexion, no como texto tecnico`() = runTest {
        every { roleManager.canEditCustomers } returns true
        coEvery { repository.updateCustomer(any(), any()) } returns Result.failure(java.io.EOFException("\\n not found: limit=0"))
        viewModel.onEditarTocado(soloTelefono)
        viewModel.guardarEdicion(ContactoEditado("Ana", "", "+527351748538", "")) {}
        advanceUntilIdle()
        assertEquals(AVISO_SIN_RED_EDITAR_CLIENTE, viewModel.errorDeEdicion.value)
    }

    @Test
    fun `P1 si el PIN vencio o se cancelo el teclado NO se reintenta solo y se dice que hay que tocar Guardar`() = runTest {
        // Codex r3 #1 y #2: el reintento automático usaba la autorización de otra edición y reabría el teclado cancelado.
        every { roleManager.canEditCustomers } returns false
        pinActivo.value = true
        coEvery { override.preauthorize(PERMISO_EDITAR_CLIENTES) } returns true
        coEvery { repository.updateCustomer(any(), any()) } returns
            Result.failure(RechazoAlEditarCliente(403, "No tienes permiso para editar clientes."))
        viewModel.onEditarTocado(soloTelefono)
        advanceUntilIdle()

        viewModel.guardarEdicion(ContactoEditado("Ana", "", "+527351748538", "")) {}
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateCustomer(any(), any()) }
        assertEquals(AVISO_FALTA_PIN_EDITAR_CLIENTE, viewModel.errorDeEdicion.value)
        assertEquals(soloTelefono, viewModel.clienteEnEdicion.value)
    }

    @Test
    fun `al guardar se vuelve a pedir la lista para que una busqueda vieja no regrese el dato anterior`() = runTest {
        every { roleManager.canEditCustomers } returns true
        coEvery { repository.updateCustomer(any(), any()) } returns Result.success(soloTelefono.copy(firstName = "Ana"))
        viewModel.onEditarTocado(soloTelefono)
        viewModel.guardarEdicion(ContactoEditado("Ana", "", "+527351748538", "")) {}
        advanceUntilIdle()
        coVerify(atLeast = 1) { repository.buscarClientes(any()) }
    }
}
