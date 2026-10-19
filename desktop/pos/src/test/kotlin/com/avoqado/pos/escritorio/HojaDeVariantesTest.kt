package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.VariantOption
import com.avoqado.pos.pos.presentation.product.variantes.Familia
import com.avoqado.pos.pos.presentation.checkout.TileSize
import com.avoqado.pos.pos.presentation.product.variantes.HojaDeVariantes
import com.avoqado.pos.pos.presentation.product.variantes.TextosVariantes
import com.avoqado.pos.pos.presentation.product.variantes.TileDeFamilia
import com.avoqado.pos.pos.presentation.product.variantes.familiasDe
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Variantes de producto, bloque 3 (T4): la hoja para elegir la variante, con la familia `g1` «Playera» de `casos-compartidos.md`
 * (Talla S/M × Color Azul/Rojo; `pl-s-ro` agotada). Lo que decide los chips es `Familias.kt`; aquí se prueba que la hoja lo pinta y
 * que el botón nunca queda muerto sin explicación ni bloquea a una agotada.
 */
@OptIn(ExperimentalTestApi::class)
class HojaDeVariantesTest {
    private val folder = Files.createTempDirectory("hoja-de-variantes")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private fun op(nombre: String, valor: String, np: Int, vp: Int) = VariantOption(nombre, valor, np, vp)

    private fun playera(id: String, talla: Pair<String, Int>, color: Pair<String, Int>, precio: Double, agotado: Boolean = false) = Product(
        id = id, name = "Playera · ${talla.first} · ${color.first}", priceValue = precio,
        trackInventory = if (agotado) true else null, availableQuantity = if (agotado) 0 else null,
        productGroupId = "g1", productGroupName = "Playera",
        variantOptions = listOf(op("Talla", talla.first, 0, talla.second), op("Color", color.first, 1, color.second)),
    )

    private val g1: Familia = familiasDe(listOf(
        playera("pl-s-az", "S" to 0, "Azul" to 0, 199.0),
        playera("pl-m-az", "M" to 1, "Azul" to 0, 199.0),
        playera("pl-s-ro", "S" to 0, "Rojo" to 1, 219.0, agotado = true),
    )).getValue("g1")

    /** Una ventana de 1280×800, como la de la caja: la hoja acota su lista al 55 % del alto (con 0 no se vería nada). */
    @Composable private fun escena(oscuro: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(context), LocalEsEscritorio provides true,
        ) { AvoqadoTheme(darkTheme = oscuro, content = content) }
    }

    private fun parecido(esperado: Color, real: Color) =
        abs(esperado.red - real.red) < 0.02f && abs(esperado.green - real.green) < 0.02f && abs(esperado.blue - real.blue) < 0.02f

    /**
     * Ronda de arreglo 1, #1: el botón del padre se ve como `ProductTile`. Sin foto, el fondo es el color de su primera variante (el que
     * pinta su tile suelto; iOS igual), y la insignia «N variantes» es la oscura de `ProductTile` también en tema oscuro (antes salía
     * gris y con una insignia clara entre tiles de color).
     */
    @Test fun `el tile de la familia toma el color de su primera variante y la insignia de ProductTile`() = runComposeUiTest {
        val rojo = Color(0xFFD32F2F)
        val familia = familiasDe(listOf("S" to 0, "M" to 1).map { (talla, pos) ->
            Product(id = talla, name = "Gorra · $talla", priceValue = 150.0, color = "#D32F2F", productGroupId = "g7",
                productGroupName = "Gorra", variantOptions = listOf(op("Talla", talla, 0, pos)))
        }).getValue("g7")
        setContent {
            escena(oscuro = true) { Box(Modifier.width(200.dp).testTag("tile")) { TileDeFamilia(familia, TileSize.MEDIANO, onTap = {}) } }
        }
        val imagen = onNodeWithTag("tile").captureToImage().toPixelMap()
        val altoDeLaImagen = (imagen.width / TileSize.MEDIANO.productImageAspect).toInt()
        // Centro de la zona de imagen: lejos de las iniciales (abajo a la izquierda) y de las insignias (arriba).
        val fondo = imagen[imagen.width / 2, altoDeLaImagen / 2]
        assertTrue(parecido(rojo, fondo), "el fondo es $fondo y no el color de la variante")

        // Dentro del fondo de la insignia, sin letras: oscuro, como la de ProductTile. Los límites del nodo son los del TEXTO (su
        // semántica va después de los paddings), así que 3 dp a su izquierda cae en el relleno interior (6 dp) de la insignia.
        val insignia = onNodeWithText(TextosVariantes.variantes(2), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val x = (insignia.left - with(density) { 3.dp.toPx() }).toInt()
        val y = insignia.center.y.toInt()
        val pixel = imagen[x, y]
        assertTrue(pixel.red < 0.5f && pixel.green < 0.5f && pixel.blue < 0.5f, "la insignia es clara ($pixel), no la oscura de ProductTile")
    }

    /** El FONDO del chip (no una letra): 4 dp adentro de su borde izquierdo, a media altura, antes de que empiece el texto. */
    private fun ComposeUiTest.fondoDelChip(texto: String): Color {
        val imagen = onNodeWithText(texto).captureToImage().toPixelMap()
        return imagen[with(density) { 4.dp.toPx() }.toInt(), imagen.height / 2]
    }

    /**
     * QA del bloque 3 (MEDIO): el chip ELEGIDO era un relleno gris casi blanco y sin borde, menos visible que los no elegidos; con tres
     * ejes la cajera no veía qué eligió. Ahora es el relleno de contraste de `primary` (oscuro en tema claro, claro en tema oscuro; iOS:
     * negro). Se mide el fondo de los dos chips, no una letra: el elegido debe diferir claramente del no elegido en AMBOS temas.
     */
    @Test fun `el chip elegido se distingue con claridad del no elegido en tema claro y oscuro`() {
        listOf(false, true).forEach { oscuro ->
            runComposeUiTest {
                setContent { escena(oscuro) { HojaDeVariantes(g1, onElegir = {}, onCerrar = {}) } }
                onNodeWithText("M").performClick()
                onNodeWithText("M").assertIsSelected()
                val elegido = fondoDelChip("M")
                val normal = fondoDelChip("Azul")
                val tema = if (oscuro) "oscuro" else "claro"
                assertTrue(
                    abs(elegido.luminance() - normal.luminance()) > 0.5f,
                    "tema $tema: el fondo del chip elegido ($elegido) casi no difiere del no elegido ($normal)",
                )
            }
        }
    }

    @Test fun `sin elegir nada el boton esta apagado y explica por que`() = runComposeUiTest {
        setContent { escena { HojaDeVariantes(g1, onElegir = {}, onCerrar = {}) } }
        onNodeWithText("Playera").assertExists()
        onNodeWithText(TextosVariantes.AGREGAR).assertIsNotEnabled()
        onNodeWithText(TextosVariantes.FALTA_ELEGIR).assertExists()
    }

    @Test fun `P1 elegir M y Azul entrega esa variante con su precio`() = runComposeUiTest {
        var elegida: Product? = null
        setContent { escena { HojaDeVariantes(g1, onElegir = { elegida = it }, onCerrar = {}) } }
        onNodeWithText("M").performClick()
        onNodeWithText("Azul").performClick()
        onNodeWithText(TextosVariantes.FALTA_ELEGIR).assertDoesNotExist()
        onNodeWithText(TextosVariantes.agregarCon(formatMoney(199.0))).assertIsEnabled().performClick()
        runOnIdle { assertEquals("pl-m-az", elegida?.id) }
    }

    /**
     * Codex P1-2: dos activaciones de «Agregar» que llegan ANTES de que la hoja se desmonte no agregan dos unidades. Aquí el reloj no
     * avanza entre los dos clics (ninguna recomposición, la hoja sigue montada) y se CUENTAN las entregas: con el último id sólo no
     * se notaría una segunda igual.
     */
    @Test fun `P1 dos activaciones seguidas de Agregar entregan la variante una sola vez`() = runComposeUiTest {
        val entregas = mutableListOf<String>()
        setContent { escena { HojaDeVariantes(g1, onElegir = { entregas += it.id }, onCerrar = {}) } }
        onNodeWithText("M").performClick()
        onNodeWithText("Azul").performClick()
        val agregar = onNodeWithText(TextosVariantes.agregarCon(formatMoney(199.0))).assertIsEnabled()
        mainClock.autoAdvance = false
        agregar.performClick()
        agregar.performClick()
        mainClock.autoAdvance = true
        runOnIdle { assertEquals(listOf("pl-m-az"), entregas) }
    }

    @Test fun `un valor que no combina se elige y suelta lo demas`() = runComposeUiTest {
        var elegida: Product? = null
        setContent { escena { HojaDeVariantes(g1, onElegir = { elegida = it }, onCerrar = {}) } }
        onNodeWithText("M").performClick()
        onNodeWithText("Rojo").performClick()   // no existe M·Rojo: Rojo se elige y M se suelta (S4)
        onNodeWithText("Rojo").assertIsSelected()
        onNodeWithText("M").assertIsNotSelected()
        onNodeWithText(TextosVariantes.AGREGAR).assertIsNotEnabled().performClick()
        onNodeWithText(TextosVariantes.FALTA_ELEGIR).assertExists()
        runOnIdle { assertNull(elegida) }
    }

    @Test fun `P1 una variante agotada avisa y se puede agregar`() = runComposeUiTest {
        var elegida: Product? = null
        setContent { escena { HojaDeVariantes(g1, onElegir = { elegida = it }, onCerrar = {}) } }
        onNodeWithText("S").performClick()
        onNodeWithText("Rojo").performClick()
        onNodeWithText(TextosVariantes.AGOTADO_AVISO).assertExists()
        onNodeWithText(TextosVariantes.agregarCon(formatMoney(219.0))).assertIsEnabled().performClick()
        runOnIdle { assertEquals("pl-s-ro", elegida?.id) }
    }

    @Test fun `los valores largos no se cortan`() = runComposeUiTest {
        val familia = familiasDe(listOf("Azul marino" to 0, "Azul cielo" to 1).map { (color, pos) ->
            Product(id = color, name = "Sudadera · $color", priceValue = 499.0, productGroupId = "g9", productGroupName = "Sudadera",
                variantOptions = listOf(op("Color", color, 0, pos)))
        }).getValue("g9")
        setContent { escena { HojaDeVariantes(familia, onElegir = {}, onCerrar = {}) } }
        listOf("Azul marino", "Azul cielo").forEach { valor ->
            val nodo = onNodeWithText(valor, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            nodo.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            // El texto cabe ENTERO en un renglón: su nodo mide al menos lo que el valor pide. Un chip de ancho fijo (el defecto de iOS)
            // lo mediría más angosto. Ojo: `hasVisualOverflow` no sirve aquí: el layout de semántica se rehace al ancho MÁXIMO del
            // padre y lo marca siempre que el texto es más angosto que su hueco (foundation 1.7, slowCreateTextLayoutResultOrNull).
            val pide = floor(layouts.single().multiParagraph.intrinsics.maxIntrinsicWidth)
            assertTrue(nodo.fetchSemanticsNode().size.width >= pide, "«$valor» se cortó")
        }
    }
}
