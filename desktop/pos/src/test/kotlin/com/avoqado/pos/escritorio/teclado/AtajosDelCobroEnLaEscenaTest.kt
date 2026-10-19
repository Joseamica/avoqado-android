package com.avoqado.pos.escritorio.teclado

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.model.PaymentContext
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentMethodSelectionScreen
import com.avoqado.pos.payment.presentation.PaymentErrorView
import com.avoqado.pos.payment.presentation.PaymentResultScreen
import com.avoqado.pos.payment.presentation.RatingScreen
import com.avoqado.pos.payment.presentation.TerminalSelectionScreen
import com.avoqado.pos.payment.presentation.TipSelectionSheet
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las teclas del cobro en el POS de Windows (founder, 19-oct): F5 efectivo, F4 terminal, Esc regresa, 1-9 elige
 * terminal y Esc en «Venta terminada» es «Venta nueva». Cada tecla hace lo MISMO que el clic en su botón.
 */
@OptIn(ExperimentalTestApi::class)
class AtajosDelCobroEnLaEscenaTest {
    /** Las hojas de propina y «Ya pagó de otra forma» piden el contexto de escritorio (`ImmersiveWindow`), como en Main. */
    @BeforeTest fun contextoDeEscritorio() {
        if (runCatching { Escritorio.contexto }.isSuccess) return
        val carpeta = Files.createTempDirectory("Avoqado POS atajos")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))
    }

    @Composable
    private fun EnWindows(contenido: @Composable () -> Unit) =
        CompositionLocalProvider(LocalEsEscritorio provides true) { MaterialTheme { contenido() } }

    private class Cobro {
        var efectivo: Int? = null
        var metodo: PaymentMethod? = null
        var manual: Any? = null
        var cancelado = 0
    }

    @Composable
    private fun Selector(cobro: Cobro, sinTerminales: Boolean = false) = PaymentMethodSelectionScreen(
        paymentContext = PaymentContext(subtotalCents = 13_700),
        onMethodSelected = { cobro.metodo = it },
        onCashPresetSelected = { cobro.efectivo = it },
        onCashCustomSelected = { cobro.efectivo = it },
        onCancel = { cobro.cancelado++ },
        terminalsUnavailable = sinTerminales,
        onManualMethodSelected = { cobro.manual = it },
    )

    @Test fun `P1 F5 abre Monto recibido con el total, Enter no cobra y F5 otra vez cobra exacto`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        onNodeWithText("F5").assertExists()
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F5)) }
        onNodeWithText("Monto recibido").assertExists()
        // En Windows real la hoja toma el foco sola al abrir (9-oct, «q1-tecleo-sin-clic»); en la escena de prueba el
        // foco de la ventana emergente no llega, así que se le da con «.», que no cambia el monto.
        onNodeWithText(".", useUnmergedTree = true).performClick()
        onNodeWithText("F5 otra vez: exacto · o teclea lo que te dieron").assertExists()
        // Un Enter suelto (la pistola con un código corto o partido) NO cobra el total sugerido (Codex r2 #2).
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertNull(cobro.efectivo) }
        // Con Mayús tampoco (Codex r3 #2): sólo F5 solo.
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F5) } }
        runOnIdle { assertNull(cobro.efectivo) }
        onNode(isFocused()).performKeyInput { pressKey(Key.F5) }
        runOnIdle { assertEquals(13_700, cobro.efectivo) }
    }

    @Test fun `P1 tras F5 lo que se teclea reemplaza al total, Enter no cobra y F5 cobra con cambio`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        runOnIdle { AtajosDeTeclado.atender(Key.F5) }
        // Los mismos dígitos que el teclado físico (`montoConTecla`); el primero borra el total sugerido.
        listOf("2", "0", "0", "0", "0").forEach { onNodeWithText(it, useUnmergedTree = true).performClick() }
        onNodeWithText("Cambio: \$63.00").assertExists()
        // En Windows Enter NUNCA cobra: la pistola termina cada código con Enter (Codex r1-r4, founder 19-oct).
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F5) } }
        runOnIdle { assertNull(cobro.efectivo) }
        // Y el Enter tampoco «pica» el botón «0» que quedó con el foco (sumaba un 0: $2,000.00).
        onNodeWithText("Cambio: \$63.00").assertExists()
        onNode(isFocused()).performKeyInput { pressKey(Key.F5) }
        runOnIdle { assertEquals(20_000, cobro.efectivo) }
    }

    @Test fun `P1 F5 con menos del total no cobra, aunque la cifra llegue pegada a F5`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        runOnIdle { AtajosDeTeclado.atender(Key.F5) }
        onNodeWithText(".", useUnmergedTree = true).performClick()
        // «5» y F5 en el mismo despacho, sin recomponer entre los dos: el monto se mide al momento ($0.05), no con
        // el «alcanza» de la última composición ($137.00) (Codex r4 #3).
        onNode(isFocused()).performKeyInput { pressKey(Key.Five); pressKey(Key.F5) }
        runOnIdle { assertNull(cobro.efectivo) }
        onNodeWithText("Mínimo \$137.00").assertExists()
    }

    @Test fun `fuera de Windows Enter sigue cobrando lo tecleado`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { MaterialTheme { Selector(cobro) } }
        onNodeWithText("Personalizado").performClick()
        listOf("2", "0", "0", "0", "0").forEach { onNodeWithText(it, useUnmergedTree = true).performClick() }
        // Aquí «Personalizado» también conserva el foco en su ventana: el que importa es el «0» de la hoja.
        onNode(isFocused() and hasText("0")).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(20_000, cobro.efectivo) }
    }

    // Un botón con el foco se «pica» con Enter o Espacio, y la pistola termina cada código con Enter (Codex r5): en el
    // cobro de Windows sólo las teclas F y el mouse cobran.
    @Test fun `P1 en Windows Enter y Espacio no pican el monto sugerido con el foco`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        onNodeWithText("\$150").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("\$150").performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        runOnIdle { assertNull(cobro.efectivo) }
        onNodeWithText("\$150").performClick()
        runOnIdle { assertEquals(15_000, cobro.efectivo) }
    }

    @Test fun `P1 en Windows Enter y Espacio no pican Confirmar con el foco`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        runOnIdle { AtajosDeTeclado.atender(Key.F5) }
        onNodeWithText("Confirmar").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Confirmar").performKeyInput { pressKey(Key.Spacebar); pressKey(Key.Enter) }
        runOnIdle { assertNull(cobro.efectivo) }
        onNodeWithText("Monto recibido").assertExists()
    }

    @Test fun `P1 en Windows Enter y Espacio no pican la terminal con el foco`() = runComposeUiTest {
        var elegida: String? = null
        setContent {
            EnWindows {
                TerminalSelectionScreen(
                    terminals = listOf(OnlineTerminal("T-A", "Caja 1"), OnlineTerminal("T-B", "Caja 2")),
                    onTerminalSelected = { elegida = it },
                    onCancel = {},
                    avisoDeOtroCobro = null,
                    onRevisarAviso = {},
                    onEntendidoAviso = {},
                )
            }
        }
        onNodeWithText("Caja 2").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Caja 2").performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        runOnIdle { assertNull(elegida) }
        onNodeWithText("Caja 2").performClick()
        runOnIdle { assertEquals("T-B", elegida) }
    }

    @Test fun `P1 en Windows Enter y Espacio no pican una forma de «Ya pagó de otra forma»`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        onNodeWithText("Ya pagó de otra forma").performClick()
        onNodeWithText("Transferencia").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Transferencia").performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        runOnIdle { assertNull(cobro.manual) }
    }

    @Test fun `P1 en Windows Enter y Espacio no pican Reintentar del error de pago, y Cancelar sí responde`() = runComposeUiTest {
        var reintentos = 0
        var cancelado = 0
        setContent { EnWindows { PaymentErrorView(message = "Sin conexión", onRetry = { reintentos++ }, onCancel = { cancelado++ }) } }
        onNodeWithText("Reintentar").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Reintentar").performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        runOnIdle { assertEquals(0, reintentos) }
        // Los botones que no cobran siguen con el teclado (Codex r6 #2: bloquear la pantalla entera era de más).
        onNodeWithText("Cancelar").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Cancelar").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, cancelado) }
    }

    @Test fun `en Windows Personalizado sigue abriendo con Enter - no cobra`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        onNodeWithText("Personalizado").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Personalizado").performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Monto recibido").assertExists()
        runOnIdle { assertNull(cobro.efectivo) }
    }

    @Test fun `con el clic en Personalizado la hoja sigue abriendo en cero`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        onNodeWithText("Personalizado").performClick()
        onNodeWithText("Mínimo \$137.00").assertExists()
        onNodeWithText("\$0.00").assertExists()
    }

    @Test fun `F4 manda a terminal, y sin terminales no hace nada`() = runComposeUiTest {
        val conTerminal = Cobro()
        setContent { EnWindows { Selector(conTerminal) } }
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F4)) }
        assertEquals(PaymentMethod.CARD, conTerminal.metodo)
    }

    @Test fun `sin terminales F4 no hace nada`() = runComposeUiTest {
        val sinTerminal = Cobro()
        setContent { EnWindows { Selector(sinTerminal, sinTerminales = true) } }
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F4)) }
        assertNull(sinTerminal.metodo)
    }

    @Test fun `Esc es la X del cobro`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.Escape)) }
        assertEquals(1, cobro.cancelado)
    }

    @Test fun `P1 con una hoja abierta encima, F5 F4 y Esc no tocan el cobro de abajo`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        onNodeWithText("Ya pagó de otra forma").performClick()
        onNodeWithText("¿Cómo pagó el cliente?").assertExists()
        runOnIdle {
            assertFalse(AtajosDeTeclado.atender(Key.F5))
            assertFalse(AtajosDeTeclado.atender(Key.F4))
            assertFalse(AtajosDeTeclado.atender(Key.Escape))
        }
        assertNull(cobro.metodo)
        assertEquals(0, cobro.cancelado)
    }

    @Test fun `P1 con la propina encima (sin pantalla del cliente), las teclas del cobro esperan`() = runComposeUiTest {
        val cobro = Cobro()
        setContent {
            EnWindows {
                // Como `PaymentFlowScreen` en `CollectingTip`: el cobro abajo y la hoja de propina encima.
                Selector(cobro)
                TipSelectionSheet(amountCents = 13_700, tipBaseCents = 13_700, tipSuggestions = listOf(10, 15, 20),
                    onTipSelected = {}, onSkip = {}, onDismiss = {})
            }
        }
        runOnIdle {
            assertFalse(AtajosDeTeclado.atender(Key.F5))
            assertFalse(AtajosDeTeclado.atender(Key.F4))
        }
        assertNull(cobro.metodo)
    }

    @Test fun `F4 otra vez elige la primera terminal y Esc cancela - los numeros no hacen nada`() = runComposeUiTest {
        var elegida: String? = null
        var cancelado = 0
        setContent {
            EnWindows {
                TerminalSelectionScreen(
                    terminals = listOf(OnlineTerminal("T-A", "Caja 1"), OnlineTerminal("T-B", "Caja 2")),
                    onTerminalSelected = { elegida = it },
                    onCancel = { cancelado++ },
                    avisoDeOtroCobro = null,
                    onRevisarAviso = {},
                    onEntendidoAviso = {},
                )
            }
        }
        onNodeWithText("F4").assertExists()
        runOnIdle {
            // Un «1» suelto puede venir de un código de la pistola cortado (Codex r1 #2): no manda cobros.
            assertFalse(AtajosDeTeclado.atender(Key.One))
            assertTrue(AtajosDeTeclado.atender(Key.F4))
        }
        assertEquals("T-A", elegida)
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.Escape)) }
        assertEquals(1, cancelado)
    }

    @Test fun `mientras se buscan terminales F4 no hace nada`() = runComposeUiTest {
        var elegida: String? = null
        setContent {
            EnWindows {
                TerminalSelectionScreen(
                    terminals = emptyList(),
                    onTerminalSelected = { elegida = it },
                    onCancel = {},
                    avisoDeOtroCobro = null,
                    onRevisarAviso = {},
                    onEntendidoAviso = {},
                )
            }
        }
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F4)) }
        assertNull(elegida)
    }

    @Test fun `en la calificacion F12 es Omitir y Esc es la X`() = runComposeUiTest {
        var omitidas = 0
        var cerradas = 0
        var calificadas = 0
        setContent {
            EnWindows { RatingScreen(onRatingSubmitted = { calificadas++ }, onSkip = { omitidas++ }, onCancel = { cerradas++ }) }
        }
        onNodeWithText("F12", useUnmergedTree = true).assertExists()
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F12)) }
        assertEquals(1, omitidas)
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.Escape)) }
        assertEquals(1, cerradas)
        assertEquals(0, calificadas)
    }

    @Test fun `en Venta terminada Esc es Venta nueva`() = runComposeUiTest {
        var listas = 0
        setContent { EnWindows { PaymentResultScreen(totalCents = 13_700, method = PaymentMethod.CASH, onDone = { listas++ }) } }
        onNodeWithText("Esc").assertExists()
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.Escape)) }
        assertEquals(1, listas)
    }

    @Test fun `la tecla sale en chiquito dentro del boton Cobrar en Windows`() = runComposeUiTest {
        setContent { EnWindows { PrimaryButton(text = "Cobrar \$137.00", onClick = {}, tecla = "F12") } }
        onNodeWithText("F12", useUnmergedTree = true).assertExists()
    }

    @Test fun `fuera de Windows el boton Cobrar no lleva tecla`() = runComposeUiTest {
        setContent { MaterialTheme { PrimaryButton(text = "Cobrar \$137.00", onClick = {}, tecla = "F12") } }
        onNodeWithText("F12", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `fuera de Windows no hay etiquetas ni atajos en el cobro`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { MaterialTheme { Selector(cobro) } }
        onNodeWithText("F5").assertDoesNotExist()
        onNodeWithText("Esc").assertDoesNotExist()
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F5)) }
        assertNull(cobro.efectivo)
    }
}
