package com.avoqado.pos.core.di

import android.content.Context
import androidx.room.Room
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.AvoqadoDatabaseMigrations
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.inventory.data.local.InventoryTransferDao
import com.avoqado.pos.inventory.data.local.PurchaseOrderDao
import com.avoqado.pos.reservations.data.PendingReservationActionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AvoqadoDatabase {
        return Room.databaseBuilder(
            context,
            AvoqadoDatabase::class.java,
            "avoqado_db",
        ).addMigrations(
            AvoqadoDatabaseMigrations.MIGRATION_1_2,
            AvoqadoDatabaseMigrations.MIGRATION_2_3,
            AvoqadoDatabaseMigrations.MIGRATION_3_4,
        )
            .build()
    }

    @Provides
    fun providePendingPaymentDao(database: AvoqadoDatabase): PendingPaymentDao {
        return database.pendingPaymentDao()
    }

    @Provides
    fun provideCashDrawerDao(database: AvoqadoDatabase): CashDrawerDao {
        return database.cashDrawerDao()
    }

    @Provides
    fun providePurchaseOrderDao(database: AvoqadoDatabase): PurchaseOrderDao {
        return database.purchaseOrderDao()
    }

    @Provides
    fun provideInventoryTransferDao(database: AvoqadoDatabase): InventoryTransferDao {
        return database.inventoryTransferDao()
    }

    @Provides
    fun providePendingReservationActionDao(database: AvoqadoDatabase): PendingReservationActionDao {
        return database.pendingReservationActionDao()
    }
}
