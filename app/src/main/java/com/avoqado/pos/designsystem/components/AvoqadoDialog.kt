package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Standard Avoqado dialog:
 * - X close button top-right (replaces Material3's implicit Cancel)
 * - Title + optional description
 * - Content slot
 * - Single full-width pill action button
 *
 * Prefer this over Material3 [androidx.compose.material3.AlertDialog] — it enforces
 * brand consistency across the app.
 */
@Composable
fun AvoqadoDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    dismissOnClickOutside: Boolean = true,
    actionButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = 280.dp, max = 480.dp)
                .padding(horizontal = AvoqadoTheme.spacing.xl),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    // Techo de altura: sin esto el diálogo crece con el contenido
                    // hasta salirse de la pantalla.
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                    .padding(
                        start = AvoqadoTheme.spacing.xl,
                        end = AvoqadoTheme.spacing.sm,
                        top = AvoqadoTheme.spacing.sm,
                        bottom = AvoqadoTheme.spacing.xl,
                    ),
            ) {
                // Header: title + X
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = AvoqadoTheme.spacing.md),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            end = AvoqadoTheme.spacing.md,
                            bottom = AvoqadoTheme.spacing.md,
                        ),
                    )
                }

                // 🔴 El contenido cede espacio ANTES que la acción. Con una lista
                // larga (Separar en otra cuenta, Fusionar, Menús…) el botón
                // primario se salía por abajo y NO había forma de confirmar: se
                // podían marcar artículos y no separar nada. `fill = false` deja
                // que un diálogo corto siga siendo corto.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = AvoqadoTheme.spacing.md),
                ) {
                    content()
                }

                if (actionButton != null) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    Box(modifier = Modifier.padding(end = AvoqadoTheme.spacing.md)) {
                        actionButton()
                    }
                }
            }
        }
    }
}

/**
 * Pill-shaped single-line text field for dialogs and forms.
 * Matches the brand standard: 48dp height, rounded background, inline placeholder.
 *
 * 🔴 Nunca uses `OutlinedTextField` crudo de Material3 en su lugar. Su etiqueta
 * flotante se monta sobre el borde y, dentro de una hoja apretada, se ve
 * CORTADA y con dos contornos encimados — medido en la D3 el 2026-08-17 en el
 * campo "Importe" del reembolso. Aquí no hay etiqueta flotante a propósito: la
 * etiqueta va de placeholder dentro de la píldora, o como texto encima.
 *
 * @param readOnly el valor no se teclea: lo elige otra cosa (un menú, un
 *   selector). El campo SÍ se pinta como campo, pero el teclado no sale. Quien
 *   lo use suele poner encima un `Box` transparente con el `clickable`.
 * @param leading contenido fijo al inicio — el "$" de un importe, un ícono.
 * @param trailing contenido fijo al final — el chevron de un desplegable.
 */
@Composable
fun AvoqadoPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        leading?.invoke()
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = enabled,
                readOnly = readOnly,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}
