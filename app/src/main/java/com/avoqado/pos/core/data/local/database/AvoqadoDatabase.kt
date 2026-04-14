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

@Database(
    entities = [
        PendingPaymentEntity::class,
        CashDrawerSessionEntity::class,
        CashDrawerEventEntity::class,
        PurchaseOrderEntity::class,
        InventoryTransferEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AvoqadoDatabase : RoomDatabase() {
    abstract fun pendingPaymentDao(): PendingPaymentDao
    abstract fun cashDrawerDao(): CashDrawerDao
    abstract fun purchaseOrderDao(): PurchaseOrderDao
    abstract fun inventoryTransferDao(): InventoryTransferDao
}
