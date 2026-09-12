package com.avoqado.pos.payment.di

import android.content.Context
import com.avoqado.pos.payment.data.AlmacenEnPreferencias
import com.avoqado.pos.payment.data.CancelacionDeCobroHttp
import com.avoqado.pos.payment.data.CancelacionDeCobroTransport
import com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto
import com.avoqado.pos.payment.data.CancelacionesDeCobroStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CancelacionDeCobroModule {

    /**
     * Dónde viven las cancelaciones de cobro entre arranques de la app. Preferencias propias (no
     * las del cobro ni las de las comandas) para que un `clear` de otra cosa no se las lleve.
     */
    @Provides
    @Singleton
    fun provideCancelacionesStore(@ApplicationContext context: Context): CancelacionesDeCobroStore =
        CancelacionesDeCobroEnTexto(AlmacenEnPreferencias(context, "cancelaciones_de_cobro", "intenciones"))

    @Provides
    @Singleton
    fun provideCancelacionTransport(impl: CancelacionDeCobroHttp): CancelacionDeCobroTransport = impl
}
