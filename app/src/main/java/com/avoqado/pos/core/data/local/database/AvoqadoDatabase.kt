package com.avoqado.pos.core.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PendingPaymentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AvoqadoDatabase : RoomDatabase() {
    abstract fun pendingPaymentDao(): PendingPaymentDao
}
