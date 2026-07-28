package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * International phone input with country selector.
 *
 * Emits the raw digits (not the dial code) — the caller composes E.164 as
 * `"+${country.dialCode}${digits}"` when submitting. [country] tracks the
 * selected country so the caller can read the dial code.
 *
 * Example:
 * ```
 * var country by remember { mutableStateOf(Countries.byIso("MX")) }
 * var digits by remember { mutableStateOf("") }
 * AvoqadoPhoneInput(
 *     country = country,
 *     onCountryChange = { country = it },
 *     digits = digits,
 *     onDigitsChange = { digits = it },
 * )
 * // Submit:
 * val e164 = "+${country.dialCode}$digits"
 * ```
 */
@Composable
fun AvoqadoPhoneInput(
    country: Country,
    onCountryChange: (Country) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Número",
) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Country selector (flag + dial code + dropdown arrow)
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable(enabled = enabled) { showPicker = true }
                .padding(horizontal = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        ) {
            Text(
                text = country.flag,
                fontSize = 20.sp,
            )
            Text(
                text = "+${country.dialCode}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Seleccionar país",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // Digits input (pill)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (digits.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = digits,
                    onValueChange = { onDigitsChange(it.filter { c -> c.isDigit() }) },
                    singleLine = true,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showPicker) {
        CountryPickerSheet(
            selected = country,
            onSelect = {
                onCountryChange(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerSheet(
    selected: Country,
    onSelect: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = AvoqadoTheme.spacing.xl,
            ),
        ) {
            item {
                Text(
                    text = "Frecuentes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = AvoqadoTheme.spacing.xl,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                )
            }
            items(Countries.pinned, key = { "pinned-${it.isoCode}" }) { country ->
                CountryRow(country = country, selected = country.isoCode == selected.isoCode, onClick = { onSelect(country) })
            }

            item {
                Text(
                    text = "Otros países",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = AvoqadoTheme.spacing.xl,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                )
            }
            items(Countries.others.sortedBy { it.name }, key = { "other-${it.isoCode}" }) { country ->
                CountryRow(country = country, selected = country.isoCode == selected.isoCode, onClick = { onSelect(country) })
            }
        }
    }
}

@Composable
private fun CountryRow(
    country: Country,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Text(text = country.flag, fontSize = 22.sp)
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+${country.dialCode}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
