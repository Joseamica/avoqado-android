package com.avoqado.pos.settings.presentation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Menu Item Definition

data class MenuItemConfig(
    val key: String,
    val label: String,
    val defaultEnabled: Boolean = true,
)

private val menuItems = listOf(
    MenuItemConfig("menu_timeclock", "Reloj checador"),
    MenuItemConfig("menu_reports", "Informes"),
    MenuItemConfig("menu_orders", "Pedidos"),
    MenuItemConfig("menu_cashdrawer", "Caja"),
    MenuItemConfig("menu_articles", "Artículos"),
    MenuItemConfig("menu_customers", "Clientes"),
    MenuItemConfig("menu_estimates", "Presupuestos"),
    MenuItemConfig("menu_permissions", "Permisos"),
    MenuItemConfig("menu_pin_settings", "Configuración PIN"),
    MenuItemConfig("menu_printer", "Impresora"),
    MenuItemConfig("menu_addons", "Complementos"),
)

private const val PREFS_NAME = "avoqado_menu_prefs"

// MARK: - Customize Menu Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeMenuSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val toggleStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            menuItems.forEach { item ->
                this[item.key] = prefs.getBoolean(item.key, item.defaultEnabled)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Personalizar menú",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Text(
                text = "Elige que opciones aparecen en el menu principal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            menuItems.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                MenuToggleRow(
                    label = item.label,
                    checked = toggleStates[item.key] ?: item.defaultEnabled,
                    onCheckedChange = { checked ->
                        toggleStates[item.key] = checked
                    },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            PrimaryButton(
                text = "Guardar",
                onClick = {
                    prefs.edit().apply {
                        toggleStates.forEach { (key, value) ->
                            putBoolean(key, value)
                        }
                        apply()
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Menu Toggle Row

@Composable
private fun MenuToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
