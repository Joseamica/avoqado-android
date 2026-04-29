package com.avoqado.pos.reservations.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ReservationModule {
    // Bindings will be added in subsequent tasks (Api, Repository, Dao).
}
