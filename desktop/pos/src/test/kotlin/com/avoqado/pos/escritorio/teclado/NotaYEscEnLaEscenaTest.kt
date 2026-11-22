package com.avoqado.pos.escritorio.teclado

import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentResultScreen
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.ModifierGroupData
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductModifierGroupEntry
import com.avoqado.pos.pos.presentation.checkout.CartItemDetailPanel
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import com.avoqado.pos.tables.presentation.NotaDeLineaPendiente
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.avoqado.pos.pos.data.model.Modifier as Extra

/**
 * hz:0555ee15:D20 (full-testing de WINDOWS 2.25.57 en el Alien, 22-nov): el teclado y el mouse en los paneles con nota.
 * 1. Esc cierra la ficha del producto, el detalle del carrito y cada nota (la del artículo, la de una parte y la de una
 *    línea de mesa). Con una nota abierta dentro de la ficha, Esc cierra primero la nota. Con texto SIN GUARDAR, Esc no
 *    cierra la nota (D10: la nota no se pierde por accidente).
 * 2. El campo de la nota toma el foco al abrirse: se escribe sin darle clic.
 * 3. Enter no mete un salto en la nota (se guarda en una línea).
 * 4. Una nota rápida se prende y se apaga: el segundo toque la quita, no la repite.
 * 5. Doble clic en «Sin recibo»: el segundo clic no cae en la venta nueva («+ Nota» del Teclado).
 *
 * La ventana de Windows (`Main.kt`) le pasa a los atajos lo que la pantalla no usó; aquí la envuelve [ventana] igual.
 */
@OptIn(ExperimentalTestApi::class)
class NotaYEscEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("nota-y-esc")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    private val ayuda = "Agrega una nota para este artículo. Se mostrará en el ticket y en la cocina."

    @Composable private fun ventana(content: @Composable () -> Unit) {
        val atajos = remember { AtajosDeLaVentana(AtajosDeTeclado::atender) }
        CompositionLocalProvider(
            LocalContext provides contexto, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(contexto), LocalEsEscritorio provides true,
        ) {
            AvoqadoTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().onPreviewKeyEvent(atajos::antes).onKeyEvent(atajos::despues)) { content() }
            }
        }
    }

    private fun ComposeUiTest.esc() {
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
    }

    /** Esc sin nada con foco: la ventana se lo pasa a los atajos. */
    private fun ComposeUiTest.escSinFoco() {
        runOnIdle { AtajosDeTeclado.atender(Key.Escape) }
        waitForIdle()
    }

    private fun ComposeUiTest.campo() = onNode(hasSetTextAction())

    private fun ComposeUiTest.textoDelCampo() = campo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    // MARK: - Ficha del producto

    private val consome = Product(
        id = "consome", name = "Consomé", priceValue = 60.0,
        modifierGroups = listOf(
            ProductModifierGroupEntry(
                groupId = "g", group = ModifierGroupData(id = "g", name = "Extras", allowMultiple = true,
                    modifiers = listOf(Extra(id = "cebolla", name = "Cebolla"))),
            ),
        ),
    )

    private fun ComposeUiTest.ficha(onCerrar: () -> Unit) = setContent {
        ventana {
            ProductDetailPanel(product = consome, isTablet = true, onAddToCart = { _, _, _, _, _, _, _, _ -> }, onDismiss = onCerrar)
        }
    }

    @Test fun `Esc cierra la ficha del producto`() = runComposeUiTest {
        var cerrado = 0
        ficha { cerrado++ }
        escSinFoco()
        assertEquals(1, cerrado)
    }

    @Test fun `nota del articulo - toma el foco y Esc la cierra primero, luego la ficha`() = runComposeUiTest {
        var cerrado = 0
        ficha { cerrado++ }
        onNodeWithText("Nota del artículo").performClick()
        waitForIdle()
        campo().assertIsFocused()
        esc()
        assertEquals(0, cerrado, "Esc cerró la ficha con la nota abierta")
        onNodeWithText(ayuda).assertDoesNotExist()
        escSinFoco()
        assertEquals(1, cerrado, "el segundo Esc ya no cierra la ficha")
    }

    @Test fun `nota del articulo - con texto sin guardar Esc no la cierra ni lo borra`() = runComposeUiTest {
        var cerrado = 0
        ficha { cerrado++ }
        onNodeWithText("Nota del artículo").performClick()
        waitForIdle()
        campo().performTextInput("sa")
        esc()
        assertEquals(0, cerrado)
        onNodeWithText(ayuda).assertExists()
        campo().assertTextContains("sa")
    }

    @Test fun `nota de una parte - toma el foco y Esc la cierra sin cerrar la ficha`() = runComposeUiTest {
        var cerrado = 0
        ficha { cerrado++ }
        onNodeWithText("Cebolla").performClick()
        onNodeWithText("+ Nota").performClick()
        waitForIdle()
        onNodeWithText("Nota de Cebolla").assertExists()
        campo().assertIsFocused()
        esc()
        assertEquals(0, cerrado)
        onNodeWithText("Nota de Cebolla").assertDoesNotExist()
    }

    @Test fun `Enter no mete un salto en la nota`() = runComposeUiTest {
        ficha {}
        onNodeWithText("Nota del artículo").performClick()
        waitForIdle()
        campo().performTextInput("a")
        campo().performKeyInput { pressKey(Key.Enter) }
        campo().performTextInput("b")
        campo().performKeyInput { pressKey(Key.NumPadEnter) }
        waitForIdle()
        assertEquals("ab", textoDelCampo())
        onNodeWithText("2/200").assertExists()
        // Un salto pegado tampoco entra.
        campo().performTextInput("\nc")
        waitForIdle()
        assertEquals("abc", textoDelCampo())
    }

    @Test fun `una nota rapida se prende y se apaga`() = runComposeUiTest {
        ficha {}
        onNodeWithText("Nota del artículo").performClick()
        onNodeWithText("Sin cebolla").performClick()
        onNodeWithText("Sin hielo").performClick()
        onNodeWithText("Sin cebolla").performClick()
        waitForIdle()
        campo().assertTextContains("Sin hielo")
        onNodeWithText("9/200").assertExists()
    }

    // MARK: - Detalle de un artículo del carrito (Mostrador)

    private fun ComposeUiTest.carrito(onCerrar: () -> Unit) = setContent {
        ventana {
            CartItemDetailPanel(
                item = CartItem(type = CartItemType.ProductItem("flauta"), name = "Flauta de pollo", unitPrice = 3_500),
                isTablet = true,
                onUpdateQuantity = {}, onUpdateNote = {}, onToggleCortesia = { _, _ -> },
                onUpdatePriceAdjustment = {}, onDelete = {}, onDismiss = onCerrar,
            )
        }
    }

    @Test fun `Esc cierra el detalle del carrito`() = runComposeUiTest {
        var cerrado = 0
        carrito { cerrado++ }
        escSinFoco()
        assertEquals(1, cerrado)
    }

    @Test fun `carrito - la nota toma el foco y Esc la cierra sin cerrar el detalle`() = runComposeUiTest {
        var cerrado = 0
        carrito { cerrado++ }
        onNodeWithText("Nota del artículo").performClick()
        waitForIdle()
        campo().assertIsFocused()
        esc()
        assertEquals(0, cerrado)
        onNodeWithText(ayuda).assertDoesNotExist()
        escSinFoco()
        assertEquals(1, cerrado)
    }

    // MARK: - Nota de una línea de mesa sin enviar (Mesas)

    private fun ComposeUiTest.notaDeMesa(nota: String = "", onCerrar: () -> Unit) = setContent {
        ventana { NotaDeLineaPendiente(isTablet = true, nota = nota, onGuardar = {}, onCerrar = onCerrar) }
    }

    @Test fun `nota de mesa - toma el foco y Esc la cierra`() = runComposeUiTest {
        var cerrado = 0
        notaDeMesa { cerrado++ }
        waitForIdle()
        campo().assertIsFocused()
        esc()
        assertEquals(1, cerrado)
    }

    @Test fun `nota de mesa - la nota que ya tenia, sin cambios, se cierra con Esc y lo nuevo va al final`() = runComposeUiTest {
        var cerrado = 0
        notaDeMesa(nota = "sin aceite") { cerrado++ }
        waitForIdle()
        campo().performTextInput(" x")
        waitForIdle()
        assertEquals("sin aceite x", textoDelCampo())
        esc()
        assertEquals(0, cerrado, "con texto sin guardar, Esc no cierra")
    }

    @Test fun `nota de mesa - con la nota de antes sin tocar, Esc cierra`() = runComposeUiTest {
        var cerrado = 0
        notaDeMesa(nota = "sin aceite") { cerrado++ }
        waitForIdle()
        esc()
        assertEquals(1, cerrado)
    }

    // MARK: - Doble clic en «Sin recibo» (D39)

    @Test fun `doble clic en Sin recibo - el segundo clic no cae en la venta nueva`() = runComposeUiTest {
        var salidas = 0
        var notas = 0
        var enVentaNueva by mutableStateOf(false)
        setContent {
            ventana {
                if (!enVentaNueva) {
                    PaymentResultScreen(
                        totalCents = 13_700, method = PaymentMethod.CASH, paymentId = "pago-1",
                        onSendWhatsApp = {}, onSendEmail = {}, onPrintReceipt = {},
                        onDone = { salidas++; enVentaNueva = true },
                    )
                } else {
                    // La venta nueva entera responde como «+ Nota»: cualquier clic que le llegue es el que se coló.
                    Box(Modifier.fillMaxSize().clickable { notas++ }) { Text("+ Nota") }
                }
            }
        }
        onNodeWithText("Sin recibo").performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(1, salidas, "el doble clic hizo más de una venta nueva")
        assertEquals(0, notas, "el segundo clic cayó en la venta nueva")
        onNodeWithText("+ Nota").assertExists()
    }

    @Test fun `un clic en Sin recibo sale una sola vez`() = runComposeUiTest {
        var salidas = 0
        setContent {
            ventana {
                PaymentResultScreen(
                    totalCents = 13_700, method = PaymentMethod.CASH, paymentId = "pago-1",
                    onSendWhatsApp = {}, onSendEmail = {}, onPrintReceipt = {}, onDone = { salidas++ },
                )
            }
        }
        onNodeWithText("Sin recibo").performClick()
        waitForIdle()
        assertEquals(1, salidas)
        onNodeWithText("Venta nueva").performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(2, salidas, "doble clic en «Venta nueva» = una sola salida")
    }
}
