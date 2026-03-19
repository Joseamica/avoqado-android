package com.avoqado.pos.core.di

import android.content.Context
import androidx.room.Room
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
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
        ).build()
    }

    @Provides
    fun providePendingPaymentDao(database: AvoqadoDatabase): PendingPaymentDao {
        return database.pendingPaymentDao()
    }
}
