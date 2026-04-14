package com.avoqado.pos.kds.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton event bus for broadcasting new orders from checkout to KDS.
 * Live display only — no persistence needed.
 */
@Singleton
class KDSOrderBus @Inject constructor() {

    private val _newOrders = MutableSharedFlow<KDSOrder>(extraBufferCapacity = 20)
    val newOrders: SharedFlow<KDSOrder> = _newOrders.asSharedFlow()

    suspend fun publish(order: KDSOrder) {
        _newOrders.emit(order)
    }
}
