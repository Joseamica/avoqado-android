package com.avoqado.pos.kds.presentation

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.kds.data.KDSRepository
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // MARK: - State

    private val _orders = MutableStateFlow<List<KDSOrder>>(emptyList())

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
            // Poll every 10 seconds
            while (isActive) {
                delay(10_000)
                fetchOrders()
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
