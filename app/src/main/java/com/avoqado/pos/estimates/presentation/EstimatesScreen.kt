package com.avoqado.pos.estimates.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.estimates.data.model.Estimate
import com.avoqado.pos.estimates.data.model.EstimateStatus
import com.avoqado.pos.designsystem.components.PrimaryButton

// MARK: - Entry Point

@Composable
fun EstimatesScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    viewModel: EstimatesViewModel = hiltViewModel(),
) {
    if (isTablet) {
        TabletEstimatesLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    } else {
        PhoneEstimatesLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    }
}

// MARK: - Tablet Layout (Split View)

@Composable
private fun TabletEstimatesLayout(
    viewModel: EstimatesViewModel,
    onDismiss: () -> Unit,
) {
    val estimates by viewModel.estimates.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var selectedEstimate by remember { mutableStateOf<Estimate?>(null) }
    var searchText by remember { mutableStateOf("") }
    var showCreateSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val filteredEstimates = remember(searchText, estimates) {
        if (searchText.isEmpty()) {
            estimates
        } else {
            val query = searchText.lowercase()
            estimates.filter { estimate ->
                (estimate.customerName?.lowercase()?.contains(query) == true) ||
                    (estimate.number?.contains(query) == true) ||
                    estimate.displayTotal.contains(query)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Panel (~380dp) - Estimate List
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight()
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
                        text = "Presupuestos",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(50),
                            )
                            .clickable { showCreateSheet = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Crear presupuesto",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Search bar
                TextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.lg)
                        .padding(bottom = AvoqadoTheme.spacing.md)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(25.dp),
                        ),
                    placeholder = { Text("Buscar") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Limpiar",
                                modifier = Modifier.clickable { searchText = "" },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(25.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Estimate list
                when {
                    isLoading && estimates.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AvoqadoBrandLoader(size = 72.dp)
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                            Text(
                                text = "Cargando presupuestos...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    filteredEstimates.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = if (searchText.isNotEmpty()) "No se encontraron presupuestos" else "No hay presupuestos",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (searchText.isEmpty()) {
                                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                                Text(
                                    text = "Crea tu primer presupuesto",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // La pantalla invitaba a crear el primero y no
                                // ofrecía CÓMO: había que adivinar el icono del
                                // encabezado. Un estado vacío que pide una
                                // acción trae el botón de esa acción.
                                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                                PrimaryButton(
                                    text = "Nuevo presupuesto",
                                    onClick = { showCreateSheet = true },
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(filteredEstimates, key = { it.id }) { estimate ->
                                EstimateListRow(
                                    estimate = estimate,
                                    isSelected = estimate.id == selectedEstimate?.id,
                                    onClick = { selectedEstimate = estimate },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }

            // Vertical divider
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Right Panel - Detail or Empty State
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (selectedEstimate != null) {
                    EstimateDetailView(estimate = selectedEstimate!!)
                } else {
                    EstimateEmptyState(hayPresupuestos = filteredEstimates.isNotEmpty())
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Create sheet
    if (showCreateSheet) {
        CreateEstimateSheet(
            viewModel = viewModel,
            onDismiss = { showCreateSheet = false },
        )
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneEstimatesLayout(
    viewModel: EstimatesViewModel,
    onDismiss: () -> Unit,
) {
    val estimates by viewModel.estimates.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var selectedEstimate by remember { mutableStateOf<Estimate?>(null) }
    var searchText by remember { mutableStateOf("") }
    var showCreateSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val filteredEstimates = remember(searchText, estimates) {
        if (searchText.isEmpty()) {
            estimates
        } else {
            val query = searchText.lowercase()
            estimates.filter { estimate ->
                (estimate.customerName?.lowercase()?.contains(query) == true) ||
                    (estimate.number?.contains(query) == true) ||
                    estimate.displayTotal.contains(query)
            }
        }
    }

    // If an estimate is selected, show detail
    if (selectedEstimate != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = { selectedEstimate = null })
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Text(
                    text = selectedEstimate!!.displayNumber,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EstimateDetailView(estimate = selectedEstimate!!)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    text = "Presupuestos",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50),
                        )
                        .clickable { showCreateSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Crear presupuesto",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Search bar
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.lg)
                    .padding(bottom = AvoqadoTheme.spacing.md)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(25.dp),
                    ),
                placeholder = { Text("Buscar") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Limpiar",
                            modifier = Modifier.clickable { searchText = "" },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(25.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // List content
            when {
                isLoading && estimates.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AvoqadoBrandLoader(size = 72.dp)
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                        Text(
                            text = "Cargando presupuestos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                filteredEstimates.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (searchText.isNotEmpty()) "No se encontraron presupuestos" else "Sin presupuestos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredEstimates, key = { it.id }) { estimate ->
                            EstimateListRow(
                                estimate = estimate,
                                isSelected = false,
                                onClick = { selectedEstimate = estimate },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Create sheet
    if (showCreateSheet) {
        CreateEstimateSheet(
            viewModel = viewModel,
            onDismiss = { showCreateSheet = false },
        )
    }
}

// MARK: - Estimate List Row

@Composable
private fun EstimateListRow(
    estimate: Estimate,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = estimate.customerName ?: "Sin cliente",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusBadge(status = estimate.estimateStatus)
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            Text(
                text = "${estimate.displayNumber} · ${estimate.displayDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = estimate.displayTotal,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Status Badge

@Composable
private fun StatusBadge(status: EstimateStatus) {
    val (bgColor, textColor) = when (status) {
        EstimateStatus.DRAFT -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        EstimateStatus.SENT -> Color(0xFF2196F3).copy(alpha = 0.12f) to Color(0xFF1565C0)
        EstimateStatus.ACCEPTED -> Color(0xFF4CAF50).copy(alpha = 0.12f) to Color(0xFF2E7D32)
        EstimateStatus.REJECTED -> Color(0xFFF44336).copy(alpha = 0.12f) to Color(0xFFC62828)
        EstimateStatus.EXPIRED -> Color(0xFFFF9800).copy(alpha = 0.12f) to Color(0xFFE65100)
        EstimateStatus.CONVERTED -> Color(0xFF9C27B0).copy(alpha = 0.12f) to Color(0xFF6A1B9A)
    }

    Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(50))
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
    )
}

// MARK: - Empty State

/**
 * @param hayPresupuestos si la lista de al lado tiene algo que seleccionar.
 *   Sin esto pedía "Selecciona un presupuesto" con la lista VACÍA — mandaba a
 *   elegir algo que no existe.
 */
@Composable
private fun EstimateEmptyState(hayPresupuestos: Boolean = true) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        Text(
            text = if (hayPresupuestos) "Selecciona un presupuesto" else "Crea tu primer presupuesto",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
