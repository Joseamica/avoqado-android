package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.customerdisplay.AppliedDesign
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.Ficha
import com.avoqado.pos.customerdisplay.LogoRef
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * La pantalla del cliente dibuja la ficha del negocio (F2, Task 3.1): tema, acento, textos y logo. Sin ficha, lo de hoy.
 * Se arma como las superficies reales: `AvoqadoTheme(darkTheme = false)` por fuera; el tema de la ficha va por dentro.
 */
@OptIn(ExperimentalTestApi::class)
class PantallaDelClienteEnLaEscenaTest {
    private val folder = Files.createTempDirectory("pantalla-del-cliente")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    /**
     * 🔴 Reloj a mano: el letrero en reposo «respira» con una animación infinita, y con el reloj automático la espera de
     * inactividad de la escena no termina nunca (se colgó así la primera corrida). Cada cambio de estado se sigue de [avanzar].
     */
    private fun ComposeUiTest.pantalla(state: CustomerDisplayState) {
        mainClock.autoAdvance = false
        setContent { superficie { CustomerDisplayScreen(state = state, onRating = {}, onTip = {}) } }
        avanzar()
    }

    private fun ComposeUiTest.avanzar() = mainClock.advanceTimeBy(1_000)

    /** Para lo que llega de otro hilo (el logo se decodifica en IO): cuadros cortos con tiempo real de por medio. */
    private fun ComposeUiTest.hasta(condicion: () -> Boolean) {
        repeat(100) {
            mainClock.advanceTimeByFrame()
            if (condicion()) return
            Thread.sleep(50)
        }
        fail("no se cumplió en ~5 s")
    }

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalView provides View(context)) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    private fun ficha(dark: Boolean = false, welcome: String? = null, thanks: String? = null, logo: LogoRef? = null) =
        Ficha(fingerprint = "huella", dark = dark, accentArgb = 0xFFE4572EL, onAccentArgb = 0xFFFFFFFFL, logo = logo,
            welcomeText = welcome, thanksText = thanks)

    private fun conMarca() = CustomerDisplayState().apply { setVenueBranding("Taquería Lupe", null) }

    /** Pixel ARGB en una fracción del alto, al centro del ancho (el centro exacto cae sobre el letrero del negocio). */
    private fun ComposeUiTest.pixel(fraccionDeAlto: Float): Int {
        val img = onRoot().captureToImage().toAwtImage()
        return img.getRGB(img.width / 2, (img.height * fraccionDeAlto).toInt())
    }

    private fun ComposeUiTest.capturar(nombre: String) {
        val dir = File(System.getenv("AVOQADO_CAPTURAS") ?: return).apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$nombre.png"))
    }

    @Test fun `sin diseno dice Bienvenido`() = runComposeUiTest {
        val state = conMarca()
        pantalla(state)
        onNodeWithText("Taquería Lupe").assertIsDisplayed()
        onNodeWithText("Bienvenido").assertIsDisplayed()
        assertEquals(0xFFFFFFFF.toInt(), pixel(0.1f))
        capturar("01-sin-diseno")
    }

    @Test fun `bienvenida propia`() = runComposeUiTest {
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(welcome = "¡Hola, vecino!"), logoFile = null)) }
        pantalla(state)
        onNodeWithText("¡Hola, vecino!").assertIsDisplayed()
        onAllNodesWithText("Bienvenido").assertCountEquals(0)
    }

    @Test fun `gracias propio en el QR`() = runComposeUiTest {
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(thanks = "¡Vuelve pronto!"), logoFile = null)) }
        state.show(CustomerContent.Done(totalCents = 12_345, receiptUrl = "https://x"))
        pantalla(state)
        onNodeWithText("¡Vuelve pronto!").assertIsDisplayed()
        onAllNodesWithText("¡Gracias por tu compra!").assertCountEquals(0)
        capturar("03-gracias-propio-con-acento")
    }

    @Test fun `tema oscuro pinta el fondo 1C1C1E`() = runComposeUiTest {
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(dark = true, welcome = "Pásale"), logoFile = null)) }
        pantalla(state)
        assertEquals(0xFF1C1C1E.toInt(), pixel(0.1f))
        capturar("02-oscuro-con-texto-propio")
    }

    @Test fun `logo pendiente muestra el nombre`() = runComposeUiTest {
        // El logo de la ficha aún no baja (logoFile null): se ve el nombre y ninguna imagen, aunque haya URL del venue.
        val state = CustomerDisplayState().apply {
            setVenueBranding("Taquería Lupe", "https://storage.googleapis.com/logo.png")
            offerDesign(AppliedDesign(ficha(logo = LogoRef.Venue("https://storage.googleapis.com/logo.png", "v1")), logoFile = null))
        }
        pantalla(state)
        onNodeWithText("Taquería Lupe").assertIsDisplayed()
        onAllNodesWithContentDescription("Taquería Lupe").assertCountEquals(0)
    }

    @Test fun `logo descargado se pinta y uno ilegible deja solo el nombre`() = runComposeUiTest {
        val png = File(folder.toFile(), "logo.png").also {
            ImageIO.write(BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB), "png", it)
        }
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(), logoFile = png, logoStamp = 1L)) }
        pantalla(state)
        hasta { onAllNodesWithContentDescription("Taquería Lupe").fetchSemanticsNodes().size == 1 }

        val basura = File(folder.toFile(), "basura.png").also { it.writeText("no es imagen") }
        state.offerDesign(AppliedDesign(ficha(), logoFile = basura, logoStamp = 2L))
        hasta { onAllNodesWithContentDescription("Taquería Lupe").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Taquería Lupe").assertIsDisplayed()
    }

    @Test fun `diseno ofrecido a media venta no cambia el carrito`() = runComposeUiTest {
        val state = conMarca()
        state.show(CustomerContent.Cart(emptyList(), subtotalCents = 0, descuentos = emptyList(), taxCents = 0, totalCents = 0))
        pantalla(state)
        state.offerDesign(AppliedDesign(ficha(dark = true, welcome = "¡Hola, vecino!"), logoFile = null))
        avanzar()
        onNodeWithText("Tu compra").assertIsDisplayed()
        assertEquals(0xFFFFFFFF.toInt(), pixel(0.5f))   // el carrito sigue en el tema de hoy

        state.idle()
        avanzar()
        onNodeWithText("¡Hola, vecino!").assertIsDisplayed()
        assertEquals(0xFF1C1C1E.toInt(), pixel(0.1f))
    }
}
