package com.avoqado.pos.reservations.presentation.waitlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.reservations.data.WaitlistRepository
import com.avoqado.pos.reservations.data.model.AddToWaitlistRequest
import com.avoqado.pos.reservations.data.model.WaitlistEntry
import com.avoqado.pos.reservations.data.model.WaitlistStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class WaitlistUiState(
    val entries: List<WaitlistEntry> = emptyList(),
    val isLoading: Boolean = false,
    val statusFilter: WaitlistStatus? = WaitlistStatus.WAITING,
    val error: String? = null,
)

@HiltViewModel
class WaitlistViewModel @Inject constructor(
    private val repository: WaitlistRepository,
    private val customersRepository: CustomersRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _state = MutableStateFlow(WaitlistUiState())
    val state: StateFlow<WaitlistUiState> = _state.asStateFlow()

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            customersRepository.fetchCustomers().onSuccess { _customers.value = it }
        }
        // Reload whenever a waitlist mutation happens elsewhere — e.g. a promote that finishes
        // through the create-reservation flow — so the row leaves the "Esperando" filter
        // without requiring a manual filter toggle.
        viewModelScope.launch {
            repository.changes.collect { load() }
        }
    }

    fun setFilter(status: WaitlistStatus?) {
        _state.update { it.copy(statusFilter = status) }
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = repository.fetchEntries(_state.value.statusFilter)
            _state.update { ui ->
                if (r.isSuccess) {
                    ui.copy(isLoading = false, entries = r.getOrNull().orEmpty(), error = null)
                } else {
                    ui.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando lista")
                }
            }
        }
    }

    fun addEntry(request: AddToWaitlistRequest, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            repository.addEntry(request)
                .onSuccess {
                    load()
                    onResult(true, null)
                }
                .onFailure { onResult(false, it.message) }
        }
    }

    fun removeEntry(id: String) {
        viewModelScope.launch {
            repository.removeEntry(id).onSuccess { load() }
        }
    }
}
