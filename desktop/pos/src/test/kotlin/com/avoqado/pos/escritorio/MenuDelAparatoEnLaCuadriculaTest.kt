package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.presentation.checkout.TileSize
import com.avoqado.pos.pos.presentation.product.CuadriculaDeProductos
import com.avoqado.pos.pos.presentation.product.MenuDelAparato
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** «Menú del aparato» en la cuadrícula de Cobrar: la regla (`MenuDelAparato`) puesta en la pantalla, como la usa `CheckoutScreen`. */
@OptIn(ExperimentalTestApi::class)
class MenuDelAparatoEnLaCuadriculaTest {
    private val folder = Files.createTempDirectory("menu-del-aparato-cuadricula")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private val categorias = listOf(ProductCategory("a", "Bebidas"), ProductCategory("b", "Postres"), ProductCategory("c", "Snacks"))
    private val productos = listOf(
        Product(id = "p1", name = "Cafe", priceValue = 40.0, categoryId = "a"),
        Product(id = "p2", name = "Pastel", priceValue = 60.0, categoryId = "b"),
        Product(id = "p3", name = "Papas", priceValue = 30.0, categoryId = "c"),
    )

    /** Lo mismo que `CheckoutScreen`: `visibles` y `verTodo` son estado de la pantalla; la regla decide el filtro. */
    @Composable private fun pantalla(visibles: List<String>?, verTodo: Boolean, alVerTodo: () -> Unit) {
        val conProductos = remember { productos.mapNotNull { it.categoryId }.toSet() }
        val filtro = MenuDelAparato.filtroDeCobrar(visibles, verTodo, conProductos)
        CuadriculaDeProductos(
            allProductsUnfiltered = productos, categoriesUnfiltered = categorias, isLoading = false, familias = emptyMap(),
            packs = emptyList(), onPackTap = null, menuCategoryIds = filtro,
            onVerTodo = if (filtro != null) alVerTodo else null, tileSize = TileSize.MEDIANO,
            onProductTap = {}, onFamiliaTap = {},
        )
    }

    @Composable private fun escena(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(context), LocalEsEscritorio provides true,
        ) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    @Test fun `con filtro solo salen sus categorias y la tarjeta Todo`() = runComposeUiTest {
        setContent { escena { pantalla(listOf("a", "b"), verTodo = false, alVerTodo = {}) } }
        onNodeWithText("Bebidas").assertExists()
        onNodeWithText("Postres").assertExists()
        onNodeWithText("Snacks").assertDoesNotExist()
        onNodeWithText("Todo ▸").assertExists()
    }

    @Test fun `tocar Todo muestra todas`() = runComposeUiTest {
        setContent { escena { var verTodo by remember { mutableStateOf(false) }; pantalla(listOf("a"), verTodo, { verTodo = true }) } }
        onNodeWithText("Snacks").assertDoesNotExist()
        onNodeWithText("Todo ▸").performClick()
        onNodeWithText("Snacks").assertExists()
        onNodeWithText("Bebidas").assertExists()
        onNodeWithText("Todo ▸").assertDoesNotExist()
    }

    @Test fun `cambiar el filtro con una categoria abierta que ya no esta regresa a la raiz`() = runComposeUiTest {
        var visibles by mutableStateOf<List<String>?>(listOf("a", "b"))
        setContent { escena { pantalla(visibles, verTodo = false, alVerTodo = {}) } }
        onNodeWithText("Postres").performClick()
        onNodeWithText("Todos los productos").assertExists() // migas: hay una categoria abierta
        onNodeWithText("Pastel").assertExists()
        visibles = listOf("a")
        waitForIdle()
        onNodeWithText("Todos los productos").assertDoesNotExist()
        onNodeWithText("Bebidas").assertExists()
        onNodeWithText("Todo ▸").assertExists()
    }

    @Test fun `filtro sin productos muestra todo`() = runComposeUiTest {
        setContent { escena { pantalla(listOf("viejo"), verTodo = false, alVerTodo = {}) } }
        onNodeWithText("Snacks").assertExists()
        onNodeWithText("Todo ▸").assertDoesNotExist()
    }
}
