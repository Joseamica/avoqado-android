package com.avoqado.pos.escritorio.teclado

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.SearchPillField
import androidx.compose.material3.MaterialTheme
import com.avoqado.pos.pos.presentation.search.flechasDeResultados
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Buscar» en Windows: ↓/↑ por los resultados y Enter elige (founder, 17-nov; cliente con teclado). */
@OptIn(ExperimentalTestApi::class)
class BuscarConFlechasEnLaEscenaTest {
    private var marcada by mutableIntStateOf(-1)
    private var elegido = -1

    private fun androidx.compose.ui.test.ComposeUiTest.buscador(total: Int): SemanticsNodeInteraction {
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                MaterialTheme {
                    SearchPillField(
                        query = "manchego", onQueryChange = {}, placeholder = "Buscar productos...",
                        textFieldModifier = Modifier.flechasDeResultados(total, marcada, onMarcada = { marcada = it }) { elegido = it },
                    )
                }
            }
        }
        return onNode(hasSetTextAction()).also { it.performClick() }
    }

    @Test fun `dos veces abajo y Enter elige el segundo`() = runComposeUiTest {
        val campo = buscador(total = 5)
        campo.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals(1, marcada)
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, elegido)
    }

    @Test fun `Enter sin nada marcado no elige (la pistola termina en Enter)`() = runComposeUiTest {
        val campo = buscador(total = 5)
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(-1, elegido)
    }

    @Test fun `abajo no pasa del ultimo y arriba desde el primero quita la marca`() = runComposeUiTest {
        val campo = buscador(total = 2)
        campo.performKeyInput { repeat(4) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        assertEquals(1, marcada)
        campo.performKeyInput { repeat(2) { pressKey(Key.DirectionUp) } }
        waitForIdle()
        assertEquals(-1, marcada)
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(-1, elegido)
    }
}
