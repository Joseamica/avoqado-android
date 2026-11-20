package com.avoqado.pos.escritorio.tactil

import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.components.BotonDeMasAcciones
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.clicDerecho
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartPanelView
import com.avoqado.pos.pos.presentation.cart.CartState
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D9 (founder, 20-nov): el POS de Windows se usa con dedo, mouse y teclado A LA VEZ. Nunca se quita el gesto del dedo; se
 * le agrega el camino del mouse: el clic derecho hace lo mismo que mantener presionado, y lo que sólo salía deslizando o
 * manteniendo presionado tiene un botón visible (sólo en Windows).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class ClicDerechoEnLaEscenaTest {
    private val folder = Files.createTempDirectory("clic-derecho-scene")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private class Cuenta { var toques = 0; var menus = 0 }

    /** La misma forma que las filas de Artículos y las mesas: `clicDerecho` por fuera del `combinedClickable`. */
    @Composable
    private fun Fila(c: Cuenta) {
        Box(
            Modifier.size(200.dp, 60.dp).testTag("fila")
                .clicDerecho { c.menus++ }
                .combinedClickable(onClick = { c.toques++ }, onLongClick = { c.menus++ }),
        ) { Text("Mesa 4") }
    }

    @Test fun `a - clic derecho abre el menu y no abre la mesa`() = runComposeUiTest {
        val c = Cuenta()
        setContent { Fila(c) }
        onNodeWithTag("fila").performMouseInput { rightClick() }
        waitForIdle()
        assertEquals(1, c.menus, "el clic derecho tiene que abrir el menú")
        assertEquals(0, c.toques, "el clic derecho no puede abrir además la mesa")
    }

    @Test fun `b - clic izquierdo sigue siendo un toque`() = runComposeUiTest {
        val c = Cuenta()
        setContent { Fila(c) }
        onNodeWithTag("fila").performMouseInput { click() }
        waitForIdle()
        assertEquals(1, c.toques)
        assertEquals(0, c.menus)
    }

    @Test fun `c - tras un clic derecho el izquierdo vuelve a funcionar`() = runComposeUiTest {
        val c = Cuenta()
        setContent { Fila(c) }
        onNodeWithTag("fila").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithTag("fila").performMouseInput { click() }
        waitForIdle()
        assertEquals(1, c.menus)
        assertEquals(1, c.toques)
    }

    /** La pregunta de la tarjeta: «¿mantener apretado el botón del mouse ya dispara los menús?». Sí (Compose de escritorio). */
    @Test fun `d - mantener apretado el boton izquierdo del mouse tambien abre el menu`() = runComposeUiTest {
        val c = Cuenta()
        setContent { Fila(c) }
        onNodeWithTag("fila").performMouseInput { press(); advanceEventTime(1_000); release() }
        waitForIdle()
        assertEquals(1, c.menus)
        assertEquals(0, c.toques)
    }

    @Test fun `e - el dedo sigue igual - mantener presionado abre el menu`() = runComposeUiTest {
        val c = Cuenta()
        setContent { Fila(c) }
        onNodeWithTag("fila").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(1, c.menus)
        assertEquals(0, c.toques)
    }

    @Test fun `f - el boton de mas acciones sale en Windows y no en Android`() = runComposeUiTest {
        var abiertos = 0
        setContent {
            Box {
                CompositionLocalProvider(LocalEsEscritorio provides true) { BotonDeMasAcciones(onClick = { abiertos++ }) }
                CompositionLocalProvider(LocalEsEscritorio provides false) { BotonDeMasAcciones(onClick = { abiertos++ }) }
            }
        }
        onAllNodesWithContentDescription("Más acciones").assertCountEquals(1)
        onNodeWithContentDescription("Más acciones").performClick()
        waitForIdle()
        assertEquals(1, abiertos)
    }

    // MARK: - El carrito: lo que sólo salía deslizando

    @Composable
    private fun Carrito(enWindows: Boolean, quitadas: MutableList<String>) {
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalView provides View(context), LocalEsEscritorio provides enWindows,
        ) {
            AvoqadoTheme {
                Box(Modifier.size(width = 480.dp, height = 700.dp)) {
                    CartPanelView(
                        cartState = CartState(
                            items = listOf(
                                CartItem(id = "combo", type = CartItemType.CustomAmount, name = "Combo del día", unitPrice = 12_000,
                                    promotionInstanceId = "p1", promotionName = "Combo del día"),
                                CartItem(id = "agua", type = CartItemType.CustomAmount, name = "Agua", unitPrice = 2_500),
                            ),
                        ),
                        onCharge = {},
                        onClearCart = {},
                        onRemoveItem = { quitadas += it },
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

    @Test fun `g - en Windows cada linea del carrito tiene Quitar - tambien el combo que solo salia deslizando`() = runComposeUiTest {
        val quitadas = mutableListOf<String>()
        setContent { Carrito(enWindows = true, quitadas) }
        onAllNodesWithContentDescription("Quitar del carrito").assertCountEquals(2)
        onAllNodesWithContentDescription("Quitar del carrito")[0].performClick()
        waitForIdle()
        // El mismo camino que deslizar: el combo llega a `onRemoveItem`, que es quien pregunta «se quitará el combo completo».
        assertEquals(listOf("combo"), quitadas)
    }

    @Test fun `h - en Android el carrito no cambia - se sigue deslizando`() = runComposeUiTest {
        setContent { Carrito(enWindows = false, mutableListOf()) }
        onAllNodesWithContentDescription("Quitar del carrito").assertCountEquals(0)
    }
}
