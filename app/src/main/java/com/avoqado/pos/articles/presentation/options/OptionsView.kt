package com.avoqado.pos.articles.presentation.options

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.avoqado.pos.articles.data.model.ProductOption
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Options List View

@Composable
fun OptionsView(
    viewModel: ArticlesViewModel,
) {
    val options by viewModel.productOptions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseListMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val contentHorizontalPadding = if (denseListMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val headerVerticalPadding = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md
    val rowVerticalPadding = if (denseListMode) 9.dp else AvoqadoTheme.spacing.md

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with "+" button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPadding, vertical = headerVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Opciones de producto",
                style = if (denseListMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { showCreateSheet = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                Text("Crear")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (options.isEmpty() && !isLoading) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                Text(
                    text = "Sin opciones de producto",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                Text(
                    text = "Crea opciones como Talla, Color o Material\npara variantes de tus productos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xxl),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(options, key = { it.id }) { option ->
                    OptionRow(
                        option = option,
                        denseMode = denseListMode,
                        horizontalPadding = contentHorizontalPadding,
                        rowVerticalPadding = rowVerticalPadding,
                        onEdit = {
                            Toast.makeText(context, "Próximamente", Toast.LENGTH_SHORT).show()
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateOptionSheet(
            viewModel = viewModel,
            onDismiss = { showCreateSheet = false },
        )
    }
}

// MARK: - Option Row

@Composable
private fun OptionRow(
    option: ProductOption,
    denseMode: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    rowVerticalPadding: androidx.compose.ui.unit.Dp,
    onEdit: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(horizontal = horizontalPadding, vertical = rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.name,
                style = if (denseMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (option.valuesPreview.isNotEmpty()) {
                Text(
                    text = option.valuesPreview,
                    style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "${option.valueCount} valores",
            style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
