package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentResultScreen
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D39 (Flautas, 21-nov): después de cobrar, la cajera creía que tenía que imprimir porque la única salida sin recibo era el
 * enlace chico «Venta nueva». «Sin recibo» va PRIMERO en la lista, como Toast («No receipt» primero), y hace lo mismo que
 * «Venta nueva»: sale de la pantalla sin imprimir ni enviar nada.
 */
@OptIn(ExperimentalTestApi::class)
class SinReciboEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("sin-recibo")
    private val contexto = ContextoDeEscritorio(carpeta)
    private var salidas = 0
    private var impresiones = 0
    private var envios = 0
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    private fun ComposeUiTest.recibo(offerReceipt: Boolean = true, entregarTarjeta: Boolean = false) = setContent {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) {
            AvoqadoTheme(darkTheme = false) {
                PaymentResultScreen(
                    totalCents = 13_700, method = PaymentMethod.CASH, paymentId = "pago-1",
                    onSendWhatsApp = { envios++ }, onSendEmail = { envios++ }, onPrintReceipt = { impresiones++ },
                    onPrintOnTerminalReceipt = { impresiones++ }, canPrintOnTerminal = true,
                    offerReceipt = offerReceipt, entregarTarjeta = entregarTarjeta, onDone = { salidas++ },
                )
            }
        }
    }

    @Test fun `sin recibo sale de la pantalla sin imprimir ni enviar`() = runComposeUiTest {
        recibo()
        onNodeWithText("Sin recibo").performClick()
        waitForIdle()
        assertEquals(1, salidas)
        assertEquals(0, impresiones)
        assertEquals(0, envios)
    }

    @Test fun `sin recibo va primero, arriba de imprimir`() = runComposeUiTest {
        recibo()
        val sinRecibo = onNodeWithText("Sin recibo").getUnclippedBoundsInRoot().top
        val imprimir = onNodeWithText("Imprimir recibo").getUnclippedBoundsInRoot().top
        assertTrue(sinRecibo < imprimir, "«Sin recibo» ($sinRecibo) debe ir arriba de «Imprimir recibo» ($imprimir)")
        onNodeWithText("Venta nueva").assertExists() // el enlace de siempre se queda: guías y costumbre
    }

    @Test fun `con ofrecer recibo apagado sigue la salida sin recibo`() = runComposeUiTest {
        recibo(offerReceipt = false)
        onNodeWithText("Sin recibo").performClick()
        waitForIdle()
        assertEquals(1, salidas)
    }

    @Test fun `venta de tarjetas de regalo no ofrece sin recibo`() = runComposeUiTest {
        // Ahí la salida lleva a entregar la tarjeta, no a otra venta: el botón que lo dice ya está.
        recibo(entregarTarjeta = true)
        onAllNodesWithText("Sin recibo").assertCountEquals(0)
    }
}
