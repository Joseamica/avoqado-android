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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.presentation.categories.CategoryListView
import com.avoqado.pos.articles.presentation.coupons.CouponListView
import com.avoqado.pos.articles.presentation.creditpacks.CreditPackListView
import com.avoqado.pos.articles.presentation.discounts.DiscountListView
import com.avoqado.pos.articles.presentation.modifiers.ModifierGroupListView
import com.avoqado.pos.articles.presentation.options.OptionsView
import com.avoqado.pos.articles.presentation.products.ProductListView
import com.avoqado.pos.articles.presentation.units.UnitsView
import com.avoqado.pos.designsystem.components.CircleBackButton
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
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar (280dp)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Header: back button on its own line, title below
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
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

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    } // end outer Box
}

// MARK: - Phone Layout

@Composable
private fun PhoneArticlesLayout(
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    var showingSection by remember { mutableStateOf<ArticleSection?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    if (showingSection != null) {
        val section = showingSection!!
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = { showingSection = null })
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
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
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
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

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    } // end outer Box
}

// MARK: - Section Row

@Composable
private fun SectionRow(
    section: ArticleSection,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.background(bgColor)
                } else {
                    Modifier.background(bgColor)
                }
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left border bar for selected state
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = section.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = if (isSelected) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
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
        ArticleSection.OPTIONS -> OptionsView(viewModel = viewModel)
        ArticleSection.DISCOUNTS -> DiscountListView(viewModel = viewModel)
        ArticleSection.COUPONS -> CouponListView(viewModel = viewModel)
        ArticleSection.CREDIT_PACKS -> CreditPackListView(viewModel = viewModel)
        ArticleSection.UNITS -> UnitsView(viewModel = viewModel)
    }
}
