package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.ModifierGroupData
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductModifierGroupEntry
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.avoqado.pos.pos.data.model.Modifier as Extra

/**
 * D3 (17-nov): con la ficha de un producto abierta, la pistola trae OTRO producto (el mostrador cambia el producto de la
 * ficha). Antes la ficha nueva heredaba la cantidad y los extras del anterior: el té salía ×3 y con «Avena» del café.
 */
@OptIn(ExperimentalTestApi::class)
class FichaAlEscanearOtroProductoTest {
    private val folder = Files.createTempDirectory("ficha-al-escanear")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private fun conExtra(id: String, nombre: String, precio: Double, grupo: String, extra: String, precioExtra: Double) = Product(
        id = id, name = nombre, priceValue = precio,
        modifierGroups = listOf(
            ProductModifierGroupEntry(
                groupId = "g-$id",
                group = ModifierGroupData(
                    id = "g-$id", name = grupo, allowMultiple = true,
                    modifiers = listOf(Extra(id = "m-$id", name = extra, price = precioExtra)),
                ),
            ),
        ),
    )

    @Test fun `la pistola trae otro producto con la ficha abierta y la ficha empieza de cero`() = runComposeUiTest {
        val cafe = conExtra("cafe", "Café", 40.0, "Leche", "Avena", 10.0)
        val te = conExtra("te", "Té", 30.0, "Endulzante", "Miel", 5.0)
        var abierto by mutableStateOf(cafe)
        var agregado: Pair<Int, List<SelectedModifier>>? = null
        setContent {
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
                LocalView provides View(context), LocalEsEscritorio provides true,
            ) {
                AvoqadoTheme {
                    ProductDetailPanel(
                        product = abierto,
                        isTablet = true,
                        onAddToCart = { cantidad, extras, _, _, _, _, _, _ -> agregado = cantidad to extras },
                        onDismiss = {},
                    )
                }
            }
        }
        onNodeWithText("Avena").performClick()
        onNodeWithContentDescription("Más").performClick()
        onNodeWithContentDescription("Más").performClick()
        assertTrue(onAllNodesWithText("$150.00").fetchSemanticsNodes().isNotEmpty(), "el café no quedó ×3 con Avena")

        abierto = te // lo que hace el mostrador al leer el código del té
        waitForIdle()

        assertTrue(onAllNodesWithText("$30.00").fetchSemanticsNodes().isNotEmpty(), "el té no empezó en su precio")
        onNodeWithText("Agregar al carrito").performClick()
        assertEquals(1 to emptyList<SelectedModifier>(), agregado)
    }
}
