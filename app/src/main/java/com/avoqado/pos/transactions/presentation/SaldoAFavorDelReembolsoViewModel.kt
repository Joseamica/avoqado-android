package com.avoqado.pos.transactions.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import com.avoqado.pos.transactions.data.DesenlaceDeSaldo
import com.avoqado.pos.transactions.data.RefundPendingStore
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.SaldoAFavorEnvio
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
 * con «Cambiar»), su teléfono obligatorio, y el envío que se guarda ANTES del POST. Mismo comportamiento y textos que el Cambio (A2).
 */
@HiltViewModel
class SaldoAFavorDelReembolsoViewModel @Inject constructor(
    refunds: RefundRepository,
    store: RefundPendingStore,
    private val customers: CustomersRepository,
    settings: TpvSettingsRepository,
    private val role: RoleManager,
    private val monitor: ConnectivityMonitor,
    private val secure: SecureStorage,
) : ViewModel() {
    private val envio = SaldoAFavorEnvio(refunds, store)
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
    private var venta: String? = null

    /** Por default, la clienta de la venta (llega sin teléfono: lo revisa el servidor). */
    fun abrir(sale: Transaction) {
        if (venta == sale.id) return
        venta = sale.id
        ficha = null
        _mensaje.value = null
        _cliente.value = sale.customerId?.let { ExchangeStoreCreditCustomer(it, sale.customerName.orEmpty()) }
    }

    fun escoger(customer: Customer) {
        if (_guardando.value) return
        ficha = customer
        _mensaje.value = null
        _cliente.value = ExchangeStoreCreditCustomer(customer.id, customer.fullName, customer.phone?.takeIf { it.isNotBlank() }, phoneChecked = true)
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
        viewModelScope.launch {
            try {
                customers.updateCustomer(original, request).fold(
                    onSuccess = { updated ->
                        ficha = updated
                        _cliente.value = c.copy(phone = updated.phone?.takeIf { it.isNotBlank() }, phoneChecked = true)
                        _mensaje.value = null
                    },
                    onFailure = { e -> _mensaje.value = motivoAlGuardarTelefono(e) },
                )
            } finally { _guardando.value = false }
        }
    }

    /** Guarda en el aparato y manda. Sin sucursal en sesión no se guarda ni se manda. */
    suspend fun enviar(paymentId: String, body: String, idempotencyKey: String): DesenlaceDeSaldo {
        val venue = secure.venueId ?: return DesenlaceDeSaldo.GuardadoFallido
        return envio.enviar(venue, paymentId, body, idempotencyKey)
    }
}
