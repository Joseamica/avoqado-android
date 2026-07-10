package com.avoqado.pos.estimates.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.estimates.data.EstimatesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

private const val TAG = "📋 EstimatesVM"

@HiltViewModel
class EstimatesViewModel @Inject constructor(
    private val repository: EstimatesRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    // MARK: - Expose repository flows

    val estimates = repository.estimates
    val isLoading = repository.isLoading
    val errorMessage = repository.errorMessage

    // MARK: - Local state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // MARK: - Inner data classes

    data class EstimateItemInput(
        val productName: String,
        val description: String? = null,
        val quantity: Int = 1,
        val unitPrice: Double = 0.0,
        val taxRate: Double = 0.16,
    )

    // MARK: - Init

    init {
        viewModelScope.launch {
            repository.fetchEstimates()
        }
    }

    // MARK: - Actions

    fun refresh() {
        viewModelScope.launch {
            repository.fetchEstimates()
        }
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    // MARK: - Create

    fun createEstimate(
        customerName: String? = null,
        customerEmail: String? = null,
        customerPhone: String? = null,
        notes: String? = null,
        validUntil: String? = null,
        items: List<EstimateItemInput>,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val staffName = listOfNotNull(
                    secureStorage.userFirstName,
                    secureStorage.userLastName,
                ).joinToString(" ").ifBlank {
                    secureStorage.userEmail ?: "Staff"
                }

                val payload = buildJsonObject {
                    put("staffName", staffName)
                    if (customerName != null) put("customerName", customerName)
                    if (customerEmail != null) put("customerEmail", customerEmail)
                    if (customerPhone != null) put("customerPhone", customerPhone)
                    if (notes != null) put("notes", notes)
                    if (validUntil != null) put("validUntil", validUntil)
                    putJsonArray("items") {
                        items.forEach { item ->
                            add(buildJsonObject {
                                put("productName", item.productName)
                                if (item.description != null) put("description", item.description)
                                put("quantity", item.quantity)
                                put("unitPrice", item.unitPrice)
                                put("taxRate", item.taxRate)
                            })
                        }
                    }
                }.toString()
                val success = repository.createEstimate(payload)
                if (success) {
                    Log.d(TAG, "✅ Estimate created")
                    onSuccess()
                } else {
                    Log.e(TAG, "❌ Estimate creation failed")
                    onError("No se pudo crear el presupuesto. Intenta de nuevo.")
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Delete

    fun deleteEstimate(estimateId: String) {
        viewModelScope.launch {
            repository.deleteEstimate(estimateId)
        }
    }
}
