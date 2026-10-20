package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.TipOption
import com.avoqado.pos.payment.presentation.TipSelectionSheet
import com.avoqado.pos.payment.presentation.tipCentsForPercent
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El «10 %» de la pantalla del cliente cobraba sobre el total CON IVA y el del cajero sobre la base SIN IVA. El defecto
 * vivía en el botón, así que se prueba tocándolo: lo que entrega es el monto que calculó el ViewModel, sin aritmética propia.
 */
@OptIn(ExperimentalTestApi::class)
class PropinaDelClienteEnLaEscenaTest {
    @Test fun `P1 tocar 10 por ciento entrega el monto del ViewModel y no el del total con IVA`() = runComposeUiTest {
        val state = CustomerDisplayState()
        var entregado: Int? = null
        // total con IVA 10000; el ViewModel ya calculó 862 sobre la base sin IVA (8621)
        state.show(
            CustomerContent.Tip(
                amountCents = 10_000,
                options = listOf(TipOption(10, 862), TipOption(15, 1293)),
                selectedTipCents = null,
            ),
        )
        setContent { MaterialTheme { CustomerDisplayScreen(state = state, onRating = {}, onTip = { entregado = it }) } }
        onNodeWithText("10%").performClick()
        waitForIdle()
        assertEquals(862, entregado) // antes entregaba 1000
    }

    // ── Hasta seis porcentajes (el servidor y el editor del panel topan en 6): cajero y cliente, en dos filas ──

    private val carpeta = Files.createTempDirectory("propina-del-cajero")
    private val contexto = ContextoDeEscritorio(carpeta)
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    private val seis = listOf(10, 15, 20, 25, 30, 35)
    private val total = 1_234_567   // $12,345.67: el monto grande que más ancho pide a cada tarjeta

    /** Arriba (Y) de cada porcentaje, en dp: la fila a la que pertenece. */
    private fun ComposeUiTest.arriba(p: Int) = onNodeWithText("$p%").getBoundsInRoot().top.value

    /** Alto de la tarjeta de cada porcentaje, en dp. */
    private fun ComposeUiTest.alto(p: Int) = onNodeWithText("$p%").getBoundsInRoot().let { it.bottom.value - it.top.value }

    /** Ancho e izquierda de la tarjeta de cada porcentaje, en dp. */
    private fun ComposeUiTest.ancho(p: Int) = onNodeWithText("$p%").getBoundsInRoot().let { it.right.value - it.left.value }
    private fun ComposeUiTest.izquierda(p: Int) = onNodeWithText("$p%").getBoundsInRoot().left.value

    /** Con 5 la fila de abajo lleva 2: miden lo mismo que las 3 de arriba y quedan bajo las dos primeras. */
    private fun ComposeUiTest.cincoDelMismoAncho() {
        assertTrue(arriba(25) > arriba(10), "montaje: dos filas")
        listOf(15, 20, 25, 30).forEach { assertEquals(ancho(10), ancho(it), 0.5f, "ancho de $it% contra 10%") }
        assertEquals(izquierda(10), izquierda(25), 0.5f, "25% bajo 10%")
        assertEquals(izquierda(15), izquierda(30), 0.5f, "30% bajo 15%")
    }

    private val cinco = listOf(10, 15, 20, 25, 30)

    @Test fun `con cinco porcentajes las tarjetas del cliente miden lo mismo`() = runDesktopComposeUiTest(1280, 800) {
        val state = CustomerDisplayState()
        state.show(CustomerContent.Tip(total, cinco.map { TipOption(it, tipCentsForPercent(total, it)) }, selectedTipCents = null))
        setContent { MaterialTheme { CustomerDisplayScreen(state = state, onRating = {}, onTip = {}) } }
        cincoDelMismoAncho()
    }

    @Test fun `con cinco porcentajes las tarjetas del cajero miden lo mismo`() = runDesktopComposeUiTest(360, 640) {
        setContent {
            CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
                LocalView provides View(contexto)) {
                MaterialTheme {
                    TipSelectionSheet(
                        amountCents = total, tipBaseCents = total, tipSuggestions = cinco,
                        onTipSelected = {}, onSkip = {}, onDismiss = {},
                    )
                }
            }
        }
        cincoDelMismoAncho()
    }

    private fun SemanticsNodeInteraction.lineas(): Int {
        val resultados = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(resultados)
        return resultados.single().lineCount
    }

    @Test fun `seis porcentajes del cliente se ven y se tocan en 1280x800`() = runDesktopComposeUiTest(1280, 800) {
        val state = CustomerDisplayState()
        var entregado: Int? = null
        state.show(CustomerContent.Tip(total, seis.map { TipOption(it, tipCentsForPercent(total, it)) }, selectedTipCents = null))
        setContent { MaterialTheme { CustomerDisplayScreen(state = state, onRating = {}, onTip = { entregado = it }) } }

        seis.forEach { onNodeWithText("$it%").assertIsDisplayed() }
        onNodeWithText("Sin propina").assertIsDisplayed()
        // Dos filas de tres, y las tarjetas bajan de 150 dp a 120 dp para que quepan.
        assertEquals(1, seis.take(3).map { arriba(it) }.distinct().size, "10, 15 y 20 en la misma fila")
        assertEquals(1, seis.drop(3).map { arriba(it) }.distinct().size, "25, 30 y 35 en la misma fila")
        assertTrue(arriba(25) > arriba(10), "la segunda fila va debajo de la primera")
        seis.forEach { assertEquals(120f, alto(it), 0.5f, "alto de $it%") }

        onNodeWithText("35%").performClick()
        waitForIdle()
        assertEquals(tipCentsForPercent(total, 35), entregado)
    }

    @Test fun `tres porcentajes del cliente siguen en una fila de 150 dp`() = runDesktopComposeUiTest(1280, 800) {
        val state = CustomerDisplayState()
        state.show(CustomerContent.Tip(total, listOf(10, 15, 20).map { TipOption(it, tipCentsForPercent(total, it)) }, selectedTipCents = null))
        setContent { MaterialTheme { CustomerDisplayScreen(state = state, onRating = {}, onTip = {}) } }

        assertEquals(1, listOf(10, 15, 20).map { arriba(it) }.distinct().size)
        listOf(10, 15, 20).forEach { assertEquals(150f, alto(it), 0.5f) }
    }

    @Test fun `seis porcentajes del cajero en 360x640 con 12,345 pesos 67 centavos`() = runDesktopComposeUiTest(360, 640) {
        var entregado: Int? = null
        setContent {
            // La hoja del cajero toma la ventana inmersiva de LocalView: se arma como las superficies reales.
            CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
                LocalView provides View(contexto)) {
                MaterialTheme {
                    TipSelectionSheet(
                        amountCents = total, tipBaseCents = total, tipSuggestions = seis,
                        onTipSelected = { entregado = it }, onSkip = {}, onDismiss = {},
                    )
                }
            }
        }

        seis.forEach { onNodeWithText("$it%").assertIsDisplayed() }
        onNodeWithText("Continuar", substring = true).assertIsDisplayed()
        onNodeWithText("Sin propina").assertIsDisplayed()
        assertEquals(1, seis.take(3).map { arriba(it) }.distinct().size, "10, 15 y 20 en la misma fila")
        assertEquals(1, seis.drop(3).map { arriba(it) }.distinct().size, "25, 30 y 35 en la misma fila")
        assertTrue(arriba(25) > arriba(10), "la segunda fila va debajo de la primera")
        // Ningún monto de propina se parte en dos renglones, aun con el monto grande en una pantalla angosta.
        seis.forEach {
            val monto = formatMoney(tipCentsForPercent(total, it) / 100.0)
            assertEquals(1, onNodeWithText(monto, useUnmergedTree = true).lineas(), "$monto se partió")
        }

        onNodeWithText("25%").performClick()
        onNodeWithText("Continuar", substring = true).performClick()
        waitForIdle()
        assertEquals(tipCentsForPercent(total, 25), entregado)
    }
}
