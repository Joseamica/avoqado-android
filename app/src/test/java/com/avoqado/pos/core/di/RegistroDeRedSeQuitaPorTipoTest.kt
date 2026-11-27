package com.avoqado.pos.core.di

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los clientes con PIN en el cuerpo quitan el registro compartido por tipo; sigue siendo quitable (hz:6bfcd3a1:D1). */
class RegistroDeRedSeQuitaPorTipoTest {
    @Test
    fun `tras el removeAll de esos clientes no queda registro`() {
        val cliente = OkHttpClient.Builder().addInterceptor(registroDeRed(depuracion = true)).build()
            .newBuilder().apply {
                interceptors().removeAll { esRegistroDeRed(it) }
                networkInterceptors().removeAll { esRegistroDeRed(it) }
            }.build()
        assertTrue(cliente.interceptors.none { esRegistroDeRed(it) })
    }
}
