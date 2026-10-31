package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.checkout.CheckoutLayoutPrefs
import com.avoqado.pos.pos.presentation.checkout.TileSize
import com.avoqado.pos.pos.presentation.product.variantes.EntradaDeCatalogo
import com.avoqado.pos.pos.presentation.product.variantes.Familia
import com.avoqado.pos.pos.presentation.product.variantes.HojaDeLaFamiliaAbierta
import com.avoqado.pos.pos.presentation.product.variantes.TileDeFamilia
import com.avoqado.pos.pos.presentation.product.variantes.agruparCatalogo

/**
 * Product grid with categories-first navigation matching iOS.
 * Shows category tiles first, then drills into products when a category is tapped.
 * Uncategorized products are shown alongside category tiles.
 */
@Composable
fun ProductGridView(
    viewModel: CartViewModel,
    onProductTap: (Product) -> Unit,
    onPackTap: ((com.avoqado.pos.articles.data.model.CreditPack) -> Unit)? = null,
    /** Menús por horario: cuando viene, la cuadrícula solo muestra las categorías
     *  (y sus productos) del menú activo. Null = catálogo completo, como siempre. */
    menuCategoryIds: Set<String>? = null,
    /** Qué tan densa se ve la cuadrícula EN ESTE APARATO. El default lee la
     *  preferencia local, así que las pantallas que no la manejan (mesas) la
     *  respetan sin tener que enterarse. El mostrador la pasa explícita para
     *  que el cambio se vea sin salir de la pantalla. */
    tileSize: TileSize = CheckoutLayoutPrefs.tileSize(androidx.compose.ui.platform.LocalContext.current),
) {
    // Agotado AVISA, nunca bloquea (Square-parity 2026-08-12): el tap agrega
    // siempre — el registro puede estar desfasado y el producto sí existir en
    // el anaquel. El aviso lo emite el ViewModel al agregar; el chip del tile
    // conserva la etiqueta. (Antes el tap abría un modal que mataba la venta.)
    val tapOrInfo: (Product) -> Unit = { p -> onProductTap(p) }

    val allProductsUnfiltered by viewModel.products.collectAsState()
    val categoriesUnfiltered by viewModel.categories.collectAsState()
    val allProducts = remember(allProductsUnfiltered, menuCategoryIds) {
        if (menuCategoryIds == null) allProductsUnfiltered
        else allProductsUnfiltered.filter { it.categoryId != null && it.categoryId in menuCategoryIds }
    }
    val categories = remember(categoriesUnfiltered, menuCategoryIds) {
        if (menuCategoryIds == null) categoriesUnfiltered
        else categoriesUnfiltered.filter { it.id in menuCategoryIds }
    }
    val isLoading by viewModel.isLoading.collectAsState()
    // Variantes: la familia ocupa el lugar de su primera variante; sin familias, la cuadrícula de hoy (mismas llaves y orden).
    val familias by viewModel.familias.collectAsState()
    // La hoja abierta guarda sólo el groupId y lee la familia VIGENTE (Codex 7).
    var abierta by remember { mutableStateOf<String?>(null) }

    // Prepaid credit packs (membresías), shown as tiles when onPackTap is set.
    val creditsVM: com.avoqado.pos.customers.presentation.CustomerCreditsViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val packs by creditsVM.packs.collectAsState()
    androidx.compose.runtime.LaunchedEffect(onPackTap != null) {
        if (onPackTap != null) creditsVM.loadPacks()
    }

    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }

    // Reset category when products clear (venue switch)
    val isEmpty = allProducts.isEmpty()
    androidx.compose.runtime.LaunchedEffect(isEmpty) {
        if (isEmpty && selectedCategory != null) {
            selectedCategory = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Breadcrumb navigation (only when viewing a category)
        if (selectedCategory != null) {
            BreadcrumbNavigation(
                categoryName = selectedCategory?.name ?: "",
                onBack = { selectedCategory = null },
            )
        }

        // Content
        when {
            isLoading && allProducts.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AvoqadoBrandLoader(size = 72.dp)
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                        Text(
                            text = "Cargando productos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            selectedCategory == null -> {
                // Categories + uncategorized products grid
                val uncategorized = remember(allProducts) { allProducts.filter { it.categoryId == null && it.category == null } }
                val sueltos = remember(uncategorized, familias) { agruparCatalogo(uncategorized, familias) }

                if (categories.isEmpty() && uncategorized.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No hay productos",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CategoriesGrid(
                        categories = categories,
                        uncategorized = sueltos,
                        onCategoryTap = { selectedCategory = it },
                        onProductTap = tapOrInfo,
                        onFamiliaTap = { abierta = it.groupId },
                        packs = if (onPackTap != null) packs else emptyList(),
                        onPackTap = onPackTap,
                        tileSize = tileSize,
                    )
                }
            }
            else -> {
                // Products in selected category
                val categoryProducts = remember(allProducts, selectedCategory) {
                    allProducts.filter { it.categoryId == selectedCategory?.id || it.category?.id == selectedCategory?.id }
                }

                if (categoryProducts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No hay productos en esta categoría",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    ProductsGrid(
                        entradas = remember(categoryProducts, familias) { agruparCatalogo(categoryProducts, familias) },
                        onProductTap = tapOrInfo,
                        onFamiliaTap = { abierta = it.groupId },
                        tileSize = tileSize,
                    )
                }
            }
        }
    }

    // La variante elegida sigue el MISMO camino que tocar un producto suelto en esta pantalla (R1).
    HojaDeLaFamiliaAbierta(viewModel, abierta, onCerrar = { abierta = null }, onElegir = tapOrInfo)
}

// MARK: - Breadcrumb Navigation

@Composable
private fun BreadcrumbNavigation(
    categoryName: String,
    onBack: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button
            CircleBackButton(onClick = onBack)

            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

            // "Todos los productos" (clickable, gray text)
            Text(
                text = "Todos los productos",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onBack),
            )

            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

            // Chevron
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

            // Category name (bold, underlined)
            Text(
                text = categoryName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        HorizontalDivider()
    }
}

// MARK: - Categories Grid

@Composable
private fun CategoriesGrid(
    categories: List<ProductCategory>,
    uncategorized: List<EntradaDeCatalogo>,
    onCategoryTap: (ProductCategory) -> Unit,
    onProductTap: (Product) -> Unit,
    onFamiliaTap: (Familia) -> Unit,
    packs: List<com.avoqado.pos.articles.data.model.CreditPack> = emptyList(),
    onPackTap: ((com.avoqado.pos.articles.data.model.CreditPack) -> Unit)? = null,
    tileSize: TileSize,
) {
    LazyVerticalGrid(
        // Adaptive y no Fixed(3): el mismo código corre en un panel de 640 dp y
        // en un teléfono de 400. Con Fixed(3) el teléfono heredaba las columnas
        // de la tablet y los tiles salían enormes en los dos.
        columns = GridCells.Adaptive(tileSize.minTileWidthDp.dp),
        contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Category tiles first
        items(categories, key = { "cat_${it.id}" }) { category ->
            CategoryTile(
                category = category,
                onClick = { onCategoryTap(category) },
                tileSize = tileSize,
            )
        }

        // Uncategorized products after categories (las llaves de hoy: `prod_<id>`)
        items(uncategorized, key = { llaveDe(it, prefijo = "prod_") }) { entrada ->
            TileDeEntrada(entrada, onProductTap, onFamiliaTap, tileSize)
        }

        // Prepaid credit packs (membresías) — sellable straight from the grid
        if (onPackTap != null) {
            items(packs, key = { "pack_${it.id}" }) { pack ->
                CreditPackTile(pack = pack, onClick = { onPackTap(pack) }, tileSize = tileSize)
            }
        }
    }
}

// MARK: - Products Grid

@Composable
private fun ProductsGrid(
    entradas: List<EntradaDeCatalogo>,
    onProductTap: (Product) -> Unit,
    onFamiliaTap: (Familia) -> Unit,
    tileSize: TileSize,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(tileSize.minTileWidthDp.dp),
        contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Dentro de una categoría la llave de hoy es el id pelón (Codex 8).
        items(entradas, key = { llaveDe(it, prefijo = "") }) { entrada ->
            TileDeEntrada(entrada, onProductTap, onFamiliaTap, tileSize)
        }
    }
}

/** La llave de HOY para un producto suelto (`prefijo` + id) y `fam_<groupId>` para una familia. */
private fun llaveDe(entrada: EntradaDeCatalogo, prefijo: String): String = when (entrada) {
    is EntradaDeCatalogo.Suelto -> prefijo + entrada.producto.id
    is EntradaDeCatalogo.DeFamilia -> "fam_${entrada.familia.groupId}"
}

@Composable
private fun TileDeEntrada(
    entrada: EntradaDeCatalogo,
    onProductTap: (Product) -> Unit,
    onFamiliaTap: (Familia) -> Unit,
    tileSize: TileSize,
) {
    when (entrada) {
        is EntradaDeCatalogo.Suelto -> ProductTile(
            product = entrada.producto,
            onClick = { onProductTap(entrada.producto) },
            tileSize = tileSize,
        )
        is EntradaDeCatalogo.DeFamilia -> TileDeFamilia(entrada.familia, tileSize, onTap = { onFamiliaTap(entrada.familia) })
    }
}

// MARK: - Category Tile

@Composable
private fun CreditPackTile(
    pack: com.avoqado.pos.articles.data.model.CreditPack,
    onClick: () -> Unit,
    tileSize: TileSize,
) {
    val bg = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(tileSize.categoryAspect)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        if (tileSize.mostrarIcono) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier
                    .padding(tileSize.paddingDp.dp)
                    .size(tileSize.iconoDp.dp)
                    .align(Alignment.TopStart),
                tint = fg.copy(alpha = 0.9f),
            )
        }
        Column(
            modifier = Modifier
                .align(if (tileSize.mostrarIcono) Alignment.BottomStart else Alignment.CenterStart)
                .padding(tileSize.paddingDp.dp),
        ) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${pack.creditCount} créditos · ${pack.displayPrice}",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryTile(
    category: ProductCategory,
    onClick: () -> Unit,
    tileSize: TileSize,
) {
    // El color del negocio sólo si es válido; la letra encima se elige por lo claro del fondo (blanco sobre amarillo
    // no se lee — «Postres», iOS 30-oct; espejo de `Color.letraSobre(hex:)` en iOS).
    val colorDelNegocio = category.color?.takeIf { it.isNotEmpty() }?.let { hex -> parseHexColor(hex)?.let { hex } }
    val bgColor = colorDelNegocio?.let { parseHexColor(it) } ?: MaterialTheme.colorScheme.primary
    val contentColor = colorDelNegocio?.let { letraSobreColorDelNegocio(it, MaterialTheme.colorScheme.onSurface) }
        ?: MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(tileSize.categoryAspect)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(bgColor)
            .clickable(onClick = onClick),
    ) {
        // Grid icon top-left. En compacto no cabe junto al nombre: gana el
        // nombre, que es lo que el cajero lee para decidir.
        if (tileSize.mostrarIcono) {
            Icon(
                imageVector = Icons.Default.GridView,
                contentDescription = null,
                modifier = Modifier
                    .padding(tileSize.paddingDp.dp)
                    .size(tileSize.iconoDp.dp)
                    .align(Alignment.TopStart),
                tint = contentColor.copy(alpha = 0.8f),
            )
        }

        Text(
            text = category.name,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(if (tileSize.mostrarIcono) Alignment.BottomStart else Alignment.CenterStart)
                .padding(tileSize.paddingDp.dp),
        )
    }
}

// MARK: - Product Tile

@Composable
private fun ProductTile(
    product: Product,
    onClick: () -> Unit,
    tileSize: TileSize,
) {
    val (bgColor, colorDeIniciales) = coloresDeTile(product.color)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            ),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        // Agotado ya NO se atenúa: el tile sigue vendible (Square-parity
        // 2026-08-12) y la etiqueta viaja en el chip de la esquina.
        Column {
            // Image container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(tileSize.productImageAspect)
                    .background(bgColor)
                    .clip(RoundedCornerShape(
                        topStart = AvoqadoTheme.cornerRadius.md,
                        topEnd = AvoqadoTheme.cornerRadius.md,
                    )),
            ) {
                if (!product.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = product.imageUrl,
                        contentDescription = product.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // Initials (2 letters, bottom-left, bold)
                    Text(
                        text = product.name.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colorDeIniciales,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(AvoqadoTheme.spacing.sm),
                    )
                }

                // «Agotado» y «tiene modificadores» comparten la esquina de arriba a la derecha: van en UNA fila para que
                // no se encimen (los dos alineados a TopEnd se pintaban uno sobre otro; visto en iOS el 30-oct, igual aquí).
                Row(
                    modifier = Modifier.align(Alignment.TopEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (product.isOutOfStock) {
                        val existencia = product.availableQuantityExact ?: product.availableQuantity?.toDouble()
                        // Con peso: el ícono de modificadores se mide primero y nunca se queda sin lugar; el chip toma lo
                        // que sobra y se recorta con «…» (Codex r1 #2: una existencia larga lo dejaba en cero).
                        ChipDeAgotado(
                            texto = if (existencia != null && existencia < 0) "Agotado · ${formatChipQty(existencia)}" else "Agotado · 0",
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }

                    // Modifier indicator
                    if (product.hasModifiers) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Tiene modificadores",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(AvoqadoTheme.spacing.xs)
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                                )
                                .padding(AvoqadoTheme.spacing.xs)
                                .size(14.dp),
                        )
                    }
                }

                // Variants badge (top-left)
                if (!product.variants.isNullOrEmpty() && product.variants.size > 1) {
                    InsigniaDeTile(texto = "${product.variants.size} variantes", modifier = Modifier.align(Alignment.TopStart))
                }
            }

            // Product info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))

                Text(
                    text = product.displayPrice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Piezas del tile que comparte el botón de una familia (`TileDeFamilia`): mismo aspecto que `ProductTile`

/**
 * Fondo de la imagen y color de las iniciales: el color propio del producto si lo trae (con letra oscura si el color es
 * claro); si no, los del tema.
 */
@Composable
internal fun coloresDeTile(hexColor: String?): Pair<Color, Color> {
    val fondo = hexColor?.let { parseHexColor(it) }
    return Pair(
        fondo ?: MaterialTheme.colorScheme.surfaceVariant,
        if (fondo != null && hexColor != null) {
            letraSobreColorDelNegocio(hexColor, MaterialTheme.colorScheme.onSurface)
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/** Insignia de arriba a la izquierda («N variantes»): oscura sobre la foto o el color del tile, en cualquier tema. */
@Composable
internal fun InsigniaDeTile(texto: String, modifier: Modifier) {
    Text(
        text = texto,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .padding(AvoqadoTheme.spacing.sm)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
            .padding(horizontal = AvoqadoTheme.spacing.xs, vertical = AvoqadoTheme.spacing.xxs),
    )
}

/**
 * Chip «Agotado» de la esquina, no badge central: informa sin estorbar el tap — el producto SIGUE vendible y el stock quedará en
 * negativo como señal (mockup aprobado 2026-08-12).
 */
@Composable
internal fun ChipDeAgotado(texto: String, modifier: Modifier) {
    Text(
        text = texto,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(AvoqadoTheme.spacing.xs)
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = 2.dp),
    )
}

// MARK: - Helpers

private fun parseHexColor(hex: String): Color? {
    return try {
        val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
        Color(android.graphics.Color.parseColor(cleanHex))
    } catch (_: Exception) {
        null
    }
}

/** "−2" · "−0.75": el chip del tile muestra el faltante sin decimales de sobra. */
private fun formatChipQty(value: Double): String {
    val abs = kotlin.math.abs(value)
    val texto = if (abs == abs.toLong().toDouble()) abs.toLong().toString() else String.format("%.2f", abs)
    return "−$texto"
}
