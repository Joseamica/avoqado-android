package com.avoqado.pos.kds.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.ui.zIndex
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.kds.data.KitchenPreparationRepository
import com.avoqado.pos.kds.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PreparationServiceState(val items: List<PreparationLine> = emptyList(), val total: Int = 0,
    val cursor: String? = null, val hasMore: Boolean = false, val loading: Boolean = false,
    val message: String? = null, val pendingKeys: Set<String> = emptySet(), val history: Boolean = false,
    val sendingUrgent: Boolean = false, val localTotal: Int = 0, val serverTotalKnown: Boolean = false,
    val paperItems: List<PreparationPaperIssue> = emptyList(), val paperTotal: Int = 0,
    val paperCursor: String? = null, val paperHasMore: Boolean = false, val paperLoading: Boolean = false)

@HiltViewModel
class PreparationServiceViewModel @Inject constructor(
    private val repository: KitchenPreparationRepository, private val storage: SecureStorage, outbox: SyncOutbox,
    private val delivery: com.avoqado.pos.kds.data.PreparationDeliveryService? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(PreparationServiceState())
    val state = _state.asStateFlow()
    private var orderId: String? = null
    private var visible = false
    private var epoch = 0L
    private var remoteRows = emptyList<PreparationLine>()
    private var localRows = emptyList<PreparationLine>()
    private var localCursor: String? = null
    private var localHasMore = false
    private var remoteHasMore = true
    private fun resetPages() { remoteRows = emptyList(); localRows = emptyList(); localCursor = null; localHasMore = false; remoteHasMore = true }
    private fun appendRows(before: List<PreparationLine>, after: List<PreparationLine>) =
        (before + after).associateBy { it.stableKey }.values.toList()
    init { viewModelScope.launch { outbox.acks.collect {
        if (visible && (it.result?.containsKey("items") == true || it.errorCode?.startsWith("PREPARATION_") == true)) {
            delay(250); refresh()
        }
    } } }
    init {
        delivery?.let { service ->
            viewModelScope.launch { service.changes.debounce(150).collect { venueId ->
                if (visible && storage.venueId == venueId) {
                    try {
                    val generation = epoch
                    val rows = _state.value.items.chunked(100).flatMap { repository.merge(venueId, it, acceptBaseline = false) }
                    if (generation == epoch && storage.venueId == venueId) {
                        val byKey = rows.associateBy { it.stableKey }
                        remoteRows = remoteRows.map { byKey[it.stableKey] ?: it }
                        localRows = localRows.map { byKey[it.stableKey] ?: it }
                        _state.value = _state.value.copy(items = rows, pendingKeys = repository.pendingKeys(venueId, rows),
                            message = service.notice.value ?: _state.value.message)
                    }
                    refreshPaper()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { _state.value = _state.value.copy(message = e.message ?: "No se pudo recuperar la preparación guardada") }
                }
            } }
            viewModelScope.launch {
                var previousNotice: String? = null
                service.notice.collect { message ->
                if (visible && (message != null || _state.value.message == previousNotice)) _state.value = _state.value.copy(message = message)
                previousNotice = message
            } }
        }
    }
    fun open(order: String?) { orderId = order; visible = true; epoch++; resetPages(); _state.value = PreparationServiceState(); refresh(negotiate = true); refreshPaper() }
    fun showHistory(history: Boolean) {
        epoch++; resetPages(); _state.value = PreparationServiceState(history = history); refresh()
    }
    fun close() { visible = false; epoch++ }
    fun refreshPaper(more: Boolean = false) {
        val service = delivery ?: return
        val v = storage.venueId ?: return
        if (!visible || _state.value.paperLoading) return
        val generation = epoch
        val previous = _state.value
        _state.value = previous.copy(paperLoading = true)
        viewModelScope.launch {
            try {
                val page = service.paperPage(v, previous.paperCursor.takeIf { more })
                if (visible && epoch == generation && storage.venueId == v) _state.value = _state.value.copy(
                    paperItems = if (more) (previous.paperItems + page.items).distinctBy { it.intentId } else page.items,
                    paperTotal = page.total, paperCursor = page.cursor, paperHasMore = page.hasMore)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (epoch == generation) _state.value = _state.value.copy(message = e.message) }
            finally { if (epoch == generation) _state.value = _state.value.copy(paperLoading = false) }
        }
    }
    fun resolvePaper(issue: PreparationPaperIssue, reprint: Boolean) {
        val service = delivery ?: return
        val v = storage.venueId ?: return
        val generation = epoch
        viewModelScope.launch {
            try { service.resolvePaper(v, issue.intentId, reprint); if (epoch == generation) refreshPaper() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) _state.value = _state.value.copy(message = e.message) }
        }
    }
    fun can(action: PreparationAction) = repository.can(action)
    private fun selection(row: PreparationLine) = _state.value.items.filter {
        it.orderId == row.orderId && (if (row.orderItemId != null) it.orderItemId == row.orderItemId
            else it.externalId == row.externalId && it.sourceKey?.substringBeforeLast(':') == row.sourceKey?.substringBeforeLast(':'))
    }
    fun limit(row: PreparationLine, action: PreparationAction): Int {
        val rows = selection(row)
        if (action.priority) return if (rows.size != row.stationCount || rows.any { it.unavailableReason != null }) 0 else rows.maxOfOrNull { it.preparation.available(action) } ?: 0
        return if (rows.size != row.stationCount || rows.any { it.unavailableReason != null }) 0 else rows.minOfOrNull { it.preparation.available(action) } ?: 0
    }
    fun refresh(more: Boolean = false, negotiate: Boolean = false) {
        if (_state.value.loading || !visible) return
        val venueId = storage.venueId ?: return
        val generation = epoch
        val previous = _state.value
        val remoteCursor = previous.cursor.takeIf { more }
        val draftCursor = localCursor.takeIf { more }
        val readRemote = !more || remoteHasMore
        val readLocal = !more || localHasMore
        _state.value = previous.copy(loading = true)
        viewModelScope.launch {
            fun current() = generation == epoch && storage.venueId == venueId
            suspend fun publish() {
                val rows = (remoteRows + localRows).distinctBy { it.stableKey }
                val pending = repository.pendingKeys(venueId, rows)
                if (current()) _state.value = _state.value.copy(items = rows, hasMore = remoteHasMore || localHasMore, pendingKeys = pending)
            }
            try {
                repository.restoreCapabilities(venueId)
                if (!current()) return@launch
                if (readLocal) try {
                    val page = repository.localPage(venueId, orderId, draftCursor, previous.history)
                    if (!current()) return@launch
                    localRows = if (more) appendRows(localRows, page?.items.orEmpty()) else page?.items.orEmpty()
                    localCursor = page?.nextCursor; localHasMore = page?.hasMore ?: false
                    _state.value = _state.value.copy(localTotal = page?.total ?: 0)
                    publish()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { if (current()) _state.value = _state.value.copy(message = e.message) }
                if (readRemote) try {
                    val saved = repository.savedPage(venueId, orderId, remoteCursor, previous.history)
                    if (!current()) return@launch
                    if (saved != null) {
                        remoteRows = if (more) appendRows(remoteRows, saved.items) else saved.items
                        remoteHasMore = saved.hasMore
                        _state.value = _state.value.copy(total = saved.total, cursor = saved.nextCursor, serverTotalKnown = !saved.cachedOnly, message = repository.notice.value)
                        publish()
                    }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { if (current()) _state.value = _state.value.copy(message = e.message) }
                if (negotiate || !repository.negotiated(venueId)) repository.refreshCapabilities(venueId)
                if (!current()) return@launch
                if (readRemote) {
                    val page = repository.page(venueId, orderId, remoteCursor, previous.history)
                    if (!current()) return@launch
                    remoteRows = if (more) appendRows(remoteRows, page.items) else page.items
                    remoteHasMore = page.hasMore
                    _state.value = _state.value.copy(total = page.total, cursor = page.nextCursor, serverTotalKnown = !page.cachedOnly, message = repository.notice.value)
                    publish()
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (current()) {
                    if (remoteRows.isEmpty() && !_state.value.serverTotalKnown) remoteHasMore = false
                    _state.value = _state.value.copy(message = e.message ?: "No se pudieron cargar los productos")
                } }
              finally { if (current()) _state.value = _state.value.copy(loading = false, hasMore = remoteHasMore || localHasMore) }
        }
    }
    fun act(row: PreparationLine, action: PreparationAction, quantity: Int, from: PreparationState?, reason: String?) {
        val venueId = storage.venueId ?: return
        val selected = selection(row).filter { !action.priority || it.preparation.available(action) > 0 }
        val generation = epoch
        viewModelScope.launch {
            try {
                val updated = repository.act(venueId, selected, action, quantity, from, reason).associateBy { it.stableKey }
                if (generation != epoch || storage.venueId != venueId) return@launch
                remoteRows = remoteRows.map { updated[it.stableKey] ?: it }
                localRows = localRows.map { updated[it.stableKey] ?: it }
                val current = _state.value
                _state.value = current.copy(items = current.items.map { updated[it.stableKey] ?: it },
                    pendingKeys = current.pendingKeys + updated.keys, message = action.label + ": " + quantity + " · " + row.productName + ". Pendiente de sincronizar.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) _state.value = _state.value.copy(message = e.message ?: "No se pudo guardar la acción") }
        }
    }
    fun sendUrgent(keys: Set<String>, onSuccess: () -> Unit) {
        val venueId = storage.venueId ?: return
        if (_state.value.sendingUrgent) return
        val generation = epoch
        _state.value = _state.value.copy(sendingUrgent = true)
        viewModelScope.launch {
            try {
                val selected = selectUrgentProducts(_state.value.items, keys)
                val updated = repository.act(venueId, selected, PreparationAction.URGENT, 1).associateBy { it.stableKey }
                if (generation != epoch || storage.venueId != venueId) return@launch
                remoteRows = remoteRows.map { updated[it.stableKey] ?: it }; localRows = localRows.map { updated[it.stableKey] ?: it }
                _state.value = _state.value.copy(items = _state.value.items.map { updated[it.stableKey] ?: it },
                    pendingKeys = _state.value.pendingKeys + updated.keys, message = "Urgencia guardada. Pendiente de sincronizar.")
                onSuccess()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) _state.value = _state.value.copy(message = e.message ?: "No se pudo guardar la urgencia") }
            finally { if (generation == epoch) _state.value = _state.value.copy(sendingUrgent = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreparationServiceScreen(orderId: String? = null, onDismiss: () -> Unit, urgentSelection: Boolean = false,
    viewModel: PreparationServiceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var urgentKeys by remember { mutableStateOf(emptySet<String>()) }
    val urgentRows = state.items.groupBy { it.urgencyProductKey }.values.mapNotNull { group ->
        group.firstOrNull { it.preparation.available(PreparationAction.URGENT) > 0 }
    }.sortedWith(compareBy({ it.serviceCourse?.sortOrder ?: 0 }, { it.urgencyProductKey }))
    var showPaper by remember { mutableStateOf(false) }
    var retryPaper by remember { mutableStateOf<PreparationPaperIssue?>(null) }
    LaunchedEffect(orderId) { viewModel.open(orderId) }
    DisposableEffect(Unit) { onDispose { viewModel.close() } }
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize().zIndex(10f)) {
        ImmersiveWindow()
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AvoqadoFullscreenHeader(
                title = if (urgentSelection) "Enviar urgente" else "Servicio y entregas",
                onNav = onDismiss,
                primaryActionText = if (urgentSelection) "Enviar · ${urgentKeys.size}" else null,
                onPrimaryAction = if (urgentSelection) ({ viewModel.sendUrgent(urgentKeys) { urgentKeys = emptySet() } }) else null,
                primaryActionEnabled = urgentKeys.isNotEmpty() && !state.sendingUrgent && viewModel.can(PreparationAction.URGENT),
                showDivider = true,
            )
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = AvoqadoTheme.spacing.lg)) {
                Text(if (urgentSelection) "Selecciona los productos. Los retenidos se liberan; los pendientes o en preparación conservan su avance."
                    else "Caja y meseros: libera los productos para cocina y marca su entrega al cliente.", style = MaterialTheme.typography.bodyMedium)
                if (orderId == null && !urgentSelection) Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    FilterChip(selected = !state.history, onClick = { viewModel.showHistory(false) }, label = { Text("En servicio") })
                    FilterChip(selected = state.history, onClick = { viewModel.showHistory(true) }, label = { Text("Terminados") })
                }
                state.message?.let { Text(it, Modifier.padding(vertical = AvoqadoTheme.spacing.sm), style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    Text(if (state.localTotal > 0) "${state.items.size} visibles · ${state.localTotal} guardados aquí" +
                        (if (state.serverTotalKnown) " · ${state.total} en servidor" else " · Servidor por consultar")
                        else if (!state.serverTotalKnown) "${state.items.size} de ${state.total} guardados aquí · Servidor por consultar"
                        else state.items.size.toString() + " de " + state.total + " renglones", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.loading) { Text("Actualizar") }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                    if (state.paperTotal > 0) item {
                        OutlinedButton(onClick = { showPaper = !showPaper; if (showPaper) viewModel.refreshPaper() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Envíos a cocina · ${state.paperTotal} por revisar en esta sucursal")
                        }
                    }
                    if (showPaper) {
                        items(state.paperItems, key = { "paper:${it.intentId}" }) { issue ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                    Text("Envío · #${issue.orderNumber}", style = MaterialTheme.typography.titleMedium)
                                    Text(issue.message, style = MaterialTheme.typography.bodyMedium)
                                    if (issue.state in setOf("FAILED", "UNCERTAIN", "REVIEW") && viewModel.can(PreparationAction.RELEASE)) {
                                        Text(if (issue.canRetry) "Antes de reimprimir, revisa si salió el ticket." else "Confirma después de avisar a cocina.", style = MaterialTheme.typography.bodySmall)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                            if (issue.canRetry) Button(onClick = { retryPaper = issue }, modifier = Modifier.weight(1f)) { Text("Reimprimir ticket") }
                                            OutlinedButton(onClick = { viewModel.resolvePaper(issue, false) }, modifier = Modifier.weight(1f)) { Text("Ya avisé a cocina") }
                                        }
                                    }
                                }
                            }
                        }
                        if (state.paperHasMore) item {
                            OutlinedButton(onClick = { viewModel.refreshPaper(more = true) }, enabled = !state.paperLoading,
                                modifier = Modifier.fillMaxWidth()) { Text("Cargar más envíos · ${state.paperItems.size} de ${state.paperTotal}") }
                        }
                    }
                    if (urgentSelection) {
                        items(urgentRows, key = { it.urgencyProductKey }) { row ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.md),
                                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                    Checkbox(checked = row.urgencyProductKey in urgentKeys, onCheckedChange = { selected ->
                                        urgentKeys = if (selected) urgentKeys + row.urgencyProductKey else urgentKeys - row.urgencyProductKey
                                    }, enabled = !state.sendingUrgent && viewModel.can(PreparationAction.URGENT))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                        Text(row.serviceCourse?.label ?: "Inmediato", style = MaterialTheme.typography.labelLarge)
                                        Text("${row.quantity} × ${row.productName}", style = MaterialTheme.typography.titleMedium)
                                        if (row.orderPromotionId != null) Text("Componente de combo o paquete", style = MaterialTheme.typography.bodySmall)
                                        PreparationBadges(row.preparation, row.stableKey in state.pendingKeys)
                                        row.unavailableReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                        if (urgentRows.isEmpty() && !state.loading) item {
                            Text("No hay productos pendientes de preparación que puedas enviar urgentes.")
                        }
                    } else items(state.items, key = { it.stableKey }) { row ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                            Text("#" + row.orderNumber + " · " + (row.serviceCourse?.label ?: "Inmediato"), style = MaterialTheme.typography.labelLarge)
                            Text(row.quantity.toString() + " × " + row.productName, style = MaterialTheme.typography.titleMedium)
                            PreparationBadges(row.preparation, row.stableKey in state.pendingKeys)
                            row.unavailableReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (row.stationCount > 1) Text("${row.stationCount} estaciones · Entregar exige que todas estén listas", style = MaterialTheme.typography.bodySmall)
                            if (state.items.count { it.urgencyProductKey == row.urgencyProductKey } < row.stationCount)
                                Text("Carga las demás estaciones del producto antes de liberarlo o entregarlo.", style = MaterialTheme.typography.bodySmall)
                            PreparationControls(row.productName, row.preparation,
                                listOf(PreparationAction.RELEASE, PreparationAction.DELIVER, PreparationAction.CANCEL, PreparationAction.REOPEN, PreparationAction.CLEAR_URGENT),
                                can = viewModel::can,
                                onAction = { action, quantity, from, reason -> viewModel.act(row, action, quantity, from, reason) },
                                limit = { action -> viewModel.limit(row, action) })
                        } }
                    }
                    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.items.isEmpty() && !state.loading) item { Text("No hay productos con seguimiento de preparación en esta cuenta.") }
                    if (state.hasMore) item { OutlinedButton(onClick = { viewModel.refresh(more = true) }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("Cargar más") } }
                    item { Spacer(Modifier.height(AvoqadoTheme.spacing.xxl)) }
                }
                if (urgentSelection) {
                    if (!viewModel.can(PreparationAction.URGENT)) Text("Necesitas acceso a tiempos de servicio y permiso para actualizar la cuenta.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
                }
            }
        }
    }
    retryPaper?.let { issue ->
        AlertDialog(onDismissRequest = { retryPaper = null }, title = { Text("Reimprimir liberación") },
            text = { Text("Reimprime sólo si falta el ticket y cocina todavía necesita preparar estos productos. Si ya se lo avisaste, usa «Ya avisé a cocina».") },
            confirmButton = { Button(onClick = { retryPaper = null; viewModel.resolvePaper(issue, true) }) { Text("Reimprimir") } },
            dismissButton = { TextButton(onClick = { retryPaper = null }) { Text("Volver") } })
    }
}
