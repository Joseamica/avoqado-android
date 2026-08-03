package com.avoqado.pos.reservations.presentation.list

import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.core.util.VenueDateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationsListScreen(
    onOpenDetail: (String) -> Unit,
    formatter: VenueDateTimeFormatter,
    viewModel: ReservationsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackHost.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    // El aviso de "se guardó, se enviará al reconectar": mismo canal, pero no es
    // un error. Sin esto la acción encolada no se anunciaba en absoluto.
    LaunchedEffect(state.queuedMessage) {
        state.queuedMessage?.let {
            snackHost.showSnackbar(it)
            viewModel.consumeQueuedMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(title = { Text("Reservas") })
                ScrollableTabRow(
                    selectedTabIndex = ReservationListTab.entries.indexOf(state.tab),
                    edgePadding = 0.dp,
                ) {
                    ReservationListTab.entries.forEachIndexed { _, tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { viewModel.setTab(tab) },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackHost) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.isLoading && state.items.isEmpty() ->
                    AvoqadoBrandLoader(
                        modifier = Modifier.align(Alignment.Center),
                        size = 72.dp,
                    )

                state.items.isEmpty() ->
                    Text(
                        "Sin reservas en esta vista",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { r ->
                            ReservationRow(
                                reservation = r,
                                isPending = r.id in state.pendingTransitionIds,
                                onClick = { onOpenDetail(r.id) },
                                formatter = formatter,
                            )
                        }
                    }
            }
        }
    }
}
