package com.avoqado.pos.inventory.di

import com.avoqado.pos.inventory.data.BorradorDeConteoPrefs
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import com.avoqado.pos.inventory.data.InventoryCountTransport
import com.avoqado.pos.inventory.data.InventoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InventoryModule {
    @Binds
    abstract fun borradorDeConteoStore(impl: BorradorDeConteoPrefs): BorradorDeConteoStore

    @Binds
    abstract fun inventoryCountTransport(impl: InventoryRepository): InventoryCountTransport
}
