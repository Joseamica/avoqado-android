package com.avoqado.pos.inventory.presentation.traslados

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.inventory.data.transfers.CreateTransferInput
import com.avoqado.pos.inventory.data.transfers.CreateTransferItemInput
import com.avoqado.pos.inventory.data.transfers.DispatchItemInput
import com.avoqado.pos.inventory.data.transfers.DispatchTransferBody
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferApi
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferDetail
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferListItem
import com.avoqado.pos.inventory.data.transfers.ReceiveItemInput
import com.avoqado.pos.inventory.data.transfers.ReceiveTransferBody
import com.avoqado.pos.inventory.data.transfers.TransferMode
import com.avoqado.pos.inventory.data.transfers.TransferPickerRawMaterial
import com.avoqado.pos.inventory.data.transfers.TransferStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.avoqado.pos.core.data.network.ServerErrorText

/** Sub-pantalla activa dentro de la sección Traslados. */
sealed interface TrasladosScreen {
    data object List : TrasladosScreen
    data class Detail(val transferId: String) : TrasladosScreen
    data object Create : TrasladosScreen
    data class Receive(val transferId: String) : TrasladosScreen
}

/** Renglón del form de crear solicitud. */
data class CreateLine(
    val sourceRawMaterialId: String? = null,
    val destinationRawMaterialId: String? = null,
    // String para que el campo sea CLEARABLE (regla de la casa): "" = vacío, nunca 0 forzado.
    val quantityText: String = "",
)

/** Renglón editable de la recepción (merma = recibir menos de lo despachado). */
data class ReceiveLine(
    val itemId: String,
    val materialName: String,
    val unit: String,
    val dispatched: Double,
    val quantityText: String,
)

@HiltViewModel
class InterVenueTransfersViewModel @Inject constructor(
    private val api: InterVenueTransferApi,
    private val secureStorage: SecureStorage,
    val roleManager: RoleManager,
) : ViewModel() {

    val currentVenueId: String get() = secureStorage.venueId.orEmpty()

    private val _screen = MutableStateFlow<TrasladosScreen>(TrasladosScreen.List)
    val screen: StateFlow<TrasladosScreen> = _screen.asStateFlow()

    private val _transfers = MutableStateFlow<List<InterVenueTransferListItem>>(emptyList())
    val transfers: StateFlow<List<InterVenueTransferListItem>> = _transfers.asStateFlow()

    private val _detail = MutableStateFlow<InterVenueTransferDetail?>(null)
    val detail: StateFlow<InterVenueTransferDetail?> = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Acción de mutación en vuelo (deshabilita botones — evita doble submit). */
    private val _isMutating = MutableStateFlow(false)
    val isMutating: StateFlow<Boolean> = _isMutating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _onlyActionRequired = MutableStateFlow(false)
    val onlyActionRequired: StateFlow<Boolean> = _onlyActionRequired.asStateFlow()

    // MARK: - Crear

    /** Venues candidatos como ORIGEN de la solicitud PULL: mis otros venues.
     *  Si StoredVenue ya trae organizationId (server futuro) filtra por org;
     *  hoy viene null y se listan todos — el server rechaza cross-org en español. */
    val counterpartVenues: List<StoredVenue>
        get() {
            val venues = secureStorage.venuesList.filter { it.id != currentVenueId }
            val myOrg = secureStorage.venuesList.firstOrNull { it.id == currentVenueId }?.organizationId
            return if (myOrg != null) venues.filter { it.organizationId == null || it.organizationId == myOrg } else venues
        }

    private val _createSourceVenueId = MutableStateFlow<String?>(null)
    val createSourceVenueId: StateFlow<String?> = _createSourceVenueId.asStateFlow()

    private val _createLines = MutableStateFlow(listOf(CreateLine()))
    val createLines: StateFlow<List<CreateLine>> = _createLines.asStateFlow()

    private val _sourceMaterials = MutableStateFlow<List<TransferPickerRawMaterial>>(emptyList())
    val sourceMaterials: StateFlow<List<TransferPickerRawMaterial>> = _sourceMaterials.asStateFlow()

    private val _destinationMaterials = MutableStateFlow<List<TransferPickerRawMaterial>>(emptyList())
    val destinationMaterials: StateFlow<List<TransferPickerRawMaterial>> = _destinationMaterials.asStateFlow()

    // MARK: - Recibir

    private val _receiveLines = MutableStateFlow<List<ReceiveLine>>(emptyList())
    val receiveLines: StateFlow<List<ReceiveLine>> = _receiveLines.asStateFlow()

    // MARK: - Navegación interna

    fun openList() {
        _screen.value = TrasladosScreen.List
        _detail.value = null
        refresh()
    }

    fun openDetail(transferId: String) {
        _screen.value = TrasladosScreen.Detail(transferId)
        loadDetail(transferId)
    }

    fun openCreate() {
        _createSourceVenueId.value = null
        _createLines.value = listOf(CreateLine())
        _sourceMaterials.value = emptyList()
        _screen.value = TrasladosScreen.Create
        loadDestinationMaterials()
    }

    fun openReceive(transferId: String) {
        val d = _detail.value ?: return
        _receiveLines.value = d.items.map { item ->
            val dispatched = item.quantityDispatched.toDoubleOrNull() ?: 0.0
            val received = item.quantityReceived.toDoubleOrNull() ?: 0.0
            val pending = (dispatched - received).coerceAtLeast(0.0)
            ReceiveLine(
                itemId = item.id,
                materialName = item.destinationRawMaterial.name,
                unit = item.unit ?: item.destinationRawMaterial.unit.orEmpty(),
                dispatched = pending,
                quantityText = trimNumber(pending),
            )
        }.filter { it.dispatched > 0.0 }
        _screen.value = TrasladosScreen.Receive(transferId)
    }

    // MARK: - Data

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            api.list()
                .onSuccess { page -> _transfers.value = page.items }
                .onFailure { _error.value = ServerErrorText.humanize(it.message) }
            _isLoading.value = false
        }
    }

    private fun loadDetail(transferId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            api.get(transferId)
                .onSuccess { _detail.value = it }
                .onFailure { _error.value = ServerErrorText.humanize(it.message) }
            _isLoading.value = false
        }
    }

    fun setOnlyActionRequired(value: Boolean) { _onlyActionRequired.value = value }

    /** "Requiere mi acción": hay algo que ESTE venue puede hacer ya. */
    fun requiresMyAction(t: InterVenueTransferListItem): Boolean {
        val isSource = t.sourceVenueId == currentVenueId
        val isDestination = t.destinationVenueId == currentVenueId
        return when (t.status) {
            TransferStatus.REQUESTED, TransferStatus.APPROVED -> isSource
            TransferStatus.IN_TRANSIT, TransferStatus.PARTIALLY_RECEIVED -> isDestination
            else -> false
        }
    }

    // MARK: - Acciones de detalle

    fun approve(id: String) = mutate("Traslado aprobado") { api.approve(id) }
    fun reject(id: String, reason: String) = mutate("Traslado rechazado") { api.reject(id, reason) }
    fun cancel(id: String, reason: String) = mutate("Traslado cancelado") { api.cancel(id, reason) }

    /** Despacho v1: manda TODO lo solicitado (short-ship se hace en dashboard). */
    fun dispatchAll(id: String) {
        val d = _detail.value ?: return
        val body = DispatchTransferBody(
            items = d.items.mapNotNull { item ->
                val qty = item.quantityRequested.toDoubleOrNull() ?: return@mapNotNull null
                DispatchItemInput(itemId = item.id, quantity = qty)
            },
        )
        if (body.items.isEmpty()) { _error.value = "El traslado no tiene renglones para despachar"; return }
        mutate("Traslado despachado") { api.dispatch(id, body) }
    }

    fun updateReceiveLine(itemId: String, quantityText: String) {
        _receiveLines.value = _receiveLines.value.map {
            if (it.itemId == itemId) it.copy(quantityText = quantityText) else it
        }
    }

    /** Valida y ejecuta la recepción. Merma = cantidad menor a lo despachado (el server
     *  la deriva y marca COMPLETED_WITH_VARIANCE / PARTIALLY_RECEIVED). */
    fun submitReceive(id: String): Boolean {
        val lines = _receiveLines.value
        val parsed = lines.map { line -> line to line.quantityText.trim().toDoubleOrNull() }
        val invalid = parsed.firstOrNull { (line, qty) -> qty == null || qty < 0.0 || qty > line.dispatched }
        if (invalid != null) {
            val (line, qty) = invalid
            _error.value = when {
                qty == null -> "Captura la cantidad recibida de ${line.materialName}"
                qty < 0.0 -> "La cantidad de ${line.materialName} no puede ser negativa"
                else -> "No puedes recibir más de lo despachado en ${line.materialName} (${trimNumber(line.dispatched)})"
            }
            return false
        }
        val items = parsed.filter { (_, qty) -> (qty ?: 0.0) > 0.0 }
            .map { (line, qty) -> ReceiveItemInput(itemId = line.itemId, quantity = qty!!) }
        if (items.isEmpty()) { _error.value = "Captura al menos una cantidad recibida"; return false }
        mutate("Recepción registrada") { api.receive(id, ReceiveTransferBody(items = items)) }
        return true
    }

    // MARK: - Crear

    fun selectSourceVenue(venueId: String) {
        _createSourceVenueId.value = venueId
        _sourceMaterials.value = emptyList()
        viewModelScope.launch {
            api.rawMaterials(venueId)
                .onSuccess { _sourceMaterials.value = it }
                .onFailure { _error.value = ServerErrorText.humanize(it.message) }
        }
    }

    private fun loadDestinationMaterials() {
        viewModelScope.launch {
            api.rawMaterials(currentVenueId)
                .onSuccess { _destinationMaterials.value = it }
                .onFailure { _error.value = ServerErrorText.humanize(it.message) }
        }
    }

    fun updateCreateLine(index: Int, transform: (CreateLine) -> CreateLine) {
        _createLines.value = _createLines.value.mapIndexed { i, line -> if (i == index) transform(line) else line }
    }

    fun addCreateLine() { _createLines.value = _createLines.value + CreateLine() }

    fun removeCreateLine(index: Int) {
        if (_createLines.value.size <= 1) return
        _createLines.value = _createLines.value.filterIndexed { i, _ -> i != index }
    }

    fun submitCreate(): Boolean {
        val sourceVenueId = _createSourceVenueId.value
        if (sourceVenueId == null) { _error.value = "Selecciona la sucursal de origen"; return false }
        val items = mutableListOf<CreateTransferItemInput>()
        for ((i, line) in _createLines.value.withIndex()) {
            val qty = line.quantityText.trim().toDoubleOrNull()
            when {
                line.sourceRawMaterialId == null -> { _error.value = "Renglón ${i + 1}: selecciona el insumo de origen"; return false }
                line.destinationRawMaterialId == null -> { _error.value = "Renglón ${i + 1}: selecciona el insumo de destino"; return false }
                qty == null || qty <= 0.0 -> { _error.value = "Renglón ${i + 1}: la cantidad debe ser mayor que cero"; return false }
                else -> items += CreateTransferItemInput(
                    sourceRawMaterialId = line.sourceRawMaterialId,
                    destinationRawMaterialId = line.destinationRawMaterialId,
                    quantity = qty,
                )
            }
        }
        val input = CreateTransferInput(
            mode = TransferMode.PULL,
            sourceVenueId = sourceVenueId,
            destinationVenueId = currentVenueId,
            items = items,
        )
        mutate("Solicitud enviada") { api.create(input) }
        return true
    }

    // MARK: - Helpers

    private fun mutate(successMessage: String, block: suspend () -> Result<InterVenueTransferDetail>) {
        if (_isMutating.value) return
        viewModelScope.launch {
            _isMutating.value = true
            block()
                .onSuccess { updated ->
                    _detail.value = updated
                    _successMessage.value = successMessage
                    _screen.value = TrasladosScreen.Detail(updated.id)
                    refresh()
                }
                .onFailure { _error.value = ServerErrorText.humanize(it.message) }
            _isMutating.value = false
        }
    }

    fun consumeError() { _error.value = null }
    fun consumeSuccess() { _successMessage.value = null }

    companion object {
        fun trimNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
