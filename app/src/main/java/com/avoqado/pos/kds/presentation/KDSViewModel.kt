package com.avoqado.pos.kds.presentation

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.TicketPlan
import com.avoqado.pos.core.data.sync.SyncOutbox
import javax.inject.Provider
import com.avoqado.pos.kds.domain.CanalReparto
import com.avoqado.pos.kds.domain.KDSFilter
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val TAG = "🍳 KDS-VM"

// MARK: - Settings data class

data class KDSSettings(
    val soundEnabled: Boolean = true,
    val autoBumpEnabled: Boolean = false,
    val largeFontEnabled: Boolean = false,
)

@HiltViewModel
class KDSViewModel @Inject constructor(
    private val orderBus: KDSOrderBus,
    private val kdsRepository: KDSRepository,
    // El MISMO despachador que usan mesas y vales: ruteo, fallbacks y la regla de que un
    // guard de configuración jamás va delante de la impresión. Una segunda implementación
    // aquí acabaría imprimiendo distinto que el resto del local.
    private val comandaDispatcher: ComandaDispatcher,
    // Para el ticket de EMPAQUE, que no pasa por el ruteo: es un plan armado a mano con el
    // pedido completo.
    private val comandaPrinter: ComandaPrinter,
    private val printConfigRepository: PrintConfigRepository,
    // El deviceId del outbox, NO uno nuevo: la regla de offline-first lo dice explícito —
    // si cambia al reiniciar, el mismo aparato se ve como dos y el árbitro deja de servir.
    private val syncOutbox: Provider<SyncOutbox>,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // MARK: - State

    private val _orders = MutableStateFlow<List<KDSOrder>>(emptyList())

    /**
     * Un mensaje que la cocina TIENE que leer, no un log.
     *
     * 🔴 Existe porque los errores de responder a un pedido de delivery no son técnicos:
     * "el plazo venció y ya no sirve reintentar" es información operativa, y el servidor la
     * manda escrita para leerse aquí. Tragársela dejaría al cocinero picándole a un botón
     * que ya no puede hacer nada.
     */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Los canales de reparto, para el control de "me saturé".
     *
     * Lista VACÍA cuando el venue no vende por reparto, no tiene el plan, o este puesto no
     * tiene el permiso: en los tres casos el control simplemente no se dibuja. Es el mismo
     * criterio del resto del tablero — no mostrarle a un cocinero un botón que le va a dar
     * error.
     */
    private val _canalesReparto = MutableStateFlow<List<CanalReparto>>(emptyList())
    val canalesReparto: StateFlow<List<CanalReparto>> = _canalesReparto.asStateFlow()

    private val _filter = MutableStateFlow(KDSFilter.ALL)
    val filter: StateFlow<KDSFilter> = _filter.asStateFlow()

    private val _settings = MutableStateFlow(KDSSettings())
    val settings: StateFlow<KDSSettings> = _settings.asStateFlow()

    val filteredOrders: StateFlow<List<KDSOrder>> = combine(
        _orders,
        _filter,
    ) { orders, filter ->
        val visible = orders.filter { it.status != KDSOrderStatus.COMPLETED }
        when (filter) {
            KDSFilter.ALL -> visible
            KDSFilter.NEW -> visible.filter { it.status == KDSOrderStatus.NEW }
            KDSFilter.PREPARING -> visible.filter { it.status == KDSOrderStatus.PREPARING }
            KDSFilter.READY -> visible.filter { it.status == KDSOrderStatus.READY }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeOrderCount: StateFlow<Int> = _orders.combine(_orders) { orders, _ ->
        orders.count { it.status != KDSOrderStatus.COMPLETED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val averageTimeSeconds: StateFlow<Long> = _orders.combine(_orders) { orders, _ ->
        val completed = orders.filter { it.completedAt != null && it.startedAt != null }
        if (completed.isEmpty()) 0L
        else completed.map { (it.completedAt!! - it.startedAt!!) / 1000 }.average().toLong()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var previousOrderIds: Set<String> = emptySet()
    private var hasLoadedFromAPI = false

    // MARK: - Init

    init {
        collectBusOrders()
        // NEVER seed mock orders in production: staff saw fabricated tickets as
        // real, a failed fetch kept them forever, and advance/bump fired REAL
        // API calls against the fake IDs. Start empty; polling fills real data.
        startPolling()
    }

    private fun collectBusOrders() {
        viewModelScope.launch {
            orderBus.newOrders.collect { order ->
                Log.d(TAG, "Nuevo pedido recibido via bus: #${order.orderNumber}")
                // Fetch fresh from API instead of adding locally
                fetchOrders()
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            // Initial fetch
            fetchOrders()
            fetchCanalesReparto()
            // Poll every 10 seconds
            while (isActive) {
                delay(10_000)
                fetchOrders()
                // Va en el MISMO ciclo: así la cuenta regresiva de la pausa se apaga sola
                // cuando el servidor reactiva el canal, sin que nadie tenga que refrescar.
                fetchCanalesReparto()
            }
        }
    }

    // MARK: - Fetch Orders from API

    private suspend fun fetchOrders() {
        kdsRepository.fetchOrders().fold(
            onSuccess = { apiOrders ->
                val newIds = apiOrders.map { it.id }.toSet()
                val addedIds = newIds - previousOrderIds
                if (hasLoadedFromAPI && addedIds.isNotEmpty()) {
                    playNotificationSound()
                }
                previousOrderIds = newIds
                hasLoadedFromAPI = true
                _orders.value = apiOrders
                // Después de publicar, no antes: la pantalla se actualiza aunque la impresora
                // esté tardando. La cocina ve el pedido primero, el papel sale enseguida.
                viewModelScope.launch { imprimirComandasPendientes(apiOrders) }
            },
            onFailure = { error ->
                Log.d(TAG, "API fetch failed (keeping current data): ${error.message}")
                // Keep mock/previous data as fallback
            },
        )
    }

    private fun loadMockOrders() {
        val now = System.currentTimeMillis()
        val mocks = listOf(
            KDSOrder(
                id = UUID.randomUUID().toString(),
                orderNumber = "101",
                orderType = "En tienda",
                items = listOf(
                    KDSOrderItem("1", "Hamburguesa clasica", 2, listOf("Sin cebolla", "Extra queso")),
                    KDSOrderItem("2", "Papas fritas", 1),
                    KDSOrderItem("3", "Refresco grande", 2, notes = "Sin hielo"),
                ),
                createdAt = now - 8 * 60 * 1000,
                status = KDSOrderStatus.NEW,
            ),
            KDSOrder(
                id = UUID.randomUUID().toString(),
                orderNumber = "102",
                orderType = "Para llevar",
                items = listOf(
                    KDSOrderItem("4", "Ensalada cesar", 1, listOf("Aderezo aparte")),
                    KDSOrderItem("5", "Agua mineral", 1),
                ),
                createdAt = now - 5 * 60 * 1000,
                status = KDSOrderStatus.PREPARING,
                startedAt = now - 3 * 60 * 1000,
            ),
            KDSOrder(
                id = UUID.randomUUID().toString(),
                orderNumber = "103",
                orderType = "En tienda",
                items = listOf(
                    KDSOrderItem("6", "Tacos al pastor", 3, listOf("Con todo")),
                    KDSOrderItem("7", "Guacamole", 1),
                ),
                createdAt = now - 12 * 60 * 1000,
                status = KDSOrderStatus.NEW,
            ),
            KDSOrder(
                id = UUID.randomUUID().toString(),
                orderNumber = "104",
                orderType = "Delivery",
                items = listOf(
                    KDSOrderItem("8", "Pizza margherita", 1),
                    KDSOrderItem("9", "Alitas BBQ", 1, listOf("Extra salsa")),
                ),
                createdAt = now - 2 * 60 * 1000,
                status = KDSOrderStatus.PREPARING,
                startedAt = now - 1 * 60 * 1000,
            ),
            KDSOrder(
                id = UUID.randomUUID().toString(),
                orderNumber = "105",
                orderType = "En tienda",
                items = listOf(
                    KDSOrderItem("10", "Cafe americano", 2),
                    KDSOrderItem("11", "Pan dulce", 3),
                ),
                createdAt = now - 15 * 60 * 1000,
                status = KDSOrderStatus.READY,
                startedAt = now - 14 * 60 * 1000,
            ),
        )
        _orders.value = mocks
        previousOrderIds = mocks.map { it.id }.toSet()
    }

    // MARK: - Actions

    fun setFilter(newFilter: KDSFilter) {
        _filter.value = newFilter
    }

    fun advanceStatus(orderId: String) {
        _orders.value = _orders.value.map { order ->
            if (order.id == orderId) {
                val now = System.currentTimeMillis()
                when (order.status) {
                    KDSOrderStatus.NEW -> order.copy(
                        status = KDSOrderStatus.PREPARING,
                        startedAt = now,
                    )
                    KDSOrderStatus.PREPARING -> order.copy(
                        status = KDSOrderStatus.READY,
                    )
                    KDSOrderStatus.READY -> order.copy(
                        status = KDSOrderStatus.COMPLETED,
                        completedAt = now,
                    )
                    KDSOrderStatus.COMPLETED -> order
                }
            } else {
                order
            }
        }

        // Sync to server
        val order = _orders.value.find { it.id == orderId }
        if (order != null) {
            viewModelScope.launch {
                kdsRepository.updateStatus(orderId, order.status.name).onFailure {
                    // Optimistic local mutation failed server-side: resync so the
                    // board self-corrects instead of silently diverging.
                    fetchOrders()
                }
            }
        }

        Log.d(TAG, "Estado avanzado para pedido: $orderId")
    }

    /**
     * "Sí lo preparo." Sólo aparece en canales configurados en MANUAL, donde el sistema NO
     * acepta solo y el plazo del proveedor (~11.5 min en Uber) ya está corriendo.
     *
     * 🔴 NO se pinta como aceptado antes de que el proveedor conteste. Con el estado del
     * pedido no aplica el optimismo que sí usa `advanceStatus`: ahí un error sólo desordena
     * un tablero, aquí haría creer a la cocina que el pedido está confirmado y que puede
     * ponerse a cocinar. Si el plazo venció, ese platillo ya no lo va a recoger nadie.
     */
    fun acceptDeliveryOrder(kdsId: String) {
        val order = _orders.value.find { it.id == kdsId } ?: return
        val orderId = order.orderId ?: return

        viewModelScope.launch {
            kdsRepository.acceptDeliveryOrder(orderId)
                .onSuccess {
                    // Se relee del servidor en vez de asumir: es el server quien sabe si el
                    // proveedor de verdad lo tomó.
                    fetchOrders()
                }
                .onFailure { e ->
                    // El mensaje viene del servidor y está escrito para leerse en la cocina
                    // (por ejemplo: el plazo venció y no sirve reintentar).
                    _errorMessage.value = e.message ?: "No se pudo aceptar el pedido"
                }
        }
    }

    /**
     * "No puedo prepararlo." El SERVIDOR decide si eso significa rechazar o cancelar según
     * si el pedido ya se había aceptado — la cocina sólo dice que no puede.
     */
    fun denyDeliveryOrder(kdsId: String, reason: String = "OUT_OF_ITEMS") {
        val order = _orders.value.find { it.id == kdsId } ?: return
        val orderId = order.orderId ?: return

        viewModelScope.launch {
            kdsRepository.denyDeliveryOrder(orderId, reason)
                .onSuccess { fetchOrders() }
                .onFailure { e -> _errorMessage.value = e.message ?: "No se pudo rechazar el pedido" }
        }
    }

    /**
     * Saca en papel las comandas que llegaron SOLAS y que nadie ha impreso.
     *
     * 🔴 Primero se RECLAMA en el servidor y sólo el ganador imprime. Un pedido de
     * marketplace aparece a la vez en todas las pantallas de cocina: sin árbitro, las tres
     * tablets del local sacan el mismo papel tres veces.
     *
     * Si la impresión falla se SUELTA en el acto, para que otro aparato lo intente sin
     * esperar a que caduque la reclamación. Una tablet sin papel no puede dejar a la cocina
     * sin enterarse del pedido.
     */
    private suspend fun imprimirComandasPendientes(pedidos: List<KDSOrder>) {
        val pendientes = pedidos.filter { it.needsPrint }
        if (pendientes.isEmpty()) return

        val deviceId = runCatching { syncOutbox.get().deviceId }.getOrNull() ?: return
        val venueId = kdsRepository.venueIdActual()

        for (pedido in pendientes) {
            if (!kdsRepository.reclamarImpresion(pedido.id, deviceId)) continue

            val lineas = pedido.items.map { item ->
                RoutableItem(
                    orderItemId = item.id,
                    productId = item.productId,
                    categoryId = item.categoryId,
                    productName = item.productName,
                    quantity = item.quantity,
                    modifiers = item.modifiers,
                    notes = item.notes,
                )
            }

            val ok = runCatching {
                comandaDispatcher.dispatch(
                    venueId = venueId,
                    lines = lineas,
                    orderNumber = pedido.orderNumber,
                    orderType = "Delivery",
                )
            }.isSuccess

            // El ticket de EMPAQUE: el pedido completo en UNA hoja, para quien mete todo en
            // la bolsa y se la da al repartidor. No es una comanda —esas dicen qué cocinar y
            // cada estación ve sólo su parte—: es la lista de verificación de la bolsa. En
            // una mesa el mesero lleva los platos y ve al cliente; aquí, si falta una salsa
            // el cliente se entera en su casa, y eso acaba en reembolso.
            //
            // Sólo si el negocio marcó una estación de empaque. Si no marcó ninguna, no sale
            // nada extra: no le cambiamos el papeleo a quien no lo pidió.
            if (ok) imprimirTicketDeEmpaque(pedido, lineas)

            kdsRepository.marcarImpresion(pedido.id, deviceId, if (ok) "confirm-print" else "release-print")
            if (!ok) Log.e(TAG, "No se pudo imprimir la comanda ${pedido.orderNumber}; soltada para que otro aparato lo intente")
        }
    }

    private suspend fun imprimirTicketDeEmpaque(pedido: KDSOrder, lineas: List<RoutableItem>) {
        val config = printConfigRepository.getCurrentConfig()
        val estacion = config.packingStationId ?: return

        // UN solo plan con TODOS los renglones, dirigido a la estación de empaque. Se arma
        // aquí en vez de rutear, justamente porque el punto es que NO se reparta.
        val plan = TicketPlan(
            stationId = estacion,
            unrouted = false,
            lines = lineas.map { l ->
                ConsolidatedLine(
                    productName = l.productName,
                    quantity = l.quantity,
                    modifiers = l.modifiers,
                    notes = l.notes,
                    orderItemIds = listOf(l.orderItemId),
                )
            },
        )

        runCatching {
            comandaPrinter.printComandas(
                plans = listOf(plan),
                config = config,
                orderNumber = pedido.orderNumber,
                // Lo que se lee ARRIBA del papel. Tiene que gritar que es para empacar, no
                // otra comanda de cocina.
                orderType = "EMPAQUE · Delivery",
            )
        }.onFailure { Log.e(TAG, "No se pudo imprimir el ticket de empaque de ${pedido.orderNumber}: ${it.message}") }
    }

    private suspend fun fetchCanalesReparto() {
        kdsRepository.fetchDeliveryChannels()
            .onSuccess { _canalesReparto.value = it }
            // Un fallo aquí NO se le grita a la cocina: el control desaparece y el tablero
            // sigue funcionando. Perder el botón de pausa no puede tapar los pedidos.
            .onFailure { Log.d(TAG, "No se pudieron leer los canales de reparto: ${it.message}") }
    }

    /**
     * "Me saturé": frena los pedidos de reparto un rato.
     *
     * Sin optimismo, por la misma razón que aceptar un pedido: pintar "pausado" antes de que
     * el marketplace lo confirme haría creer a la cocina que ya no van a entrar pedidos
     * mientras siguen entrando. Se relee del servidor, que es quien sabe.
     */
    fun pausarReparto(linkId: String, minutos: Int) {
        viewModelScope.launch {
            kdsRepository.snoozeDelivery(linkId, minutos)
                .onSuccess { fetchCanalesReparto() }
                .onFailure { e -> _errorMessage.value = e.message ?: "No se pudo pausar el reparto" }
        }
    }

    /** "Ya nos pusimos al día." */
    fun reanudarReparto(linkId: String) {
        viewModelScope.launch {
            kdsRepository.reanudarDelivery(linkId)
                .onSuccess { fetchCanalesReparto() }
                .onFailure { e -> _errorMessage.value = e.message ?: "No se pudo reanudar el reparto" }
        }
    }

    fun bumpOrder(orderId: String) {
        _orders.value = _orders.value.map { order ->
            if (order.id == orderId) {
                order.copy(
                    status = KDSOrderStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                )
            } else {
                order
            }
        }

        // Sync to server
        viewModelScope.launch {
            kdsRepository.bumpOrder(orderId).onFailure { fetchOrders() }
        }

        Log.d(TAG, "Pedido completado (bump): $orderId")
    }

    fun toggleSound() {
        _settings.value = _settings.value.copy(soundEnabled = !_settings.value.soundEnabled)
    }

    fun toggleAutoBump() {
        _settings.value = _settings.value.copy(autoBumpEnabled = !_settings.value.autoBumpEnabled)
    }

    fun toggleLargeFont() {
        _settings.value = _settings.value.copy(largeFontEnabled = !_settings.value.largeFontEnabled)
    }

    // MARK: - Sound

    private fun playNotificationSound() {
        if (!_settings.value.soundEnabled) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(appContext, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo sonido: ${e.message}")
        }
    }
}

// MARK: - Helper for KDSOrderItem (used by KDSOrderBus convenience)

fun KDSOrderItem(
    id: String,
    productName: String,
    quantity: Int,
    modifiers: List<String> = emptyList(),
    notes: String? = null,
) = com.avoqado.pos.kds.domain.KDSOrderItem(
    id = id,
    productName = productName,
    quantity = quantity,
    modifiers = modifiers,
    notes = notes,
)
