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
import com.avoqado.pos.printing.data.ESCPOSPrinter
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
    var leftMarginChars by remember { mutableIntStateOf(printer.leftMarginChars) }
    var autoPrintReceipts by remember { mutableStateOf(printer.autoPrintReceipts) }
    var autoPrintKitchenTickets by remember { mutableStateOf(printer.autoPrintKitchenTickets) }
    var autoOpenCashDrawer by remember { mutableStateOf(printer.autoOpenCashDrawer) }
    var numberOfCopies by remember { mutableIntStateOf(printer.numberOfCopies) }
    var isPrinting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    /**
     * La impresora CON lo que hay ahora en pantalla.
     *
     * 🔴 `printer` es el parámetro del Composable: una copia congelada al abrir
     * la hoja. Lo que el usuario edita vive en los `remember` de arriba, así que
     * usar `printer` directamente imprime con los valores VIEJOS.
     *
     * Eso es lo que pasaba: elegías 58 mm, tocabas "Imprimir página de prueba" y
     * salía a 80 —el ancho con el que se abrió la hoja— con el ticket cortado.
     * El ancho SÍ se guardaba; lo que iba mal era lo que se mandaba a imprimir.
     *
     * Todo lo que use la impresora en esta pantalla tiene que pasar por aquí.
     */
    fun printerEditado(): SavedPrinter = printer.conEdiciones(
        name = name,
        roles = selectedRoles.toList(),
        paperWidthMm = paperWidthMm,
        leftMarginChars = leftMarginChars,
        autoPrintReceipts = autoPrintReceipts,
        autoPrintKitchenTickets = autoPrintKitchenTickets,
        autoOpenCashDrawer = autoOpenCashDrawer,
        numberOfCopies = numberOfCopies,
    )

    fun saveChanges() {
        printerService.updatePrinter(printerEditado())
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
                            try { printerService.connect(printerEditado()) } catch (_: Exception) {}
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
                            // Hereda el corrimiento que YA calibró otra impresora
                            // del local con este mismo ancho. En una flota de
                            // impresoras iguales con adaptadores iguales el número
                            // es el mismo, así que se mide una vez por sucursal y
                            // no una vez por aparato.
                            //
                            // Sólo cuando esta impresora sigue en 0 (nadie la ha
                            // tocado): pisar un valor que alguien ajustó a mano
                            // sería peor que no ayudar. Y se ve en el stepper de
                            // abajo, no se aplica a escondidas.
                            if (leftMarginChars == 0) {
                                margenHeredado(
                                    guardadas = printerService.savedPrinters.value,
                                    exceptoId = printer.id,
                                    anchoMm = width.mm,
                                    destino = printer.connectionTypeEnum,
                                )?.let { leftMarginChars = it }
                            }
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

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            // Corrimiento a la derecha. Sólo hace falta cuando el rollo NO empieza
            // donde la impresora cree que empieza — típicamente un rollo angosto
            // montado con adaptadores en un cabezal más ancho. No se puede
            // detectar: estas impresoras no traen sensor de ancho ni de posición.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recorrer a la derecha", modifier = Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                ) {
                    TextButton(
                        onClick = {
                            if (leftMarginChars > 0) {
                                leftMarginChars--
                                saveChanges()
                            }
                        },
                        enabled = leftMarginChars > 0,
                    ) { Text("-") }

                    Text(
                        "$leftMarginChars",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    TextButton(
                        onClick = {
                            if (leftMarginChars < ESCPOSPrinter.MAX_LEFT_MARGIN_CHARS) {
                                leftMarginChars++
                                saveChanges()
                            }
                        },
                        enabled = leftMarginChars < ESCPOSPrinter.MAX_LEFT_MARGIN_CHARS,
                    ) { Text("+") }
                }
            }

            Text(
                "Déjalo en 0 salvo que el ticket salga mocho de la izquierda. " +
                    "Eso pasa con un rollo angosto puesto con adaptadores en una " +
                    "impresora más ancha. Imprime la página de prueba, cuenta el " +
                    "primer número de la regla que alcances a ver y ponlo aquí.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md))

            // -- AUTO PRINT --
            SectionHeader("Impresión automática")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Imprimir recibos automáticamente", modifier = Modifier.weight(1f))
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
                Text("Imprimir comandas automáticamente", modifier = Modifier.weight(1f))
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
                text = if (isPrinting) "Imprimiendo..." else "Imprimir página de prueba",
                onClick = {
                    isPrinting = true
                    scope.launch {
                        try {
                            printerService.printTestPage(printerEditado())
                            feedbackMessage = "Página de prueba enviada"
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
                            printerService.openCashDrawer(printerEditado())
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
            text = { Text("Esta acción no se puede deshacer.") },
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

/**
 * El corrimiento que ya calibró OTRA impresora del local con el mismo ancho de
 * papel, o `null` si no hay ninguna.
 *
 * Existe para que calibrar sea una vez por sucursal y no una vez por aparato:
 * un local que monta cinco impresoras iguales con los mismos adaptadores tiene
 * el mismo número en las cinco. Se excluye la impresora que se está editando
 * ([exceptoId]) para no heredarse de sí misma.
 *
 * Vive FUERA del Composable para poder fijarlo con un test.
 */
internal fun margenHeredado(
    guardadas: List<SavedPrinter>,
    exceptoId: String,
    anchoMm: Int,
    destino: PrinterConnectionType,
): Int? {
    // 🔴 La INTEGRADA queda fuera, como destino y como origen. El corrimiento
    // existe SÓLO porque un rollo angosto montado con adaptadores no empieza
    // donde el cabezal empieza; un cabezal soldado al equipo no lleva
    // adaptadores nunca, así que su rollo llena su ancho por construcción.
    //
    // Sin esta exclusión, la integrada de una Sunmi hereda el margen de la
    // Epson con adaptadores del mismo local y se recorre a la derecha,
    // comiéndose la columna del precio. Y ése es el peor lado para fallar: un
    // ticket mocho de la izquierda se ve de inmediato, uno al que le falta el
    // último dígito del total se paga.
    if (destino == PrinterConnectionType.INTERNAL) return null

    return guardadas
        .firstOrNull {
            it.id != exceptoId &&
                it.paperWidthMm == anchoMm &&
                it.leftMarginChars > 0 &&
                it.connectionTypeEnum != PrinterConnectionType.INTERNAL
        }
        ?.leftMarginChars
}

/**
 * Aplica a la impresora lo que hay editado en la hoja de configuración.
 *
 * Vive FUERA del Composable para poder fijarlo con un test: el defecto que
 * originó esto (la prueba de impresión salía a 80 mm con 58 elegido) venía de
 * imprimir con la copia congelada, no de un cálculo mal hecho.
 */
internal fun SavedPrinter.conEdiciones(
    name: String,
    roles: List<String>,
    paperWidthMm: Int,
    leftMarginChars: Int,
    autoPrintReceipts: Boolean,
    autoPrintKitchenTickets: Boolean,
    autoOpenCashDrawer: Boolean,
    numberOfCopies: Int,
): SavedPrinter = copy(
    name = name,
    roles = roles,
    paperWidthMm = paperWidthMm,
    leftMarginChars = leftMarginChars,
    autoPrintReceipts = autoPrintReceipts,
    autoPrintKitchenTickets = autoPrintKitchenTickets,
    autoOpenCashDrawer = autoOpenCashDrawer,
    numberOfCopies = numberOfCopies,
)
