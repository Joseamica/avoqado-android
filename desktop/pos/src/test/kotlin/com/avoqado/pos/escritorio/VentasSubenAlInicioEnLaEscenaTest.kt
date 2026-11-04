package com.avoqado.pos.escritorio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.pos.transactions.presentation.SubirAlInicioDeLaLista
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
