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
                val uncategorized = allProducts.filter { it.categoryId == null && it.category == null }

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
                        uncategorizedProducts = uncategorized,
                        onCategoryTap = { selectedCategory = it },
                        onProductTap = tapOrInfo,
                        packs = if (onPackTap != null) packs else emptyList(),
                        onPackTap = onPackTap,
                    )
                }
            }
            else -> {
                // Products in selected category
                val categoryProducts = allProducts.filter {
                    it.categoryId == selectedCategory?.id || it.category?.id == selectedCategory?.id
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
                        products = categoryProducts,
                        onProductTap = tapOrInfo,
                    )
                }
            }
        }
    }
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
    uncategorizedProducts: List<Product>,
    onCategoryTap: (ProductCategory) -> Unit,
    onProductTap: (Product) -> Unit,
    packs: List<com.avoqado.pos.articles.data.model.CreditPack> = emptyList(),
    onPackTap: ((com.avoqado.pos.articles.data.model.CreditPack) -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Category tiles first
        items(categories, key = { "cat_${it.id}" }) { category ->
            CategoryTile(
                category = category,
                onClick = { onCategoryTap(category) },
            )
        }

        // Uncategorized products after categories
        items(uncategorizedProducts, key = { "prod_${it.id}" }) { product ->
            ProductTile(
                product = product,
                onClick = { onProductTap(product) },
            )
        }

        // Prepaid credit packs (membresías) — sellable straight from the grid
        if (onPackTap != null) {
            items(packs, key = { "pack_${it.id}" }) { pack ->
                CreditPackTile(pack = pack, onClick = { onPackTap(pack) })
            }
        }
    }
}

// MARK: - Products Grid

@Composable
private fun ProductsGrid(
    products: List<Product>,
    onProductTap: (Product) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        items(products, key = { it.id }) { product ->
            ProductTile(
                product = product,
                onClick = { onProductTap(product) },
            )
        }
    }
}

// MARK: - Category Tile

@Composable
private fun CreditPackTile(
    pack: com.avoqado.pos.articles.data.model.CreditPack,
    onClick: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            modifier = Modifier
                .padding(AvoqadoTheme.spacing.md)
                .size(24.dp)
                .align(Alignment.TopStart),
            tint = fg.copy(alpha = 0.9f),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(AvoqadoTheme.spacing.md),
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
) {
    val hasCustomColor = !category.color.isNullOrEmpty()
    val bgColor = if (hasCustomColor) {
        parseHexColor(category.color!!) ?: MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val contentColor = if (hasCustomColor) Color.White else MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(bgColor)
            .clickable(onClick = onClick),
    ) {
        // Grid icon top-left
        Icon(
            imageVector = Icons.Default.GridView,
            contentDescription = null,
            modifier = Modifier
                .padding(AvoqadoTheme.spacing.md)
                .size(24.dp)
                .align(Alignment.TopStart),
            tint = contentColor.copy(alpha = 0.8f),
        )

        // Category name bottom-left
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(AvoqadoTheme.spacing.md),
        )
    }
}

// MARK: - Product Tile

@Composable
private fun ProductTile(
    product: Product,
    onClick: () -> Unit,
) {
    val bgColor = product.color?.let { parseHexColor(it) }
        ?: MaterialTheme.colorScheme.surfaceVariant

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
                    .aspectRatio(1.2f)
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
                        color = if (product.color != null) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(AvoqadoTheme.spacing.sm),
                    )
                }

                if (product.isOutOfStock) {
                    // Chip en la esquina, no badge central: informa sin estorbar
                    // el tap — el producto SIGUE vendible y el stock quedará en
                    // negativo como señal (mockup aprobado 2026-08-12).
                    val existencia = product.availableQuantityExact ?: product.availableQuantity?.toDouble()
                    Text(
                        text = if (existencia != null && existencia < 0) "Agotado · ${formatChipQty(existencia)}" else "Agotado · 0",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AvoqadoTheme.spacing.xs)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = 2.dp),
                    )
                }

                // Variants badge (top-left)
                if (!product.variants.isNullOrEmpty() && product.variants.size > 1) {
                    Text(
                        text = "${product.variants.size} variantes",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(AvoqadoTheme.spacing.sm)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                            )
                            .padding(
                                horizontal = AvoqadoTheme.spacing.xs,
                                vertical = AvoqadoTheme.spacing.xxs,
                            ),
                    )
                }

                // Modifier indicator (top-right)
                if (product.hasModifiers) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Tiene modificadores",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AvoqadoTheme.spacing.sm)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                            )
                            .padding(AvoqadoTheme.spacing.xs)
                            .size(14.dp),
                    )
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
