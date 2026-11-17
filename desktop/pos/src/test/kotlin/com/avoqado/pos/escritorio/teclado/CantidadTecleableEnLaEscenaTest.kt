package com.avoqado.pos.escritorio.teclado

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.designsystem.components.CantidadTecleable
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import androidx.compose.material3.MaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** La cantidad se teclea (founder, 17-nov: pan en Windows, «para no sumar de 1 en 1»). */
@OptIn(ExperimentalTestApi::class)
class CantidadTecleableEnLaEscenaTest {
    @Test fun `tocar el 1 y teclear 24 deja 24, no 124`() = runComposeUiTest {
        var cantidad by mutableIntStateOf(1)
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                MaterialTheme { CantidadTecleable(cantidad, onCantidad = { cantidad = it }) }
            }
        }
        val campo = onNode(hasSetTextAction())
        campo.performClick()
        campo.performTextInput("24")
        waitForIdle()
        assertEquals(24, cantidad)

        campo.performImeAction()   // Enter
        waitForIdle()
        assertEquals("24", campo.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun `tocar y salir sin teclear deja la cantidad de antes`() = runComposeUiTest {
        var cantidad by mutableIntStateOf(5)
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                MaterialTheme { CantidadTecleable(cantidad, onCantidad = { cantidad = it }) }
            }
        }
        val campo = onNode(hasSetTextAction())
        campo.performClick()
        campo.performTextInput("0")   // un cero no es cantidad: no quita el renglón
        campo.performImeAction()
        waitForIdle()
        assertEquals(5, cantidad)
        assertEquals("5", campo.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}
