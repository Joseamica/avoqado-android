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
}
