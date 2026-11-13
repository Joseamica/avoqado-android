package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.tables.presentation.TableActionsPanel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * D653 (12-nov, SAMSUNG en horizontal): la lista de «Acciones» de la mesa se desplaza, y el aviso de un botón gris de abajo
 * («Recompensas», «Caja abierta») salía ARRIBA de la lista, fuera de la vista: la cajera creía que el toque no hizo nada.
 * El aviso va fijo encima de la lista (como en «Cuenta»): se ve sin importar dónde esté la lista, también si el mismo motivo
 * se repite. Lo mismo corre en ANDROID.
 */
@OptIn(ExperimentalTestApi::class)
class AvisoDelBotonGrisEnLaEscenaTest {
    private val folder = Files.createTempDirectory("aviso-gris-scene")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private val sinPuntos = "En esta cuenta ya no se pueden canjear puntos: tiene un pago registrado o no queda nada que descontar."
    private val sinCajon = "No hay impresora de recibos con cajón en esta caja. Agrégala en Más › Impresoras y cajón."

    @Composable
    private fun Acciones(aviso: MutableState<String?>) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalView provides View(context)) {
            AvoqadoTheme {
                // La mitad derecha de la tableta en horizontal: la lista no cabe y se desplaza.
                Box(Modifier.size(width = 520.dp, height = 300.dp)) {
                    TableActionsPanel(
                        hasPending = false,
                        hasSent = true,
                        onBlocked = { aviso.value = it },
                        blockedNotice = aviso.value,
                        onDismissNotice = { aviso.value = null },
                        onClearPending = {},
                        onAnular = {},
                        onPrintPreBill = {},
                        onReprintComandas = {},
                        onCustomAmount = {},
                        hasLoyalty = false,
                        rewardsBlockedReason = sinPuntos,
                    )
                }
            }
        }
    }

    @Test fun `a - Recompensas gris abajo de la lista - el aviso se ve`() = runComposeUiTest {
        val aviso = mutableStateOf<String?>(null)
        setContent { Acciones(aviso) }
        onNodeWithText("Recompensas").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(sinPuntos).assertIsDisplayed()
    }

    @Test fun `b - otro boton gris hasta el fondo (Caja abierta) - el aviso se ve`() = runComposeUiTest {
        val aviso = mutableStateOf<String?>(null)
        setContent { Acciones(aviso) }
        onNodeWithText("Caja abierta").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(sinCajon).assertIsDisplayed()
    }

    @Test fun `c - el mismo motivo otra vez tras bajar la lista - el aviso se sigue viendo`() = runComposeUiTest {
        val aviso = mutableStateOf<String?>(null)
        setContent { Acciones(aviso) }
        onNodeWithText("Recompensas").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Caja abierta").performScrollTo() // la cajera baja sin cerrar el aviso
        onNodeWithText("Recompensas").performScrollTo().performClick() // mismo texto: no hay estado nuevo que lo anuncie
        waitForIdle()
        onNodeWithText(sinPuntos).assertIsDisplayed()
    }

    @Test fun `d - Entendido lo cierra`() = runComposeUiTest {
        val aviso = mutableStateOf<String?>(null)
        setContent { Acciones(aviso) }
        onNodeWithText("Caja abierta").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Entendido").performClick()
        waitForIdle()
        onNodeWithText(sinCajon).assertDoesNotExist()
    }
}
