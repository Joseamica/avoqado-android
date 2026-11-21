package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.kds.domain.PreparationCounts
import com.avoqado.pos.kds.presentation.KDSOrderCard
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** D15: en la pantalla de cocina (la misma tarjeta en WINDOWS y ANDROID) lo anulado en la caja se ve tachado y sin botones. */
@OptIn(ExperimentalTestApi::class)
class RenglonAnuladoEnLaEscenaTest {
    private val folder = Files.createTempDirectory("renglon-anulado")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }
    @Composable private fun theme(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalView provides View(context)) { AvoqadoTheme(content = content) }
    }

    private fun comanda(version: Int) = KDSOrder(id = "ticket", orderNumber = "8", orderType = "DINE_IN",
        items = listOf(
            KDSOrderItem(id = "vivo", productName = "Taco", quantity = 1,
                preparation = if (version == 1) PreparationCounts(PENDING = 1) else null),
            KDSOrderItem(id = "anulado", productName = "ANULADO · Agua de pepino", quantity = 1,
                removedAt = "2026-11-20T18:00:00.000Z",
                preparation = if (version == 1) PreparationCounts(CANCELLED = 1) else null),
        ),
        createdAt = System.currentTimeMillis(), status = KDSOrderStatus.NEW, preparationVersion = version)

    @Test fun `comanda sencilla - el anulado sigue a la vista con su etiqueta y la comanda conserva Listo`() = runComposeUiTest {
        setContent { theme { Box(Modifier.width(320.dp)) {
            KDSOrderCard(comanda(0), elapsedText = "00:30", isLargeFont = false, etiqueta = null, onListo = {})
        } } }
        onNodeWithTag("kds-renglon-quitado-anulado").assertIsDisplayed()
        onNodeWithText("ANULADO").assertIsDisplayed()
        onNodeWithText("1x Agua de pepino").assertIsDisplayed()
        onNodeWithTag("kds-renglon-vivo").assertIsDisplayed()
        onNodeWithTag("kds-listo-ticket").assertIsDisplayed()
    }

    @Test fun `con tiempos - el anulado dice Cancelado y no ofrece botones, el vivo si`() = runComposeUiTest {
        setContent { theme { Box(Modifier.width(320.dp)) {
            KDSOrderCard(comanda(1), elapsedText = "00:30", isLargeFont = false, etiqueta = null, onListo = {},
                canPreparation = { true })
        } } }
        onNodeWithText("ANULADO").assertIsDisplayed()
        onNodeWithText("1 · Cancelado").assertIsDisplayed()
        onNodeWithTag("preparation-START-Taco").assertIsDisplayed()
        onNodeWithTag("preparation-START-ANULADO · Agua de pepino").assertDoesNotExist()
    }
}
