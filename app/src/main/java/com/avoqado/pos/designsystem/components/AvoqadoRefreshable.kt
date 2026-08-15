package com.avoqado.pos.designsystem.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Envoltorio único de pull-to-refresh (spec §4.7). isRefreshing = SOLO el gesto
 * manual (nunca la carga inicial). NO es dueño del banner de conectividad —
 * ese sigue siendo el ConnectivityBanner global del NavGraph.
 * PullToRefreshBox es @ExperimentalMaterial3Api en M3 1.3.x: vigilar al subir el BOM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoRefreshable(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        content()
    }
}
