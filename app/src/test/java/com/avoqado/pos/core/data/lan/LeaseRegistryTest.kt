package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Núcleo del hub LAN: leases de mesa + elección de árbitro.
 *
 * El escenario que motivó todo esto: Juan y Alberto abren la MISMA mesa sin
 * internet. Hoy eso se descubre al reconectar; con leases se previene en el acto.
 * El reloj entra por parámetro, así que la caducidad se prueba sin esperar.
 */
class LeaseRegistryTest {

    private val t0 = 1_000_000L
    private val ttl = LeaseRegistry.DEFAULT_TTL_MILLIS

    private fun registry() = LeaseRegistry()

    private fun granted(result: LeaseResult): TableLease {
        assertTrue("esperaba Granted, llegó $result", result is LeaseResult.Granted)
        return (result as LeaseResult.Granted).lease
    }

    @Test
    fun `el primero en pedir la mesa la obtiene`() {
        val lease = granted(registry().acquire("mesa-5", "tablet-juan", "staff-juan", "Juan", t0))

        assertEquals("tablet-juan", lease.holderDeviceId)
        assertEquals(1L, lease.epoch)
        assertEquals(t0 + ttl, lease.expiresAtMillis)
    }

    @Test
    fun `EL CASO - Alberto NO puede abrir la mesa que Juan ya tiene`() {
        val r = registry()
        r.acquire("mesa-5", "tablet-juan", "staff-juan", "Juan", t0)

        val result = r.acquire("mesa-5", "tablet-alberto", "staff-alberto", "Alberto", t0 + 1_000)

        assertTrue(result is LeaseResult.Denied)
        // Se le dice QUIÉN la tiene: el mesero puede ir a hablar con él en vez
        // de quedarse con un "no se pudo" inútil.
        assertEquals("Juan", (result as LeaseResult.Denied).holder.holderName)
        assertFalse(r.canWrite("mesa-5", "tablet-alberto", t0 + 1_000))
    }

    @Test
    fun `si la tablet de Juan muere, la mesa se libera sola al caducar el lease`() {
        val r = registry()
        r.acquire("mesa-5", "tablet-juan", "staff-juan", "Juan", t0)

        // Justo antes de caducar sigue siendo de Juan.
        assertTrue(r.acquire("mesa-5", "tablet-alberto", "s-a", "Alberto", t0 + ttl - 1) is LeaseResult.Denied)

        // Pasado el TTL, Alberto la toma sin que nadie intervenga.
        val after = granted(r.acquire("mesa-5", "tablet-alberto", "s-a", "Alberto", t0 + ttl + 1))
        assertEquals("Alberto", after.holderName)
    }

    @Test
    fun `ZOMBI - Juan vuelve con su época vieja y NO puede pisar a Alberto`() {
        val r = registry()
        val juan = granted(r.acquire("mesa-5", "tablet-juan", "s-j", "Juan", t0))
        assertEquals(1L, juan.epoch)

        // Juan se queda sin señal; el lease caduca y Alberto toma la mesa.
        val alberto = granted(r.acquire("mesa-5", "tablet-alberto", "s-a", "Alberto", t0 + ttl + 1))
        assertEquals(2L, alberto.epoch) // la época SUBE aunque el anterior caducara

        // Juan regresa creyéndose dueño y trata de renovar con su época 1.
        val zombi = r.renew("mesa-5", "tablet-juan", juan.epoch, t0 + ttl + 2)

        assertTrue("el zombi NO debe poder renovar, llegó $zombi", zombi is LeaseResult.Denied)
        assertFalse(r.canWrite("mesa-5", "tablet-juan", t0 + ttl + 2))
        assertTrue(r.canWrite("mesa-5", "tablet-alberto", t0 + ttl + 2))
    }

    @Test
    fun `la época NUNCA se reinicia, ni soltando la mesa a mano`() {
        val r = registry()
        val first = granted(r.acquire("mesa-9", "d1", "s1", "Uno", t0))
        assertTrue(r.release("mesa-9", "d1", first.epoch))

        val second = granted(r.acquire("mesa-9", "d2", "s2", "Dos", t0 + 10))
        // Si esto volviera a 1, un zombi con época 1 parecería vigente otra vez.
        assertEquals(2L, second.epoch)
    }

    @Test
    fun `renovar extiende la mesa mientras el dueño siga vivo`() {
        val r = registry()
        val lease = granted(r.acquire("mesa-5", "d1", "s1", "Juan", t0))

        val extended = granted(r.renew("mesa-5", "d1", lease.epoch, t0 + LeaseRegistry.RENEW_INTERVAL_MILLIS))

        assertEquals(t0 + LeaseRegistry.RENEW_INTERVAL_MILLIS + ttl, extended.expiresAtMillis)
        assertEquals(lease.epoch, extended.epoch) // renovar NO sube la época
    }

    @Test
    fun `renovar un lease ya caducado NO revive al dueño`() {
        val r = registry()
        val lease = granted(r.acquire("mesa-5", "d1", "s1", "Juan", t0))

        // Nadie más la tomó, pero el lease ya venció: hay que volver a pedirla
        // (subiendo la época), no extender el viejo por la puerta de atrás.
        assertTrue(r.renew("mesa-5", "d1", lease.epoch, t0 + ttl + 1) is LeaseResult.Stale)
    }

    @Test
    fun `reintentar acquire por una respuesta perdida no le quita la mesa al dueño`() {
        val r = registry()
        val first = granted(r.acquire("mesa-5", "d1", "s1", "Juan", t0))

        // Misma tablet pide otra vez (no le llegó el ack): sigue siendo suya.
        val again = granted(r.acquire("mesa-5", "d1", "s1", "Juan", t0 + 500))

        assertEquals("d1", again.holderDeviceId)
        assertTrue("época nueva, dueño el mismo", again.epoch > first.epoch)
    }

    @Test
    fun `solo el dueño con la época vigente puede soltar la mesa`() {
        val r = registry()
        val lease = granted(r.acquire("mesa-5", "d1", "s1", "Juan", t0))

        assertFalse(r.release("mesa-5", "d2", lease.epoch)) // otro dispositivo
        assertFalse(r.release("mesa-5", "d1", lease.epoch + 99)) // época inventada
        assertTrue(r.release("mesa-5", "d1", lease.epoch))
    }

    @Test
    fun `activeLeases solo lista las mesas realmente ocupadas ahora`() {
        val r = registry()
        r.acquire("mesa-1", "d1", "s1", "Juan", t0)
        r.acquire("mesa-2", "d2", "s2", "Alberto", t0)

        assertEquals(listOf("mesa-1", "mesa-2"), r.activeLeases(t0 + 1_000).map { it.tableId })
        assertTrue(r.activeLeases(t0 + ttl + 1).isEmpty())
    }
}

class ArbiterElectionTest {

    @Test
    fun `el cableado gana aunque otro lleve más tiempo encendido`() {
        val wifiViejo = LanPeer("tablet-a", "10.0.0.2", 8080, isWired = false, bootedAtMillis = 100)
        val cableado = LanPeer("tablet-b", "10.0.0.3", 8080, isWired = true, bootedAtMillis = 9_000)

        assertEquals("tablet-b", ArbiterElection.pick(listOf(wifiViejo, cableado))?.deviceId)
    }

    @Test
    fun `entre iguales gana el que lleva más tiempo vivo`() {
        val nuevo = LanPeer("tablet-a", "10.0.0.2", 8080, bootedAtMillis = 9_000)
        val viejo = LanPeer("tablet-b", "10.0.0.3", 8080, bootedAtMillis = 100)

        assertEquals("tablet-b", ArbiterElection.pick(listOf(nuevo, viejo))?.deviceId)
    }

    @Test
    fun `empate perfecto se rompe por deviceId - NUNCA puede haber dos árbitros`() {
        val a = LanPeer("tablet-a", "10.0.0.2", 8080, bootedAtMillis = 500)
        val b = LanPeer("tablet-b", "10.0.0.3", 8080, bootedAtMillis = 500)

        // Lo que importa: el orden en que se descubren NO cambia el resultado.
        assertEquals("tablet-a", ArbiterElection.pick(listOf(a, b))?.deviceId)
        assertEquals("tablet-a", ArbiterElection.pick(listOf(b, a))?.deviceId)
        assertTrue(ArbiterElection.isArbiter("tablet-a", listOf(b, a)))
        assertFalse(ArbiterElection.isArbiter("tablet-b", listOf(b, a)))
    }

    @Test
    fun `sin peers no hay árbitro - el dispositivo trabaja como isla`() {
        assertNull(ArbiterElection.pick(emptyList()))
    }
}
