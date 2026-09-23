package com.avoqado.pos.inventory.di

import com.avoqado.pos.inventory.data.BorradorDeConteoPrefs
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import com.avoqado.pos.inventory.data.InventoryCountTransport
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.waste.data.CatalogoDeMerma
import com.avoqado.pos.inventory.waste.data.HistorialDeMerma
import com.avoqado.pos.inventory.waste.data.TransporteDeMerma
import com.avoqado.pos.inventory.waste.data.WasteRepository
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

    @Binds
    abstract fun transporteDeMerma(impl: WasteRepository): TransporteDeMerma

    @Binds
    abstract fun catalogoDeMerma(impl: WasteRepository): CatalogoDeMerma

    @Binds
    abstract fun historialDeMerma(impl: WasteRepository): HistorialDeMerma
}
