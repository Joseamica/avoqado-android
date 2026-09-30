package com.avoqado.pos.core.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.inventory.data.local.InventoryTransferDao
import com.avoqado.pos.inventory.data.local.PurchaseOrderDao
import com.avoqado.pos.inventory.waste.data.PendingWasteDao
import com.avoqado.pos.inventory.waste.data.WasteCatalogDao
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.kds.data.local.KdsTicketsLocalesDao
import com.avoqado.pos.reservations.data.PendingReservationActionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * Reemplazo de escritorio del DatabaseModule de Android: la MISMA AvoqadoDatabase (mismas entidades y DAOs,
 * generados por KSP), en `<carpeta de datos>/databases/avoqado_db`.
 * 🔴 Sin migraciones y SIN fallbackToDestructiveMigration: una instalación nueva no las necesita, y el destructivo
 * borraría en silencio los cobros encolados al actualizar. Antes de publicar, las 12 migraciones se pasan a SQLiteConnection.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AvoqadoDatabase =
        Room.databaseBuilder<AvoqadoDatabase>(name = context.getDatabasePath("avoqado_db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Provides
    fun providePendingPaymentDao(database: AvoqadoDatabase): PendingPaymentDao {
        return database.pendingPaymentDao()
    }

    @Provides
    fun provideWasteCatalogDao(database: AvoqadoDatabase): WasteCatalogDao = database.wasteCatalogDao()

    @Provides
    fun providePendingWasteDao(database: AvoqadoDatabase): PendingWasteDao = database.pendingWasteDao()

    @Provides
    fun provideEntregasKdsPendientesDao(database: AvoqadoDatabase): EntregasKdsPendientesDao = database.entregasKdsPendientesDao()

    @Provides
    fun provideKdsTicketsLocalesDao(database: AvoqadoDatabase): KdsTicketsLocalesDao = database.kdsTicketsLocalesDao()

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

    @Provides
    fun provideCachedPayloadDao(database: AvoqadoDatabase): com.avoqado.pos.core.data.local.database.CachedPayloadDao {
        return database.cachedPayloadDao()
    }

    @Provides
    fun provideSyncIntentDao(database: AvoqadoDatabase): com.avoqado.pos.core.data.local.database.SyncIntentDao {
        return database.syncIntentDao()
    }
}
