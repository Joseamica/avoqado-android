package com.avoqado.pos.reservations.di

import com.avoqado.pos.core.data.network.ApiConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReservationModule {

    @Provides
    @Named("apiBaseUrl")
    fun provideApiBaseUrl(): () -> String = { ApiConstants.BASE_URL }

    @Provides
    fun provideReservationsCapability(): com.avoqado.pos.reservations.domain.ReservationsCapability {
        // TODO Task 13b: wire from JWT permissions once auth layer exposes them via SecureStorage.permissions
        return com.avoqado.pos.reservations.domain.ReservationsCapability(
            canRead = true,
            canCreate = true,
            canUpdate = true,
            canCancel = true,
        )
    }
}
