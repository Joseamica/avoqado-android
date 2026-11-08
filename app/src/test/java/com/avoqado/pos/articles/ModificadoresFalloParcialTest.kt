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
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D4: el grupo se guarda en varios pasos. Si uno falla se detiene ahí y la hoja se queda abierta. Cada alta le avisa a la
 * hoja el id que le dio el servidor, así que en el reintento esa fila va por PATCH. Nada se reconoce por nombre: la hoja
 * sólo toca lo que abrió o lo que ella misma creó.
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
    private val avisosALaHoja = mutableListOf<Pair<Int, String>>()
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
            var idNuevo: String? = null
            val aceptado = responde("POST ${nombreDe(payload)}") {
                idNuevo = "creado-${siguienteId++}"
                enServidor += ArticleModifier(id = idNuevo!!, name = nombreDe(payload), price = precioDe(payload))
                true
            }
            idNuevo.takeIf { aceptado }
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

    private fun otroAparatoCrea(id: String, nombre: String, precio: Double) {
        enServidor += ArticleModifier(id = id, name = nombre, price = precio)
        listaViva.value = loQueDevuelveLaLista()
    }

    /** Guarda como la hoja de verdad: cada alta le pone su id a la fila, en la misma lista que se manda. */
    private fun TestScope.guardar(
        vm: ArticlesViewModel,
        originales: List<ArticleModifier>,
        hoja: MutableList<InlineModifier>,
    ): Boolean? {
        var resultado: Boolean? = null
        vm.updateModifierGroup(
            groupId = "g",
            name = "G",
            required = false,
            allowMultiple = false,
            originalModifiers = originales,
            currentModifiers = hoja.toList(),
            onModificadorCreado = { indice, id ->
                avisosALaHoja += indice to id
                hoja[indice] = hoja[indice].copy(id = id)
            },
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
        val hoja = mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0), InlineModifier(name = "Nuevo", price = 3.0))

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo", "PATCH m-uno", "POST Nuevo"), peticiones)
        assertEquals(listOf("Uno" to 2.0, "Nuevo" to 3.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `falla un DELETE, no sigue y avisa false con el motivo`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        val hoja = mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0), InlineModifier(name = "Nuevo", price = 3.0))
        falla = { it == "DELETE m-viejo" }

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo"), peticiones)
        verify(exactly = 0) { repository.setError(any()) }
    }

    @Test
    fun `falla el PATCH de un existente, no crea los nuevos y avisa false`() = runTest {
        val originales = abreLaHojaCon(uno)
        val hoja = mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0), InlineModifier(name = "Nuevo", price = 3.0))
        falla = { it == "PATCH m-uno" }

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
        assertEquals(listOf("PATCH grupo", "PATCH m-uno"), peticiones)
    }

    @Test
    fun `falla el segundo POST, no sigue, avisa false y la hoja ya sabe el id del primero`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = mutableListOf(
            InlineModifier(name = "Uno", price = 1.0),
            InlineModifier(name = "Dos", price = 2.0),
            InlineModifier(name = "Tres", price = 3.0),
        )
        falla = { it == "POST Dos" }

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(false, resultado)
        assertEquals(motivo, repositoryError.value)
        assertEquals(listOf("PATCH grupo", "POST Uno", "POST Dos"), peticiones)
        assertEquals(listOf(0 to "creado-1"), avisosALaHoja)
    }

    @Test
    fun `un paso rechazado sin motivo cae al texto propio`() = runTest {
        val originales = abreLaHojaCon(viejo)
        motivoDelRechazo = null
        falla = { it == "DELETE m-viejo" }

        val resultado = guardar(viewModel(), originales, mutableListOf())

        assertEquals(false, resultado)
        assertEquals(respaldo, repositoryError.value)
    }

    @Test
    fun `primer intento, un modificador de otro aparato con el mismo nombre no se toca y el nuestro se crea`() = runTest {
        val originales = abreLaHojaCon()
        otroAparatoCrea("m-ajeno", "Queso", 9.0)
        val hoja = mutableListOf(InlineModifier(name = "Queso", price = 2.0))

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "POST Queso"), peticiones)
        assertEquals(listOf("m-ajeno" to 9.0, "creado-1" to 2.0), enServidor.map { it.id to it.price })
    }

    @Test
    fun `reintento, un modificador de otro aparato con el mismo nombre no se toca y el nuestro se crea`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = mutableListOf(InlineModifier(name = "Uno", price = 1.0), InlineModifier(name = "Queso", price = 2.0))
        val vm = viewModel()
        falla = { it == "POST Queso" }
        assertEquals(false, guardar(vm, originales, hoja))
        otroAparatoCrea("m-ajeno-uno", "Uno", 8.0)
        otroAparatoCrea("m-ajeno-queso", "Queso", 9.0)

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH creado-1", "POST Queso"), peticiones)
        assertEquals(
            setOf("creado-1" to 1.0, "m-ajeno-uno" to 8.0, "m-ajeno-queso" to 9.0, "creado-2" to 2.0),
            enServidor.map { it.id to it.price }.toSet(),
        )
    }

    @Test
    fun `reintento usa PATCH con el id recibido y no crea otro`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = mutableListOf(InlineModifier(name = "Uno", price = 1.0), InlineModifier(name = "Dos", price = 2.0))
        val vm = viewModel()
        falla = { it == "POST Dos" }
        assertEquals(false, guardar(vm, originales, hoja))

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH creado-1", "POST Dos"), peticiones)
        assertEquals(listOf("Uno", "Dos"), enServidor.map { it.name })
    }

    @Test
    fun `renombrar y cambiar el precio de un nuevo ya creado entre intentos no crea otro`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = mutableListOf(InlineModifier(name = "Uno", price = 1.0), InlineModifier(name = "Dos", price = 2.0))
        val vm = viewModel()
        falla = { it == "POST Dos" }
        assertEquals(false, guardar(vm, originales, hoja))
        hoja[0] = hoja[0].copy(name = "Uno renombrado", price = 5.0)

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("Uno renombrado" to 5.0, "Dos" to 2.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `dos nuevos con el mismo nombre se crean los dos`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = mutableListOf(InlineModifier(name = "Queso", price = 1.0), InlineModifier(name = "Queso", price = 1.0))

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "POST Queso", "POST Queso"), peticiones)
        assertEquals(listOf(0 to "creado-1", 1 to "creado-2"), avisosALaHoja)
    }

    @Test
    fun `dos nuevos con el mismo nombre, el reintento crea solo el que falta`() = runTest {
        val originales = abreLaHojaCon()
        val hoja = mutableListOf(InlineModifier(name = "Queso", price = 1.0), InlineModifier(name = "Queso", price = 1.0))
        val vm = viewModel()
        var quesos = 0
        falla = { it == "POST Queso" && ++quesos == 2 }
        assertEquals(false, guardar(vm, originales, hoja))

        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH creado-1", "POST Queso"), peticiones)
        assertEquals(listOf("Queso", "Queso"), enServidor.map { it.name })
    }

    @Test
    fun `el aviso a la hoja trae el indice de su lista aunque haya filas con isDeleted antes`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        val hoja = mutableListOf(
            InlineModifier(id = "m-viejo", name = "Viejo", price = 1.0, isDeleted = true),
            InlineModifier(name = "Nuevo", price = 3.0),
            InlineModifier(id = "m-uno", name = "Uno", price = 1.0),
            InlineModifier(name = "Otro", price = 4.0),
        )

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf(1 to "creado-1", 3 to "creado-2"), avisosALaHoja)
        assertEquals(listOf("m-viejo", "creado-1", "m-uno", "creado-2"), hoja.map { it.id })
    }

    @Test
    fun `reintento donde el borrado ya se hizo no vuelve a borrar`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        val hoja = mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0))
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
    fun `si el DELETE llego pero se perdio la respuesta el reintento no falla por 404`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        val hoja = mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0))
        val vm = viewModel()
        seCortaLaRespuesta = { it == "DELETE m-viejo" }
        assertEquals(false, guardar(vm, originales, hoja))

        // La lista todavía no sabe del DELETE: se entera con el PATCH del grupo con el que empieza el reintento.
        reintentoSinFallas()
        val resultado = guardar(vm, originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH m-uno"), peticiones)
    }

    @Test
    fun `un modificador de otro aparato en la lista viva no se borra ni se toca`() = runTest {
        val originales = abreLaHojaCon(uno)
        otroAparatoCrea("m-ajeno", "Ajeno", 9.0)

        val resultado = guardar(viewModel(), originales, mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0)))

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "PATCH m-uno"), peticiones)
        assertEquals(listOf("m-uno", "m-ajeno"), enServidor.map { it.id })
    }

    @Test
    fun `un nuevo que se llama igual que uno que ya estaba se crea y no pisa al original`() = runTest {
        val originales = abreLaHojaCon(uno)
        val hoja = mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 1.0), InlineModifier(name = "Uno", price = 7.0))

        val resultado = guardar(viewModel(), originales, hoja)

        assertEquals(true, resultado)
        assertEquals(listOf("Uno" to 1.0, "Uno" to 7.0), enServidor.map { it.name to it.price })
    }

    @Test
    fun `sin el grupo en la lista viva usa los originales tal cual`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        loQueDevuelveLaLista = { emptyList() }
        listaViva.value = emptyList()

        val resultado = guardar(viewModel(), originales, mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0)))

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo", "PATCH m-uno"), peticiones)
    }

    @Test
    fun `un grupo que llega sin su lista de modificadores tampoco cancela el borrado`() = runTest {
        val originales = abreLaHojaCon(viejo, uno)
        loQueDevuelveLaLista = { listOf(ModifierGroup(id = "g", modifiers = null)) }
        listaViva.value = loQueDevuelveLaLista()

        val resultado = guardar(viewModel(), originales, mutableListOf(InlineModifier(id = "m-uno", name = "Uno", price = 2.0)))

        assertEquals(true, resultado)
        assertEquals(listOf("PATCH grupo", "DELETE m-viejo", "PATCH m-uno"), peticiones)
    }
}

/** D4: el alta devuelve el id que creó el servidor, y un 2xx sin id no pasa por alta confirmada. */
class AltaDeModificadorRepositorioTest {
    private fun repositorio(cuerpoDelAlta: String) = ArticlesRepository(
        mockk<SecureStorage> { every { venueId } returns "venue-1" },
        mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                val esAlta = request.method == "POST"
                mockk<Call> {
                    every { execute() } returns Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (esAlta) 201 else 200)
                        .message("Respuesta")
                        .body((if (esAlta) cuerpoDelAlta else "[]").toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
        },
        mockk(relaxed = true),
    )

    @Test
    fun `devuelve el id que manda el servidor en la raiz de la respuesta`() = runTest {
        val repository = repositorio("""{"id":"cm0modificador","groupId":"grupo-1","name":"Queso","price":"2"}""")

        assertEquals("cm0modificador", repository.addModifierToGroup("grupo-1", "{}"))
        assertNull(repository.errorMessage.value)
    }

    @Test
    fun `un 2xx del que no se puede leer el id no se da por creado y lo dice`() = runTest {
        listOf("<html>Portal del WiFi</html>", "{}", """{"id":""}""", "").forEach { cuerpo ->
            val repository = repositorio(cuerpo)

            assertNull(cuerpo, repository.addModifierToGroup("grupo-1", "{}"))
            assertTrue(cuerpo, repository.errorMessage.value.orEmpty().contains("No pudimos confirmar"))
        }
    }
}
