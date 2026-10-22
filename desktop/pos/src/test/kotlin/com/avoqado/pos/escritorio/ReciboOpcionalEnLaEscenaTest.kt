package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.money
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentResultScreen
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * «Ofrecer recibo» (`showReceiptScreen`) apagado esconde SÓLO el envío. En la caja: «Enviar por correo» y «Enviar por
 * WhatsApp» (igual que iOS); «Imprimir recibo» y «Venta nueva» siguen. En la pantalla del cliente: sólo el gracias y el
 * total, sin QR ni opciones. El ticket impreso no cambia.
 */
@OptIn(ExperimentalTestApi::class)
class ReciboOpcionalEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("recibo-opcional")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    private fun ComposeUiTest.caja(offerReceipt: Boolean) = setContent {
        superficie {
            PaymentResultScreen(
                totalCents = 12_345, method = PaymentMethod.CASH, paymentId = "pago-1",
                onSendWhatsApp = {}, onSendEmail = {}, onPrintReceipt = {},
                offerReceipt = offerReceipt, onDone = {},
            )
        }
    }

    @Test fun `recibo apagado oculta correo y WhatsApp`() = runComposeUiTest {
        caja(offerReceipt = false)
        onNodeWithText("Imprimir recibo").assertExists()
        onNodeWithText("Venta nueva").assertExists()
        onAllNodesWithText("Enviar por correo").assertCountEquals(0)
        onAllNodesWithText("Enviar por WhatsApp").assertCountEquals(0)
    }

    @Test fun `recibo prendido los muestra`() = runComposeUiTest {
        caja(offerReceipt = true)
        onNodeWithText("Imprimir recibo").assertExists()
        onNodeWithText("Enviar por correo").assertExists()
        onNodeWithText("Enviar por WhatsApp").assertExists()
    }

    @Test fun `cliente con recibo apagado no ve QR ni opciones`() = runComposeUiTest {
        // Pantalla física táctil con captura: con «Ofrecer recibo» prendido aquí saldrían «¿Cómo quieres tu recibo?» y el QR.
        val state = CustomerDisplayState().apply { setPresenting(true); setCustomerCaptureEnabled(true); setTouchCapable(true) }
        state.show(CustomerContent.Done(totalCents = 12_345, receiptUrl = "https://x", offerReceipt = false))
        // Reloj a mano, como en PantallaDelClienteEnLaEscenaTest: la pantalla tiene animaciones infinitas.
        mainClock.autoAdvance = false
        setContent { superficie { CustomerDisplayScreen(state = state, onRating = {}, onTip = {}) } }

        fun soloGraciasYTotal() {
            mainClock.advanceTimeBy(1_000)
            onNodeWithText("¡Gracias por tu compra!").assertIsDisplayed()
            onNodeWithText(money(12_345)).assertIsDisplayed()
            listOf("¿Cómo quieres tu recibo?", "Escanea tu recibo", "o escanéalo con tu teléfono", "WhatsApp", "Correo")
                .forEach { onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        }
        soloGraciasYTotal()
        // Sin pantalla táctil (el camino del QR) tampoco.
        state.setTouchCapable(false)
        soloGraciasYTotal()
    }
}
