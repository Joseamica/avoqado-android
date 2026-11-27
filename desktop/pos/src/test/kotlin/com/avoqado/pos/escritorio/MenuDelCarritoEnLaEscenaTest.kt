package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartPanelView
import com.avoqado.pos.pos.presentation.cart.CartState
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D31 (hz:be99f328:D31): el menú «⋯» del carrito de Cobrar (Mostrador; ANDROID y WINDOWS comparten este carrito) ofrecía
 * «Canjear recompensa» y el toque sólo cerraba el menú. El canje de puntos de Mesas trabaja sobre una orden que ya existe en
 * el SERVIDOR; la venta de Mostrador se crea hasta cobrar y el SERVIDOR no acepta puntos al crearla. Mientras eso no exista,
 * la opción no se ofrece (regla del controlador, 25-nov; docs/audit/2026-11-25-defectos-lealtad-referidos/pos/promesa.md).
 *
 * D39 (atajo, founder 26-nov): vuelve como «Usar puntos», ahora con efecto: manda la venta a «Pagar después» y abre esa cuenta en
 * Pedidos con «Recompensas» (d39/promesa-atajo.md). Sin lealtad en el plan no se ofrece.
 */
@OptIn(ExperimentalTestApi::class)
class MenuDelCarritoEnLaEscenaTest {
    private val folder = Files.createTempDirectory("menu-del-carrito")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    /**
     * La hoja llama `ImmersiveWindow`, que pide el contexto de escritorio: se instala una vez si no está, igual que
     * `HojaConXEnLaEscenaTest` (es el patrón de la casa; ninguna prueba lo desinstala).
     */
    @BeforeTest fun contextoDeEscritorio() {
        if (runCatching { Escritorio.contexto }.isSuccess) return
        val carpeta = Files.createTempDirectory("Avoqado POS menu del carrito")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))
    }

    private class Toques { var importe = 0; var tarjeta = 0; var vaciar = 0; var puntos = 0 }

    /** [enWindows]: la variante de Windows de la hoja (con su X) o la de Android; el menú es el mismo en las dos. */
    private fun ComposeUiTest.abrirElMenu(t: Toques, enWindows: Boolean, conLealtad: Boolean = false) {
        setContent {
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalView provides View(context), LocalEsEscritorio provides enWindows,
            ) {
                AvoqadoTheme {
                    Box(Modifier.size(width = 480.dp, height = 700.dp)) {
                        CartPanelView(
                            cartState = CartState(
                                items = listOf(CartItem(id = "agua", type = CartItemType.CustomAmount, name = "Agua", unitPrice = 2_500)),
                            ),
                            onCharge = {},
                            onClearCart = { t.vaciar++ },
                            onAddCustomAmount = { t.importe++ },
                            onTarjetaDeRegalo = { t.tarjeta++ },
                            onUsarPuntos = if (conLealtad) ({ t.puntos++ }) else null,
                            onDiscountTap = {},
                            onCustomerRemove = {},
                            onSplitPayment = {},
                            onRemoveStampReward = {},
                            avisoDescuentoDelCliente = null,
                            onQuitarDescuentoDelCliente = {},
                            onAccionDescuentoDelCliente = {},
                        )
                    }
                }
            }
        }
        onNodeWithContentDescription("Mas opciones").performClick()
        waitForIdle()
    }

    @Test fun `P1 en Windows el menu del carrito no ofrece Canjear recompensa`() = runComposeUiTest {
        abrirElMenu(Toques(), enWindows = true)
        onAllNodesWithText("Canjear recompensa").assertCountEquals(0)
    }

    @Test fun `P1 en Android el menu del carrito no ofrece Canjear recompensa`() = runComposeUiTest {
        abrirElMenu(Toques(), enWindows = false)
        onAllNodesWithText("Canjear recompensa").assertCountEquals(0)
    }

    @Test fun `D39 en Windows Usar puntos se ofrece y llega a su accion`() = runComposeUiTest { usarPuntos(enWindows = true) }

    @Test fun `D39 en Android Usar puntos se ofrece y llega a su accion`() = runComposeUiTest { usarPuntos(enWindows = false) }

    @Test fun `D39 sin lealtad en el plan no se ofrece Usar puntos`() = runComposeUiTest {
        abrirElMenu(Toques(), enWindows = true, conLealtad = false)
        onAllNodesWithText(com.avoqado.pos.pos.presentation.cart.USAR_PUNTOS).assertCountEquals(0)
    }

    private fun ComposeUiTest.usarPuntos(enWindows: Boolean) {
        val t = Toques()
        abrirElMenu(t, enWindows, conLealtad = true)
        onAllNodesWithText("Canjear recompensa").assertCountEquals(0)
        onNodeWithText(com.avoqado.pos.pos.presentation.cart.USAR_PUNTOS).performClick()
        waitForIdle()
        assertEquals(1, t.puntos, "«Usar puntos» tiene que llegar a su acción")
    }

    @Test fun `P2 en Windows las otras tres opciones siguen y llegan a su accion`() = runComposeUiTest { lasOtrasTres(enWindows = true) }

    @Test fun `P2 en Android las otras tres opciones siguen y llegan a su accion`() = runComposeUiTest { lasOtrasTres(enWindows = false) }

    private fun ComposeUiTest.lasOtrasTres(enWindows: Boolean) {
        val t = Toques()
        abrirElMenu(t, enWindows)
        onNodeWithText("Vaciar carrito").performClick()
        waitForIdle()
        assertEquals(1, t.vaciar, "«Vaciar carrito» tiene que llegar a su acción")
        abrirDeNuevo()
        onNodeWithText("Agregar importe personalizado").performClick()
        waitForIdle()
        assertEquals(1, t.importe, "«Agregar importe personalizado» tiene que llegar a su acción")
        abrirDeNuevo()
        onNodeWithText(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.MOSAICO).performClick()
        waitForIdle()
        assertEquals(1, t.tarjeta, "la opción de tarjetas de regalo tiene que llegar a su acción")
    }

    private fun ComposeUiTest.abrirDeNuevo() {
        onNodeWithContentDescription("Mas opciones").performClick()
        waitForIdle()
    }
}
