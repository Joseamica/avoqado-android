package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentResultScreen
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Al cobrar una venta de tarjetas de regalo, el recibo es sólo la constancia del pago: la tarjeta sale en el siguiente paso.
 * El botón grande lleva a ella, para que la cajera no entregue el recibo creyendo que es la tarjeta.
 */
@OptIn(ExperimentalTestApi::class)
class ReciboDeVentaDeTarjetasEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("recibo-de-tarjetas")
    private val contexto = ContextoDeEscritorio(carpeta)
    private var salidas = 0
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    private fun ComposeUiTest.recibo(entregarTarjeta: Boolean) = setContent {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) {
            AvoqadoTheme(darkTheme = false) {
                PaymentResultScreen(
                    totalCents = 50_000, method = PaymentMethod.CASH, paymentId = "pago-1",
                    onSendWhatsApp = {}, onSendEmail = {}, onPrintReceipt = {},
                    entregarTarjeta = entregarTarjeta, onDone = { salidas++ },
                )
            }
        }
    }

    @Test fun `venta de tarjetas lleva a entregarla y no ofrece venta nueva`() = runComposeUiTest {
        recibo(entregarTarjeta = true)
        onAllNodesWithText("Venta nueva").assertCountEquals(0)
        onNodeWithText(TextosDeTarjetas.ENTREGAR_TARJETA).performClick()
        waitForIdle()
        assertEquals(1, salidas)
    }

    @Test fun `el recibo sigue a la vista junto al boton de entregar`() = runComposeUiTest {
        recibo(entregarTarjeta = true)
        onNodeWithText("Imprimir recibo").assertExists()
        onNodeWithText("Enviar por correo").assertExists()
    }

    @Test fun `una venta normal no cambia`() = runComposeUiTest {
        recibo(entregarTarjeta = false)
        onNodeWithText("Venta nueva").assertExists()
        onAllNodesWithText(TextosDeTarjetas.ENTREGAR_TARJETA).assertCountEquals(0)
    }
}
