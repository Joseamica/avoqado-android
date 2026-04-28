package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.Modifier as ProductModifier

@Composable
fun ModifierGroupSection(
    group: ProductModifierGroup,
    selectedModifiers: List<SelectedModifier>,
    onModifierToggled: (ProductModifier, Boolean) -> Unit,
) {
    val requiredCount = if (group.required) {
        (group.minSelections ?: 0).coerceAtLeast(1)
    } else {
        0
    }
    val selectedCount = selectedModifiers.count { it.groupId == group.id }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AvoqadoTheme.spacing.lg,
                end = AvoqadoTheme.spacing.lg,
                top = AvoqadoTheme.spacing.lg,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (group.required) {
                Text(
                    text = "Requerido $selectedCount/$requiredCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        group.modifiers.forEach { modifier ->
            val isSelected = selectedModifiers.any {
                it.groupId == group.id && it.modifierId == modifier.id
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.multipleSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onModifierToggled(modifier, it) },
                    )
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onModifierToggled(modifier, !isSelected) },
                    )
                }

                Text(
                    text = modifier.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )

                if (modifier.price > 0) {
                    Text(
                        text = "+$${String.format("%.2f", modifier.price)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
    }
}
