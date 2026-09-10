package com.avoqado.pos.inventory.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import com.avoqado.pos.inventory.data.model.onHandDisplay
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.unitSuffixOf
import com.avoqado.pos.scale.ScaleConnectionState
import java.util.Locale

// MARK: - Main Counting View

@Composable
fun StockCountingView(
    viewModel: InventoryViewModel,
    isTablet: Boolean,
    scaleState: ScaleConnectionState = ScaleConnectionState.NotConfigured,
    scaleConfigured: Boolean = false,
    onRetryScale: () -> Unit = {},
) {
    val countItems by viewModel.countItems.collectAsState()
    val selectedIndex by viewModel.selectedItemIndex.collectAsState()
    val countedText by viewModel.countedText.collectAsState()
    val activeCountType by viewModel.activeCountType.collectAsState()
    val countNote by viewModel.countNote.collectAsState()
    val stockItems by viewModel.stockItems.collectAsState()
    val countableRawMaterials by viewModel.countableRawMaterials.collectAsState()
    val salidaPendiente by viewModel.salidaPendiente.collectAsState()
    val confirmacionDeDescarte by viewModel.confirmacionDeDescarte.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val conflictoDelServidor by viewModel.conflictoDelServidor.collectAsState()
    val conflictoDeRevision by viewModel.conflictoDeRevision.collectAsState()
    val soloConsulta by viewModel.soloConsulta.collectAsState()
    // UNA sola fuente para la banda ambar. La regla (el conflicto manda; si no, cuanto
    // trabajo vive solo en este aparato) vive en el ViewModel: repartida entre las dos
    // pantallas, cada una podia decir una cosa distinta del mismo conteo.
    val aviso by viewModel.bandaDeAviso.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var searchText by remember { mutableStateOf("") }
    var showAddPopup by remember { mutableStateOf(false) }

    LaunchedEffect(soloConsulta) {
        if (soloConsulta) showAddPopup = false
    }

    // El BACK del sistema es la salida mas comun en una tablet: sin esto se perdia
    // el conteo sin preguntar. Vive mientras la pantalla esta montada (el Screen la
    // dibuja con `return` temprano, asi que solo existe con `showCounting` en true).
    //
    // 🔴 Son DOS capas y EXCLUYENTES entre si: `AddItemsPopup` NO es un `Dialog` —es
    // un `Surface(fillMaxSize())` compuesto en linea, sin BackHandler propio—, asi que
    // con un solo handler incondicional el BACK sobre el selector no cerraba el
    // selector: abria el dialogo de salida ENCIMA de el, con «Descartar el conteo» de
    // primer boton tocable. Cerrar lo que se ve encima es lo unico que el BACK puede
    // significar ahi. Con `enabled` excluyente hay UN solo handler activo a la vez, asi
    // que el resultado no depende del orden en que se registran.
    //
    // 🔴 Y ninguno de los dos mientras el conteo se esta cerrando en el servidor: el dialogo de
    // salida trae «Descartar el conteo», y descartar lo local con el `/confirm` en vuelo deja al
    // cajero mirando una pantalla vacia sobre un conteo que el servidor SI aplico.
    BackHandler(enabled = showAddPopup) { if (!isSaving) showAddPopup = false }
    // Siempre se consume mientras la pantalla exista; durante `isSaving` se ignora (M1 r2).
    BackHandler(enabled = !showAddPopup) { if (!isSaving) viewModel.pedirSalida() }

    // El selector se abre desde DOS sitios (tablet y telefono) y el catalogo pudo quedarse
    // vacio si su unica peticion murio sin red: entonces «Agregar articulos» abre en blanco
    // y el ciclico es inservible hasta reiniciar la app (D1 del QA). Se pide aqui, keyeado
    // por el estado, y no en cada `onAddItems`: asi lo cubre TODO camino que abra el
    // selector, tambien el que alguien agregue manana. `asegurarCatalogo` no hace nada si
    // ya hay catalogo o si no hay red.
    LaunchedEffect(showAddPopup) { if (showAddPopup) viewModel.asegurarCatalogo() }

    // 🔴 Un error del ViewModel en esta pantalla NO se veia. El unico `SnackbarHost` del
    // modulo vive en `InventoryScreen`, DESPUES de su `return` temprano: mientras se cuenta
    // no hay ninguno montado, asi que su `showSnackbar` queda suspendido para siempre y el
    // mensaje se lo traga la pantalla. Es lo que midio el QA en la tablet. Mismo patron que
    // `InventoryScreen` (host propio + efecto + limpiar al cerrarse).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearErrorMessage()
        }
    }

    val filteredItems = if (searchText.isBlank()) countItems
    else countItems.filter {
        it.productName.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        CountingHeader(
            onCancel = { viewModel.pedirSalida() },
            onNext = { viewModel.finishCounting() },
            hasItems = countItems.isNotEmpty() && !soloConsulta,
            soloConsulta = soloConsulta,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 🔴 Calcular esto AQUI era el defecto I1: en un conteo CICLICO `pendientesDeEnviar`
        // esta siempre vacio (no hay conteo en el servidor al que mandarle nada), asi que la
        // banda de «sin conexion» no podia salir NUNCA y el cajero contaba con el WiFi
        // apagado sin que la pantalla dijera una palabra. La regla ya resuelta la da el
        // ViewModel.
        aviso?.let { BandaDeAvisoDeConteo(it) }

        if (soloConsulta) {
            NotaLocalEnConsulta(countNote)
        }

        if (isTablet) {
            // iPad-style split layout
            Row(modifier = Modifier.fillMaxSize()) {
                // Left: Item list
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    ItemListPanel(
                        items = filteredItems,
                        selectedIndex = if (searchText.isBlank()) selectedIndex else -1,
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onItemTap = { index ->
                            val realIndex = if (searchText.isBlank()) index
                            else countItems.indexOf(filteredItems[index])
                            viewModel.selectCountItem(realIndex)
                            searchText = ""
                        },
                        onAddItems = { showAddPopup = true },
                        isCycle = activeCountType == StockCountType.CYCLE,
                        puedeEditar = !soloConsulta,
                        stockSuggestions = if (soloConsulta || searchText.isBlank()) emptyList()
                        else (stockItems + countableRawMaterials).filter { stock ->
                            stock.id !in countItems.map { it.productId } && (
                                stock.name.contains(searchText, ignoreCase = true) ||
                                    (stock.sku?.contains(searchText, ignoreCase = true) == true)
                                )
                        }.take(10),
                        onAddSuggestion = { stock ->
                            viewModel.addItemsToCycleCount(listOf(stock))
                            searchText = ""
                        },
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                // Right: Quantity controls + keypad
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .padding(AvoqadoTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val selectedItem = countItems.getOrNull(selectedIndex)
                    if (selectedItem != null) {
                        SelectedItemInfo(item = selectedItem)
                        InventoryScaleReading(
                            item = selectedItem,
                            state = scaleState,
                            configured = scaleConfigured,
                            planQueFalta = if (viewModel.hasScaleIntegration) null else viewModel.scaleTierLabel,
                            onUseWeight = viewModel::updateCountedText,
                            onRetry = onRetryScale,
                            puedeEditar = !soloConsulta,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    QuantityControls(
                        countedText = countedText,
                        onIncrement = { viewModel.incrementCount() },
                        onDecrement = { viewModel.decrementCount() },
                        enabled = !soloConsulta,
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

                    NumericKeypad(
                        onDigit = { digit ->
                            val updated = when (digit) {
                                "." -> when {
                                    countedText.contains(".") -> countedText
                                    countedText.isEmpty() -> "0."
                                    else -> countedText + "."
                                }
                                else -> countedText + digit
                            }
                            viewModel.updateCountedText(updated)
                        },
                        onBackspace = {
                            if (countedText.isNotEmpty()) {
                                viewModel.updateCountedText(countedText.dropLast(1))
                            }
                        },
                        enabled = !soloConsulta,
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                    // El botón decía "Guardar conteo" pero su acción era
                    // `moveToNextItem()`: avanzaba al siguiente artículo sin cerrar
                    // nada, y en el ÚLTIMO no hacía absolutamente nada. Quien acababa
                    // de contar su estante tocaba "Guardar", no pasaba nada, y se
                    // quedaba sin saber si su conteo existía. El layout de teléfono
                    // ya lo llamaba "Siguiente artículo" — era el nombre, no la lógica.
                    val esUltimoArticulo = selectedIndex >= countItems.size - 1
                    PrimaryButton(
                        text = if (esUltimoArticulo) "Revisar conteo" else "Siguiente artículo",
                        onClick = {
                            if (esUltimoArticulo) viewModel.finishCounting() else viewModel.moveToNextItem()
                        },
                        enabled = selectedIndex >= 0 && countItems.isNotEmpty() && !soloConsulta,
                    )
                }
            }
        } else {
            // iPhone-style stacked layout
            Column(modifier = Modifier.fillMaxSize()) {
                // Search + add
                SearchBar(
                    searchText = searchText,
                    onSearchChange = { searchText = it },
                    onAddItems = { showAddPopup = true },
                    isCycle = activeCountType == StockCountType.CYCLE,
                    puedeAgregar = !soloConsulta,
                )

                // Quantity controls
                val selectedItem = countItems.getOrNull(selectedIndex)
                if (selectedItem != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AvoqadoTheme.spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedItem.productName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        QuantityControls(
                            countedText = countedText,
                            onIncrement = { viewModel.incrementCount() },
                            onDecrement = { viewModel.decrementCount() },
                            compact = true,
                            enabled = !soloConsulta,
                        )
                    }
                    InventoryScaleReading(
                        item = selectedItem,
                        state = scaleState,
                        configured = scaleConfigured,
                        planQueFalta = if (viewModel.hasScaleIntegration) null else viewModel.scaleTierLabel,
                        onUseWeight = viewModel::updateCountedText,
                        onRetry = onRetryScale,
                        puedeEditar = !soloConsulta,
                        modifier = Modifier.padding(
                            horizontal = AvoqadoTheme.spacing.lg,
                            vertical = AvoqadoTheme.spacing.sm,
                        ),
                    )
                }

                // Keypad
                NumericKeypad(
                    onDigit = { digit ->
                        val updated = when (digit) {
                            "." -> when {
                                countedText.contains(".") -> countedText
                                countedText.isEmpty() -> "0."
                                else -> countedText + "."
                            }
                            else -> countedText + digit
                        }
                        viewModel.updateCountedText(updated)
                    },
                    onBackspace = {
                        if (countedText.isNotEmpty()) {
                            viewModel.updateCountedText(countedText.dropLast(1))
                        }
                    },
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg),
                    enabled = !soloConsulta,
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                // Mismo criterio que el layout de tablet: en el último artículo el
                // botón cierra el conteo en vez de quedarse apagado sin explicación.
                val esUltimoArticuloCompacto = selectedIndex >= countItems.size - 1
                PrimaryButton(
                    text = if (esUltimoArticuloCompacto) "Revisar conteo" else "Siguiente artículo",
                    onClick = {
                        if (esUltimoArticuloCompacto) viewModel.finishCounting() else viewModel.moveToNextItem()
                    },
                    enabled = selectedIndex >= 0 && countItems.isNotEmpty() && !soloConsulta,
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg),
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                // Items list
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(filteredItems, key = { _, it -> it.productId }) { index, item ->
                        val realIndex = if (searchText.isBlank()) index
                        else countItems.indexOf(item)
                        CountItemRow(
                            item = item,
                            isSelected = realIndex == selectedIndex,
                            onTap = {
                                viewModel.selectCountItem(realIndex)
                                searchText = ""
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // Add Items Popup
    if (showAddPopup && !soloConsulta) {
        AddItemsPopup(
            // Sólo lo que se puede contar físicamente. Los artículos por RECETA no
            // tienen existencia propia —se calcula desde sus ingredientes— y el
            // server los descarta al crear el conteo (`inventoryMethod != RECIPE`).
            // Ofrecerlos hacía que el gerente contara su estante, confirmara, y el
            // conteo se guardara VACÍO diciendo "Completado": el trabajo se perdía
            // sin un solo aviso. Para esos artículos se cuentan sus insumos, que ya
            // aparecen en esta misma lista.
            stockItems = stockItems.filter { it.isCountable } + countableRawMaterials,
            existingIds = countItems.map { it.productId }.toSet(),
            isLoading = isLoading,
            onAdd = { selected ->
                viewModel.addItemsToCycleCount(selected)
                searchText = ""
                showAddPopup = false
            },
            onDismiss = { showAddPopup = false },
        )
    }

    // Tres opciones, no dos: «Descartar» y «Seguir contando» son cosas distintas y
    // colapsarlas hace que la X del dialogo signifique una de ellas por accidente.
    if (salidaPendiente && confirmacionDeDescarte == null) {
        val contadas = ConteoEnCurso.contadas(countItems)
        val cerradoEnServidor = conflictoDelServidor != null
        val conflictoDeRevisionVisible = conflictoDeRevision
        val descripcion = when {
            cerradoEnServidor -> ConteoEnCurso.descripcionSalir(contadas, countItems.size, hayConflicto = true)
            conflictoDeRevisionVisible != null ->
                ConteoEnCurso.descripcionConflictoRevision(conflictoDeRevisionVisible)
            else -> ConteoEnCurso.descripcionSalir(contadas, countItems.size)
        }
        AvoqadoDialog(
            title = ConteoEnCurso.TITULO_SALIR,
            description = descripcion,
            // Cerrar con la X o tocando fuera = «Seguir contando»: es lo unico que no
            // decide nada sobre el conteo.
            onDismiss = { viewModel.cancelarSalida() },
            actionButton = {
                PrimaryButton(
                    text = if (soloConsulta) ConteoEnCurso.SALIR_Y_CONSERVAR else ConteoEnCurso.GUARDAR_EL_AVANCE,
                    onClick = { viewModel.guardarYSalir() },
                    fullWidth = true,
                    enabled = !isSaving,
                )
            },
            content = {
                // 🔴 La irreversible va AL FINAL, nunca primero. `AvoqadoDialog` pinta
                // `content` arriba del boton de accion, asi que «Descartar» era el
                // primer elemento tocable bajo la descripcion: un dedo que baja leyendo
                // aterrizaba en el boton rojo del conteo que vino a NO perder.
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { viewModel.cancelarSalida() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (soloConsulta) ConteoEnCurso.SEGUIR_VIENDO else ConteoEnCurso.SEGUIR_CONTANDO,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // CLOSED conserva el descarte de W4. Un conflicto de revisión o UNKNOWN, en
                    // cambio, es una copia local de consulta que no se puede borrar desde aquí.
                    if (conflictoDeRevisionVisible == null) {
                        TextButton(
                            onClick = { viewModel.pedirDescarte() },
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = ConteoEnCurso.DESCARTAR,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = AvoqadoTheme.spacing.lg),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            },
        )
    }

    confirmacionDeDescarte?.let { confirmacion ->
        AvoqadoDialog(
            title = ConteoEnCurso.tituloDescartar(confirmacion.contadas),
            description = ConteoEnCurso.DESCRIPCION_DESCARTAR,
            onDismiss = { viewModel.cancelarDescarte() },
            actionButton = {
                PrimaryButton(
                    text = ConteoEnCurso.DESCARTAR,
                    onClick = { viewModel.confirmarDescarte() },
                    enabled = !isSaving,
                    fullWidth = true,
                    destructive = true,
                )
            },
            content = {
                TextButton(
                    onClick = { viewModel.cancelarDescarte() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ConteoEnCurso.VOLVER, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // Hermano del `Column` raiz y AL FINAL, que es el mismo recurso del que ya vive
    // `AddItemsPopup` (un `Surface(fillMaxSize)` en linea): asi se dibuja encima de todo,
    // incluido el selector de articulos. Un `Box` sin `pointerInput` no es blanco de toque,
    // asi que sin snackbar no estorba a lo que hay debajo.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = snackbarHostState)
    }
}

/**
 * Banda de aviso del conteo — la MISMA en contar y en revisar.
 *
 * Ambar (`Warning`) y nunca roja: sin red es un estado NORMAL y el conflicto es un
 * hecho del servidor, no una falla del cajero.
 *
 * Vive en un solo sitio a proposito: si cada pantalla dibujara la suya, una podria
 * decir el conflicto y la otra callarlo — que es justo el defecto que se arreglo aqui.
 *
 * `Color.White` sobre `Warning` es el patron ya establecido en el repo para esta banda
 * (`QuarantineSheet.kt:317`); hoy no hay token `OnWarning` en el tema.
 */
@Composable
internal fun BandaDeAvisoDeConteo(texto: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Warning)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = texto,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InventoryScaleReading(
    item: StockCountItem,
    state: ScaleConnectionState,
    configured: Boolean,
    /** null = el local tiene el plan. Con texto, es la etiqueta del plan que falta. */
    planQueFalta: String? = null,
    onUseWeight: (String) -> Unit,
    onRetry: () -> Unit,
    puedeEditar: Boolean = true,
    modifier: Modifier = Modifier.padding(top = AvoqadoTheme.spacing.md),
) {
    val conversion = scaleConversion(item.unit) ?: return
    // Antes: sin perfil configurado no se pintaba NADA. Con el candado de plan
    // eso dejaba la báscula muda —el operador no sabía por qué no aparece—, así
    // que el aviso de plan se muestra aunque no haya perfil. Nunca bloquea el
    // conteo manual. Espejo de `.requierePlan` en iOS.
    if (planQueFalta != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
            ) {
                Text(
                    text = "Báscula · Incluido en $planQueFalta",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Conectar una báscula es parte del plan $planQueFalta. " +
                        "Actívalo desde tu dashboard web (Configuración → Plan). " +
                        "El conteo manual sigue disponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    if (!configured) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        ) {
            Text(
                text = "Báscula",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when (state) {
                is ScaleConnectionState.Connecting ->
                    Text("Conectando ${state.profileName}…", style = MaterialTheme.typography.bodySmall)
                is ScaleConnectionState.Ready ->
                    Text("Coloca el producto y espera una lectura estable.", style = MaterialTheme.typography.bodySmall)
                is ScaleConnectionState.Unstable -> {
                    Text(
                        text = "${formatScaleValue(conversion.fromKg(state.reading.netKg.toDouble()))} ${conversion.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Esperando que el peso se estabilice…", style = MaterialTheme.typography.bodySmall)
                }
                is ScaleConnectionState.Stable -> {
                    val converted = conversion.fromKg(state.reading.netKg.toDouble())
                    Text(
                        text = "${formatScaleValue(converted)} ${conversion.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    PrimaryButton(
                        text = "Usar este peso",
                        onClick = { onUseWeight(formatScaleValue(converted)) },
                        enabled = puedeEditar,
                    )
                }
                is ScaleConnectionState.Problem -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Text("Reintentar conexión")
                    }
                }
                ScaleConnectionState.NotConfigured -> {
                    Text(
                        text = "Báscula no conectada. Puedes capturar el conteo manualmente.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetry) {
                        Text("Conectar")
                    }
                }
            }
        }
    }
}

private data class ScaleUnitConversion(
    val label: String,
    val fromKg: (Double) -> Double,
)

private fun scaleConversion(unit: String?): ScaleUnitConversion? = when (unit?.uppercase()) {
    "KILOGRAM", "KILOGRAMS", "KG", "KILO", "KILOS" ->
        ScaleUnitConversion("kg") { it }
    "GRAM", "GRAMS", "G", "GRAMO", "GRAMOS" ->
        ScaleUnitConversion("g") { it * 1_000.0 }
    else -> null
}

private fun formatScaleValue(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }

// MARK: - Header

@Composable
private fun CountingHeader(
    onCancel: () -> Unit,
    onNext: () -> Unit,
    hasItems: Boolean,
    soloConsulta: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cancel pill button
        Surface(
            onClick = onCancel,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = if (soloConsulta) "Salir" else "Cancelar",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Conteo de existencias",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Next pill button
        Surface(
            onClick = onNext,
            enabled = hasItems,
            shape = RoundedCornerShape(50),
            color = if (hasItems) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = "Siguiente",
                style = MaterialTheme.typography.labelLarge,
                color = if (hasItems) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            )
        }
    }
}

@Composable
private fun NotaLocalEnConsulta(nota: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
    ) {
        Text(
            text = "Nota del conteo",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                // Sólo la cadena exactamente vacía usa placeholder. Espacios y saltos son parte
                // de la nota local y se conservan sin normalizar ni truncar.
                text = if (nota.isEmpty()) "Sin nota" else nota,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(AvoqadoTheme.spacing.md),
            )
        }
    }
}

// MARK: - Search Bar

@Composable
private fun SearchBar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onAddItems: () -> Unit,
    isCycle: Boolean,
    puedeAgregar: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        TextField(
            value = searchText,
            onValueChange = onSearchChange,
            placeholder = { Text("Buscar artículos") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        )

        if (isCycle && puedeAgregar) {
            IconButton(onClick = onAddItems) {
                Icon(Icons.Filled.Add, contentDescription = "Agregar artículos")
            }
        }
    }
}

// MARK: - Item List Panel (iPad left side)

@Composable
private fun ItemListPanel(
    items: List<StockCountItem>,
    selectedIndex: Int,
    searchText: String,
    onSearchChange: (String) -> Unit,
    onItemTap: (Int) -> Unit,
    onAddItems: () -> Unit,
    isCycle: Boolean,
    puedeEditar: Boolean = true,
    stockSuggestions: List<StockItem> = emptyList(),
    onAddSuggestion: (StockItem) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            searchText = searchText,
            onSearchChange = onSearchChange,
            onAddItems = onAddItems,
            isCycle = isCycle,
            puedeAgregar = puedeEditar,
        )

        val isSearching = searchText.isNotBlank()
        val hasItems = items.isNotEmpty()
        val hasSuggestions = stockSuggestions.isNotEmpty()

        when {
            // Empty state: no items and no search
            !hasItems && !isSearching -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        Icon(
                            Icons.Filled.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Text(
                            text = "Busca o agrega artículos para contar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isCycle && puedeEditar) {
                            PrimaryButton(
                                text = "Agregar artículos",
                                onClick = onAddItems,
                                )
                        }
                    }
                }
            }
            // Searching with no matches and no suggestions
            isSearching && !hasItems && !hasSuggestions -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Sin resultados para \"$searchText\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Show items + (when searching) suggestions
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (hasItems) {
                        itemsIndexed(items, key = { _, it -> "count_${it.productId}" }) { index, item ->
                            CountItemRow(
                                item = item,
                                isSelected = index == selectedIndex,
                                onTap = { onItemTap(index) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (puedeEditar && isSearching && hasSuggestions) {
                        item(key = "suggestions_header") {
                            Text(
                                text = "Agregar al conteo",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = AvoqadoTheme.spacing.lg,
                                    vertical = AvoqadoTheme.spacing.sm,
                                ),
                            )
                        }
                        items(stockSuggestions, key = { "stock_${it.id}" }) { stock ->
                            StockSuggestionRow(
                                stock = stock,
                                onTap = { onAddSuggestion(stock) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Stock Suggestion Row (tap to add to count)

@Composable
private fun StockSuggestionRow(
    stock: StockItem,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
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
                text = stock.name.take(2).uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stock.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stock.sku != null) {
                Text(
                    text = stock.sku,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Icon(
            Icons.Filled.Add,
            contentDescription = "Agregar",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

// MARK: - Count Item Row

@Composable
private fun CountItemRow(
    item: StockCountItem,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                else Modifier,
            )
            .clickable(onClick = onTap)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Initials avatar
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.sku != null) {
                Text(
                    text = item.sku,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Counted badge
        if (item.counted > 0) {
            Surface(
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = if (item.counted == item.counted.toLong().toDouble()) {
                        item.counted.toLong().toString()
                    } else {
                        String.format(java.util.Locale.US, "%.2f", item.counted)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// MARK: - Selected Item Info (iPad right panel)

@Composable
private fun SelectedItemInfo(item: StockCountItem) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md),
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.productName.take(2).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = item.productName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        if (item.isIngredient) {
            Text(
                text = "Insumo" + unitSuffixOf(item.unit).let { if (it.isNotEmpty()) " ·${it}" else "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (item.sku != null) {
            Text(
                text = "SKU: ${item.sku}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Esperado: ${item.expectedDisplay}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Quantity Controls

@Composable
private fun QuantityControls(
    countedText: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.xl),
    ) {
        // Minus button
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(if (compact) 36.dp else 48.dp)
                .clickable(enabled = enabled, onClick = onDecrement),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = "Menos",
                    modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                )
            }
        }

        // Current count display
        Text(
            text = countedText.ifEmpty { "0" },
            style = if (compact) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(if (compact) 60.dp else 120.dp),
            textAlign = TextAlign.Center,
        )

        // Plus button
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(if (compact) 36.dp else 48.dp)
                .clickable(enabled = enabled, onClick = onIncrement),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Mas",
                    modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                )
            }
        }
    }
}

// MARK: - Numeric Keypad

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                row.forEach { key ->
                    Surface(
                        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable(enabled = enabled) {
                                if (key == "⌫") onBackspace()
                                else onDigit(key)
                            },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (key == "⌫") {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Borrar",
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Add Items Popup

@Composable
private fun AddItemsPopup(
    stockItems: List<StockItem>,
    existingIds: Set<String>,
    isLoading: Boolean,
    onAdd: (List<StockItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by remember { mutableStateOf("") }
    val selectedItems = remember { mutableStateListOf<StockItem>() }

    val available = stockItems.filter { it.id !in existingIds }
    val filtered = if (searchText.isBlank()) available
    else available.filter {
        it.name.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Agregar artículos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = { onAdd(selectedItems.toList()) },
                    enabled = selectedItems.isNotEmpty(),
                ) {
                    Text("Agregar (${selectedItems.size})")
                }
            }

            HorizontalDivider()

            // Search
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Buscar por nombre o SKU") },
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

            when {
                isLoading && available.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(ConteoEnCurso.CARGANDO_ARTICULOS, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                available.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(ConteoEnCurso.NO_HAY_ARTICULOS_DISPONIBLES, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                searchText.isNotBlank() && filtered.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ConteoEnCurso.sinResultados(searchText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
                ) {
                    items(filtered.size, key = { filtered[it].id }) { index ->
                    val item = filtered[index]
                    val isSelected = item in selectedItems

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selectedItems.remove(item)
                                else selectedItems.add(item)
                            }
                            .padding(vertical = AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (isSelected) selectedItems.remove(item)
                                else selectedItems.add(item)
                            },
                        )

                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (item.sku != null) {
                                Text(
                                    text = item.sku,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Text(
                            text = "En mano: ${item.onHandDisplay}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
