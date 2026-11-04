package com.avoqado.pos.escritorio

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.pos.transactions.presentation.SubirAlInicioDeLaLista
import com.avoqado.pos.transactions.presentation.subirOAbandonar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D22 ronda 3 (D3, 4-nov): con cinco páginas y la lista abajo, tocar «Hay ventas nuevas · Actualizar» reemplazaba las páginas
 * por la 1 nueva pero dejaba la lista a media altura. Tras una carga a mano buena sube al inicio; la automática con la cajera
 * abajo no la mueve. Lo mismo corre en ANDROID: es el mismo composable de la pantalla.
 */
@OptIn(ExperimentalTestApi::class)
class VentasSubenAlInicioEnLaEscenaTest {
    private val cincoPaginas = (1..100).map { "fila-$it" }

    private fun escena(test: (estado: LazyListState, cambiar: (List<String>, Long) -> Unit) -> Unit) = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        var subidas by mutableLongStateOf(0L)
        var hechas by mutableLongStateOf(0L) // lo que guarda el ViewModel
        setContent {
            Box(Modifier.height(400.dp)) {
                LazyColumn(state = estado, modifier = Modifier.testTag("lista")) {
                    items(filas, key = { it }) { BasicText(it, Modifier.height(48.dp)) }
                }
            }
            SubirAlInicioDeLaLista(estado, filas.first(), subidasPedidas = subidas, subidasHechas = hechas, alSubir = { hechas = it })
        }
        onNodeWithTag("lista").performScrollToIndex(60) // la cajera bajó a la página 4
        waitForIdle()
        assertTrue(estado.firstVisibleItemIndex >= 60)
        test(estado) { nuevas, nuevasSubidas ->
            filas = nuevas
            subidas = nuevasSubidas
            waitForIdle()
        }
    }

    @Test fun `tocar la pildora con la carga buena sube al inicio aunque la cajera hubiera bajado`() = escena { estado, cambiar ->
        cambiar(listOf("nueva") + cincoPaginas.take(20), 1L) // la página 1 nueva reemplaza las cinco
        assertEquals(0, estado.firstVisibleItemIndex)
    }

    @Test fun `si la carga a mano falla la lista no se mueve`() = escena { estado, cambiar ->
        cambiar(cincoPaginas, 0L)
        assertTrue(estado.firstVisibleItemIndex >= 60)
    }

    @Test fun `ronda 4 - si la carga a mano termina con otra pestana, al volver la lista sube al inicio`() = runComposeUiTest {
        var mostrar by mutableStateOf(true)
        var estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        var subidas by mutableLongStateOf(0L)
        var hechas by mutableLongStateOf(0L) // en el ViewModel: sobrevive a la pestaña
        setContent {
            if (mostrar) {
                Box(Modifier.height(400.dp)) {
                    LazyColumn(state = estado, modifier = Modifier.testTag("lista")) {
                        items(filas, key = { it }) { BasicText(it, Modifier.height(48.dp)) }
                    }
                }
                SubirAlInicioDeLaLista(estado, filas.first(), subidasPedidas = subidas, subidasHechas = hechas, alSubir = { hechas = it })
            }
        }
        onNodeWithTag("lista").performScrollToIndex(60) // bajó varias páginas y tocó «Actualizar»
        waitForIdle()
        val guardada = estado.firstVisibleItemIndex
        assertTrue(guardada >= 60)

        mostrar = false // cambia de pestaña mientras consulta
        waitForIdle()
        filas = listOf("nueva") + cincoPaginas.take(20) // la carga termina bien con la pantalla desmontada
        subidas = 1L
        estado = LazyListState(firstVisibleItemIndex = guardada) // la navegación restaura la posición guardada
        mostrar = true // vuelve a Ventas
        waitForIdle()

        assertEquals(0, estado.firstVisibleItemIndex)
        assertEquals(0, estado.firstVisibleItemScrollOffset)
        assertEquals(1L, hechas)
    }

    @Test fun `la automatica con la cajera abajo no la mueve`() = escena { estado, cambiar ->
        cambiar(listOf("nueva") + cincoPaginas, 0L) // entra una venta arriba, sin que nadie la pida
        assertTrue(estado.firstVisibleItemIndex > 0)
    }

    @Test fun `Cargar mas pega al final y no sube`() = escena { estado, cambiar ->
        cambiar(cincoPaginas + (101..120).map { "fila-$it" }, 0L) // llega la página siguiente
        assertTrue(estado.firstVisibleItemIndex >= 60)
    }

    // MARK: - Ronda 5: si la cajera toca o arrastra la lista, manda ella (pasada completa de la familia)

    @Test fun `3 - con el dedo encima cuando llega la carga no sube, queda anotada y no salta despues`() = runComposeUiTest {
        var mostrar by mutableStateOf(true)
        val estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        var subidas by mutableLongStateOf(0L)
        var hechas by mutableLongStateOf(0L)
        lateinit var alcance: CoroutineScope
        setContent {
            alcance = rememberCoroutineScope()
            if (mostrar) {
                Box(Modifier.height(400.dp)) {
                    LazyColumn(state = estado, modifier = Modifier.testTag("lista")) {
                        items(filas, key = { it }) { BasicText(it, Modifier.height(48.dp)) }
                    }
                }
                SubirAlInicioDeLaLista(estado, filas.first(), subidasPedidas = subidas, subidasHechas = hechas, alSubir = { hechas = it })
            }
        }
        onNodeWithTag("lista").performScrollToIndex(60)
        waitForIdle()
        val dedo = alcance.launch { estado.scroll(MutatePriority.UserInput) { awaitCancellation() } } // la cajera arrastra
        waitForIdle()

        filas = listOf("nueva") + cincoPaginas // la carga termina bien con el dedo encima
        subidas = 1L
        waitForIdle()
        assertTrue(estado.firstVisibleItemIndex > 0)
        assertEquals(1L, hechas, "descartada: manda ella")

        dedo.cancel() // suelta el dedo
        waitForIdle()
        assertTrue(estado.firstVisibleItemIndex > 0, "al soltar no salta")
        mostrar = false
        waitForIdle()
        mostrar = true
        waitForIdle()
        assertTrue(estado.firstVisibleItemIndex > 0, "al reconstruirse tampoco")
    }

    @Test fun `4 - un gesto que empieza durante el salto lo cancela y la subida queda anotada`() = runComposeUiTest {
        val estado = LazyListState()
        lateinit var alcance: CoroutineScope
        setContent {
            alcance = rememberCoroutineScope()
            LazyColumn(state = estado) { items(cincoPaginas, key = { it }) { BasicText(it, Modifier.height(48.dp)) } }
        }
        waitForIdle()
        var anotada = false
        // Un salto que tarda: así el gesto llega a medio camino (scrollToItem es instantáneo).
        val salto = alcance.launch { subirOAbandonar(subir = { estado.scroll { awaitCancellation() } }, anotar = { anotada = true }) }
        waitForIdle()
        alcance.launch { estado.scroll(MutatePriority.UserInput) { } } // la cajera toca la lista
        waitForIdle()
        assertTrue(anotada)
        assertTrue(salto.isCompleted)
        assertFalse(salto.isCancelled)
    }

    @Test fun `5 - salir de la pantalla durante el salto no la anota`() = runComposeUiTest {
        val estado = LazyListState()
        lateinit var alcance: CoroutineScope
        setContent {
            alcance = rememberCoroutineScope()
            LazyColumn(state = estado) { items(cincoPaginas, key = { it }) { BasicText(it, Modifier.height(48.dp)) } }
        }
        waitForIdle()
        var anotada = false
        val salto = alcance.launch { subirOAbandonar(subir = { estado.scroll { awaitCancellation() } }, anotar = { anotada = true }) }
        waitForIdle()
        salto.cancel() // la pantalla sale de la composición
        waitForIdle()
        assertFalse(anotada, "queda pendiente para cuando vuelva")
        assertTrue(salto.isCancelled)
    }

    @Test fun `6 - dos cargas a mano buenas antes de saltar dan un solo salto y quedan las dos anotadas`() = runComposeUiTest {
        var mostrar by mutableStateOf(true)
        val estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        var subidas by mutableLongStateOf(0L)
        var hechas by mutableLongStateOf(0L)
        var saltos = 0
        setContent {
            if (mostrar) {
                Box(Modifier.height(400.dp)) {
                    LazyColumn(state = estado, modifier = Modifier.testTag("lista")) {
                        items(filas, key = { it }) { BasicText(it, Modifier.height(48.dp)) }
                    }
                }
                SubirAlInicioDeLaLista(estado, filas.first(), subidasPedidas = subidas, subidasHechas = hechas, alSubir = { saltos++; hechas = it })
            }
        }
        onNodeWithTag("lista").performScrollToIndex(60)
        waitForIdle()
        mostrar = false
        waitForIdle()
        filas = listOf("nueva") + cincoPaginas.take(20)
        subidas = 2L // dos cargas a mano buenas mientras tanto
        mostrar = true
        waitForIdle()
        assertEquals(0, estado.firstVisibleItemIndex)
        assertEquals(1, saltos)
        assertEquals(2L, hechas)
    }
}
