package com.avoqado.pos.customers.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.RechazoAlEditarCliente
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.domain.AVISO_SERVIDOR_FALLO_CLIENTES
import com.avoqado.pos.customers.domain.AVISO_FALTA_PIN_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.AVISO_SIN_PERMISO_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.AVISO_SIN_RED_CLIENTES
import com.avoqado.pos.customers.domain.AVISO_SIN_RED_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.AccesoAEditarCliente
import com.avoqado.pos.customers.domain.ContactoEditado
import com.avoqado.pos.customers.domain.PERMISO_EDITAR_CLIENTES
import com.avoqado.pos.customers.domain.accesoAEditarCliente
import com.avoqado.pos.customers.domain.cambiosDeContacto
import com.avoqado.pos.customers.domain.erroresDeContacto
import com.avoqado.pos.customers.domain.avisoDeMasResultados
import com.avoqado.pos.customers.domain.filtrarClientesLocal
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
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
    /** Para pedir el PIN de un gerente AL TOCAR «Editar», como «Emitir reembolso». */
    private val managerOverrideCoordinator: ManagerOverrideCoordinator,
    /** Sólo para saber si el negocio prendió el PIN de gerente: apagado, el teclado no serviría. */
    private val tpvSettingsRepository: TpvSettingsRepository,
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

    private val _avisoDeGuardados = MutableStateFlow<String?>(null)
    /**
     * El aviso ámbar cuando la lista sale de lo guardado en la tablet y no del servidor: sin red
     * ([AVISO_SIN_RED_CLIENTES]) o porque el servidor contestó con un error ([AVISO_SERVIDOR_FALLO_CLIENTES]).
     * null = la lista es la del servidor. Nunca en silencio: sin aviso, «No se encontraron» + «Crear cliente» mentía.
     */
    val avisoDeGuardados: StateFlow<String?> = _avisoDeGuardados.asStateFlow()

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

    /**
     * Al abrir la lista: texto vacío y los recientes («Creados recientemente»), sin esperar. Antes se borra lo de la
     * apertura anterior: el ViewModel vive con la pantalla de navegación, así que sus resultados —quizá de OTRA
     * sucursal— se verían bajo «Creados recientemente» hasta que llegaran los recientes. Con la lista vacía y la carga en
     * vuelo se ve el cargador. Tampoco se arrastra un error viejo al diálogo de crear cliente, ni el aviso ámbar de la
     * vez anterior: la red pudo volver, y «Sin conexión» se dice cuando ESTA búsqueda falle (revisión final, Minor #6).
     */
    fun abrirLista() {
        _customers.value = emptyList()
        _avisoDeMasResultados.value = null
        _avisoDeGuardados.value = null
        _offline.value = false
        _error.value = null
        onQueryChange("")
    }

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
        // Revisión final #1: en WiFi malo el servidor tarda (hasta el plazo del cliente compartido, 30 s) y la lista del
        // texto ANTERIOR se quedaba a la vista. Al instante se ve lo guardado en la tablet que cumple el texto NUEVO (el
        // mismo filtro de sin red) y la barra de carga dice que viene la respuesta. Si no hay nadie, lista vacía + carga =
        // el cargador, nunca «No se encontraron» + «Crear cliente» (duplicado). Sin texto no: «todo lo guardado» no son
        // los recientes y al reabrir se ve el cargador (revisión A2, P2).
        if (texto.isNotBlank()) {
            _customers.value = filtrarClientesLocal(repository.clientesGuardados(), texto)
            _avisoDeMasResultados.value = null
        }
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
                    _avisoDeGuardados.value = null
                    _error.value = null
                    _customers.value = pagina.clientes
                    _avisoDeMasResultados.value =
                        avisoDeMasResultados(texto, pagina.clientes.size, pagina.total, pagina.busquedaTopada)
                },
                onFailure = { e ->
                    _avisoDeMasResultados.value = null
                    // Sin red es un ESTADO (regla todo-funciona-sin-red): se busca entre lo guardado y se dice. Un error
                    // del servidor (5xx, 401, sin sucursal) también cae a lo guardado —la caja no se queda sin lista— y
                    // también se dice, con su propio texto: a una respuesta del servidor no se le llama «sin conexión»
                    // (mismo criterio que `ServerErrorText.isOffline`, espejo de iOS).
                    // 🔴 Nunca a `_error`: ese lo pinta `CreateCustomerView` (mismo ViewModel) como «No se pudo crear el
                    // cliente», y un fallo de BÚSQUEDA abría ese diálogo falso al tocar «Crear».
                    val sinRed = ServerErrorText.isOffline(e)
                    if (!sinRed) Log.e("👤", "Error buscando clientes: ${e.message}")
                    _offline.value = sinRed
                    _avisoDeGuardados.value = if (sinRed) AVISO_SIN_RED_CLIENTES else AVISO_SERVIDOR_FALLO_CLIENTES
                    _customers.value = filtrarClientesLocal(repository.clientesGuardados(), texto)
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

    // MARK: - Editar cliente (26-oct-2026)

    private val _clienteEnEdicion = MutableStateFlow<Customer?>(null)
    /** El cliente cuyo formulario «Editar cliente» está abierto; null = cerrado. */
    val clienteEnEdicion: StateFlow<Customer?> = _clienteEnEdicion.asStateFlow()

    private val _avisoSinPermiso = MutableStateFlow<String?>(null)
    /** Quien no puede editar y el PIN de gerente está apagado: por qué no y qué hacer. */
    val avisoSinPermiso: StateFlow<String?> = _avisoSinPermiso.asStateFlow()

    private val _errorDeEdicion = MutableStateFlow<String?>(null)
    /** Lo que impidió guardar (sin red o rechazo del servidor). Se pinta DENTRO del formulario, que sigue abierto. */
    val errorDeEdicion: StateFlow<String?> = _errorDeEdicion.asStateFlow()

    /**
     * Tocaron «Editar» (siempre visible, founder 26-oct). Quien tiene `customers:update` entra directo; quien no, teclea
     * el PIN de un gerente ANTES de llenar nada; con el PIN apagado se le explica, sin teclado que el servidor
     * rechazaría. Si el permiso cambió en el servidor sin que la app lo sepa, el 403 de guardar abre el teclado solo
     * (`ForbiddenInterceptor`).
     */
    fun onEditarTocado(cliente: Customer) {
        // Dos toques antes de que salga el teclado no piden dos PIN (Codex r2 #3).
        if (pidiendoPin || _clienteEnEdicion.value != null) return
        _errorDeEdicion.value = null
        when (accesoAEditarCliente(roleManager.canEditCustomers, tpvSettingsRepository.managerPinOverrideEnabled.value)) {
            AccesoAEditarCliente.DIRECTO -> abrirEdicion(cliente, conPin = false)
            AccesoAEditarCliente.CON_PIN -> {
                pidiendoPin = true
                viewModelScope.launch {
                    try {
                        if (managerOverrideCoordinator.preauthorize(PERMISO_EDITAR_CLIENTES)) abrirEdicion(cliente, conPin = true)
                        // Canceló: no se abre nada. El teclado ya le dijo por qué.
                    } finally {
                        pidiendoPin = false
                    }
                }
            }
            AccesoAEditarCliente.SIN_PERMISO -> _avisoSinPermiso.value = AVISO_SIN_PERMISO_EDITAR_CLIENTE
        }
    }

    /** Hay un teclado de PIN pedido por «Editar» que todavía no se contesta. */
    private var pidiendoPin = false

    /**
     * Cada apertura del formulario lleva su número: una respuesta que llega TARDE (el cajero cerró y abrió a otro
     * cliente) no cierra ni ensucia el formulario nuevo (Codex r2 #2).
     */
    private var edicionVigente = 0L

    /** Esta edición entró con PIN de gerente (decide si un 403 se reintenta para que el teclado vuelva a salir). */
    private var edicionConPin = false

    private fun abrirEdicion(cliente: Customer, conPin: Boolean) {
        edicionVigente++
        edicionConPin = conPin
        _clienteEnEdicion.value = cliente
    }

    fun cerrarAvisoSinPermiso() {
        _avisoSinPermiso.value = null
    }

    fun cerrarEdicion() {
        edicionVigente++
        _clienteEnEdicion.value = null
        _errorDeEdicion.value = null
        _isSaving.value = false
        // El PIN pedido para ESTE cliente no autoriza al siguiente (Codex r2 #1).
        managerOverrideCoordinator.descartarPreautorizado(PERMISO_EDITAR_CLIENTES)
    }

    /**
     * «Guardar». Sólo viaja lo que cambió; con un dato vaciado o sin cambios no se llama al servidor (el botón ya está
     * apagado, esto es la red de seguridad). Online-only: sin red el formulario sigue abierto con lo escrito.
     */
    fun guardarEdicion(editado: ContactoEditado, onGuardado: (Customer) -> Unit) {
        val original = _clienteEnEdicion.value ?: return
        if (_isSaving.value) return
        if (erroresDeContacto(original, editado).isNotEmpty()) return
        val cambios = cambiosDeContacto(original, editado) ?: return
        val estaEdicion = edicionVigente
        val conPin = edicionConPin
        viewModelScope.launch {
            _isSaving.value = true
            _errorDeEdicion.value = null
            val resultado = repository.updateCustomer(original, cambios)
            resultado.onSuccess { actualizado ->
                // El cambio YA está en el servidor: la lista lo refleja aunque el formulario ya sea otro, y se vuelve a
                // pedir para que una búsqueda que salió ANTES de guardar no regrese el dato viejo (Codex r3 #3).
                _customers.value = _customers.value.map { if (it.id == actualizado.id) actualizado else it }
                fetchCustomers()
            }
            if (estaEdicion != edicionVigente) return@launch
            resultado.fold(
                onSuccess = { actualizado ->
                    _clienteEnEdicion.value = null
                    onGuardado(actualizado)
                },
                onFailure = { e ->
                    // Cualquier corte de conexión (también un EOF a media respuesta) es «Sin conexión», nunca texto
                    // técnico en rojo (Codex r2 #6). Una respuesta del servidor trae su propio texto.
                    _errorDeEdicion.value = if (e !is RechazoAlEditarCliente && (e is java.io.IOException || ServerErrorText.isOffline(e))) {
                        AVISO_SIN_RED_EDITAR_CLIENTE
                    } else if (conPin && (e as? RechazoAlEditarCliente)?.codigo == 403) {
                        AVISO_FALTA_PIN_EDITAR_CLIENTE
                    } else {
                        e.message ?: "No se pudo guardar el cliente. Intenta de nuevo."
                    }
                },
            )
            _isSaving.value = false
        }
    }

    companion object {
        /** Espera tras la última tecla antes de preguntar al servidor. */
        internal const val DEBOUNCE_BUSQUEDA_MS = 300L
    }
}
