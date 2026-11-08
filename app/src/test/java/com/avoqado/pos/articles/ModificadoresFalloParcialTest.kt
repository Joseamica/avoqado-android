package com.avoqado.pos.articles

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.articles.presentation.ArticlesViewModel.InlineModifier
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * D4: el grupo se guarda en varios pasos. Si uno falla se detiene ahí y la hoja se queda abierta; y como la hoja conserva
 * su lista vieja, el reintento tiene que saltarse lo que el intento anterior ya dejó hecho.
 *
 * El repositorio es un servidor de mentira que se porta como el de verdad: cada paso exitoso refresca la lista, y borrar
 * dos veces lo mismo da 404. Lo que se comprueba al final es lo que quedó en el servidor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModificadoresFalloParcialTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ArticlesRepository>(relaxed = true)
    private val repositoryError = MutableStateFlow<String?>(null)
    private val listaViva = MutableStateFlow<List<ModifierGroup>>(emptyList())

    private val motivo = "No se pudo por una regla del servidor"
    private val respaldo = "No se pudo guardar el grupo de modificadores. Intenta de nuevo."

    private val enServidor = mutableListOf<ArticleModifier>()
    private val peticiones = mutableListOf<String>()
    private var siguienteId = 1
    private var falla: (String) -> Boolean = { false }
    private var seCortaLaRespuesta: (String) -> Boolean = { false }
    private var motivoDelRechazo: String? = motivo
    private var loQueDevuelveLaLista: () -> List<ModifierGroup> = {
        listOf(ModifierGroup(id = "g", modifiers = enServidor.toList()))
    }

    /** Una petición: se rechaza, llega pero se pierde la respuesta, o se aplica y refresca la lista. */
    private fun responde(peticion: String, aplica: () -> Boolean): Boolean {
        peticiones += peticion
        if (falla(peticion)) {
            repositoryError.value = motivoDelRechazo
            return false
        }
        if (seCortaLaRespuesta(peticion)) {
            aplica()
            repositoryError.value = "Sin conexión. Revisa la red e inténtalo de nuevo."
            return false
        }
        if (!aplica()) {
            repositoryError.value = "No existe: 404"
            return false
        }
        listaViva.value = loQueDevuelveLaLista()
        return true
    }

    private fun nombreDe(payload: String) = Json.parseToJsonElement(payload).jsonObject["name"]!!.jsonPrimitive.content
    private fun precioDe(payload: String) = Json.parseToJsonElement(payload).jsonObject["price"]!!.jsonPrimitive.double

    private fun viewModel(): ArticlesViewModel {
        every { repository.products } returns MutableStateFlow(emptyList())
        every { repository.categories } returns MutableStateFlow(emptyList())
        every { repository.modifierGroups } returns listaViva
        every { repository.discounts } returns MutableStateFlow(emptyList())
        every { repository.coupons } returns MutableStateFlow(emptyList())
        every { repository.creditPacks } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.errorMessage } returns repositoryError
        every { repository.setError(any()) } answers { repositoryError.value = firstArg() }
        every { repository.clearError() } answers { repositoryError.value = null }

        coEvery { repository.updateModifierGroup("g", any()) } answers { responde("PATCH grupo") { true } }
        coEvery { repository.deleteModifier("g", any()) } answers {
            val id = secondArg<String>()
            responde("DELETE $id") { enServidor.removeAll { it.id == id } }
        }
        coEvery { repository.updateModifier("g", any(), any()) } answers {
            val id = secondArg<String>()
            val payload = thirdArg<String>()
            responde("PATCH $id") {
                val i = enServidor.indexOfFirst { it.id == id }
                if (i >= 0) enServidor[i] = enServidor[i].copy(name = nombreDe(payload), price = precioDe(payload))
                i >= 0
            }
        }
        coEvery { repository.addModifierToGroup("g", any()) } answers {
            val payload = secondArg<String>()
            responde("POST ${nombreDe(payload)}") {
                enServidor += ArticleModifier(id = "creado-${siguienteId++}", name = nombreDe(payload), price = precioDe(payload))
                true
            }
        }

        val storage = mockk<SecureStorage>()
        every { storage.planSnapshot } returns null
        every { storage.planTier } returns null
        every { storage.planExempt } returns false
        val refreshGateFactory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns
            com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return ArticlesViewModel(repository, PlanManager(storage), refreshGateFactory)
    }

    /** La persona abre la hoja con lo que hoy hay en el servidor. */
    private fun abreLaHojaCon(vararg modificadores: ArticleModifier): List<ArticleModifier> {
        enServidor += modificadores
        listaViva.value = loQueDevuelveLaLista()
        return modificadores.toList()
    }

    private fun TestScope.guardar(
        vm: ArticlesViewModel,
        originales: List<ArticleModifier>,
        hoja: List<InlineModifier>,
    ): Boolean? {
        var resultado: Boolean? = null
        vm.updateModifierGroup(
            groupId = "g",
            name = "G",
            required = false,
            allowMultiple = false,
            originalModifiers = originales,
            currentModifiers = hoja,
            onResult = { resultado = it },
        )
        advanceUntilIdle()
        return resultado
    }

    private fun reintentoSinFallas() {
        falla = { false }
        seCortaLaRespuesta = { false }
        peticiones.clear()
    }

    private val viejo = ArticleModifier(id = "m-viejo", name = "Viejo", price = 1.0)
    private val uno = ArticleModifier(id = "m-uno", name = "Uno", price = 1.0)

    @Test
    fun `todo bien avisa true y hace cada paso en orden`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)

        val resultado = guardar(
            viewModel(),
            originales,
            listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0), InlineModifier(name = "Nuevo", price = 3.0)),
        )

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo", "PATCH m-uno", "POST Nuevo"), peticiones)
        assertEquals(listOf("Uno" to 2.0, "Nuevo" to 3.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `falla un DELETE, no sigue y avisa false con el motivo`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        falla = { it == "DELETE m-viejo" }

        val resultado = guardar(
            viewModel(),
            originales,
            listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0), InlineModifier(name = "Nuevo", price = 3.0)),
        )

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo"), peticiones)
        verify(exactly = 0) { repository.setError(any()) }
    }

    @Test
    fun `falla el PATCH de un existente, no crea los nuevos y avisa false`() = runTest {
        val originales = abreLaHojaCon(uno)
        falla = { it == "PATCH m-uno" }

        val resultado = guardar(
            viewModel(),
            originales,
            listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0), InlineModifier(name = "Nuevo", price = 3.0)),
        )

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
        assertEquals(listOf("PATCH grupo", "PATCH m-uno"), peticiones)
    }

    @Test
    fun `falla el segundo POST, no sigue y avisa false con el motivo`() = runTest {
        val originales = abreLaHojaCon()
        falla = { it == "POST Dos" }

        val resultado = guardar(
            viewModel(),
            originales,
            listOf(
                InlineModifier(name = "Uno", price = 1.0),
                InlineModifier(name = "Dos", price = 2.0),
                InlineModifier(name = "Tres", price = 3.0),
            ),
        )

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
        assertEquals(listOf("PATCH grupo", "POST Uno", "POST Dos"), peticiones)
    }

    @Test
    fun `un paso rechazado sin motivo cae al texto propio`() = runTest {
        val originales = abreLaHojaCon(viejo)
        motivoDelRechazo = null
        falla = { it == "DELETE m-viejo" }

        val resultado = guardar(viewModel(), originales, emptyList())

        assertEquals(false, resultado)
        assertEquals(respaldo, repositoryError.value)
    }

    @Test
    fun `reintento donde el primer nuevo ya existe solo crea el segundo`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = listOf(InlineModifier(name = "Uno", price = 1.0), InlineModifier(name = "Dos", price = 2.0))
        val vm = viewModel()
        falla = { it == "POST Dos" }
        assertEquals(false, guardar(vm, originales, hoja))

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(1, peticiones.count { it.startsWith("POST") })
        assertEquals("POST Dos", peticiones.last())
        assertEquals(listOf("Uno", "Dos"), enServidor.map { it.name })
    }

    @Test
    fun `reintento donde el borrado ya se hizo no vuelve a borrar`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        val hoja = listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0))
        val vm = viewModel()
        falla = { it == "PATCH m-uno" }
        assertEquals(false, guardar(vm, originales, hoja))

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH m-uno"), peticiones)
        assertEquals(listOf("Uno" to 2.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `un modificador de otro aparato en la lista viva no se borra ni se toca`() = runTest {
        val originales = abreLaHojaCon(uno)
        enServidor += ArticleModifier(id = "m-ajeno", name = "Ajeno", price = 9.0)
        listaViva.value = loQueDevuelveLaLista()

        val resultado = guardar(viewModel(), originales, listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0)))

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH m-uno"), peticiones)
        assertEquals(listOf("m-uno", "m-ajeno"), enServidor.map { it.id })
    }

    @Test
    fun `si el POST llego pero se perdio la respuesta el reintento no lo duplica`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = listOf(InlineModifier(name = "Uno", price = 1.0))
        val vm = viewModel()
        seCortaLaRespuesta = { it == "POST Uno" }
        assertEquals(false, guardar(vm, originales, hoja))
        assertEquals(1, enServidor.size)

        // La lista todavía no sabe del POST: se entera con el PATCH del grupo con el que empieza el reintento.
        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("Uno"), enServidor.map { it.name })
    }

    @Test
    fun `dos nuevos con el mismo nombre, el reintento crea solo el que falta`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = listOf(InlineModifier(name = "Queso", price = 1.0), InlineModifier(name = "Queso", price = 1.0))
        val vm = viewModel()
        var quesos = 0
        falla = { it == "POST Queso" && ++quesos == 2 }
        assertEquals(false, guardar(vm, originales, hoja))
        assertEquals(1, enServidor.size)

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("Queso", "Queso"), enServidor.map { it.name })
    }

    @Test
    fun `un nuevo que se llama igual que uno que ya estaba se crea y no pisa al original`() = runTest {
        val originales = abreLaHojaCon(uno)

        val resultado = guardar(
            viewModel(),
            originales,
            listOf(InlineModifier(id = "m-uno", name = "Uno", price = 1.0), InlineModifier(name = "Uno", price = 7.0)),
        )

        assertEquals(true, resultado)
        assertEquals(listOf("Uno" to 1.0, "Uno" to 7.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `el precio cambiado entre intentos llega al que ya se habia creado`() = runTest {
        val originales = abreLaHojaCon()
        val vm = viewModel()
        falla = { it == "POST Dos" }
        assertEquals(
            false,
            guardar(vm, originales, listOf(InlineModifier(name = "Uno", price = 1.0), InlineModifier(name = "Dos", price = 2.0))),
        )

        reintentoSinFallas()
        val resultado = guardar(
            vm,
            originales,
            listOf(InlineModifier(name = "Uno", price = 5.0), InlineModifier(name = "Dos", price = 2.0)),
        )

        assertEquals(true, resultado)
        assertEquals(listOf("Uno" to 5.0, "Dos" to 2.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `sin el grupo en la lista viva usa los originales tal cual`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        loQueDevuelveLaLista = { emptyList() }
        listaViva.value = emptyList()

        val resultado = guardar(viewModel(), originales, listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0)))

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo", "PATCH m-uno"), peticiones)
    }

    @Test
    fun `un grupo que llega sin su lista de modificadores tampoco cancela el borrado`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        loQueDevuelveLaLista = { listOf(ModifierGroup(id = "g", modifiers = null)) }
        listaViva.value = loQueDevuelveLaLista()

        val resultado = guardar(viewModel(), originales, listOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0)))

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo", "PATCH m-uno"), peticiones)
    }
}
