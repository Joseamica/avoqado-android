package com.avoqado.pos.articles.presentation.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Create Option Sheet

@Composable
fun CreateOptionSheet(
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    var optionName by remember { mutableStateOf("") }
    val values = remember { mutableStateListOf("") }

    AvoqadoFullScreenModal(
        title = "Nueva opción",
        onDismiss = onDismiss,
        primaryActionText = if (isSaving) "Creando..." else "Crear",
        onPrimaryAction = {
            val validValues = values.filter { it.isNotBlank() }
            viewModel.createProductOption(
                name = optionName,
                values = validValues,
                onSuccess = { onDismiss() },
            )
        },
        primaryActionEnabled = optionName.isNotBlank() && values.any { it.isNotBlank() } && !isSaving,
        primaryActionDisabledReason = when {
            isSaving -> null
            optionName.isBlank() -> "Ponle nombre a la opción"
            else -> "Agrega al menos un valor a la opción"
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            // Option name
            OutlinedTextField(
                value = optionName,
                onValueChange = { optionName = it },
                label = { Text("Nombre de la opción") },
                placeholder = { Text("Ej: Talla, Color, Material") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Values section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "VALORES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { values.add("") }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.height(AvoqadoTheme.spacing.lg),
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                    Text("Agregar valor")
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            values.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { values[index] = it },
                        label = { Text("Valor ${index + 1}") },
                        placeholder = { Text("Ej: Grande, Rojo") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    if (values.size > 1) {
                        IconButton(onClick = { values.removeAt(index) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Eliminar valor",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (index < values.lastIndex) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                }
            }
        }
    }
}
