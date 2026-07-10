package com.avoqado.pos.cashdrawer.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "💰 CashDrawerVM"

enum class CashDrawerSection(val label: String) {
    CURRENT("Caja en curso"),
    HISTORY("Historial"),
}

@HiltViewModel
class CashDrawerViewModel @Inject constructor(
    private val repository: CashDrawerRepository,
) : ViewModel() {

    // MARK: - State

    private val _currentSession = MutableStateFlow<CashDrawerSessionEntity?>(null)
    val currentSession: StateFlow<CashDrawerSessionEntity?> = _currentSession.asStateFlow()

    private val _events = MutableStateFlow<List<CashDrawerEventEntity>>(emptyList())
    val events: StateFlow<List<CashDrawerEventEntity>> = _events.asStateFlow()

    private val _expectedAmountCents = MutableStateFlow(0)
    val expectedAmountCents: StateFlow<Int> = _expectedAmountCents.asStateFlow()

    private val _closedSessions = MutableStateFlow<List<CashDrawerSessionEntity>>(emptyList())
    val closedSessions: StateFlow<List<CashDrawerSessionEntity>> = _closedSessions.asStateFlow()

    private val _selectedSection = MutableStateFlow(CashDrawerSection.CURRENT)
    val selectedSection: StateFlow<CashDrawerSection> = _selectedSection.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // MARK: - Init

    init {
        syncAndLoad()
    }

    private fun syncAndLoad() {
        viewModelScope.launch {
            try {
                repository.syncFromApi()
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ API sync failed, using local data: ${e.message}")
            }
            loadCurrentSession()
        }
    }

    // MARK: - Navigation

    fun selectSection(section: CashDrawerSection) {
        _selectedSection.value = section
        when (section) {
            CashDrawerSection.CURRENT -> loadCurrentSession()
            CashDrawerSection.HISTORY -> loadHistory()
        }
    }

    // MARK: - Load Data

    fun loadCurrentSession() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val session = repository.getOpenSession()
                _currentSession.value = session
                if (session != null) {
                    _events.value = repository.getEvents(session.id)
                    _expectedAmountCents.value = repository.computeExpectedAmount(
                        session.id,
                        session.startingAmountCents,
                    )
                } else {
                    _events.value = emptyList()
                    _expectedAmountCents.value = 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading session: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Sync from API first, then read from Room
                repository.syncFromApi()
                _closedSessions.value = repository.getHistory()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading history: ${e.message}")
                // Fall back to local data
                try {
                    _closedSessions.value = repository.getHistory()
                } catch (_: Exception) { /* ignore */ }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /// Drawer-op failures were Log.e-only — the VM had NO error channel at
    /// all, so a failed pay-out/close looked identical to success.
    val errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // MARK: - Actions

    fun openSession(startingAmountCents: Int) {
        viewModelScope.launch {
            try {
                val session = repository.openSession(startingAmountCents)
                _currentSession.value = session
                _events.value = repository.getEvents(session.id)
                _expectedAmountCents.value = startingAmountCents
                Log.d(TAG, "✅ Session opened")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error opening session: ${e.message}")
                errorMessage.value = "No se pudo abrir la caja. Intenta de nuevo."
            }
        }
    }

    fun addPayIn(amountCents: Int, note: String?) {
        viewModelScope.launch {
            try {
                repository.addPayIn(amountCents, note)
                loadCurrentSession()
                Log.d(TAG, "✅ Pay-in added: $amountCents")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding pay-in: ${e.message}")
                errorMessage.value = "No se pudo registrar la entrada de efectivo."
            }
        }
    }

    fun addPayOut(amountCents: Int, note: String?) {
        viewModelScope.launch {
            try {
                repository.addPayOut(amountCents, note)
                loadCurrentSession()
                Log.d(TAG, "✅ Pay-out added: $amountCents")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding pay-out: ${e.message}")
                errorMessage.value = "No se pudo registrar la salida de efectivo."
            }
        }
    }

    /// Returns true only when the close actually succeeded — callers must gate
    /// the daily report on this (before, the report was FABRICATED client-side
    /// and shown even when the close failed).
    suspend fun closeSession(actualAmountCents: Int, note: String?): Boolean {
        return try {
                repository.closeSession(actualAmountCents, note)
                _currentSession.value = null
                _events.value = emptyList()
                _expectedAmountCents.value = 0
                Log.d(TAG, "✅ Session closed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing session: ${e.message}")
            errorMessage.value = "No se pudo cerrar la caja. Intenta de nuevo."
            false
        }
    }

    // MARK: - Load events for a specific session (used by history -> report)

    fun loadEventsForSession(sessionId: String, onResult: (List<CashDrawerEventEntity>) -> Unit) {
        viewModelScope.launch {
            try {
                val events = repository.getEvents(sessionId)
                onResult(events)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading events for session $sessionId: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    // MARK: - Helpers

    fun cashSalesTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.CASH_SALE.name }
            .sumOf { it.amountCents }
    }

    fun payInsTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.PAY_IN.name }
            .sumOf { it.amountCents }
    }

    fun payOutsTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.PAY_OUT.name }
            .sumOf { it.amountCents }
    }
}
