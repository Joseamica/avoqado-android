package com.avoqado.pos.articles.presentation.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Color palette

private val categoryColors = listOf(
    "#6B7280", "#EF4444", "#F97316", "#EAB308",
    "#22C55E", "#10B981", "#06B6D4", "#3B82F6",
    "#6366F1", "#8B5CF6", "#D946EF", "#EC4899",
)

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

    val isEditing = category != null

    AvoqadoFullScreenModal(
        title = if (isEditing) "Editar categoria" else "Nueva categoria",
        onDismiss = onDismiss,
        primaryActionText = if (isEditing) "Guardar" else "Crear",
        onPrimaryAction = {
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
        primaryActionEnabled = name.isNotBlank() && !isSaving,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
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

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    categoryColors.chunked(6).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                        ) {
                            rowColors.forEach { hex ->
                                val circleColor = remember(hex) {
                                    try {
                                        Color(android.graphics.Color.parseColor(hex))
                                    } catch (_: Exception) {
                                        null
                                    }
                                } ?: MaterialTheme.colorScheme.outlineVariant
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

                            repeat(6 - rowColors.size) {
                                Spacer(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
