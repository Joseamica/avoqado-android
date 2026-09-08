package com.avoqado.pos.inventory.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.inventory.data.model.StockCountItem

// MARK: - Stock Count Review View

@Composable
fun StockCountReviewView(
    viewModel: InventoryViewModel,
    isTablet: Boolean,
) {
    val countItems by viewModel.countItems.collectAsState()
    val countNote by viewModel.countNote.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    // El MISMO flow que pinta la banda del conteo: una sola regla para las dos pantallas.
    val aviso by viewModel.bandaDeAviso.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // El BACK vuelve a contar, igual que el boton «Atras». Sin esto salia de la
    // pantalla entera y lo capturado no se podia revisar ni confirmar.
    //
    // 🔴 Deshabilitado mientras dice «Confirmando…»: volver a contar ahi permite corregir una
    // cantidad que el cierre EN VUELO va a sellar con su valor viejo, y al salir bien borra el
    // borrador — la correccion desaparece sin un solo error. El boton «Atras» va igual.
    // Siempre se consume: con `enabled = !isSaving` el BACK se escapaba del módulo durante
    // «Confirmando…» y destruía el ViewModel con el cierre en vuelo (M1 de la re-revisión r2).
    BackHandler { if (!isSaving) viewModel.backToCounting() }

    // 🔴 Esta es LA pantalla del defecto que midio el QA: se toca «Confirmar» sin red, el
    // ViewModel pone su mensaje... y no pasaba nada visible. El unico `SnackbarHost` del
    // modulo vive en `InventoryScreen`, DESPUES de su `return` temprano, asi que aqui no hay
    // ninguno montado y su `showSnackbar` se queda suspendido para siempre. Mismo patron que
    // `InventoryScreen`: host propio + efecto + limpiar cuando se cierra.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearErrorMessage()
        }
    }

    var searchText by remember { mutableStateOf("") }
    var showNoteField by remember { mutableStateOf(false) }

    val filteredItems = if (searchText.isBlank()) countItems
    else countItems.filter {
        it.productName.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.backToCounting() }, enabled = !isSaving) {
                Text("Atrás")
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Revisar conteo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Invisible spacer for centering
            Box(modifier = Modifier.width(64.dp))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // La misma banda del conteo, leyendo el MISMO flow: si otro aparato ya cerro este
        // conteo, revisar linea por linea no sirve de nada y el cajero se enteraria hasta
        // chocar al confirmar.
        //
        // 🔴 Y ahora dice tambien «sin conexion», que antes se callaba aqui con el argumento
        // de que «todavia no hay nada que enviar». Era falso: en un ciclico que aun no existe
        // en el servidor, TODAS las lineas contadas viven solo en este aparato. Y es la
        // pantalla desde la que se toca «Confirmar», o sea donde mas caro sale no decirlo.
        aviso?.let { BandaDeAvisoDeConteo(it) }

        // Search
        TextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Buscar artículos") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        )

        // Column headers (tablet only)
        if (isTablet) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = "Artículo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(2f),
                )
                Text(
                    text = "SKU",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Esperado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
                Text(
                    text = "Contado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
                Text(
                    text = "Diferencia",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Items list
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            items(filteredItems, key = { it.productId }) { item ->
                if (isTablet) {
                    ReviewRowTablet(item = item, viewModel = viewModel)
                } else {
                    ReviewRowPhone(item = item, viewModel = viewModel)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // Bottom actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            // Note field
            if (showNoteField) {
                OutlinedTextField(
                    value = countNote,
                    onValueChange = { viewModel.updateCountNote(it) },
                    label = { Text("Nota (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            } else {
                TextButton(onClick = { showNoteField = true }) {
                    Text("Agregar nota")
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            }

            PrimaryButton(
                text = if (isSaving) "Confirmando..." else "Confirmar",
                onClick = { viewModel.confirmCount() },
                isLoading = isSaving,
                enabled = !isSaving,
            )
        }
    }

    // Hermano del `Column` raiz para dibujarse encima de el. Un `Box` sin `pointerInput` no
    // es blanco de toque: sin snackbar no estorba al boton «Confirmar» que tiene debajo.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = snackbarHostState)
    }
}

// MARK: - Review Row Tablet

@Composable
private fun ReviewRowTablet(
    item: StockCountItem,
    viewModel: InventoryViewModel,
) {
    val diff = item.counted - item.expected
    val diffColor = when {
        diff > 0 -> Success
        diff < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name
        Row(
            modifier = Modifier.weight(2f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.productName.take(2).uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // SKU
        Text(
            text = item.sku ?: "-",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )

        // Expected
        Text(
            text = viewModel.formatQuantity(item.expected),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )

        // Counted — untouched lines show a dash: they are NOT sent on
        // confirm, so stock stays as-is (never a misleading "0 / -98").
        val touched = viewModel.touchedItemIds.collectAsState().value.contains(item.id)
        Text(
            text = if (touched) viewModel.formatQuantity(item.counted) else "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )

        // Difference
        Text(
            text = when {
                !touched -> "Sin contar"
                diff > 0 -> "+${viewModel.formatQuantity(diff)}"
                else -> viewModel.formatQuantity(diff)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (touched) FontWeight.SemiBold else FontWeight.Normal,
            color = if (touched) diffColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

// MARK: - Review Row Phone

@Composable
private fun ReviewRowPhone(
    item: StockCountItem,
    viewModel: InventoryViewModel,
) {
    val diff = item.counted - item.expected
    val diffColor = when {
        diff > 0 -> Success
        diff < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.productName.take(2).uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
                Text(
                    text = "Esperado: ${viewModel.formatQuantity(item.expected)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val touched = viewModel.touchedItemIds.collectAsState().value.contains(item.id)
                Text(
                    text = "Contado: " + if (touched) viewModel.formatQuantity(item.counted) else "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Difference
        val touchedRow = viewModel.touchedItemIds.collectAsState().value.contains(item.id)
        Text(
            text = when {
                !touchedRow -> "Sin contar"
                diff > 0 -> "+${viewModel.formatQuantity(diff)}"
                else -> viewModel.formatQuantity(diff)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (touchedRow) FontWeight.Bold else FontWeight.Normal,
            color = if (touchedRow) diffColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
