package com.avoqado.pos.inventory.presentation.traslados

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Info
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferDetail
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferListItem
import com.avoqado.pos.inventory.data.transfers.TransferAction
import com.avoqado.pos.inventory.data.transfers.TransferStatus
import com.avoqado.pos.inventory.data.transfers.availableTransferActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType

// MARK: - Root

@Composable
fun InterVenueTransfersView(viewModel: InterVenueTransfersViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsState()
    val error by viewModel.error.collectAsState()
    val success by viewModel.successMessage.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = screen) {
            is TrasladosScreen.List -> TrasladosListContent(viewModel)
            is TrasladosScreen.Detail -> TrasladoDetailContent(viewModel, s.transferId)
            is TrasladosScreen.Create -> CreateTrasladoContent(viewModel)
            is TrasladosScreen.Receive -> ReceiveTrasladoContent(viewModel, s.transferId)
        }

        error?.let { message ->
            AvoqadoDialog(
                title = "No se pudo completar",
                description = message,
                onDismiss = { viewModel.consumeError() },
                actionButton = { PrimaryButton(text = "Entendido", onClick = { viewModel.consumeError() }, fullWidth = true) },
            ) {}
        }

        success?.let { message ->
            AvoqadoSuccessToast(message = message, onDismiss = { viewModel.consumeSuccess() })
        }
    }
}

// MARK: - Lista

@Composable
private fun TrasladosListContent(viewModel: InterVenueTransfersViewModel) {
    val transfers by viewModel.transfers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val onlyAction by viewModel.onlyActionRequired.collectAsState()

    val visible = if (onlyAction) transfers.filter { viewModel.requiresMyAction(it) } else transfers

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Traslados", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Pide, despacha y recibe insumos entre sucursales",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryButton(text = "Nueva solicitud", onClick = { viewModel.openCreate() })
        }

        Row(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg)) {
            FilterChip(
                selected = onlyAction,
                onClick = { viewModel.setOnlyActionRequired(!onlyAction) },
                label = { Text("Requiere mi acción") },
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        when {
            isLoading && transfers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.LocalShipping,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        if (onlyAction) "Nada requiere tu acción" else "Sin traslados todavía",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                items(visible, key = { it.id }) { transfer ->
                    TransferRow(
                        transfer = transfer,
                        currentVenueId = viewModel.currentVenueId,
                        requiresAction = viewModel.requiresMyAction(transfer),
                        onClick = { viewModel.openDetail(transfer.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    transfer: InterVenueTransferListItem,
    currentVenueId: String,
    requiresAction: Boolean,
    onClick: () -> Unit,
) {
    val incoming = transfer.destinationVenueId == currentVenueId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(transfer.number, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(AvoqadoTheme.spacing.sm))
            DirectionPill(incoming = incoming)
            Spacer(Modifier.weight(1f))
            StatusPill(status = transfer.status, highlighted = requiresAction)
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        Text(
            "${transfer.sourceVenue.name}  →  ${transfer.destinationVenue.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        Text(
            "${transfer._count.items} insumo(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DirectionPill(incoming: Boolean) {
    val (label, color) = if (incoming) "Entrante" to Info else "Saliente" to Warning
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = 2.dp),
    )
}

@Composable
private fun StatusPill(status: String, highlighted: Boolean = false) {
    val color = when (status) {
        TransferStatus.REQUESTED -> Warning
        TransferStatus.APPROVED, TransferStatus.IN_TRANSIT -> Info
        TransferStatus.COMPLETED -> Success
        TransferStatus.PARTIALLY_RECEIVED, TransferStatus.COMPLETED_WITH_VARIANCE -> Warning
        TransferStatus.REJECTED, TransferStatus.CANCELLED -> Error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = TransferStatus.label(status) + if (highlighted) " •" else "",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = 2.dp),
    )
}

// MARK: - Detalle

@Composable
private fun TrasladoDetailContent(viewModel: InterVenueTransfersViewModel, transferId: String) {
    val detail by viewModel.detail.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isMutating by viewModel.isMutating.collectAsState()

    var reasonDialog by remember { mutableStateOf<TransferAction?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SubHeader(title = detail?.number ?: "Traslado", onBack = { viewModel.openList() })

        val d = detail
        if (d == null || d.id != transferId) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        val isSource = d.sourceVenueId == viewModel.currentVenueId
        val isDestination = d.destinationVenueId == viewModel.currentVenueId
        val actions = if (viewModel.roleManager.canDecideInventoryTransfers) {
            availableTransferActions(d.status, isSource = isSource, isDestination = isDestination)
        } else {
            emptySet()
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(AvoqadoTheme.spacing.lg),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(status = d.status)
                        Spacer(Modifier.width(AvoqadoTheme.spacing.sm))
                        DirectionPill(incoming = isDestination)
                    }
                    Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        "${d.sourceVenue.name}  →  ${d.destinationVenue.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    d.rejectionReason?.let { InfoLine("Motivo de rechazo", it) }
                    d.cancellationReason?.let { InfoLine("Motivo de cancelación", it) }
                    d.notes?.let { InfoLine("Notas", it) }
                }
            }

            item {
                Text("Insumos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            items(d.items, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(AvoqadoTheme.spacing.md),
                ) {
                    Text(item.destinationRawMaterial.name, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    val unit = item.unit ?: item.destinationRawMaterial.unit.orEmpty()
                    Text(
                        buildString {
                            append("Solicitado ${item.quantityRequested} $unit")
                            if ((item.quantityDispatched.toDoubleOrNull() ?: 0.0) > 0.0) append(" · Enviado ${item.quantityDispatched}")
                            if ((item.quantityReceived.toDoubleOrNull() ?: 0.0) > 0.0) append(" · Recibido ${item.quantityReceived}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (actions.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                if (TransferAction.CANCEL in actions) {
                    OutlinedButton(onClick = { reasonDialog = TransferAction.CANCEL }, enabled = !isMutating) {
                        Text("Cancelar")
                    }
                }
                if (TransferAction.REJECT in actions) {
                    OutlinedButton(onClick = { reasonDialog = TransferAction.REJECT }, enabled = !isMutating) {
                        Text("Rechazar")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (TransferAction.APPROVE in actions) {
                    PrimaryButton(text = "Aprobar", onClick = { viewModel.approve(d.id) }, isLoading = isMutating)
                }
                if (TransferAction.DISPATCH in actions) {
                    PrimaryButton(text = "Despachar", onClick = { viewModel.dispatchAll(d.id) }, isLoading = isMutating)
                }
                if (TransferAction.RECEIVE in actions) {
                    PrimaryButton(text = "Recibir…", onClick = { viewModel.openReceive(d.id) }, isLoading = isMutating)
                }
            }
        }
    }

    reasonDialog?.let { action ->
        ReasonDialog(
            title = if (action == TransferAction.REJECT) "Rechazar traslado" else "Cancelar traslado",
            confirmLabel = if (action == TransferAction.REJECT) "Rechazar" else "Cancelar traslado",
            onDismiss = { reasonDialog = null },
            onConfirm = { reason ->
                reasonDialog = null
                detail?.let { d ->
                    if (action == TransferAction.REJECT) viewModel.reject(d.id, reason) else viewModel.cancel(d.id, reason)
                }
            },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReasonDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val valid = reason.trim().length >= 3
    AvoqadoDialog(
        title = title,
        description = "Escribe el motivo (mínimo 3 caracteres).",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = confirmLabel,
                onClick = { onConfirm(reason.trim()) },
                enabled = valid,
                fullWidth = true,
            )
        },
    ) {
        AvoqadoPillTextField(value = reason, onValueChange = { reason = it }, placeholder = "Motivo")
    }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Regresar")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

// MARK: - Crear solicitud (PULL: este venue PIDE al origen)

@Composable
private fun CreateTrasladoContent(viewModel: InterVenueTransfersViewModel) {
    val sourceVenueId by viewModel.createSourceVenueId.collectAsState()
    val lines by viewModel.createLines.collectAsState()
    val sourceMaterials by viewModel.sourceMaterials.collectAsState()
    val destinationMaterials by viewModel.destinationMaterials.collectAsState()
    val isMutating by viewModel.isMutating.collectAsState()

    val venues = viewModel.counterpartVenues
    var showVenuePicker by remember { mutableStateOf(false) }
    var materialPicker by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // (index, isSource)

    Column(modifier = Modifier.fillMaxSize()) {
        SubHeader(title = "Nueva solicitud", onBack = { viewModel.openList() })

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            item {
                Text(
                    "Pide insumos a otra sucursal. La solicitud queda pendiente hasta que la sucursal de origen la apruebe y despache.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Text("Sucursal de origen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
                if (venues.isEmpty()) {
                    Text(
                        "No tienes otra sucursal accesible. El traslado se crea desde la sucursal que RECIBE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning,
                    )
                } else {
                    PickerField(
                        value = venues.firstOrNull { it.id == sourceVenueId }?.name ?: "Selecciona una sucursal",
                        onClick = { showVenuePicker = true },
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Insumos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.addCreateLine() }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Agregar")
                    }
                }
            }

            items(lines.size) { index ->
                val line = lines[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(AvoqadoTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Text("Del origen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PickerField(
                        value = sourceMaterials.firstOrNull { it.id == line.sourceRawMaterialId }?.name
                            ?: if (sourceVenueId == null) "Elige primero la sucursal" else "Selecciona el insumo",
                        enabled = sourceVenueId != null,
                        onClick = { materialPicker = index to true },
                    )
                    Text("Entra como", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PickerField(
                        value = destinationMaterials.firstOrNull { it.id == line.destinationRawMaterialId }?.name
                            ?: "Selecciona el insumo local",
                        onClick = { materialPicker = index to false },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = line.quantityText,
                            onValueChange = { raw -> viewModel.updateCreateLine(index) { it.copy(quantityText = raw) } },
                            label = { Text("Cantidad") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (lines.size > 1) {
                            IconButton(onClick = { viewModel.removeCreateLine(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Quitar renglón", tint = Error)
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
            PrimaryButton(
                text = "Enviar solicitud",
                onClick = { viewModel.submitCreate() },
                isLoading = isMutating,
                enabled = venues.isNotEmpty(),
                fullWidth = true,
            )
        }
    }

    if (showVenuePicker) {
        OptionPickerDialog(
            title = "Sucursal de origen",
            options = venues.map { it.id to it.name },
            onDismiss = { showVenuePicker = false },
            onSelect = { id ->
                showVenuePicker = false
                viewModel.selectSourceVenue(id)
            },
        )
    }

    materialPicker?.let { (index, isSource) ->
        val options = (if (isSource) sourceMaterials else destinationMaterials).map { material ->
            // La unidad va a la vista porque el server EXIGE que los dos insumos
            // vinculados usen la misma unidad base, y lo dice sólo después de
            // enviar. Sin verla aquí, emparejar es prueba y error: eliges,
            // envías, te rechaza, vuelves a empezar.
            val abrev = com.avoqado.pos.articles.data.model.MeasurementUnit.abbreviate(material.unit)
            val unidad = abrev?.let { " $it" } ?: ""
            val stock = material.currentStock?.let { " · stock $it$unidad" }
                ?: abrev?.let { " · $it" }
                ?: ""
            material.id to "${material.name}$stock"
        }
        OptionPickerDialog(
            title = if (isSource) "Insumo del origen" else "Insumo local (entra como)",
            options = options,
            onDismiss = { materialPicker = null },
            onSelect = { id ->
                materialPicker = null
                viewModel.updateCreateLine(index) {
                    if (isSource) it.copy(sourceRawMaterialId = id) else it.copy(destinationRawMaterialId = id)
                }
            },
        )
    }
}

@Composable
private fun PickerField(value: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OptionPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AvoqadoDialog(title = title, onDismiss = onDismiss) {
        if (options.isEmpty()) {
            Text(
                "Sin opciones disponibles",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(options, key = { it.first }) { (id, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = AvoqadoTheme.spacing.md),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// MARK: - Recibir (mermas = recibir menos de lo despachado)

@Composable
private fun ReceiveTrasladoContent(viewModel: InterVenueTransfersViewModel, transferId: String) {
    val lines by viewModel.receiveLines.collectAsState()
    val isMutating by viewModel.isMutating.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SubHeader(title = "Recibir traslado", onBack = { viewModel.openDetail(transferId) })

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            item {
                Text(
                    "Confirma lo que llegó. Si recibes menos de lo enviado, la diferencia queda registrada como merma en tránsito.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(lines, key = { it.itemId }) { line ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(AvoqadoTheme.spacing.md),
                ) {
                    Text(line.materialName, fontWeight = FontWeight.Medium)
                    Text(
                        "Enviado: ${InterVenueTransfersViewModel.trimNumber(line.dispatched)} ${line.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                    OutlinedTextField(
                        value = line.quantityText,
                        onValueChange = { raw -> viewModel.updateReceiveLine(line.itemId, raw) },
                        label = { Text("Cantidad recibida") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val entered = line.quantityText.trim().toDoubleOrNull()
                    if (entered != null && entered < line.dispatched) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Merma: ${InterVenueTransfersViewModel.trimNumber(line.dispatched - entered)} ${line.unit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
            PrimaryButton(
                text = "Confirmar recepción",
                onClick = { viewModel.submitReceive(transferId) },
                isLoading = isMutating,
                fullWidth = true,
            )
        }
    }
}
