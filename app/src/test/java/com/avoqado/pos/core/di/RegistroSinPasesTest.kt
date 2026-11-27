package com.avoqado.pos.core.di

import okhttp3.logging.HttpLoggingInterceptor.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** Las rutas de entrar nunca registran el cuerpo: lleva contraseña, pase de Google y tokens (hz:6bfcd3a1:D1). */
class RegistroSinPasesTest {
    @Test
    fun `rutas de auth en debug - solo HEADERS`() {
        assertEquals(Level.HEADERS, nivelDeRegistro("/api/v1/mobile/auth/login", true))
        assertEquals(Level.HEADERS, nivelDeRegistro("/api/v1/mobile/auth/google", true))
        assertEquals(Level.HEADERS, nivelDeRegistro("/api/v1/mobile/auth/refresh", true))
    }

    @Test
    fun `otras rutas en debug - BODY`() {
        assertEquals(Level.BODY, nivelDeRegistro("/api/v1/mobile/venues/x", true))
    }

    @Test
    fun `release - NONE siempre`() {
        assertEquals(Level.NONE, nivelDeRegistro("/api/v1/mobile/auth/google", false))
        assertEquals(Level.NONE, nivelDeRegistro("/api/v1/mobile/venues/x", false))
    }
}
