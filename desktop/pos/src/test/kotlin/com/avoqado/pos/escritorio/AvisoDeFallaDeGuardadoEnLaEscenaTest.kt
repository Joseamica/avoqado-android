package com.avoqado.pos.escritorio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.FallasDeGuardado
import com.avoqado.escritorio.configuracionDeAndroid
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** El aviso al cajero cuando el disco no guardó (Ronda 2 de C1), en la escena real de Compose. */
@OptIn(ExperimentalTestApi::class)
class AvisoDeFallaDeGuardadoEnLaEscenaTest {
    private val aviso = "No se pudo guardar en el disco de esta computadora. Lo último que hiciste (un cobro, un retiro, " +
        "un ingreso o un cierre de caja) puede no haber quedado guardado. No cierres Avoqado POS y avisa a soporte."

    @BeforeTest @AfterTest fun limpiar() = FallasDeGuardado.entendido()

    private fun androidx.compose.ui.test.ComposeUiTest.ponerAviso() = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f)) { AvisoDeFallaDeGuardado() }
        }
    }

    @Test fun `sin fallas no hay aviso`() = runComposeUiTest {
        ponerAviso()
        onNodeWithText(aviso).assertDoesNotExist()
    }

    @Test fun `con una falla aparece el aviso con el archivo y la hora, y Entendido lo cierra`() = runComposeUiTest {
        ponerAviso()
        runOnIdle { FallasDeGuardado.reportar("avoqado_secure_prefs", LocalTime.of(14, 5, 0)) }
        onNodeWithText(aviso).assertExists()
        onNodeWithText("avoqado_secure_prefs", substring = true).assertExists()
        onNodeWithText("14:05:00", substring = true).assertExists()   // con segundos aunque sean 00
        onNodeWithText("Entendido").performClick()
        waitForIdle()
        onNodeWithText(aviso).assertDoesNotExist()
        assertEquals(emptyList(), FallasDeGuardado.fallas.value)
    }

    @Test fun `si despues si se guardo lo dice, pero el aviso no se va solo`() = runComposeUiTest {
        ponerAviso()
        runOnIdle {
            FallasDeGuardado.reportar("avoqado_secure_prefs", LocalTime.of(9, 30, 15))
            FallasDeGuardado.guardoBien("avoqado_secure_prefs")
        }
        mainClock.advanceTimeBy(60_000)
        waitForIdle()
        onNodeWithText(aviso).assertExists()
        onNodeWithText("después sí se pudo guardar", substring = true).assertExists()
    }
}
