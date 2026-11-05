package com.avoqado.pos.customers.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.data.model.CreditPurchaseBalance
import com.avoqado.pos.customers.data.DesenlaceDeEntrega
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

    /** Lo que falta sólo espera red: se dice sin alarma. false = el servidor rechazó algo y lo tiene que ver una persona. */
    private val _entregaSoloEsperaRed = MutableStateFlow(false)
    val entregaSoloEsperaRed: StateFlow<Boolean> = _entregaSoloEsperaRed.asStateFlow()

    /** Cómo quedó la venta de la ficha, si no quedó entregada. */
    private val _avisoDeVenta = MutableStateFlow<String?>(null)
    val avisoDeVenta: StateFlow<String?> = _avisoDeVenta.asStateFlow()

    /** Se abrió otra vez «Vender»: lo de la venta anterior ya se leyó. */
    fun limpiarAvisoDeVenta() {
        _avisoDeVenta.value = null
    }

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

    /**
     * La venta desde la ficha: la cajera ya cobró a mano. 🔴 D4: la entrega queda en disco con su llave ANTES de la red: si se pierde
     * la respuesta, la cola reintenta con la misma llave y el servidor contesta la misma compra, en vez de vender otra.
     */
    fun sell(pack: CreditPack, customerId: String, customerName: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            _sellingPackId.value = pack.id
            _avisoDeVenta.value = null
            val fila = pendingGrantQueue.guardarVentaDeFicha(pack, customerId, customerName)
            if (fila == null) {
                _sellingPackId.value = null
                _avisoDeVenta.value = "No se pudo guardar la venta en este aparato. Intenta de nuevo."
                return@launch
            }
            val desenlace = pendingGrantQueue.entregarDeFicha(fila.id)
            _sellingPackId.value = null
            when (desenlace) {
                // null: la cola ya la entregó mientras tanto.
                DesenlaceDeEntrega.ENTREGADA, null -> {
                    load(customerId)
                    onDone()
                }
                // La cajera ya cobró: se queda en «Operaciones por revisar» aunque cierre esta ventana.
                DesenlaceDeEntrega.RECHAZADA -> _avisoDeVenta.value =
                    "${pendingGrantQueue.motivoDe(fila.id) ?: "No se pudo vender el paquete"}. Quedó en «Operaciones por revisar»."
                // Sin red, un 5xx o la sesión vencida: ya está guardada y la cola la entrega. Volver a tocar vendería otra.
                else -> _avisoDeVenta.value = "Sin conexión con Avoqado: la venta quedó guardada y el paquete se entrega solo al volver."
            }
        }
    }

    /**
     * El cobro de esta venta se confirmó: se entrega lo suyo y se le dice a la cajera cómo quedó.
     *
     * ── El criterio del modal global, con sus DOS mitades ──────────
     * El modal crudo de permisos sólo puede salir de una petición que
     *   (a) nació de un TOQUE del usuario en esta pantalla y cuyo
     *       fracaso impide justo lo que pidió, O BIEN
     *   (b) cuyo fracaso deja DINERO YA COBRADO sin su contraparte.
     * Ésta corre sola al confirmarse el cobro, así que (a) no aplica: a un cajero, "activa «creditPacks:sell»" no le sirve de
     * nada y le tapa la pantalla a media venta. Pero (b) SÍ aplica: el cliente pagó su membresía y no la recibió.
     *
     * Por eso el fallo hace las DOS cosas: se queda en la cola (durable, no se pierde la entrega) y se expone para que la pantalla
     * lo avise. No lo vuelvas a silenciar razonando sólo "corre sola": eso es la mitad (a), y aquí manda la (b).
     */
    fun entregarCobradas(venta: String) {
        viewModelScope.launch {
            val desenlaces = pendingGrantQueue.entregarVenta(venta)
            val sinEntregar = desenlaces.count { it != DesenlaceDeEntrega.ENTREGADA }
            if (sinEntregar > 0) {
                _entregaSoloEsperaRed.value = DesenlaceDeEntrega.RECHAZADA !in desenlaces
                _undeliveredGrants.value = sinEntregar
            }
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
