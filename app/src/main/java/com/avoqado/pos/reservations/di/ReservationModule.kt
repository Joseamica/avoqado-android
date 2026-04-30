package com.avoqado.pos.reservations.di

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.reservations.domain.ReservationsCapability
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object ReservationModule {

    @Provides
    @Named("apiBaseUrl")
    fun provideApiBaseUrl(): () -> String = { ApiConstants.BASE_URL }

    /**
     * Provides a fresh ReservationsCapability per request — re-reads permissions
     * each time so role/permission changes (e.g. after switchVenue) take effect
     * without needing to restart the process. Callers inject Provider<ReservationsCapability>
     * to pick up the latest state on each access.
     */
    @Provides
    fun provideReservationsCapability(secureStorage: SecureStorage): ReservationsCapability =
        ReservationsCapability.fromPermissions(secureStorage.venuePermissions)
}
