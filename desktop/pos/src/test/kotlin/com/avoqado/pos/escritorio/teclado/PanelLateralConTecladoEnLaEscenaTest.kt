package com.avoqado.pos.escritorio.teclado

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.checkout.CartItemDetailPanel
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import com.avoqado.pos.tables.presentation.NotaDeLineaPendiente
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * hz:0555ee15:D10 (Flautas, video del 20-nov): en el POS de WINDOWS con teclado físico, al escribir «salsa » en la nota
 * del artículo el ESPACIO cerraba el panel lateral y la nota se perdía. El fondo oscuro del panel es `clickable` (cerrar
 * al tocar afuera) y en escritorio `clickable` también contesta al teclado: la tecla que el campo no consume (el KeyUp
 * del Espacio o de Enter) subía hasta el fondo y contaba como clic. Los tres paneles con nota, en la escena real:
 * - la nota de una línea de mesa sin enviar (Mesas, el del video);
 * - la ficha del producto al agregar (Mostrador y Mesas);
 * - el detalle de un artículo del carrito (Mostrador).
 * Y de paso: un clic en un hueco vacío DENTRO del panel tampoco lo cierra; un clic AFUERA sí.
 */
@OptIn(ExperimentalTestApi::class)
class PanelLateralConTecladoEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("panel-lateral-teclado")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    private val texto = "Agrega una nota para este artículo. Se mostrará en el ticket y en la cocina."

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides contexto, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(contexto), LocalEsEscritorio provides true,
        ) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    /** Lo del video: clic en el campo y teclear «s», ESPACIO y Enter sin tocar el mouse. */
    private fun ComposeUiTest.escribirConElTeclado() {
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput {
            pressKey(Key.S)
            pressKey(Key.Spacebar)
            pressKey(Key.Enter)
            pressKey(Key.NumPadEnter)
        }
        waitForIdle()
    }

    /** Clic en el texto de ayuda de la nota: no es un botón, es un hueco del panel. */
    private fun ComposeUiTest.clicEnUnHuecoDelPanel() {
        onNodeWithText(texto).performClick()
        waitForIdle()
    }

    /** Clic en la esquina de arriba a la izquierda: el fondo oscuro, fuera del panel de 400 dp de la derecha. */
    private fun ComposeUiTest.clicAfuera() {
        onRoot().performTouchInput { click(Offset(5f, 5f)) }
        waitForIdle()
    }

    // MARK: - Nota de una línea de mesa sin enviar (el del video)

    private fun ComposeUiTest.notaDeMesa(onCerrar: () -> Unit) = setContent {
        superficie { NotaDeLineaPendiente(isTablet = true, nota = "", onGuardar = {}, onCerrar = onCerrar) }
    }

    @Test fun `nota de mesa - el espacio y Enter no cierran el panel`() = runComposeUiTest {
        var cerrado = 0
        notaDeMesa { cerrado++ }
        escribirConElTeclado()
        assertEquals(0, cerrado, "teclear en la nota cerró el panel")
        onNodeWithText(texto).assertExists()
    }

    @Test fun `nota de mesa - un clic en un hueco del panel no lo cierra y uno afuera si`() = runComposeUiTest {
        var cerrado = 0
        notaDeMesa { cerrado++ }
        clicEnUnHuecoDelPanel()
        assertEquals(0, cerrado, "un clic dentro del panel lo cerró")
        clicAfuera()
        assertEquals(1, cerrado, "un clic afuera ya no cierra el panel")
    }

    // MARK: - Ficha del producto al agregar (Mostrador y Mesas)

    private fun ComposeUiTest.fichaEnLaNota(onCerrar: () -> Unit) {
        setContent {
            superficie {
                ProductDetailPanel(
                    product = Product(id = "flauta", name = "Flauta de pollo", priceValue = 35.0),
                    isTablet = true,
                    onAddToCart = { _, _, _, _, _, _, _, _ -> },
                    onDismiss = onCerrar,
                )
            }
        }
        onNodeWithText("Nota del artículo").performClick()
        onNodeWithText(texto).assertExists()
    }

    @Test fun `ficha del producto - el espacio y Enter en la nota no cierran el panel`() = runComposeUiTest {
        var cerrado = 0
        fichaEnLaNota { cerrado++ }
        escribirConElTeclado()
        assertEquals(0, cerrado, "teclear en la nota cerró la ficha")
        onNodeWithText(texto).assertExists()
    }

    @Test fun `ficha del producto - un clic en un hueco del panel no la cierra y uno afuera si`() = runComposeUiTest {
        var cerrado = 0
        fichaEnLaNota { cerrado++ }
        clicEnUnHuecoDelPanel()
        assertEquals(0, cerrado, "un clic dentro de la ficha la cerró")
        clicAfuera()
        assertEquals(1, cerrado, "un clic afuera ya no cierra la ficha")
    }

    // MARK: - Detalle de un artículo del carrito (Mostrador)

    private fun ComposeUiTest.carritoEnLaNota(onCerrar: () -> Unit) {
        setContent {
            superficie {
                CartItemDetailPanel(
                    item = CartItem(type = CartItemType.ProductItem("flauta"), name = "Flauta de pollo", unitPrice = 3_500),
                    isTablet = true,
                    onUpdateQuantity = {}, onUpdateNote = {}, onToggleCortesia = { _, _ -> },
                    onUpdatePriceAdjustment = {}, onDelete = {}, onDismiss = onCerrar,
                )
            }
        }
        onNodeWithText("Nota del artículo").performClick()
        onNodeWithText(texto).assertExists()
    }

    @Test fun `carrito - el espacio y Enter en la nota no cierran el panel`() = runComposeUiTest {
        var cerrado = 0
        carritoEnLaNota { cerrado++ }
        escribirConElTeclado()
        assertEquals(0, cerrado, "teclear en la nota cerró el detalle del carrito")
        onNodeWithText(texto).assertExists()
    }

    @Test fun `carrito - un clic en un hueco del panel no lo cierra y uno afuera si`() = runComposeUiTest {
        var cerrado = 0
        carritoEnLaNota { cerrado++ }
        clicEnUnHuecoDelPanel()
        assertEquals(0, cerrado, "un clic dentro del detalle del carrito lo cerró")
        clicAfuera()
        assertEquals(1, cerrado, "un clic afuera ya no cierra el detalle del carrito")
    }
}
