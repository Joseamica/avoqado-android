package com.avoqado.pos.printing.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.SavedPrinter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterConfigSheet(
    printer: SavedPrinter,
    printerService: PrinterService,
    onDismiss: () -> Unit,
) {
    val statuses by printerService.printerStatuses.collectAsState()
    val status = statuses[printer.id] ?: PrinterStatus.Disconnected
    val scope = rememberCoroutineScope()

    // Local editable state
    var name by remember { mutableStateOf(printer.name) }
    var selectedRoles by remember { mutableStateOf(printer.roles.toSet()) }
    var paperWidthMm by remember { mutableIntStateOf(printer.paperWidthMm) }
    var autoPrintReceipts by remember { mutableStateOf(printer.autoPrintReceipts) }
    var autoPrintKitchenTickets by remember { mutableStateOf(printer.autoPrintKitchenTickets) }
    var autoOpenCashDrawer by remember { mutableStateOf(printer.autoOpenCashDrawer) }
    var numberOfCopies by remember { mutableIntStateOf(printer.numberOfCopies) }
    var isPrinting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    fun saveChanges() {
        printerService.updatePrinter(
            printer.copy(
                name = name,
                roles = selectedRoles.toList(),
                paperWidthMm = paperWidthMm,
                autoPrintReceipts = autoPrintReceipts,
                autoPrintKitchenTickets = autoPrintKitchenTickets,
                autoOpenCashDrawer = autoOpenCashDrawer,
                numberOfCopies = numberOfCopies,
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            saveChanges()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xxxl)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Text(
                text = "Configurar impresora",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Status card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                    .background(
                        if (status.isConnected) Success.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (printer.connectionTypeEnum) {
                        PrinterConnectionType.WIFI -> Icons.Filled.Wifi
                        PrinterConnectionType.BLUETOOTH -> Icons.Filled.Bluetooth
                        PrinterConnectionType.USB -> Icons.Filled.Usb
                        PrinterConnectionType.INTERNAL -> Icons.Filled.Print
                    },
                    contentDescription = null,
                    tint = if (status.isConnected) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(printer.displayAddress, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        status.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.isConnected) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        if (status.isConnected) {
                            printerService.disconnect(printer)
                        } else {
                            try { printerService.connect(printer) } catch (_: Exception) {}
                        }
                    }
                }) {
                    Text(if (status.isConnected) "Desconectar" else "Conectar")
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // -- FUNCIONES (Roles) --
            SectionHeader("Funciones")
            Text(
                "Selecciona para que tipo de impresiones usar esta impresora.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            PrinterRole.entries.forEach { role ->
                val isSelected = selectedRoles.contains(role.value)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedRoles = if (isSelected) {
                                selectedRoles - role.value
                            } else {
                                selectedRoles + role.value
                            }
                            saveChanges()
                        }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = role.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = isSelected,
                        onCheckedChange = null, // Click handled by parent Row
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm))

            // -- PAPEL --
            SectionHeader("Ancho de papel")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PaperWidth.entries.forEachIndexed { index, width ->
                    SegmentedButton(
                        selected = paperWidthMm == width.mm,
                        onClick = {
                            paperWidthMm = width.mm
                            saveChanges()
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PaperWidth.entries.size,
                        ),
                    ) {
                        Text(width.displayName)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md))

            // -- AUTO PRINT --
            SectionHeader("Impresion automatica")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Imprimir recibos automaticamente", modifier = Modifier.weight(1f))
                Switch(
                    checked = autoPrintReceipts,
                    onCheckedChange = {
                        autoPrintReceipts = it
                        saveChanges()
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Imprimir comandas automaticamente", modifier = Modifier.weight(1f))
                Switch(
                    checked = autoPrintKitchenTickets,
                    onCheckedChange = {
                        autoPrintKitchenTickets = it
                        saveChanges()
                    },
                )
            }

            // Cajón de dinero: solo tiene sentido en la impresora de recibos (por
            // ahí va el pulso al cajón). Conducta estándar de POS: al cobrar en
            // efectivo, el cajón se abre solo.
            if (selectedRoles.contains("receipt")) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Abrir cajón en ventas de efectivo", modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoOpenCashDrawer,
                        onCheckedChange = {
                            autoOpenCashDrawer = it
                            saveChanges()
                        },
                    )
                }
            }

            // Copies
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Copias", modifier = Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                ) {
                    TextButton(
                        onClick = {
                            if (numberOfCopies > 1) {
                                numberOfCopies--
                                saveChanges()
                            }
                        },
                        enabled = numberOfCopies > 1,
                    ) { Text("-") }

                    Text(
                        "$numberOfCopies",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    TextButton(
                        onClick = {
                            if (numberOfCopies < 5) {
                                numberOfCopies++
                                saveChanges()
                            }
                        },
                        enabled = numberOfCopies < 5,
                    ) { Text("+") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md))

            // -- TEST PRINT --
            PrimaryButton(
                text = if (isPrinting) "Imprimiendo..." else "Imprimir pagina de prueba",
                onClick = {
                    isPrinting = true
                    scope.launch {
                        try {
                            printerService.printTestPage(printer)
                            feedbackMessage = "Pagina de prueba enviada"
                        } catch (e: Exception) {
                            feedbackMessage = "Error: ${e.message}"
                        }
                        isPrinting = false
                    }
                },
                isLoading = isPrinting,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Open cash drawer
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            printerService.openCashDrawer(printer)
                            feedbackMessage = "Comando enviado al cajon"
                        } catch (e: Exception) {
                            feedbackMessage = "Error: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                Text("Abrir cajon de dinero")
            }

            // Feedback message
            feedbackMessage?.let { msg ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("Error")) MaterialTheme.colorScheme.error else Success,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Delete
            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Eliminar impresora",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Eliminar impresora?") },
            text = { Text("Esta accion no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    printerService.deletePrinter(printer)
                    showDeleteConfirm = false
                    onDismiss()
                }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
