package com.avoqado.pos.escritorio.teclado

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.tables.presentation.RenglonEnviadoDialog
import com.avoqado.pos.tables.presentation.ULTIMO_PRODUCTO_SIN_ANULAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * «Anular producto» en la cuenta de MESA del POS de Windows (Flautas, 20-nov; el cliente usa mouse y teclado): todo se alcanza
 * con un clic, los motivos se eligen con clic, Esc cierra sin hacer nada y Enter/Espacio no confirman (dinero).
 */
@OptIn(ExperimentalTestApi::class)
class RenglonEnviadoEnLaEscenaTest {
    private class Salida {
        var anular: String? = null
        var cortesia: String? = null
        var cerrada = 0
    }

    @Composable
    private fun EnWindows(salida: Salida, sinAnular: String? = null) = CompositionLocalProvider(
        LocalEsEscritorio provides true,
        androidx.compose.ui.platform.LocalConfiguration provides com.avoqado.escritorio.configuracionDeAndroid(1280.dp, 800.dp, 1f),
    ) {
        MaterialTheme {
            RenglonEnviadoDialog(
                productName = "Agua de pepino",
                sinAnular = sinAnular,
                onDismiss = { salida.cerrada++ },
                onAnular = { salida.anular = it },
                onCortesia = { salida.cortesia = it },
            )
        }
    }

    @Test fun `con clics - motivo y Anular producto lo quitan con ese motivo`() = runComposeUiTest {
        val salida = Salida()
        setContent { EnWindows(salida) }
        onNodeWithText("Anular producto").assertIsNotEnabled()
        onNodeWithText("Dar de cortesía").assertIsNotEnabled()
        onNodeWithText("El cliente cambió de parecer").performClick()
        onNodeWithText("Anular producto").assertIsEnabled().performClick()
        runOnIdle { assertEquals("El cliente cambió de parecer", salida.anular); assertNull(salida.cortesia) }
    }

    @Test fun `con clics - Dar de cortesia sigue como hoy`() = runComposeUiTest {
        val salida = Salida()
        setContent { EnWindows(salida) }
        onNodeWithText("Amigos y familia").performClick()
        onNodeWithText("Dar de cortesía").performClick()
        runOnIdle { assertEquals("Amigos y familia", salida.cortesia); assertNull(salida.anular) }
    }

    @Test fun `P1 Enter y Espacio no confirman Anular ni Cortesia con el foco`() = runComposeUiTest {
        val salida = Salida()
        setContent { EnWindows(salida) }
        onNodeWithText("Error de entrada").performClick()
        for (boton in listOf("Anular producto", "Dar de cortesía")) {
            onNodeWithText(boton).performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithText(boton).performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        }
        runOnIdle { assertNull(salida.anular); assertNull(salida.cortesia) }
    }

    @Test fun `Esc cierra la ventana sin anular ni regalar`() = runComposeUiTest {
        val salida = Salida()
        setContent { EnWindows(salida) }
        onNodeWithText("Error de entrada").performClick()
        onNodeWithText("Anular producto").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Anular producto").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(1, salida.cerrada); assertNull(salida.anular); assertNull(salida.cortesia) }
    }

    @Test fun `la ventana es barrera - F12 y Esc no llegan a la cuenta de abajo`() = runComposeUiTest {
        setContent { EnWindows(Salida()) }
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F12)); assertFalse(AtajosDeTeclado.atender(Key.Escape)) }
    }

    @Test fun `el unico producto - Anular en gris con la salida, la cortesia sigue`() = runComposeUiTest {
        val salida = Salida()
        setContent { EnWindows(salida, ULTIMO_PRODUCTO_SIN_ANULAR) }
        onNodeWithText("Error de entrada").performClick()
        onNodeWithText(ULTIMO_PRODUCTO_SIN_ANULAR).assertExists()
        onNodeWithText("Anular producto").assertIsNotEnabled()
        onNodeWithText("Dar de cortesía").assertIsEnabled()
    }
}
