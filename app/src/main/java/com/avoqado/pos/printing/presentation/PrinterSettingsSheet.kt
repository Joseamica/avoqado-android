package com.avoqado.pos.printing.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.SavedPrinter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSettingsSheet(
    printerService: PrinterService,
    onDismiss: () -> Unit,
) {
    val savedPrinters by printerService.savedPrinters.collectAsState()
    val statuses by printerService.printerStatuses.collectAsState()
    val isDiscovering by printerService.isDiscovering.collectAsState()
    val discoveredPrinters by printerService.discoveredPrinters.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Config sheet state
    var configPrinter by remember { mutableStateOf<SavedPrinter?>(null) }

    // Bluetooth permission handling
    var hasBluetoothPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        hasBluetoothPermission = permissions.values.all { it }
        if (hasBluetoothPermission) {
            printerService.startDiscovery()
        }
    }

    // Search automatically on open (Square-style): USB appears instantly, network
    // results stream in. Without BT permission the scan still covers USB + WiFi
    // (the Bluetooth leg skips itself); the button below can request BT explicitly.
    LaunchedEffect(Unit) {
        printerService.startDiscovery()
    }

    // If config sheet is open, render only that — avoid stacked ModalBottomSheets
    configPrinter?.let { printer ->
        PrinterConfigSheet(
            printer = printer,
            printerService = printerService,
            onDismiss = { configPrinter = null },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = {
            printerService.stopDiscovery()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Impresoras",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Saved printers list
            if (savedPrinters.isNotEmpty()) {
                Text(
                    text = "Impresoras guardadas",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                savedPrinters.forEach { printer ->
                    val status = statuses[printer.id] ?: PrinterStatus.Disconnected
                    SavedPrinterRow(
                        printer = printer,
                        status = status,
                        onClick = { configPrinter = printer },
                    )
                    HorizontalDivider()
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            }

            // Discovery section — the scan runs automatically on open; this row
            // shows live progress and the button re-runs / stops the scan.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Impresoras disponibles",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isDiscovering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                    Text(
                        text = "Buscando…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN,
                                ),
                            )
                        } else {
                            printerService.startDiscovery()
                        }
                    }) {
                        Text("Buscar de nuevo")
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            if (discoveredPrinters.isEmpty() && isDiscovering) {
                // Scanning, nothing found yet — placeholder instead of a blank gap.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Buscando impresoras por USB, red y Bluetooth…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (discoveredPrinters.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Print,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Text(
                        text = "No se encontraron impresoras",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Conecta la impresora por USB, o asegúrate que esté encendida y en la misma red WiFi o emparejada por Bluetooth",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(discoveredPrinters) { printer ->
                        val alreadySaved = savedPrinters.any { it.address == printer.address }
                        DiscoveredPrinterRow(
                            printer = printer,
                            alreadySaved = alreadySaved,
                            onClick = {
                                if (!alreadySaved) {
                                    val saved = printer.toSavedPrinter()
                                    printerService.savePrinter(saved)
                                    scope.launch {
                                        try {
                                            printerService.connect(saved)
                                        } catch (_: Exception) {}
                                    }
                                    // Open config for the new printer
                                    configPrinter = saved
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            ManualIpSection(printerService = printerService, scope = scope) { saved ->
                configPrinter = saved
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Saved Printer Row

@Composable
private fun SavedPrinterRow(
    printer: SavedPrinter,
    status: PrinterStatus,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(
                    if (status.isConnected) Success.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (printer.connectionTypeEnum) {
                    PrinterConnectionType.WIFI -> Icons.Filled.Wifi
                    PrinterConnectionType.BLUETOOTH -> Icons.Filled.Bluetooth
                    PrinterConnectionType.USB -> Icons.Filled.Usb
                        PrinterConnectionType.INTERNAL -> Icons.Filled.Print
                },
                contentDescription = null,
                tint = if (status.isConnected) Success
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = printer.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when (status) {
                                is PrinterStatus.Connected, is PrinterStatus.Printing -> Success
                                is PrinterStatus.Connecting -> MaterialTheme.colorScheme.tertiary
                                is PrinterStatus.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                            CircleShape,
                        ),
                )
                Text(
                    text = status.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Roles
            Text(
                text = printer.roleEnums.joinToString(", ") { it.displayName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Discovered Printer Row

@Composable
private fun DiscoveredPrinterRow(
    printer: DiscoveredPrinter,
    alreadySaved: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !alreadySaved, onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (printer.connectionType) {
                    PrinterConnectionType.WIFI -> Icons.Filled.Wifi
                    PrinterConnectionType.BLUETOOTH -> Icons.Filled.Bluetooth
                    PrinterConnectionType.USB -> Icons.Filled.Usb
                        PrinterConnectionType.INTERNAL -> Icons.Filled.Print
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = printer.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${printer.connectionType.displayName} - ${printer.address}${printer.port?.let { ":$it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (alreadySaved) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Ya guardada",
                tint = Success,
            )
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Alta manual por IP

/**
 * El último recurso cuando el descubrimiento no la encuentra.
 *
 * Sin esto, una impresora que no se anuncia por mDNS —VLAN separada,
 * aislamiento de cliente en el WiFi, IP fija sin anuncio— era INCONFIGURABLE, y
 * el local se quedaba sin comandas aunque la impresora estuviera encendida y en
 * la misma red. Era el hueco de instalación más grande que quedaba.
 *
 * Espejo de la sección "Entrada manual" de `PrinterDiscoverySheet` en iOS.
 *
 * 🔴 Se PRUEBA la conexión antes de guardar: guardar una impresora que no
 * responde deja al local con una entrada que dice "Conectada" y nunca imprime
 * —ya pasó con la impresora fantasma de las Sunmi—. Si falla, no se guarda
 * nada y se dice por qué.
 */
@Composable
private fun ManualIpSection(
    printerService: PrinterService,
    scope: kotlinx.coroutines.CoroutineScope,
    onAdded: (SavedPrinter) -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9100") }
    var name by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "¿No aparece tu impresora?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
        Text(
            text = "Agrégala escribiendo su dirección IP. El puerto estándar es 9100.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim(); error = null },
                label = { Text("Dirección IP") },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }; error = null },
                label = { Text("Puerto") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre (opcional)") },
            placeholder = { Text("Cocina") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        Button(
            onClick = {
                val direccion = ip.trim()
                if (direccion.isEmpty() || isAdding) return@Button
                isAdding = true
                error = null
                val printer = SavedPrinter(
                    name = name.trim().ifEmpty { "Impresora $direccion" },
                    connectionType = "wifi",
                    address = direccion,
                    port = port.toIntOrNull() ?: DEFAULT_MANUAL_PORT,
                )
                scope.launch {
                    try {
                        // Probar ANTES de guardar.
                        printerService.connect(printer)
                        printerService.savePrinter(printer)
                        ip = ""; name = ""; port = "$DEFAULT_MANUAL_PORT"
                        isAdding = false
                        onAdded(printer)
                    } catch (e: Exception) {
                        isAdding = false
                        error = "No se pudo conectar con $direccion. " +
                            "Revisa que la impresora esté encendida, en esta misma red y que el puerto sea el correcto."
                    }
                }
            },
            enabled = ip.isNotBlank() && !isAdding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isAdding) "Conectando…" else "Agregar impresora")
        }
    }
}

private const val DEFAULT_MANUAL_PORT = 9100
