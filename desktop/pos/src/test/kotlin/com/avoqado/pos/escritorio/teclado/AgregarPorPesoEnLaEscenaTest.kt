package com.avoqado.pos.escritorio.teclado

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.designsystem.components.AtajoDeTeclado
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.BarreraDeAtajos
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.product.WeightCapturePanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F12 en la hoja de venta por peso del POS de Windows (founder, 4-nov, foto de la cremería): F12 = «Agregar • $X».
 * Con la hoja abierta F12 agrega y NO cobra; cerrada, F12 sigue siendo Cobrar (lo de abajo es el F12 del mostrador).
 */
@OptIn(ExperimentalTestApi::class)
class AgregarPorPesoEnLaEscenaTest {
    private val queso = Product(id = "queso", name = "Queso Oaxaca", priceValue = 280.20, soldByWeight = true)

    private class Mostrador {
        var cobros = 0
        val agregados = mutableListOf<Double>()
        var abierta by mutableStateOf(true)
        var aviso by mutableStateOf(false)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.escena(m: Mostrador) = setContent {
        CompositionLocalProvider(LocalEsEscritorio provides true) {
            MaterialTheme {
                AtajoDeTeclado(Key.F12) { m.cobros++ }   // el F12 = Cobrar del mostrador (CheckoutScreen)
                if (m.abierta) {
                    WeightCapturePanel(
                        product = queso,
                        isTablet = true,
                        onAdd = { m.agregados += it },
                        onDismiss = { m.abierta = false },
                    )
                }
                if (m.aviso) BarreraDeAtajos()   // lo que registra un aviso (AvoqadoWarningToast) encima
            }
        }
    }

    @Test fun `hoja abierta con peso - F12 agrega una vez y no cobra, aunque se repita rapido`() = runComposeUiTest {
        val m = Mostrador()
        escena(m)
        onNode(hasSetTextAction()).performTextInput("0.5")
        onNodeWithText("Agregar • \$140.10").assertExists()
        onNodeWithText("F12").assertExists()
        // Dos F12 antes de que la hoja alcance a cerrarse (doble apretón): una sola línea.
        runOnIdle {
            assertTrue(AtajosDeTeclado.atender(Key.F12))
            AtajosDeTeclado.atender(Key.F12)
        }
        runOnIdle {
            assertEquals(listOf(0.5), m.agregados)
            assertEquals(0, m.cobros, "con la hoja abierta F12 es Agregar, no Cobrar")
            assertFalse(m.abierta)
        }
        // Ya cerrada, F12 vuelve a ser Cobrar.
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F12)) }
        assertEquals(1, m.cobros)
    }

    @Test fun `sin peso el boton esta apagado - F12 no agrega ni cobra`() = runComposeUiTest {
        val m = Mostrador()
        escena(m)
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F12)) }
        onNode(hasSetTextAction()).performTextInput("abc")
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F12)) }
        runOnIdle {
            assertEquals(emptyList(), m.agregados)
            assertEquals(0, m.cobros)
            assertTrue(m.abierta)
        }
    }

    @Test fun `con un aviso encima de la hoja F12 espera`() = runComposeUiTest {
        val m = Mostrador()
        escena(m)
        onNode(hasSetTextAction()).performTextInput("0.5")
        m.aviso = true
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F12)) }
        runOnIdle { assertEquals(0 to 0, m.agregados.size to m.cobros) }
    }
}
