package com.avoqado.pos.customers.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.data.model.CreditPurchaseBalance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Staff-facing prepaid credit-pack ("membresías") operations for a customer:
 * balance, sell in person, redeem. Mirrors iOS CreditPacksRepository usage.
 */
@HiltViewModel
class CustomerCreditsViewModel @Inject constructor(
    private val articlesRepository: ArticlesRepository,
    private val pendingGrantQueue: com.avoqado.pos.customers.data.PendingGrantQueue,
    private val roleManager: com.avoqado.pos.core.domain.RoleManager,
) : ViewModel() {

    /// Role gate for selling/redeeming paid credit packs (WAITER can view a
    /// customer but must not move their money).
    val canManageCustomers: Boolean get() = roleManager.canManageCustomers

    private val _balances = MutableStateFlow<List<CreditPurchaseBalance>>(emptyList())
    val balances: StateFlow<List<CreditPurchaseBalance>> = _balances.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _busyBalanceId = MutableStateFlow<String?>(null)
    val busyBalanceId: StateFlow<String?> = _busyBalanceId.asStateFlow()

    private val _sellingPackId = MutableStateFlow<String?>(null)
    val sellingPackId: StateFlow<String?> = _sellingPackId.asStateFlow()

    /** Packs available to sell (reuses the existing list load). */
    val packs: StateFlow<List<CreditPack>> = articlesRepository.creditPacks

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    /**
     * Cuántas membresías YA COBRADAS se quedaron sin entregar en el último
     * intento. Van encoladas (no se pierden), pero el mostrador tiene que
     * enterarse en el momento: la pantalla lo pinta y luego lo limpia.
     */
    private val _undeliveredGrants = MutableStateFlow(0)
    val undeliveredGrants: StateFlow<Int> = _undeliveredGrants.asStateFlow()

    /** El aviso ya se mostró y el usuario lo acusó de recibo. */
    fun clearUndeliveredGrants() {
        _undeliveredGrants.value = 0
    }

    /**
     * @param background la consulta corre SOLA (la tarjeta del carrito la lanza
     * al adjuntar un cliente). En la ficha del cliente se deja en `false`: ahí
     * el usuario abrió justamente ese expediente, y esa pantalla presenta un
     * saldo vacío como "Sin paquetes activos" — callar el 403 la haría mentir.
     */
    fun load(customerId: String, background: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = articlesRepository.fetchCustomerCredits(customerId, background = background)
            _loadError.value = result == null
            _balances.value = result ?: emptyList()
            _isLoading.value = false
        }
    }

    fun loadPacks() {
        // Product grids are shared by several roles. Memberships are optional:
        // do not issue a request that the effective venue permissions already
        // tell us will be rejected (and would surface as a global 403 toast).
        if (!roleManager.canReadCreditPacks) return
        // Y aunque el espejo de permisos del cliente se desfase del server, el
        // 403 tampoco puede saltar: esta precarga la dispara pintar la pantalla.
        viewModelScope.launch { articlesRepository.fetchCreditPacks(background = true) }
    }

    fun sell(packId: String, customerId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _sellingPackId.value = packId
            val ok = articlesRepository.sellPackToCustomer(packId, customerId)
            _sellingPackId.value = null
            if (ok) {
                load(customerId)
                onDone()
            }
        }
    }

    /** Grant one or more packs to a customer after an in-person sale (used by the
     *  sell-from-grid flow: charge goes through the cart, credits granted on success). */
    fun grantPacks(packIds: List<String>, customerId: String) {
        viewModelScope.launch {
            var sinEntregar = 0
            packIds.forEach { packId ->
                // ── El criterio del modal global, con sus DOS mitades ──────────
                // El modal crudo de permisos sólo puede salir de una petición que
                //   (a) nació de un TOQUE del usuario en esta pantalla y cuyo
                //       fracaso impide justo lo que pidió, O BIEN
                //   (b) cuyo fracaso deja DINERO YA COBRADO sin su contraparte.
                // La mitad (b) faltaba, y por eso esto se silenció de más.
                //
                // Ésta corre sola al confirmarse el cobro, así que (a) no aplica:
                // `background = true` se queda — a un cajero, "activa
                // «creditPacks:create»" no le sirve de nada y le tapa la pantalla
                // a media venta. Pero (b) SÍ aplica: el cliente pagó su membresía
                // y no la recibió. Callar del todo era el bug — nadie en el
                // mostrador se enteraba, y con un motivo permanente (403) la cola
                // reintentaba para siempre sin éxito y sin avisar jamás.
                //
                // Por eso el fallo hace las DOS cosas: se encola (durable, no se
                // pierde la entrega) y se expone para que la pantalla lo avise.
                // No lo vuelvas a silenciar razonando sólo "corre sola": eso es
                // la mitad (a), y aquí manda la (b).
                val ok = articlesRepository.sellPackToCustomer(packId, customerId, background = true)
                if (!ok) {
                    // The customer already PAID — never drop the grant. Queue it
                    // durably; the queue retries on next app start / drain.
                    pendingGrantQueue.enqueue(packId, customerId)
                    sinEntregar++
                }
            }
            if (sinEntregar > 0) _undeliveredGrants.value = sinEntregar
        }
    }

    fun redeem(balanceId: String, customerId: String) {
        viewModelScope.launch {
            _busyBalanceId.value = balanceId
            val ok = articlesRepository.redeemCredit(balanceId)
            _busyBalanceId.value = null
            if (ok) load(customerId)
        }
    }
}
