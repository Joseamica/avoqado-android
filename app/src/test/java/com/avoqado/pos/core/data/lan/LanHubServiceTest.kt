package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test
    fun `D12 opción D - el hub enciende las cuentas entre cajas con la sucursal y las apaga al parar`() {
        val cuentas = mockk<CuentasEntreCajas>(relaxed = true)
        val h = LanHubService(secure, transporte, cuentas)
        h.start()
        verify(exactly = 1) { cuentas.encender("venue-a") }
        h.stop()
        io.mockk.verifyOrder { cuentas.encender("venue-a"); cuentas.apagar() } // `start` también apaga lo anterior antes de encender
    }

    // MARK: - Hub D3 (mesa declarada): el hub declara lo que guarda `MesasDeEstaCaja`

    private val mesas = MesasDeEstaCaja({ null }, flowOf(null), ContadorDeVidas({ 0L }, { true })) { System.nanoTime() / 1_000_000 }

    @Test
    fun `D3 - start fija la sucursal de las aperturas y stop suelta las suyas`() {
        val h = LanHubService(secure, transporte, mesas = mesas)
        mesas.abrirApertura("m1", "s1", "Juan", "venue-b")
        val propia = h.abrirApertura("m2", "s1", "Juan")

        h.start()
        assertTrue(mesas.declaracion("venue-b").aperturas.isEmpty())
        assertEquals(listOf(propia), mesas.declaracion("venue-a").aperturas)

        h.stop()
        assertTrue(mesas.declaracion("venue-a").aperturas.isEmpty())
    }

    @Test
    fun `D3 - sin hub, esperarApertura da isla al instante y el adaptador acquire termina su ticket`() = runTest {
        val h = LanHubService(secure, transporte, mesas = mesas)
        val ticket = h.abrirApertura("m5", "s1", "Juan")

        assertEquals(LeaseOutcome.NoHub, h.esperarApertura(ticket))
        assertEquals(LeaseOutcome.NoHub, h.acquire("m6", "s1", "Juan"))
        assertEquals("el de acquire se terminó; el otro es de su dueño", listOf(ticket), mesas.declaracion("venue-a").aperturas)
        h.terminarApertura(ticket)
        assertTrue(mesas.declaracion("venue-a").aperturas.isEmpty())
    }

    @Test
    fun `D3 - release solo despierta - nunca suelta una apertura por nombre de mesa`() = runTest {
        val h = LanHubService(secure, transporte, mesas = mesas)
        h.release("m5") // sin hub: nada que despertar
        h.start()
        val ticket = h.abrirApertura("m5", "s1", "Juan")

        h.release("m5")

        assertEquals(listOf(ticket), mesas.declaracion("venue-a").aperturas)
        h.stop()
    }
}
