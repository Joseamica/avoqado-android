package com.avoqado.pos.referrals.di

import com.avoqado.pos.referrals.data.api.ReferralsApiService
import com.avoqado.pos.referrals.data.repository.ReferralsRepositoryImpl
import com.avoqado.pos.referrals.domain.repository.ReferralsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Hilt module for the C2C referral capture feature (Plan 5B).
 *
 * Provides the Retrofit-backed [ReferralsApiService] and binds the
 * [ReferralsRepository] interface to its concrete implementation. Uses the
 * default `Retrofit` instance from [com.avoqado.pos.core.di.NetworkModule]
 * — referrals share the same auth + base URL as the rest of the dashboard
 * routes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReferralsModule {

    @Binds
    @Singleton
    abstract fun bindReferralsRepository(
        impl: ReferralsRepositoryImpl,
    ): ReferralsRepository

    companion object {

        @Provides
        @Singleton
        fun provideReferralsApiService(retrofit: Retrofit): ReferralsApiService =
            retrofit.create(ReferralsApiService::class.java)
    }
}
