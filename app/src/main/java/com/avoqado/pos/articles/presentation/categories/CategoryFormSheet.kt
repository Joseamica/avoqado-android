package com.avoqado.pos.articles.presentation.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.avoqado.pos.articles.data.model.ArticleCategory
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Color palette

private val categoryColors = listOf(
    "#6B7280", "#EF4444", "#F97316", "#EAB308",
    "#22C55E", "#10B981", "#06B6D4", "#3B82F6",
    "#6366F1", "#8B5CF6", "#D946EF", "#EC4899",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFormSheet(
    category: ArticleCategory?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    var name by remember { mutableStateOf(category?.name ?: "") }
    var description by remember { mutableStateOf(category?.description ?: "") }
    var selectedColor by remember { mutableStateOf(category?.color) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = category != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            // MARK: - Title
            Text(
                text = if (isEditing) "Editar categoria" else "Nueva categoria",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - Name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre de la categoria") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = name.isBlank(),
            )

            // MARK: - Description field
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Descripcion (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            // MARK: - Color picker
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "COLOR",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    userScrollEnabled = false,
                ) {
                    items(categoryColors) { hex ->
                        val circleColor = try {
                            Color(android.graphics.Color.parseColor(hex))
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                        val isSelected = selectedColor == hex

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(color = circleColor, shape = CircleShape)
                                .clickable { selectedColor = if (isSelected) null else hex },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Seleccionado",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // MARK: - Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancelar")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (isEditing) {
                            viewModel.updateCategory(
                                categoryId = category!!.id,
                                name = name,
                                description = description.ifBlank { null },
                                color = selectedColor,
                            )
                        } else {
                            viewModel.createCategory(
                                name = name,
                                description = description.ifBlank { null },
                                color = selectedColor,
                            )
                        }
                        onDismiss()
                    },
                    enabled = name.isNotBlank() && !isSaving,
                ) {
                    Text(text = if (isEditing) "Guardar" else "Crear")
                }
            }
        }
    }
}
