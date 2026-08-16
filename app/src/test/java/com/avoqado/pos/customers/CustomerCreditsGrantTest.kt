package com.avoqado.pos.customers

import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.PendingGrantQueue
import com.avoqado.pos.customers.presentation.CustomerCreditsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * La entrega de una membresía ocurre DESPUÉS de que el cliente ya pagó.
 * Si falla, tiene que pasar lo doble: encolarse (para no perder la entrega)
 * y avisarse (para que el mostrador se entere de que hay dinero cobrado sin
 * su contraparte). Silenciar el modal global no puede significar silenciar
 * también al cajero — ése fue el defecto que estas pruebas fijan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerCreditsGrantTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(
        repo: ArticlesRepository,
        queue: PendingGrantQueue,
    ): CustomerCreditsViewModel {
        val roles: RoleManager = mockk()
        every { roles.canManageCustomers } returns true
        every { roles.canReadCreditPacks } returns true
        every { repo.creditPacks } returns MutableStateFlow(emptyList())
        return CustomerCreditsViewModel(repo, queue, roles)
    }

    @Test
    fun `entrega fallida tras el cobro se encola Y se avisa`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        // El 403/500 llega marcado como background: no hay modal global. Ese
        // silencio es el correcto; el que NO puede quedarse callado es el aviso.
        coEvery { repo.sellPackToCustomer("pack-1", "cli-1", background = true) } returns false

        val vm = buildViewModel(repo, queue)
        vm.grantPacks(listOf("pack-1"), "cli-1")
        advanceUntilIdle()

        verify(exactly = 1) { queue.enqueue("pack-1", "cli-1") }
        assertEquals(1, vm.undeliveredGrants.value)
    }

    @Test
    fun `la peticion sigue marcada background para no sacar el modal de permisos`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { repo.sellPackToCustomer(any(), any(), background = true) } returns false

        val vm = buildViewModel(repo, queue)
        vm.grantPacks(listOf("pack-1"), "cli-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.sellPackToCustomer("pack-1", "cli-1", background = true) }
    }

    @Test
    fun `entrega exitosa no encola ni avisa`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { repo.sellPackToCustomer("pack-1", "cli-1", background = true) } returns true

        val vm = buildViewModel(repo, queue)
        vm.grantPacks(listOf("pack-1"), "cli-1")
        advanceUntilIdle()

        verify(exactly = 0) { queue.enqueue(any(), any()) }
        assertEquals(0, vm.undeliveredGrants.value)
    }

    @Test
    fun `avisa solo por las que fallaron cuando el carrito trae varias`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { repo.sellPackToCustomer("ok-1", "cli-1", background = true) } returns true
        coEvery { repo.sellPackToCustomer("mal-1", "cli-1", background = true) } returns false
        coEvery { repo.sellPackToCustomer("mal-2", "cli-1", background = true) } returns false

        val vm = buildViewModel(repo, queue)
        vm.grantPacks(listOf("ok-1", "mal-1", "mal-2"), "cli-1")
        advanceUntilIdle()

        verify(exactly = 1) { queue.enqueue("mal-1", "cli-1") }
        verify(exactly = 1) { queue.enqueue("mal-2", "cli-1") }
        verify(exactly = 0) { queue.enqueue("ok-1", "cli-1") }
        assertEquals(2, vm.undeliveredGrants.value)
    }

    @Test
    fun `el aviso se limpia al acusarlo de recibo`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { repo.sellPackToCustomer(any(), any(), background = true) } returns false

        val vm = buildViewModel(repo, queue)
        vm.grantPacks(listOf("pack-1"), "cli-1")
        advanceUntilIdle()
        assertEquals(1, vm.undeliveredGrants.value)

        vm.clearUndeliveredGrants()

        // Se limpia el AVISO, no la cola: la entrega sigue pendiente y se
        // reintenta sola. Descartar el modal nunca puede borrar el dinero.
        assertEquals(0, vm.undeliveredGrants.value)
        verify(exactly = 1) { queue.enqueue("pack-1", "cli-1") }
    }
}
