package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.domain.dejaRepetir
import com.avoqado.pos.pos.domain.notaDelExtra
import com.avoqado.pos.pos.domain.puedeSumar
import com.avoqado.pos.pos.domain.vecesElegida
import com.avoqado.pos.pos.data.model.Modifier as ProductModifier

@Composable
fun ModifierGroupSection(
    group: ProductModifierGroup,
    selectedModifiers: List<SelectedModifier>,
    onModifierToggled: (ProductModifier, Boolean) -> Unit,
    /**
     * Grupo que deja repetir la misma opción (encargo a508f4): cada opción lleva − n + en vez de casilla. Sin estos dos (o con
     * un grupo que no repite) se ve como siempre.
     */
    onSumar: ((ProductModifier) -> Unit)? = null,
    onRestar: ((ProductModifier) -> Unit)? = null,
    /**
     * D40: nota de cada parte. Con esto, debajo de cada opción elegida sale «+ Nota» (o la nota ya puesta) y al tocarla se escribe.
     * Sin esto (piezas de un combo) se ve como siempre.
     */
    onNota: ((ProductModifier) -> Unit)? = null,
) {
    val conContador = group.dejaRepetir && onSumar != null && onRestar != null
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

        if (conContador) {
            group.modifiers.forEach { modifier ->
                val veces = vecesElegida(selectedModifiers, group, modifier)
                FilaConContador(
                    modifier = modifier,
                    veces = veces,
                    puedeSumar = puedeSumar(selectedModifiers, group, modifier),
                    onSumar = { onSumar!!(modifier) },
                    onRestar = { onRestar!!(modifier) },
                )
                if (veces > 0 && onNota != null) {
                    FilaDeNotaDeLaParte(nota = notaDelExtra(selectedModifiers, group.id, modifier.id), onClick = { onNota(modifier) })
                }
            }
        } else group.modifiers.forEach { modifier ->
            val isSelected = selectedModifiers.any {
                it.groupId == group.id && it.modifierId == modifier.id
            }

            // 🔴 TODA la fila selecciona, no sólo el control. Un radio mide ~20dp
            // y el mesero captura con el dedo y con prisa: tocar el nombre del
            // modificador no hacía NADA y parecía que la app no respondía.
            // `selectable` da además el rol de accesibilidad correcto.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        role = if (group.multipleSelect) Role.Checkbox else Role.RadioButton,
                        onClick = { onModifierToggled(modifier, !isSelected) },
                    )
                    .padding(vertical = AvoqadoTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.multipleSelect) {
                    Checkbox(
                        checked = isSelected,
                        // null: el click lo maneja la fila — evita doble manejo
                        // y que el control se coma el toque.
                        onCheckedChange = null,
                    )
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                    )
                }

                Text(
                    text = modifier.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )

                if (modifier.price > 0) {
                    Text(
                        text = "+${formatMoney(modifier.price)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelected && onNota != null) {
                FilaDeNotaDeLaParte(nota = notaDelExtra(selectedModifiers, group.id, modifier.id), onClick = { onNota(modifier) })
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
    }
}

/**
 * D40: debajo de una opción elegida, «+ Nota» o la nota que ya tiene («Nota: sin cebolla»). Toda la fila responde al dedo (48dp de
 * alto, como las demás filas) y no cambia lo elegido.
 */
@Composable
private fun FilaDeNotaDeLaParte(nota: String?, onClick: () -> Unit) {
    Text(
        text = nota?.let { "Nota: $it" } ?: "+ Nota",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (nota != null) FontWeight.SemiBold else null,
        color = if (nota != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (nota != null) "Cambiar la nota" else "Agregar una nota", onClick = onClick)
            .heightIn(min = 40.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(start = AvoqadoTheme.spacing.xxxl + AvoqadoTheme.spacing.sm, top = AvoqadoTheme.spacing.xs, bottom = AvoqadoTheme.spacing.xs),
    )
}

/**
 * Una opción de un grupo que deja repetir: nombre, precio y «− n +». Tocar la fila suma una vez (como la casilla de siempre, la
 * fila entera responde al dedo); el «+» se apaga al llegar al máximo del grupo o al de la opción, y el «−» quita una.
 */
@Composable
private fun FilaConContador(
    modifier: ProductModifier,
    veces: Int,
    puedeSumar: Boolean,
    onSumar: () -> Unit,
    onRestar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = veces > 0,
                enabled = puedeSumar,
                role = Role.Button,
                onClick = onSumar,
            )
            .semantics { contentDescription = "${modifier.name}: $veces" }
            .padding(vertical = AvoqadoTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = modifier.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (veces > 0) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
        )
        if (modifier.price > 0) {
            Text(
                text = "+${formatMoney(modifier.price)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = AvoqadoTheme.spacing.sm),
            )
        }
        Row(
            modifier = Modifier.background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRestar, enabled = veces > 0, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Uno menos de ${modifier.name}", modifier = Modifier.size(18.dp))
            }
            Text(
                text = veces.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (veces > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onSumar, enabled = puedeSumar, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Uno más de ${modifier.name}", modifier = Modifier.size(18.dp))
            }
        }
    }
}
