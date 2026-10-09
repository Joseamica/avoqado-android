package com.avoqado.pos.escritorio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import android.view.View
import com.avoqado.escritorio.ContextoDeEscritorio
import java.nio.file.Files
import kotlin.test.AfterTest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.kds.domain.PreparationAction
import com.avoqado.pos.kds.domain.PreparationCounts
import com.avoqado.pos.kds.domain.PreparationUrgency
import com.avoqado.pos.kds.presentation.KDSOrderCard
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.kds.presentation.PreparationControls
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PreparacionAccionesEnLaEscenaTest {
    private val folder = Files.createTempDirectory("preparation-scene")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }
    @Composable private fun theme(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalView provides View(context)) { AvoqadoTheme(content = content) }
    }

    @Test fun `el acuse urgente se ve junto a Preparar y envia una unidad`() = runComposeUiTest {
        var acknowledged = 0
        setContent {
            theme {
                Box(Modifier.width(280.dp)) {
                    PreparationControls("Café", PreparationCounts(PENDING = 1,
                        urgency = PreparationUrgency("request")),
                        listOf(PreparationAction.START, PreparationAction.READY, PreparationAction.ACK_URGENT),
                        can = { true }, onAction = { action, quantity, _, _ ->
                            assertEquals(PreparationAction.ACK_URGENT, action)
                            assertEquals(1, quantity)
                            acknowledged++
                        })
                }
            }
        }
        onNodeWithText("Preparar · 1").assertIsDisplayed()
        onNodeWithText("Marcar listo · 0").assertDoesNotExist()
        onNodeWithText("Ya lo vi").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, acknowledged) }
    }

    @Test fun `cargar capacidades muestra el acuse aunque la comanda no cambie`() = runComposeUiTest {
        val capabilitiesReady = mutableStateOf(false)
        var negotiated = false
        setContent {
            val allowed = remember(capabilitiesReady.value) {
                PreparationAction.entries.filter { !it.priority || negotiated }.toSet()
            }
            theme {
                Box(Modifier.width(280.dp)) {
                    KDSOrderCard(KDSOrder(id = "ticket", orderNumber = "1", orderType = "DINE_IN",
                        items = listOf(KDSOrderItem(id = "item", productName = "Café", quantity = 1,
                            preparation = PreparationCounts(PENDING = 1, urgency = PreparationUrgency("request")))),
                        createdAt = System.currentTimeMillis(), status = KDSOrderStatus.NEW, preparationVersion = 1),
                        elapsedText = "00:01", isLargeFont = false, etiqueta = null, onListo = {},
                        canPreparation = { it in allowed })
                }
            }
        }
        onNodeWithText("Preparar · 1").assertIsDisplayed()
        onNodeWithText("Ya lo vi").assertDoesNotExist()
        runOnIdle { negotiated = true; capabilitiesReady.value = true }
        onNodeWithText("Ya lo vi").assertIsDisplayed()
        onNodeWithText("Preparar · 1").assertIsDisplayed()
    }
    @Test fun `la tarjeta completa muestra un acuse ya habilitado al abrir`() = runComposeUiTest {
        setContent {
            theme {
                Box(Modifier.width(280.dp)) {
                    KDSOrderCard(KDSOrder(id = "ticket", orderNumber = "1", orderType = "DINE_IN",
                        items = listOf(KDSOrderItem(id = "item", productName = "Café", quantity = 1,
                            preparation = PreparationCounts(PENDING = 1, urgency = PreparationUrgency("request")))),
                        createdAt = System.currentTimeMillis(), status = KDSOrderStatus.NEW, preparationVersion = 1),
                        elapsedText = "00:01", isLargeFont = false, etiqueta = null, onListo = {},
                        canPreparation = { true })
                }
            }
        }
        onNodeWithText("Preparar · 1").assertIsDisplayed()
        onNodeWithText("Ya lo vi").assertIsDisplayed()
    }

}
