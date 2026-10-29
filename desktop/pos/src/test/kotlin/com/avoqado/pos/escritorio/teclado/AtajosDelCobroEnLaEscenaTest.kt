package com.avoqado.pos.escritorio.teclado

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.foundation.layout.Column
import com.avoqado.pos.designsystem.components.sinTeclasDetrasDelCobro
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.model.PaymentContext
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentMethodSelectionScreen
import com.avoqado.pos.payment.presentation.PaymentErrorView
import com.avoqado.pos.payment.presentation.PaymentProcessingView
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Las teclas del cobro en el POS de Windows (founder, 19-oct): F5 efectivo, F4 terminal, Esc regresa, 1-9 elige
 * terminal y Esc en «Venta terminada» es «Venta nueva». Cada tecla hace lo MISMO que el clic en su botón.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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

    // Pendientes del 19-oct: en Windows cada hoja lleva UNA X (la suya o la de AvoqadoModalBottomSheet, nunca las dos).
    @Test fun `en Windows la hoja de propina lleva una sola X`() = runComposeUiTest {
        setContent {
            EnWindows {
                TipSelectionSheet(amountCents = 13_700, tipBaseCents = 13_700, tipSuggestions = listOf(10, 15, 20),
                    onTipSelected = {}, onSkip = {}, onDismiss = {})
            }
        }
        onAllNodesWithContentDescription("Cerrar").assertCountEquals(1)
    }

    @Test fun `en Windows Monto recibido lleva una sola X`() = runComposeUiTest {
        val cobro = Cobro()
        setContent { EnWindows { Selector(cobro) } }
        runOnIdle { AtajosDeTeclado.atender(Key.F5) }
        onNodeWithText("Monto recibido").assertExists()
        // La X de «¿Cómo paga?», detrás, y la de la hoja. Antes eran 3: la hoja traía dos.
        onAllNodesWithContentDescription("Cerrar").assertCountEquals(2)
    }

    @Test fun `en Windows una hoja sin X propia recibe la de Windows`() = runComposeUiTest {
        setContent { EnWindows { AvoqadoModalBottomSheet(onDismissRequest = {}) { Text("Contenido de la hoja") } } }
        onNodeWithText("Contenido de la hoja").assertExists()
        onAllNodesWithContentDescription("Cerrar").assertCountEquals(1)
    }

    // Pendientes del 19-oct (Codex r1 #1): con el cobro abierto, el producto de atrás que quedó con el foco no se «pica»
    // con Enter ni Espacio (la pistola manda Enter). Las teclas de atajo sí pasan: las atiende la ventana para el cobro.
    @Test fun `P1 con el cobro abierto la venta de atras no recibe Enter, y las teclas F si pasan`() = runComposeUiTest {
        var clics = 0
        var efes = 0
        var cobroAbierto by mutableStateOf(true)
        setContent {
            EnWindows {
                Box(Modifier.sinTeclasDetrasDelCobro { cobroAbierto }) {
                    Button(
                        onClick = { clics++ },
                        modifier = Modifier.onKeyEvent { e -> if (e.key == Key.F5 && e.type == KeyEventType.KeyDown) efes++; false },
                    ) { Text("Café") }
                }
            }
        }
        onNodeWithText("Café").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Café").performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar); pressKey(Key.F5) }
        runOnIdle { assertEquals(0, clics); assertEquals(1, efes) }
        cobroAbierto = false
        onNodeWithText("Café").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, clics) }
    }

    // Codex r2 #2: el candado no atrapa el foco. Tab y las flechas pasan, para llegar con teclado a los botones del cobro.
    @Test fun `con el cobro abierto Tab si mueve el foco fuera de la venta de atras`() = runComposeUiTest {
        setContent {
            EnWindows {
                Column {
                    Box(Modifier.sinTeclasDetrasDelCobro { true }) { Button(onClick = {}) { Text("Café") } }
                    Button(onClick = {}) { Text("Pagar") }
                }
            }
        }
        onNodeWithText("Café").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Café").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Pagar").assertIsFocused()
    }

    // Al registrar un efectivo no se espera a ninguna terminal (iOS dice «Procesando…»).
    @Test fun `la espera de un efectivo no menciona la terminal`() = runComposeUiTest {
        setContent { EnWindows { PaymentProcessingView(esperandoTerminal = false) } }
        onNodeWithText("Procesando pago...").assertExists()
        onNodeWithText("Esperando respuesta de la terminal").assertDoesNotExist()
    }

    @Test fun `la espera de la terminal si la menciona`() = runComposeUiTest {
        setContent { EnWindows { PaymentProcessingView(esperandoTerminal = true) } }
        onNodeWithText("Esperando respuesta de la terminal").assertExists()
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

    /** Mismos módulos y Guice de producción; sólo se sustituye el transporte HTTP, sin sockets de producto. */
    private class R6Entorno : AutoCloseable {
        private val contextoAnterior = Escritorio.contexto
        private val inyectorAnterior = Escritorio.inyector
        private val actividad = ActividadDeEscritorio(Files.createTempDirectory("Avoqado R6 aislado"))
        val peticiones = java.util.concurrent.CopyOnWriteArrayList<String>()
        var alConsultarCuenta: (() -> String)? = null
        private val cliente = okhttp3.OkHttpClient.Builder().addInterceptor { cadena ->
            val r = cadena.request()
            peticiones += "${r.method} ${r.url.encodedPath}"
            val body = when {
                r.url.encodedPath.endsWith("/terminals/online") -> """{"terminals":[{"terminalId":"c123456789012345678901234","name":"Caja"}]}"""
                "/gift-cards/orders/" in r.url.encodedPath -> """{"orderId":"c223456789012345678901234","orderNumber":"n","total":500.0,"paymentStatus":"PENDING","orderStatus":"CONFIRMED","sobres":[{"id":"c323456789012345678901234","ultimos4":"1234","monto":500.0,"saldo":500.0,"status":"${alConsultarCuenta?.invoke() ?: "ACTIVE"}","deliveryChannel":"PRINTED","usada":false,"puedeReenviar":false,"puedeCambiarDestino":false,"puedeRevelar":false}],"cancel":{"allowed":true}}"""
                else -> "{}"
            }
            val respuesta = if ("/gift-cards/orders/" in r.url.encodedPath) """{"success":true,"data":$body}""" else body
            okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("R6 local")
                .header("Content-Type", "application/json")
                .body(respuesta.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val inyector = com.google.inject.Guice.createInjector(
            object : com.google.inject.AbstractModule() {
                override fun configure() {
                    binder().disableCircularProxies()
                    binder().requireAtInjectOnConstructors()
                    bind(android.content.Context::class.java).toInstance(actividad)
                    bind(com.google.inject.Key.get(android.content.Context::class.java,
                        dagger.hilt.android.qualifiers.ApplicationContext::class.java)).toInstance(actividad)
                }
            },
            com.avoqado.escritorio.ModulosDeDagger(*Inyector.modulosDeLaApp) { if (it is okhttp3.OkHttpClient) cliente else it },
        )
        val storage = inyector.getInstance(com.avoqado.pos.core.data.local.SecureStorage::class.java)
        val red = inyector.getInstance(com.avoqado.pos.core.util.ConnectivityMonitor::class.java)
        val owner = object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = androidx.lifecycle.ViewModelStore() }
        private var app: com.avoqado.pos.auth.presentation.AppState? = null
        init { Escritorio.instalar(actividad, inyector); ruta(true); servidor(true) }

        @Suppress("UNCHECKED_CAST")
        private fun bandera(nombre: String, valor: Boolean) {
            (red.javaClass.getDeclaredField(nombre).apply { isAccessible = true }.get(red) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = valor
        }
        fun ruta(valor: Boolean) = bandera("_isConnected", valor)
        fun servidor(valor: Boolean) = bandera("_isServerReachable", valor)
        fun sesion(venue: String = "c423456789012345678901234") {
            storage.venueId = venue; storage.userId = "c523456789012345678901234"; storage.accessToken = "fixture-r6"
        }
        @Suppress("UNCHECKED_CAST")
        fun modelo(): com.avoqado.pos.payment.presentation.PaymentFlowViewModel {
            sesion()
            val ajustes = inyector.getInstance(com.avoqado.pos.tpvsettings.data.TpvSettingsRepository::class.java)
            (ajustes.javaClass.getDeclaredField("_settings").apply { isAccessible = true }.get(ajustes)
                as kotlinx.coroutines.flow.MutableStateFlow<com.avoqado.pos.tpvsettings.data.TpvSettings>).value =
                com.avoqado.pos.tpvsettings.data.TpvSettings(showReviewScreen = false, showTipScreen = false)
            return Escritorio.crearViewModel(com.avoqado.pos.payment.presentation.PaymentFlowViewModel::class.java,
                androidx.lifecycle.SavedStateHandle()).also { owner.viewModelStore.put("cobro", it) }
        }
        fun estadoDeLaApp(): com.avoqado.pos.auth.presentation.AppState =
            inyector.getInstance(com.avoqado.pos.auth.presentation.AppState::class.java).also { vm ->
                app = vm; owner.viewModelStore.put("app", vm)
                // Vecinos de I/O fuera del caso: no arrancar LAN, cocina ni aparatos desde una prueba de sesión.
                listOf("transporteLan", "lanHubService", "preparationDelivery", "replayDeEntregasKds",
                    "wasteSyncCoordinator", "deviceCapabilitySyncCoordinator", "customerDisplayState").forEach {
                    vm.javaClass.getDeclaredField(it).apply { isAccessible = true }.set(vm, null)
                }
            }
        override fun close() {
            app?.onLogout()
            app?.let { vm -> kotlinx.coroutines.runBlocking {
                check(kotlinx.coroutines.withTimeoutOrNull(5000) { vm.isLoggedIn.first { !it } } == false) {
                    "La sesión de la fixture no terminó su limpieza"
                }
            } }
            owner.viewModelStore.clear()
            inyector.getInstance(com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta::class.java).onSessionChanged(false)
            Escritorio.instalar(contextoAnterior, inyectorAnterior)
            cliente.dispatcher.executorService.shutdown()
            cliente.connectionPool.evictAll()
        }
    }

    private fun esperarMetodos(m: com.avoqado.pos.payment.presentation.PaymentFlowViewModel, habilitados: Boolean) {
        val observados = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(2000) { m.metodosHabilitados.first { it == habilitados } }
        }
        assertEquals(habilitados, observados, "El motor debe publicar el cambio de red de la cuenta")
    }

    @Composable
    private fun PantallaR6(e: R6Entorno, m: com.avoqado.pos.payment.presentation.PaymentFlowViewModel, cobro: Cobro, tarjetas: Boolean = true) {
        CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides e.owner) {
            EnWindows {
                com.avoqado.pos.payment.presentation.PaymentFlowScreen(
                    cartState = com.avoqado.pos.pos.presentation.cart.CartState(items = listOf(
                        com.avoqado.pos.pos.data.model.CartItem(type = com.avoqado.pos.pos.data.model.CartItemType.CustomAmount,
                            name = "Tarjetas de regalo", unitPrice = 13_700))),
                    onPaymentCommitted = { error("No debe cobrar en este recorrido") }, onDone = { error("No debe terminar una venta") },
                    onCancel = { cobro.cancelado++ }, onPreviousChargeResolved = { error("Sin otro cobro") },
                    resumeOrderId = "c623456789012345678901234", viewModel = m, cuentaDeTarjetas = tarjetas,
                )
            }
        }
    }

    @Test fun `R6 Guice inyecta el singleton y el callback final despierta sin transicion de red`() = kotlinx.coroutines.runBlocking {
        R6Entorno().use { e ->
            val app = e.estadoDeLaApp()
            val resolver = e.inyector.getInstance(com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta::class.java)
            kotlin.test.assertSame(resolver, app.solicitudesDeTarjeta)
            val store = e.inyector.getInstance(com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore::class.java)
            assertTrue(store.guardar(com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta(tipo = "GIFT_CARD_SALE", llave = "c723456789012345678901234",
                venueId = "c423456789012345678901234", creadaEn = 1, orderId = "c223456789012345678901234", cuerpo = "{}")))
            e.sesion(); app.onLoginSuccess()
            val listas = kotlinx.coroutines.withTimeoutOrNull(2000) {
                resolver.ventasListas.first { it.isNotEmpty() }
            }
            assertEquals(listOf("c723456789012345678901234"), listas)
            assertEquals(1, e.peticiones.count { "/gift-cards/orders/" in it })
        }
    }

    @Test fun `R6 el cobro real observa ambas banderas y bloquea filas F4 F5 manteniendo X y Esc`() = runComposeUiTest {
        R6Entorno().use { e ->
            val m = e.modelo(); val cobro = Cobro()
            setContent { PantallaR6(e, m, cobro) }
            onNodeWithText("\$150").assertIsEnabled()
            for (bandera in listOf<(Boolean) -> Unit>(e::ruta, e::servidor)) {
                runOnIdle { bandera(false) }
                esperarMetodos(m, false)
                listOf("\$150", "Personalizado", "Cobrar con terminal", "Ya pagó de otra forma").forEach {
                    onNodeWithText(it).assertIsNotEnabled()
                }
                onNodeWithText(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.CUENTA_SIN_RED).assertExists()
                runOnIdle {
                    assertFalse(AtajosDeTeclado.atender(Key.F4)); assertFalse(AtajosDeTeclado.atender(Key.F5))
                }
                onNodeWithText("Monto recibido").assertDoesNotExist()
                runOnIdle { bandera(true) }
                esperarMetodos(m, true)
                onNodeWithText("\$150").assertIsEnabled()
            }
            runOnIdle { e.ruta(false); assertTrue(AtajosDeTeclado.atender(Key.Escape)) }
            onNodeWithContentDescription("Cerrar").performClick()
            runOnIdle { assertEquals(2, cobro.cancelado); assertFalse(e.peticiones.any { it.startsWith("POST ") }) }
        }
    }

    @Test fun `R6 al caer red se cierran las hojas abiertas y no vuelven solas ni mandan pagos`() = runComposeUiTest {
        R6Entorno().use { e ->
            val m = e.modelo(); val cobro = Cobro()
            setContent { PantallaR6(e, m, cobro) }
            onNodeWithText("Personalizado").performClick()
            onNodeWithText("Monto recibido").assertExists()
            runOnIdle { e.ruta(false) }
            esperarMetodos(m, false)
            onNodeWithText("Monto recibido").assertDoesNotExist()
            runOnIdle { e.ruta(true) }
            esperarMetodos(m, true)
            onNodeWithText("Monto recibido").assertDoesNotExist()
            onNodeWithText("Ya pagó de otra forma").performClick()
            onNodeWithText("¿Cómo pagó el cliente?").assertExists()
            runOnIdle { e.servidor(false) }
            esperarMetodos(m, false)
            onNodeWithText("¿Cómo pagó el cliente?").assertDoesNotExist()
            runOnIdle { e.servidor(true) }
            esperarMetodos(m, true)
            onNodeWithText("¿Cómo pagó el cliente?").assertDoesNotExist()
            assertFalse(e.peticiones.any { it.startsWith("POST ") })
        }
    }

    @Test fun `R6 el preset bloqueado usa el gris del tema en el texto real`() = runComposeUiTest {
        var gris = androidx.compose.ui.graphics.Color.Unspecified
        setContent {
            EnWindows {
                gris = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                PaymentMethodSelectionScreen(paymentContext = PaymentContext(subtotalCents = 13_700), onMethodSelected = {},
                    onCancel = {}, metodosHabilitados = false,
                    motivoDeshabilitado = com.avoqado.pos.giftcards.domain.TextosDeTarjetas.CUENTA_SIN_RED)
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        onNodeWithText("\$150").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(gris, layouts.single().layoutInput.style.color)
    }

    @Test fun `R6 la venta ordinaria conserva efectivo con cualquiera de las banderas sin red`() = runComposeUiTest {
        R6Entorno().use { e ->
            val m = e.modelo(); val cobro = Cobro()
            e.ruta(false); e.servidor(false)
            setContent { PantallaR6(e, m, cobro, tarjetas = false) }
            onNodeWithText("\$150").assertIsEnabled()
            onNodeWithText("Personalizado").assertIsEnabled()
            onNodeWithText(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.CUENTA_SIN_RED).assertDoesNotExist()
            runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F5)) }
            onNodeWithText("Monto recibido").assertExists()
        }
    }

    @Test fun `R6 una respuesta de la sesion anterior no repuebla las listas tras invalidar y volver a entrar`() = kotlinx.coroutines.runBlocking {
        R6Entorno().use { e ->
            e.sesion()
            val resolver = e.inyector.getInstance(com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta::class.java)
            val store = e.inyector.getInstance(com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore::class.java)
            assertTrue(store.guardar(com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta(tipo = "GIFT_CARD_SALE", llave = "c723456789012345678901234",
                venueId = "c423456789012345678901234", creadaEn = 1, orderId = "c223456789012345678901234", cuerpo = "{}")))
            val control = e.inyector.getInstance(com.avoqado.pos.giftcards.data.GiftCardsRepository::class.java)
                .cuenta("c423456789012345678901234", "c223456789012345678901234")
            assertTrue(control is com.avoqado.pos.giftcards.data.Respuesta.Ok, "El transporte de la fixture debe llegar al parser real")
            val esperando = java.util.concurrent.CountDownLatch(1)
            val continuar = java.util.concurrent.CountDownLatch(1)
            val consultas = java.util.concurrent.atomic.AtomicInteger()
            e.alConsultarCuenta = {
                if (consultas.getAndIncrement() == 0) {
                    esperando.countDown()
                    check(continuar.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    "ACTIVE"
                } else "PENDING"
            }
            val primera = async(kotlinx.coroutines.Dispatchers.IO) { resolver.resolverAhora() }
            try {
                assertTrue(esperando.await(5, java.util.concurrent.TimeUnit.SECONDS))
                resolver.onSessionChanged(false)
                resolver.onSessionChanged(true)
                continuar.countDown()
                primera.await()
                assertTrue(resolver.ventasListas.value.isEmpty(), "La respuesta vieja no pertenece a la sesión nueva")
            } finally { continuar.countDown() }
        }
    }
    private class ConsultaR3(e: R6Entorno) : AutoCloseable {
        val cuerpos = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val cliente = okhttp3.OkHttpClient.Builder().addInterceptor { cadena ->
            val req = cadena.request()
            val buffer = okio.Buffer(); req.body?.writeTo(buffer); cuerpos += buffer.readUtf8()
            okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixtureR3")
                .body("""{"success":true,"data":{"usableBalance":0,"cards":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val vm: com.avoqado.pos.giftcards.presentation.IdentificarTarjetaViewModel
        init {
            e.sesion(); e.storage.venuePermissions = listOf("giftCards:read")
            e.storage.guardarAjustesDeTarjetas(e.storage.venueId!!,
                """{"enabled":true,"suggestedAmounts":[],"minAmount":100,"maxAmount":10000,"sale":{"sellable":true},"capacidades":["PAGE_TOKEN"]}""")
            vm = com.avoqado.pos.giftcards.presentation.IdentificarTarjetaViewModel(
                com.avoqado.pos.giftcards.data.GiftCardsRepository(cliente,e.storage), e.storage,e.red,
                com.avoqado.pos.pos.data.LectorHidBus(),com.avoqado.pos.core.domain.RoleManager(e.storage))
        }
        fun esperarConsulta() = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(1500) { while(cuerpos.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("""{"code":"abcd"}"""),cuerpos.toList(),"El campo debe consultar UNA vez, sin otra entrada del llamador")
        }
        override fun close() { vm.cerrar();cliente.dispatcher.executorService.shutdown();cliente.connectionPool.evictAll() }
    }
    @Test fun `R3 campo real de identificar conserva tecleo y Enter consulta una vez`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaR3(e).use { q ->
            setContent { EnWindows {
                com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                    e.storage.venueId!!,null,onIdentificada={},onCerrar={},viewModel=q.vm)
            } }
            onNode(hasSetTextAction()).performTextInput("abcd")
            runOnIdle { assertEquals("abcd",q.vm.codigo.value);assertTrue(q.cuerpos.isEmpty()) }
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
            q.esperarConsulta()
        } }
    }
    @Test fun `R3 Listo del campo real de identificar consulta una vez`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaR3(e).use { q ->
            setContent { EnWindows {
                com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                    e.storage.venueId!!,null,onIdentificada={},onCerrar={},viewModel=q.vm)
            } }
            onNode(hasSetTextAction()).performTextInput("abcd")
            onNode(hasSetTextAction()).assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ImeAction,androidx.compose.ui.text.input.ImeAction.Done))
            onNode(hasSetTextAction()).performImeAction()
            q.esperarConsulta()
        } }
    }

    private class ConsultaG(val e: R6Entorno, capacidades: Set<String>) : AutoCloseable {
        val apertura = ajustes(capacidades)
        @Volatile private var actuales: com.avoqado.pos.giftcards.data.AjustesDeTarjetas? = apertura
        val consultas = java.util.concurrent.CopyOnWriteArrayList<String>()
        val lector = com.avoqado.pos.pos.data.LectorHidBus()
        private val cliente = okhttp3.OkHttpClient.Builder().addInterceptor { cadena ->
            val req = cadena.request()
            val settings = req.url.encodedPath.endsWith("/settings")
            val vigente = actuales
            val datos = if (settings) vigente?.let {
                kotlinx.serialization.json.Json.encodeToString(com.avoqado.pos.giftcards.data.AjustesDeTarjetas.serializer(), it)
            } else {
                consultas += req.url.encodedPath
                """{"usableBalance":0,"cards":[]}"""
            }
            okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (settings && vigente == null) 404 else 200).message("fixtureG")
                .body((datos?.let { """{"success":true,"data":$it}""" } ?: """{"message":"Sin soporte"}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo: com.avoqado.pos.giftcards.data.GiftCardsRepository
        val vm: com.avoqado.pos.giftcards.presentation.IdentificarTarjetaViewModel
        private val store = androidx.lifecycle.ViewModelStore()
        init {
            e.sesion(); e.storage.venuePermissions = listOf("giftCards:read")
            guardarApertura(apertura)
            repo = com.avoqado.pos.giftcards.data.GiftCardsRepository(cliente, e.storage)
            vm = com.avoqado.pos.giftcards.presentation.IdentificarTarjetaViewModel(repo, e.storage, e.red,
                lector, com.avoqado.pos.core.domain.RoleManager(e.storage))
            store.put("identificarG", vm)
        }
        fun ajustes(caps: Set<String>) = com.avoqado.pos.giftcards.data.AjustesDeTarjetas(true, emptyList(), 100.0, 10000.0,
            sale = com.avoqado.pos.giftcards.data.VentaPermitida(true), capacidades = caps)
        fun guardarApertura(a: com.avoqado.pos.giftcards.data.AjustesDeTarjetas) {
            e.storage.guardarAjustesDeTarjetas(e.storage.venueId!!,
                kotlinx.serialization.json.Json.encodeToString(com.avoqado.pos.giftcards.data.AjustesDeTarjetas.serializer(), a))
        }
        fun refrescar(caps: Set<String>?) = kotlinx.coroutines.runBlocking {
            actuales = caps?.let(::ajustes)
            repo.refrescarAjustes(e.storage.venueId!!)
        }
        fun escanear() = lector.recibirDelLectorIntegrado("https://pay.avoqado.io/tarjeta/Xk3pQ9vLabcdefghij12")
        fun recibioEscaneo() = vm.estado.value is com.avoqado.pos.giftcards.presentation.EstadoDeIdentificacion.Saldo ||
            vm.estado.value == com.avoqado.pos.giftcards.presentation.EstadoDeIdentificacion.Desconocido
        override fun close() { vm.cerrar(); store.clear(); cliente.dispatcher.executorService.shutdown(); cliente.connectionPool.evictAll() }
    }

    @Test fun `union G hoja real no amplia PAGE_TOKEN con refresco y props nuevos`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA")).use { q ->
            var apertura by mutableStateOf(q.apertura)
            setContent { EnWindows { com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                e.storage.venueId!!, apertura, onIdentificada = {}, onCerrar = {}, viewModel = q.vm) } }
            waitForIdle()
            q.refrescar(setOf("ENTREGA", "PAGE_TOKEN"))
            runOnIdle { apertura = q.ajustes(setOf("ENTREGA", "PAGE_TOKEN")) }
            waitForIdle()
            runOnIdle { q.escanear() }
            waitUntil(timeoutMillis = 5000) { q.recibioEscaneo() }
            onNodeWithText("Producto no encontrado").assertExists()
            runOnIdle { assertTrue(q.consultas.isEmpty(), "Una recomposición no autoriza lookup nuevo") }
        } }
    }

    @Test fun `union G hoja real sigue angostando al quitar PAGE_TOKEN`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA", "PAGE_TOKEN")).use { q ->
            setContent { EnWindows { com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                e.storage.venueId!!, q.apertura, onIdentificada = {}, onCerrar = {}, viewModel = q.vm) } }
            waitForIdle(); q.refrescar(setOf("ENTREGA")); waitForIdle()
            runOnIdle { q.escanear() }
            waitUntil(timeoutMillis = 5000) { q.recibioEscaneo() }
            onNodeWithText("Producto no encontrado").assertExists()
            runOnIdle { assertTrue(q.consultas.isEmpty()) }
        } }
    }

    private fun hojaGSeCierra(caps: Set<String>?) = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA", "PAGE_TOKEN")).use { q ->
            var cierres = 0
            setContent { EnWindows { com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                e.storage.venueId!!, q.apertura, onIdentificada = {}, onCerrar = { cierres++ }, viewModel = q.vm) } }
            waitForIdle(); q.refrescar(caps)
            waitUntil(timeoutMillis = 5000) { cierres == 1 }
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            runOnIdle { q.escanear() }
            waitForIdle()
            runOnIdle { assertFalse(q.vm.contextoVigente()); assertTrue(q.consultas.isEmpty()); assertEquals(1, cierres) }
        } }
    }
    @Test fun `union G hoja real cierra con capacidades vacias`() = hojaGSeCierra(emptySet())
    @Test fun `union G hoja real cierra al perder entrada 404`() = hojaGSeCierra(null)

    @Test fun `union G recreacion de hoja conserva el argumento de apertura`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA")).use { q ->
            var recreacion by mutableStateOf(0)
            setContent { EnWindows { androidx.compose.runtime.key(recreacion) {
                com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                    e.storage.venueId!!, q.apertura, onIdentificada = {}, onCerrar = {}, viewModel = q.vm)
            } } }
            waitForIdle(); q.refrescar(setOf("ENTREGA", "PAGE_TOKEN")); waitForIdle()
            runOnIdle { recreacion++ }; waitForIdle()
            runOnIdle { q.escanear() }
            waitUntil(timeoutMillis = 5000) { q.recibioEscaneo() }
            onNodeWithText("Producto no encontrado").assertExists()
            runOnIdle { assertTrue(q.consultas.isEmpty()) }
        } }
    }

    @Test fun `union G cerrar y abrir expresamente permite capacidades de la nueva apertura`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA")).use { q ->
            var visible by mutableStateOf(true)
            var apertura by mutableStateOf(q.apertura)
            setContent { EnWindows { if (visible) com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                e.storage.venueId!!, apertura, onIdentificada = {}, onCerrar = {}, viewModel = q.vm) } }
            waitForIdle(); q.refrescar(setOf("ENTREGA", "PAGE_TOKEN")); waitForIdle()
            runOnIdle { visible = false }; waitForIdle()
            runOnIdle { apertura = q.ajustes(setOf("ENTREGA", "PAGE_TOKEN")); visible = true }; waitForIdle()
            runOnIdle { q.escanear() }
            waitUntil(timeoutMillis = 5000) { q.recibioEscaneo() }
            runOnIdle {
                assertTrue(q.vm.estado.value is com.avoqado.pos.giftcards.presentation.EstadoDeIdentificacion.Saldo)
                assertEquals(1, q.consultas.size)
                assertTrue(q.consultas.single().endsWith("/lookup"))
            }
        } }
    }

    @Test fun `union G cambiar negocio usa limite propio y no lo amplia en su refresco`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA", "PAGE_TOKEN")).use { q ->
            var venue by mutableStateOf(e.storage.venueId!!)
            var apertura by mutableStateOf(q.apertura)
            setContent { EnWindows { com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                venue, apertura, onIdentificada = {}, onCerrar = {}, viewModel = q.vm) } }
            waitForIdle()
            runOnIdle {
                e.sesion("c823456789012345678901234")
                apertura = q.ajustes(setOf("ENTREGA")); q.guardarApertura(apertura); venue = e.storage.venueId!!
            }
            waitForIdle(); q.refrescar(setOf("ENTREGA", "PAGE_TOKEN")); waitForIdle()
            runOnIdle { q.escanear() }
            waitUntil(timeoutMillis = 5000) { q.recibioEscaneo() }
            onNodeWithText("Producto no encontrado").assertExists()
            runOnIdle { assertTrue(q.consultas.isEmpty()) }
        } }
    }
    @Test fun `union G argumento inicial amplio no vence el limite vigente al abrir`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA")).use { q ->
            setContent { EnWindows { com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                e.storage.venueId!!, q.ajustes(setOf("ENTREGA", "PAGE_TOKEN")),
                onIdentificada = {}, onCerrar = {}, viewModel = q.vm) } }
            waitForIdle(); q.refrescar(setOf("ENTREGA", "PAGE_TOKEN")); waitForIdle()
            runOnIdle { q.escanear() }
            waitUntil(timeoutMillis = 5000) { q.recibioEscaneo() }
            onNodeWithText("Producto no encontrado").assertExists()
            runOnIdle { assertTrue(q.consultas.isEmpty()) }
        } }
    }
    @Test fun `union G la hoja tampoco agrega el modo Telefono durante la apertura`() = runComposeUiTest {
        R6Entorno().use { e -> ConsultaG(e, setOf("ENTREGA")).use { q ->
            setContent { EnWindows { com.avoqado.pos.giftcards.presentation.IdentificarTarjetaSheet(
                e.storage.venueId!!, q.apertura, onIdentificada = {}, onCerrar = {}, viewModel = q.vm) } }
            waitForIdle(); q.refrescar(setOf("ENTREGA", "TELEFONO")); waitForIdle()
            onNodeWithText(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.TELEFONO).assertDoesNotExist()
            runOnIdle { assertTrue(q.consultas.isEmpty()) }
        } }
    }
}
