package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El hub Premium se ENGANCHA al transporte, se reinicia al cambiar de venue y se apaga al cerrar sesión (3.5, D1/D12). */
class LanHubServiceTest {

    private val secure = mockk<SecureStorage> { every { venueId } returns "venue-a" }
    private val transporte = mockk<TransporteLan>(relaxed = true) {
        every { deviceId } returns "tablet-1"
        every { peers } returns MutableStateFlow(emptyList())
    }
    private val hub = LanHubService(secure, transporte)

    @Test
    fun `start engancha el hub al transporte una sola vez por venue`() {
        hub.start()
        hub.start()
        verify(exactly = 1) { transporte.iniciar("venue-a") }
        verify(exactly = 1) { transporte.conectarHub(any()) }
        assertTrue(hub.enabled.value)
    }

    @Test
    fun `P1 al cambiar de venue el hub viejo se desengancha y el siguiente start lo rearma con el nuevo`() {
        hub.start()
        every { secure.venueId } returns "venue-b"

        hub.sincronizarVenue("venue-b")
        verify(exactly = 1) { transporte.desconectarHub() }
        assertFalse(hub.enabled.value)

        hub.start()
        verify(exactly = 1) { transporte.iniciar("venue-b") }
        verify(exactly = 2) { transporte.conectarHub(any()) }
    }

    @Test
    fun `sincronizarVenue con el mismo venue no toca nada, y stop desengancha`() {
        hub.start()
        hub.sincronizarVenue("venue-a")
        verify(exactly = 0) { transporte.desconectarHub() }
        hub.stop()
        verify(exactly = 1) { transporte.desconectarHub() }
        assertFalse(hub.enabled.value)
    }

    @Test
    fun `sin venue el hub no arranca`() {
        every { secure.venueId } returns null
        hub.start()
        verify(exactly = 0) { transporte.conectarHub(any()) }
    }
}
