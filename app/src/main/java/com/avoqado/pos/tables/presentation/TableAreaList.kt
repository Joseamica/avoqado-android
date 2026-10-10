package com.avoqado.pos.tables.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.tables.data.DiningTable

/**
 * Teléfono (y tablet sin plano): la cuadrícula de hoy —4 por renglón, las mismas tarjetas— pero de UNA pestaña de área
 * a la vez, en el orden del dueño (spec 2026-10-09 §4.3). Espejo: avoqado-ios Tables/TableAreaList.swift.
 */
@Composable
internal fun TableAreaList(
    tables: List<DiningTable>,
    onTap: (DiningTable) -> Unit,
    onLongPress: (DiningTable) -> Unit,
    selectedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    if (tables.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "No hay mesas en esta área",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AvoqadoTheme.spacing.xl),
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        items(tables, key = { it.id }) { t ->
            TableCard(table = t, onTap = { onTap(t) }, onLongPress = { onLongPress(t) }, selected = t.id in selectedIds)
        }
    }
}
