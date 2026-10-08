package com.avoqado.pos.escritorio

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * En Windows cada hoja modal trae una X que la cierra: con mouse no hay gesto de bajarla ni botón de atrás, y
 * arrastrarla hasta abajo dejaba la app bloqueada detrás (La Galeterie, 8-oct).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class HojaConXEnLaEscenaTest {
    @Test fun `P1 en escritorio la X cierra la hoja y avisa una vez`() = runComposeUiTest {
        var cierres = 0
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                MaterialTheme {
                    var abierta by remember { mutableStateOf(true) }
                    if (abierta) {
                        AvoqadoModalBottomSheet(onDismissRequest = { cierres++; abierta = false }) { Text("Configurar impresora") }
                    }
                }
            }
        }
        onNodeWithText("Configurar impresora").assertExists()
        onNodeWithContentDescription("Cerrar").performClick()
        waitForIdle()
        assertEquals(1, cierres)
        onNodeWithText("Configurar impresora").assertDoesNotExist()
    }

    @Test fun `fuera de escritorio la hoja es la de Material sin X`() = runComposeUiTest {
        setContent { MaterialTheme { AvoqadoModalBottomSheet(onDismissRequest = {}) { Text("Configurar impresora") } } }
        onNodeWithText("Configurar impresora").assertExists()
        onNodeWithContentDescription("Cerrar").assertDoesNotExist()
    }
}
