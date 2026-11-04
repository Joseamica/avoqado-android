package com.avoqado.pos.escritorio

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.pos.transactions.presentation.PildoraDeVentasNuevas
import com.avoqado.pos.transactions.presentation.SubirAlInicioDeLaLista
import com.avoqado.pos.transactions.presentation.TextosDeVentas
import com.avoqado.pos.transactions.presentation.alTocarLaPildora
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D22 (rediseño r6): la lista de Ventas sube al inicio sólo en dos momentos, y nunca queda un salto pendiente.
 * 1. Al llegar la página 1 (automática o a mano), si la lista estaba exactamente arriba y quieta, sube para enseñar lo nuevo.
 * 2. Al tocar «Hay ventas nuevas · Actualizar», el mismo toque sube la lista y luego pide la página 1.
 * La escena arma la lista igual que la pantalla (la píldora encima, la lista debajo) con los mismos composables; lo mismo
 * corre en ANDROID.
 */
@OptIn(ExperimentalTestApi::class)
class VentasSubenAlInicioEnLaEscenaTest {
    private val unaPagina = (1..20).map { "fila-$it" }
    private val cincoPaginas = (1..100).map { "fila-$it" }
    private val paginaNueva = listOf("nueva") + unaPagina

    @Composable
    private fun Ventas(estado: LazyListState, filas: List<String>, hayVentasNuevas: Boolean, actualizar: () -> Unit) {
        val alcance = rememberCoroutineScope()
        SubirAlInicioDeLaLista(estado, filas.first())
        Column(Modifier.height(400.dp)) {
            if (hayVentasNuevas) {
                PildoraDeVentasNuevas(onActualizar = { alTocarLaPildora(alcance, estado, actualizar) })
            }
            LazyColumn(state = estado, modifier = Modifier.fillMaxWidth().weight(1f).testTag("lista")) {
                items(filas, key = { it }) { BasicText(it, Modifier.height(48.dp)) }
            }
        }
    }

    private val LazyListState.primeraVisible get() = layoutInfo.visibleItemsInfo.firstOrNull()?.key

    @Test fun `a - tocar la pildora con varias paginas abajo y la carga buena - queda arriba y la venta nueva es la primera`() = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        var hay by mutableStateOf(true)
        var pedidas = 0
        var indiceAlPedir = -1
        setContent {
            Ventas(estado, filas, hay) { pedidas++; indiceAlPedir = estado.firstVisibleItemIndex }
        }
        onNodeWithTag("lista").performScrollToIndex(60) // bajó a la página 4
        waitForIdle()
        assertTrue(estado.firstVisibleItemIndex >= 60)

        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).performClick()
        waitForIdle()
        assertEquals(1, pedidas, "el toque pide la página 1")
        assertEquals(0, indiceAlPedir, "primero sube, luego pide")

        filas = paginaNueva // la carga buena reemplaza las cinco páginas
        hay = false
        waitForIdle()
        assertEquals(0, estado.firstVisibleItemIndex)
        assertEquals(0, estado.firstVisibleItemScrollOffset)
        assertEquals("nueva", estado.primeraVisible)
    }

    @Test fun `b - tocar la pildora, cambiar de pestana, carga buena y volver - queda arriba`() = runComposeUiTest {
        var mostrar by mutableStateOf(true)
        var filas by mutableStateOf(cincoPaginas)
        var hay by mutableStateOf(true)
        var pedidas = 0
        lateinit var estado: LazyListState
        setContent {
            val pestanas = rememberSaveableStateHolder() // lo que hace la navegación con cada pestaña
            if (mostrar) {
                pestanas.SaveableStateProvider("ventas") {
                    estado = rememberLazyListState()
                    Ventas(estado, filas, hay) { pedidas++ }
                }
            }
        }
        onNodeWithTag("lista").performScrollToIndex(60)
        waitForIdle()
        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).performClick()
        waitForIdle()
        assertEquals(1, pedidas)

        mostrar = false // otra pestaña mientras consulta: la navegación guarda la posición
        waitForIdle()
        filas = paginaNueva // la carga termina bien con la pantalla desmontada
        hay = false
        mostrar = true // vuelve a Ventas
        waitForIdle()

        assertEquals(0, estado.firstVisibleItemIndex)
        assertEquals(0, estado.firstVisibleItemScrollOffset)
        assertEquals("nueva", estado.primeraVisible)
    }

    @Test fun `c - tocar la pildora y bajar la lista antes de la carga - no vuelve a subir`() = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        var hay by mutableStateOf(true)
        setContent { Ventas(estado, filas, hay) {} }
        onNodeWithTag("lista").performScrollToIndex(60)
        waitForIdle()
        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).performClick()
        waitForIdle()

        onNodeWithTag("lista").performScrollToIndex(10) // la cajera la baja otra vez
        waitForIdle()
        filas = paginaNueva
        hay = false
        waitForIdle()
        assertEquals("fila-11", estado.primeraVisible, "manda ella")
    }

    @Test fun `c2 - inercia en curso justo arriba cuando llega la carga - no la corta ni sube`() = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(unaPagina)
        lateinit var alcance: CoroutineScope
        setContent {
            alcance = rememberCoroutineScope()
            Ventas(estado, filas, hayVentasNuevas = false) {}
        }
        waitForIdle()
        assertEquals(0, estado.firstVisibleItemIndex)
        // La inercia corre con la prioridad de siempre (Default): un scrollToItem la cortaría.
        val inercia = alcance.launch { estado.scroll(MutatePriority.Default) { awaitCancellation() } }
        waitForIdle()

        filas = paginaNueva // la automática llega con la lista en movimiento
        waitForIdle()
        assertTrue(inercia.isActive, "la inercia sigue")
        assertEquals("fila-1", estado.primeraVisible, "no se movió")

        inercia.cancel()
        waitForIdle()
        assertEquals("fila-1", estado.primeraVisible, "y nada salta después")
    }

    @Test fun `d1 - la automatica con la cajera abajo no la mueve`() = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(unaPagina)
        setContent { Ventas(estado, filas, hayVentasNuevas = false) {} }
        onNodeWithTag("lista").performScrollToIndex(10)
        waitForIdle()
        filas = paginaNueva // entra una venta arriba, sin que nadie la pida
        waitForIdle()
        assertEquals("fila-11", estado.primeraVisible)
    }

    @Test fun `d2 - Cargar mas pega al final y no la mueve`() = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(cincoPaginas)
        setContent { Ventas(estado, filas, hayVentasNuevas = false) {} }
        onNodeWithTag("lista").performScrollToIndex(60)
        waitForIdle()
        val antes = estado.primeraVisible
        filas = cincoPaginas + (101..120).map { "fila-$it" } // llega la página siguiente
        waitForIdle()
        assertEquals(antes, estado.primeraVisible)
    }

    @Test fun `e - la automatica con la lista arriba ensena lo nuevo`() = runComposeUiTest {
        val estado = LazyListState()
        var filas by mutableStateOf(unaPagina)
        setContent { Ventas(estado, filas, hayVentasNuevas = false) {} }
        waitForIdle()
        filas = paginaNueva
        waitForIdle()
        assertEquals(0, estado.firstVisibleItemIndex)
        assertEquals("nueva", estado.primeraVisible)
    }

    @Test fun `f - tocar la pildora y la carga falla - queda arriba de lo de antes y la pildora sigue`() = runComposeUiTest {
        val estado = LazyListState()
        var pedidas = 0
        setContent { Ventas(estado, cincoPaginas, hayVentasNuevas = true) { pedidas++ } } // el fallo no cambia nada
        onNodeWithTag("lista").performScrollToIndex(60)
        waitForIdle()
        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).performClick()
        waitForIdle()

        assertEquals(1, pedidas)
        assertEquals(0, estado.firstVisibleItemIndex)
        assertEquals(0, estado.firstVisibleItemScrollOffset)
        assertEquals("fila-1", estado.primeraVisible)
        onNodeWithText(TextosDeVentas.HAY_VENTAS_NUEVAS).assertExists()
    }
}
