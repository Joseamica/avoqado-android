package com.avoqado.pos.escritorio

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Humo: la app de verdad (AvoqadoNavGraph) arranca sin ventana y llega al inicio de sesión aunque no haya backend. */
class AppAbreSinVentanaTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun `la app arranca y muestra Iniciar sesion sin backend`() = runDesktopComposeUiTest(1280, 800) {
        val carpeta = Files.createTempDirectory("Avoqado POS humo ñ")
        Bitacora.iniciar(carpeta)
        Arranque.abrir(carpeta)   // lo mismo que hace Main.kt antes de abrir la ventana (incluye AvoqadoApp.onCreate)
        // Una carpeta nueva queda marcada con el modo del build (las pruebas son de PRUEBA): producción ya no la abriría.
        assertEquals("prueba", Files.readString(carpeta.resolve(".modo-de-datos")).trim())
        // Sembrada ANTES de la primera petición: DeviceHeadersInterceptor la lee una sola vez (con 0 diría PHONE).
        assertEquals(800, Escritorio.contexto.resources.configuration.smallestScreenWidthDp)

        val inicio = System.nanoTime()
        setContent { AppEscritorio() }
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Iniciar sesión").fetchSemanticsNodes().isNotEmpty() }
        val ms = (System.nanoTime() - inicio) / 1_000_000
        mainClock.advanceTimeBy(1_600)   // el splash (1.35 s + 220 ms de salida) se va: la captura es del inicio de sesión
        waitForIdle()
        System.getProperty("avoqado.evidencia")?.let { dir ->
            File(dir).mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "humo-01-inicio.png"))
            // SIN VENTANA (Mac, dibujo por software, reloj de prueba): NO es el arranque que verá el cajero en Windows.
            File(dir, "humo-arranque.txt").writeText(
                "sin ventana: de setContent a «Iniciar sesión» en pantalla = $ms ms de reloj real " +
                    "(el splash de 1.35 s corre con el reloj de prueba y NO está incluido)\n",
            )
        }
    }
}
