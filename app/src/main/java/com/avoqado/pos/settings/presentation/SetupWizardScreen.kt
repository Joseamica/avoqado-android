package com.avoqado.pos.settings.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

// MARK: - Setup Wizard Data

data class SetupTask(
    val title: String,
    val description: String,
    val isCompleted: Boolean,
)

// MARK: - Setup Wizard Screen

@Composable
fun SetupWizardScreen(
    onDismiss: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val isSavingTipTaxSetting by viewModel.isSavingTipTaxSetting.collectAsState()

    val tasks = listOf(
        SetupTask(
            title = "Crear tu primer producto",
            description = "Agrega productos o servicios a tu catalogo",
            isCompleted = true,
        ),
        SetupTask(
            title = "Configurar metodo de pago",
            description = "Conecta una terminal de pago o habilita pagos en efectivo",
            isCompleted = true,
        ),
        SetupTask(
            title = "Agregar empleados",
            description = "Invita a tu equipo y asigna roles",
            isCompleted = false,
        ),
        SetupTask(
            title = "Configurar impuestos",
            description = "Define las tasas de impuesto para tus productos",
            isCompleted = false,
        ),
        SetupTask(
            title = "Personalizar recibos",
            description = "Agrega tu logo y datos de contacto a los recibos",
            isCompleted = false,
        ),
    )

    val completedCount = tasks.count { it.isCompleted }
    val totalCount = tasks.size
    val progress = completedCount.toFloat() / totalCount.toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
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
                text = "Configuracion",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                    Text(
                        text = "Propinas e IVA",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                    Text(
                        text = "Define sobre qué base se calcula el porcentaje de propina.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    TipTaxToggleRow(
                        checked = settings.includeTaxInTipBase,
                        enabled = !isSavingTipTaxSetting,
                        onCheckedChange = viewModel::updateIncludeTaxInTipBase,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                    Text(
                        text = if (settings.includeTaxInTipBase) {
                            "La propina porcentual se calcula sobre el total con IVA."
                        } else {
                            "La propina porcentual se calcula antes de IVA."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Progress card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                    Text(
                        text = "Progreso de configuracion",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        text = "$completedCount de $totalCount completados",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AvoqadoTheme.spacing.sm)
                            .clip(RoundedCornerShape(50)),
                        color = Success,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Tasks list
            Text(
                text = "TAREAS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 56.dp),
                        )
                    }
                    SetupTaskRow(task = task)
                }
            }
        }
    }
}

@Composable
private fun TipTaxToggleRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Incluir IVA en base de propina",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Recomendado en MX: desactivado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

// MARK: - Setup Task Row

@Composable
private fun SetupTaskRow(task: SetupTask) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkmark circle
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (task.isCompleted) {
                        Success
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (task.isCompleted) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Completado",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.surface,
                )
            }
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (task.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = task.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!task.isCompleted) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
