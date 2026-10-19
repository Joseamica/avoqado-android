package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.customerdisplay.AppliedDesign
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.Ficha
import com.avoqado.pos.customerdisplay.LogoRef
import com.avoqado.pos.customerdisplay.decodificarLogo
import com.avoqado.pos.customerdisplay.money
import com.avoqado.pos.pos.data.model.UpsellCard
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    private fun ficha(dark: Boolean = false, welcome: String? = null, thanks: String? = null, logo: LogoRef? = null, acento: Boolean = true) =
        Ficha(fingerprint = "huella", dark = dark, accentArgb = if (acento) 0xFFE4572EL else null,
            onAccentArgb = if (acento) 0xFFFFFFFFL else null, logo = logo, welcomeText = welcome, thanksText = thanks)

    /** Pantalla FÍSICA táctil con la captura del cliente prendida: el cliente teclea su WhatsApp y ve «No, gracias». */
    private fun CustomerDisplayState.tactil() = apply { setPresenting(true); setCustomerCaptureEnabled(true); setTouchCapable(true) }

    /** Los pixeles de un nodo, para comparar dos dibujos del mismo lugar. */
    private fun imagen(nodo: SemanticsNodeInteraction): IntArray {
        val img = nodo.captureToImage().toAwtImage()
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    private fun ComposeUiTest.esquina(): Int = onRoot().captureToImage().toAwtImage().getRGB(2, 2)

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

    // ── Arrastres de la revisión de la 3.1 ──

    @Test fun `No gracias no toma el acento`() = runComposeUiTest {
        // Sólo los botones que se LLENAN de primary (Agregar, Enviar) toman el acento; la salida conserva su color de hoy.
        val state = conMarca().tactil()
        val upsell = CustomerContent.Upsell(cards = emptyList(), cartTotalCents = 10_000)
        state.show(upsell)
        pantalla(state)
        val hoy = imagen(onNodeWithText("No, gracias"))

        state.idle()
        state.offerDesign(AppliedDesign(ficha(), logoFile = null))
        state.show(upsell)
        avanzar()

        assertContentEquals(hoy, imagen(onNodeWithText("No, gracias")), "«No, gracias» se pintó del acento")
    }

    @Test fun `quitar el diseno a media captura de WhatsApp no borra lo tecleado`() = runComposeUiTest {
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(dark = true), logoFile = null)) }.tactil()
        state.show(CustomerContent.Done(totalCents = 12_345, receiptUrl = "https://x"))
        pantalla(state)
        onNodeWithText("WhatsApp").performClick()
        avanzar()
        listOf("9", "8", "7", "6").forEach { onNodeWithText(it).performClick(); avanzar() }
        onNodeWithText("9876").assertIsDisplayed()
        assertEquals(0xFF1C1C1E.toInt(), esquina())

        state.clearDesign()   // logout, cambio de sucursal o desligado a media venta
        avanzar()

        onNodeWithText("9876").assertIsDisplayed()
        assertEquals(0xFFFFFFFF.toInt(), esquina(), "sin ficha vuelve el tema de hoy")
    }

    @Test fun `el monto destacado se pinta del acento`() = runComposeUiTest {
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(), logoFile = null)) }
        state.show(CustomerContent.Total(totalCents = 12_345))
        pantalla(state)
        assertTrue(imagen(onNodeWithText(money(12_345))).any { it == 0xFFE4572E.toInt() }, "ningún pixel del monto es del acento")
    }

    @Test fun `sin acento las estrellas y el upsell se ven como hoy`() = runComposeUiTest {
        val state = conMarca().tactil()
        val tarjeta = UpsellCard(ruleId = "r1", productId = "p1", name = "Galleta", displayPriceCents = 2_500, imageUrl = null,
            headline = null, badge = "-20%")
        pantalla(state)
        fun foto(): List<IntArray> {
            state.show(CustomerContent.Rating(amountCents = 10_000))
            avanzar()
            val estrellas = imagen(onRoot())
            state.show(CustomerContent.Upsell(cards = listOf(tarjeta), cartTotalCents = 10_000, selectedRuleIds = setOf("r1")))
            avanzar()
            val upsell = imagen(onRoot())
            state.idle()
            avanzar()
            return listOf(estrellas, upsell)
        }
        val hoy = foto()
        onNodeWithText("Bienvenido").assertIsDisplayed()

        state.offerDesign(AppliedDesign(ficha(acento = false), logoFile = null))
        avanzar()
        val sinAcento = foto()

        assertContentEquals(hoy[0], sinAcento[0], "las estrellas cambiaron sin acento")
        assertContentEquals(hoy[1], sinAcento[1], "el upsell cambió sin acento")
    }

    @Test fun `el gracias propio sale en las opciones del recibo y al enviarlo`() = runComposeUiTest {
        val state = conMarca().apply { offerDesign(AppliedDesign(ficha(thanks = "¡Vuelve pronto!"), logoFile = null)) }.tactil()
        state.show(CustomerContent.Done(totalCents = 12_345, receiptUrl = "https://x"))
        pantalla(state)
        onNodeWithText("¿Cómo quieres tu recibo?").assertIsDisplayed()
        onNodeWithText("¡Vuelve pronto!").assertIsDisplayed()
        onAllNodesWithText("¡Gracias por tu compra!").assertCountEquals(0)

        state.setReceiptSend(CustomerDisplayState.ReceiptSend.Sent)
        avanzar()

        onNodeWithText("¡Recibo enviado!").assertIsDisplayed()
        onNodeWithText("¡Vuelve pronto!").assertIsDisplayed()
        onAllNodesWithText("Gracias por tu compra").assertCountEquals(0)
    }

    @Test fun `un logo grande se decodifica a unos 720 px de lado y uno chico queda igual`() {
        fun png(nombre: String, ancho: Int, alto: Int) = File(folder.toFile(), nombre).also {
            ImageIO.write(BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB), "png", it)
        }
        val ancho = decodificarLogo(png("ancho.png", 3_000, 1_500).path)!!
        assertTrue(maxOf(ancho.width, ancho.height) in 720 until 1_440, "${ancho.width}×${ancho.height}")
        val alto = decodificarLogo(png("alto.png", 500, 2_000).path)!!
        assertTrue(maxOf(alto.width, alto.height) in 720 until 1_440, "${alto.width}×${alto.height}")
        val chico = decodificarLogo(png("chico.png", 40, 20).path)!!
        assertEquals(40 to 20, chico.width to chico.height)
    }
}
