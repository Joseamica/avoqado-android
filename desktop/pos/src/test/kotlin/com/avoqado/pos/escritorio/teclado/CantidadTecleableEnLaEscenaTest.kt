package com.avoqado.pos.escritorio.teclado

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.CantidadTecleable
import com.avoqado.pos.designsystem.components.CasillaDeCantidad
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import kotlin.test.Test
import kotlin.test.assertEquals

/** La cantidad se teclea (founder, 17-nov: pan en Windows, «para no sumar de 1 en 1»). */
@OptIn(ExperimentalTestApi::class)
class CantidadTecleableEnLaEscenaTest {
    private var cantidad by mutableIntStateOf(1)
    private var clicsAlLado = 0

    /** La casilla con un botón al lado (en la hoja, «Agregar al carrito»): un Enter no debe picarlo. */
    private fun ComposeUiTest.casilla(inicial: Int): SemanticsNodeInteraction {
        cantidad = inicial
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                MaterialTheme {
                    Column {
                        Box(Modifier.size(40.dp).clickable { clicsAlLado++ })
                        CantidadTecleable(cantidad, onCantidad = { cantidad = it })
                    }
                }
            }
        }
        return onNode(hasSetTextAction()).also { it.performClick() }
    }

    private fun SemanticsNodeInteraction.texto() = fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun `tocar el 1 y teclear 24 deja 24, no 124`() = runComposeUiTest {
        val campo = casilla(inicial = 1)
        campo.performTextInput("2")
        campo.performTextInput("4")
        waitForIdle()
        assertEquals(24, cantidad)
    }

    @Test fun `Enter de verdad suelta la casilla y no pica el boton de al lado`() = runComposeUiTest {
        val campo = casilla(inicial = 1)
        campo.performTextInput("24")
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(24, cantidad)
        assertEquals(0, clicsAlLado)
        assertEquals("24", campo.texto())
    }

    @Test fun `un cero no quita el renglon y al salir vuelve la cantidad`() = runComposeUiTest {
        val campo = casilla(inicial = 5)
        campo.performTextInput("0")
        campo.performImeAction()
        waitForIdle()
        assertEquals(5, cantidad)
        assertEquals("5", campo.texto())
    }

    // La pistola teclea letra por letra en el campo con foco (LectorDePistola deja pasar todo si hay campo escribiendo).
    @Test fun `un codigo de la pistola en la casilla regresa la cantidad que tenia al tocarla`() = runComposeUiTest {
        val campo = casilla(inicial = 3)
        campo.performTextInput("24")
        waitForIdle()
        assertEquals(24, cantidad)
        "7501632510590".forEach { campo.performTextInput(it.toString()) }
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(3, cantidad, "ni 247 ni 24: la de antes de tocarla")
        assertEquals("3", campo.texto())
        assertEquals(0, clicsAlLado)
    }

    @Test fun `un codigo con letras en la casilla tampoco cambia la cantidad`() = runComposeUiTest {
        val campo = casilla(inicial = 3)
        "ABC123".forEach { campo.performTextInput(it.toString()) }
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(3, cantidad, "no 123")
    }

    // Los lectores de la pistola leen esta marca: mientras la casilla tiene el foco, un código no le llega.
    @Test fun `la casilla avisa a la pistola mientras tiene el foco`() = runComposeUiTest {
        val campo = casilla(inicial = 1)
        waitForIdle()
        assertEquals(true, CasillaDeCantidad.activa)
        campo.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(false, CasillaDeCantidad.activa)
    }
}
