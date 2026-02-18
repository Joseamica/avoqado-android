package com.avoqado.pos.customers.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.Customer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repository: CustomersRepository,
) : ViewModel() {

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun fetchCustomers() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.fetchCustomers()
            result.fold(
                onSuccess = { _customers.value = it },
                onFailure = {
                    Log.e("👤", "Error: ${it.message}")
                    _error.value = "Error al cargar clientes"
                },
            )
            _isLoading.value = false
        }
    }

    fun createCustomer(
        firstName: String?,
        lastName: String?,
        phone: String?,
        email: String?,
        onSuccess: (Customer) -> Unit,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val request = CreateCustomerRequest(
                firstName = firstName,
                lastName = lastName,
                phone = phone,
                email = email,
            )
            val result = repository.createCustomer(request)
            result.fold(
                onSuccess = { customer ->
                    // Add to list and notify
                    _customers.value = listOf(customer) + _customers.value
                    onSuccess(customer)
                },
                onFailure = {
                    _error.value = it.message
                },
            )
            _isSaving.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}
