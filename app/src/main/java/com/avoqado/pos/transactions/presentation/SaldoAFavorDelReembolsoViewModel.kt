package com.avoqado.pos.transactions.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.exchange.ExchangeStoreCreditCustomer
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.transactions.presentation.exchange.motivoAlGuardarTelefono
import com.avoqado.pos.transactions.presentation.exchange.pedidoDeTelefonoDelSaldo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * El saldo a favor de la hoja de Reembolso (spec 2026-10-30 §4, §7; A3): a quién se abona (la clienta de la venta o la que se escoja
 * con «Cambiar») y su teléfono obligatorio. Mismo comportamiento y textos que el Cambio (A2). El envío NO vive aquí: lo lleva
 * `TransactionsViewModel.enviarReembolsoASaldo`, que sobrevive a la hoja.
 */
@HiltViewModel
class SaldoAFavorDelReembolsoViewModel @Inject constructor(
    private val customers: CustomersRepository,
    settings: TpvSettingsRepository,
    private val role: RoleManager,
    private val monitor: ConnectivityMonitor,
) : ViewModel() {
    val storeCreditOnly = settings.storeCreditOnly
    val storeCreditAvailable = settings.storeCreditAvailable
    private val _cliente = MutableStateFlow<ExchangeStoreCreditCustomer?>(null)
    val cliente = _cliente.asStateFlow()
    private val _guardando = MutableStateFlow(false)
    val guardando = _guardando.asStateFlow()
    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje = _mensaje.asStateFlow()
    val puedeCrearCliente: Boolean get() = role.hasVenuePermission("customers:create")
    private var ficha: Customer? = null
    /** Cada apertura de la hoja cuenta: lo que llega tarde de otra apertura (un teléfono guardado) se tira (Codex apps r1, P1). */
    private var apertura = 0L
    private var altas = 0L
    private var altaVigente: Long? = null

    /** En CADA apertura de la hoja (como iOS): la clienta de la venta (llega sin teléfono: lo revisa el servidor), sin mensaje viejo. */
    fun abrir(sale: Transaction) {
        apertura++
        altaVigente = null
        _guardando.value = false
        ficha = null
        _mensaje.value = null
        _cliente.value = sale.customerId?.let { ExchangeStoreCreditCustomer(it, sale.customerName.orEmpty()) }
    }

    fun escoger(customer: Customer) {
        if (_guardando.value) return
        altaVigente = null
        ficha = customer
        _mensaje.value = null
        _cliente.value = ExchangeStoreCreditCustomer(customer.id, customer.fullName, customer.phone?.takeIf { it.isNotBlank() }, phoneChecked = true)
    }

    /**
     * El alta de clienta pedida desde «Cambiar» (Codex apps r2, P1): sólo la VIGENTE escoge a la destinataria. Si el cajero cerró el
     * formulario, escogió a otra de la lista o reabrió la hoja, el alta que llega tarde deja a la clienta creada pero no la liga.
     * Espejo de `aperturaDelAlta` de `CustomerPickerView` en iOS.
     */
    fun empezarAlta(): Long { altaVigente = ++altas; return altas }
    fun dejarAlta() { altaVigente = null }
    fun altaCreada(alta: Long, customer: Customer): Boolean {
        if (alta != altaVigente) return false
        escoger(customer)
        return true
    }

    /** El servidor pidió el teléfono de la clienta (`STORE_CREDIT_CUSTOMER_PHONE_REQUIRED`): ahora se pide aquí antes de volver a mandar. */
    fun pedirTelefono() { _cliente.value = _cliente.value?.copy(phone = null, phoneChecked = true) }

    /** Igual que el Cambio: sin red no se guarda ni se encola; si falla, se queda lo capturado con el motivo. */
    fun guardarTelefono(phone: String) {
        val c = _cliente.value ?: return
        if (_guardando.value) return
        if (!monitor.isOnline()) { _mensaje.value = TextosCambio.SALDO_SIN_RED; return }
        val (original, request) = pedidoDeTelefonoDelSaldo(c, ficha, phone)
            ?: run { _mensaje.value = TextosCambio.SALDO_TELEFONO_INVALIDO; return }
        _guardando.value = true
        val miApertura = apertura
        viewModelScope.launch {
            try {
                val resultado = customers.updateCustomer(original, request)
                // La hoja se cerró y se abrió otra (otra destinataria): esta respuesta ya no es de nadie en pantalla.
                if (miApertura != apertura) return@launch
                resultado.fold(
                    onSuccess = { updated ->
                        ficha = updated
                        _cliente.value = c.copy(phone = updated.phone?.takeIf { it.isNotBlank() }, phoneChecked = true)
                        _mensaje.value = null
                    },
                    onFailure = { e -> _mensaje.value = motivoAlGuardarTelefono(e) },
                )
            } finally { if (miApertura == apertura) _guardando.value = false }
        }
    }

}
