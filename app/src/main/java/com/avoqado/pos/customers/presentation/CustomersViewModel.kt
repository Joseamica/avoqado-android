package com.avoqado.pos.customers.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.domain.avisoDeMasResultados
import com.avoqado.pos.customers.domain.filtrarClientesLocal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repository: CustomersRepository,
    private val roleManager: com.avoqado.pos.core.domain.RoleManager,
) : ViewModel() {

    /// Role gate for money-adjacent customer actions (create, sell/redeem
    /// prepaid credits). WAITER can VIEW customers but must not manage them.
    val canManageCustomers: Boolean get() = roleManager.canManageCustomers

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    /** Lo que se pinta: la respuesta del servidor para [query] o —sin red— el filtro local sobre lo guardado. */
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _query = MutableStateFlow("")
    /** El texto vigente. Sólo la respuesta de ESTE texto puede pintar la lista. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _offline = MutableStateFlow(false)
    /** true = la última búsqueda no llegó al servidor por falta de red: se busca entre lo guardado y se avisa. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _avisoDeMasResultados = MutableStateFlow<String?>(null)
    /**
     * «2 de 120: escribe más para afinar» cuando el servidor tiene más clientes con ese texto de los que se ven
     * (auditoría A #10; con el total EXACTO aunque el servidor tope en 5000, B3). null = nada que decir; sin red tampoco
     * (no se conoce el total).
     */
    val avisoDeMasResultados: StateFlow<String?> = _avisoDeMasResultados.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var busqueda: Job? = null

    /** Al abrir la lista: texto vacío y los recientes («Creados recientemente»), sin esperar. */
    fun abrirLista() = onQueryChange("")

    /**
     * Cada tecla del buscador. Pregunta al servidor [DEBOUNCE_BUSQUEDA_MS] después de la ÚLTIMA tecla (el texto vacío,
     * de inmediato). Filtrar aquí sólo lo que llegó es justo por qué «Dany no aparecía»: busca el servidor.
     */
    fun onQueryChange(texto: String) {
        _query.value = texto
        lanzarBusqueda(texto, esperar = texto.isNotBlank())
    }

    /** «Actualizar»: vuelve a pedir el texto vigente, sin esperar. */
    fun fetchCustomers() = lanzarBusqueda(_query.value, esperar = false)

    private fun lanzarBusqueda(texto: String, esperar: Boolean) {
        busqueda?.cancel()
        _isLoading.value = true
        busqueda = viewModelScope.launch {
            if (esperar) delay(DEBOUNCE_BUSQUEDA_MS)
            val result = repository.buscarClientes(search = texto.trim().ifEmpty { null })
            // 🔴 Una respuesta VIEJA no pinta. Si el cajero siguió tecleando, esta corrutina ya se canceló, pero la
            // llamada HTTP no se interrumpe y regresa igual: se revisa aquí, antes de tocar la lista. «Dan» llegando
            // después de «Dany» le enseñaría al cajero a otra gente.
            currentCoroutineContext().ensureActive()
            if (texto != _query.value) return@launch
            result.fold(
                onSuccess = { pagina ->
                    _offline.value = false
                    _customers.value = pagina.clientes
                    _avisoDeMasResultados.value =
                        avisoDeMasResultados(texto, pagina.clientes.size, pagina.total, pagina.busquedaTopada)
                },
                onFailure = { e ->
                    _avisoDeMasResultados.value = null
                    // Sin red es un ESTADO (regla todo-funciona-sin-red): se busca entre lo guardado y se dice. Otro
                    // fallo también cae a lo guardado —la caja no se queda sin lista—, pero a una respuesta del
                    // servidor no se le llama «sin conexión» (mismo criterio que `ServerErrorText.isOffline`, espejo de iOS).
                    val sinRed = ServerErrorText.isOffline(e)
                    _offline.value = sinRed
                    _customers.value = filtrarClientesLocal(repository.clientesGuardados(), texto)
                    if (!sinRed) {
                        Log.e("👤", "Error: ${e.message}")
                        _error.value = ServerErrorText.humanize(
                            e,
                            fallback = "No se pudieron cargar los clientes. Inténtalo de nuevo.",
                        )
                    }
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
                    // Arriba de la lista y sin repetir: `LazyColumn(key = id)` truena con dos llaves iguales.
                    _customers.value = listOf(customer) + _customers.value.filterNot { it.id == customer.id }
                    onSuccess(customer)
                },
                onFailure = {
                    _error.value = ServerErrorText.humanize(it.message)
                },
            )
            _isSaving.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        /** Espera tras la última tecla antes de preguntar al servidor. */
        internal const val DEBOUNCE_BUSQUEDA_MS = 300L
    }
}
