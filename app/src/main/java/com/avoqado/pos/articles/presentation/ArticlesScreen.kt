package com.avoqado.pos.articles.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.presentation.categories.CategoryListView
import com.avoqado.pos.articles.presentation.coupons.CouponListView
import com.avoqado.pos.articles.presentation.creditpacks.CreditPackListView
import com.avoqado.pos.articles.presentation.discounts.DiscountListView
import com.avoqado.pos.articles.presentation.modifiers.ModifierGroupListView
import com.avoqado.pos.articles.presentation.products.ProductListView
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Entry Point

@Composable
fun ArticlesScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    viewModel: ArticlesViewModel = hiltViewModel(),
) {
    if (isTablet) {
        TabletArticlesLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    } else {
        PhoneArticlesLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    }
}

// MARK: - Tablet Layout

@Composable
private fun TabletArticlesLayout(
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val selectedSection by viewModel.selectedSection.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar (280dp)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Header: back arrow + title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Artículos",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Section rows
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ArticleSection.entries.forEach { section ->
                    SectionRow(
                        section = section,
                        isSelected = section == selectedSection,
                        onClick = { viewModel.selectSection(section) },
                    )
                }
            }
        }

        // Hairline divider
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            SectionContent(section = selectedSection, viewModel = viewModel)
        }
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneArticlesLayout(
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    var showingSection by remember { mutableStateOf<ArticleSection?>(null) }

    if (showingSection != null) {
        val section = showingSection!!
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showingSection = null }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(modifier = Modifier.weight(1f)) {
                SectionContent(section = section, viewModel = viewModel)
            }
        }
    } else {
        // Section list
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Artículos",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                ArticleSection.entries.forEach { section ->
                    SectionRow(
                        section = section,
                        isSelected = false,
                        onClick = {
                            viewModel.selectSection(section)
                            showingSection = section
                        },
                    )
                }
            }
        }
    }
}

// MARK: - Section Row

@Composable
private fun SectionRow(
    section: ArticleSection,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = sectionIcon(section),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Text(
            text = section.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Section Content Dispatcher

@Composable
private fun SectionContent(
    section: ArticleSection,
    viewModel: ArticlesViewModel,
) {
    when (section) {
        ArticleSection.PRODUCTS -> ProductListView(viewModel = viewModel)
        ArticleSection.CATEGORIES -> CategoryListView(viewModel = viewModel)
        ArticleSection.MODIFIERS -> ModifierGroupListView(viewModel = viewModel)
        ArticleSection.DISCOUNTS -> DiscountListView(viewModel = viewModel)
        ArticleSection.COUPONS -> CouponListView(viewModel = viewModel)
        ArticleSection.CREDIT_PACKS -> CreditPackListView(viewModel = viewModel)
    }
}

// MARK: - Section Icon Helper

private fun sectionIcon(section: ArticleSection): ImageVector = when (section) {
    ArticleSection.PRODUCTS -> Icons.Filled.LocalOffer
    ArticleSection.CATEGORIES -> Icons.Filled.Folder
    ArticleSection.MODIFIERS -> Icons.Filled.Tune
    ArticleSection.DISCOUNTS -> Icons.Filled.Percent
    ArticleSection.COUPONS -> Icons.Filled.ConfirmationNumber
    ArticleSection.CREDIT_PACKS -> Icons.Filled.CreditCard
}
