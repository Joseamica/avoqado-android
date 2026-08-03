package com.avoqado.pos.core.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.inventory.data.local.InventoryTransferDao
import com.avoqado.pos.inventory.data.local.InventoryTransferEntity
import com.avoqado.pos.inventory.data.local.PurchaseOrderDao
import com.avoqado.pos.inventory.data.local.PurchaseOrderEntity
import com.avoqado.pos.reservations.data.PendingReservationActionDao
import com.avoqado.pos.reservations.data.PendingReservationActionEntity

@Database(
    entities = [
        PendingPaymentEntity::class,
        CashDrawerSessionEntity::class,
        CashDrawerEventEntity::class,
        PurchaseOrderEntity::class,
        InventoryTransferEntity::class,
        PendingReservationActionEntity::class,
        // v5 — offline-first Corte A: espejo en disco de payloads de lectura
        CachedPayloadEntity::class,
        // v6 — offline-first Corte B: outbox de intents (comandas/mesas offline)
        SyncIntentEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AvoqadoDatabase : RoomDatabase() {
    abstract fun pendingPaymentDao(): PendingPaymentDao
    abstract fun cashDrawerDao(): CashDrawerDao
    abstract fun purchaseOrderDao(): PurchaseOrderDao
    abstract fun inventoryTransferDao(): InventoryTransferDao
    abstract fun pendingReservationActionDao(): PendingReservationActionDao
    abstract fun cachedPayloadDao(): CachedPayloadDao
    abstract fun syncIntentDao(): SyncIntentDao
}
