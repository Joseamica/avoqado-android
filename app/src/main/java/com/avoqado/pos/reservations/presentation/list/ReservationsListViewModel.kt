package com.avoqado.pos.reservations.presentation.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.domain.ReservationAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReservationsListViewModel @Inject constructor(
    private val repository: ReservationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReservationsListUiState(isLoading = true))
    val state: StateFlow<ReservationsListUiState> = _state.asStateFlow()

    init {
        refresh()
        // Refetch whenever a reservation mutation happens elsewhere so the list stays in sync.
        viewModelScope.launch {
            repository.changes.collect { refresh() }
        }
    }

    fun setTab(tab: ReservationListTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    fun setSearch(query: String) {
        _state.update { it.copy(search = query) }
        refresh()
    }

    fun refresh() {
        val s = _state.value
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val filters = ReservationFilters(
                page = 1,
                pageSize = 100,
                statuses = s.tab.statusFilter,
                search = s.search.takeIf { it.isNotBlank() },
                channel = s.channelFilter,
            )
            val r = repository.fetchList(filters)
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, items = r.getOrNull()?.data.orEmpty(), error = null)
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando reservas")
            }
        }
    }

    fun runTransition(id: String, action: ReservationAction, payload: ReservationRepository.ActionPayload? = null) {
        _state.update { it.copy(pendingTransitionIds = it.pendingTransitionIds + id, queuedMessage = null) }
        viewModelScope.launch {
            val r = repository.runAction(id, action, payload)
            _state.update { current ->
                val pendingCleared = current.pendingTransitionIds - id
                if (r.isSuccess) {
                    val updated = r.getOrNull()
                    val filtered = if (updated != null && (updated.status in current.tab.statusFilter || current.tab.statusFilter.isEmpty())) {
                        current.items.map { if (it.id == id && updated != null) updated else it }
                    } else {
                        current.items.filter { it.id != id }
                    }
                    current.copy(items = filtered, pendingTransitionIds = pendingCleared, error = null)
                } else {
                    val queued = r.exceptionOrNull() as? ReservationRepository.OfflineEnqueuedException
                    if (queued != null) {
                        // Sin red la acción SÍ quedó guardada: se avisa, no se acusa.
                        current.copy(pendingTransitionIds = pendingCleared, queuedMessage = queued.message)
                    } else {
                        current.copy(pendingTransitionIds = pendingCleared, error = r.exceptionOrNull()?.message ?: "Error")
                    }
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeQueuedMessage() = _state.update { it.copy(queuedMessage = null) }
}
