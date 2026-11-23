package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.payment.presentation.PaymentResultScreen
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * E1 (recorrido real de la máquina, 21-nov): la pantalla de éxito de un PAGADO con cambio que la máquina no dio dice «Te debemos» con su folio
 * (el FAIL: sólo decía «$280.00 Efectivo»), y mientras la venta de la máquina sube con red no dice «cuando haya conexión».
 */
@OptIn(ExperimentalTestApi::class)
class DeudaDeLaMaquinaEnLaEscenaTest {
    private val carpeta = Files.createTempDirectory("deuda-de-la-maquina")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    private val deuda = "Te debemos \$220.00 de cambio: pasa a caja a cobrarlo · Folio AQMWRB7HK9R2PS"

    @Test fun `el PAGADO con deuda la ensena con su folio`() = runComposeUiTest {
        setContent {
            superficie {
                PaymentResultScreen(
                    totalCents = 28_000, method = PaymentMethod.CASH, isQueued = true, sincronizacion = SincronizacionDelCobro.REGISTRANDO,
                    avisoDeLaMaquina = deuda, onDone = {},
                )
            }
        }
        onNodeWithText(deuda).assertIsDisplayed()
        onNodeWithText("Registrando la venta…").assertExists()
        onAllNodesWithText("Se sincronizará cuando haya conexión").assertCountEquals(0)
    }

    @Test fun `sin red sigue diciendo que se sincronizara`() = runComposeUiTest {
        setContent {
            superficie {
                PaymentResultScreen(totalCents = 28_000, method = PaymentMethod.CASH, isQueued = true, onDone = {})
            }
        }
        onNodeWithText("Se sincronizará cuando haya conexión").assertExists()
        onAllNodesWithText("Te debemos", substring = true).assertCountEquals(0)
    }
}
