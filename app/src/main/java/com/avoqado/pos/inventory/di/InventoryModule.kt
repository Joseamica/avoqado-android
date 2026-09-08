package com.avoqado.pos.inventory.di

import com.avoqado.pos.inventory.data.BorradorDeConteoPrefs
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InventoryModule {
    @Binds
    abstract fun borradorDeConteoStore(impl: BorradorDeConteoPrefs): BorradorDeConteoStore
}
