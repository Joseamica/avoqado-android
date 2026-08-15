package com.avoqado.pos.core.domain.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RefreshGateTest {

    private var now: Duration = Duration.ZERO

    private fun gate(ttl: Duration = 30.seconds) = RefreshGate(
        ttl = ttl,
        clock = { now },
        random = { 0.5 }, // jitter ×1.0 → determinista
    )

    @Test
    fun `primer arranque siempre pide`() = runTest {
        val g = gate()
        var calls = 0
        val outcome = g.run({ false }, manual = false) { calls++; Result.success(Unit) }
        assertEquals(RefreshOutcome.Completed, outcome)
        assertEquals(1, calls)
    }

    @Test
    fun `dentro del TTL no pide`() = runTest {
        val g = gate()
        var calls = 0
        g.run({ false }, manual = false) { calls++; Result.success(Unit) }
        now += 10.seconds
        val outcome = g.run({ false }, manual = false) { calls++; Result.success(Unit) }
        assertEquals(RefreshOutcome.SkippedFresh, outcome)
        assertEquals(1, calls)
    }

    @Test
    fun `pasado el TTL vuelve a pedir`() = runTest {
        val g = gate()
        var calls = 0
        g.run({ false }, manual = false) { calls++; Result.success(Unit) }
        now += 31.seconds
        val outcome = g.run({ false }, manual = false) { calls++; Result.success(Unit) }
        assertEquals(RefreshOutcome.Completed, outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `dos llamadas simultaneas comparten una sola peticion`() = runTest {
        val g = gate()
        var calls = 0
        val latch = CompletableDeferred<Unit>()
        val block: suspend () -> Result<Unit> = {
            calls++
            latch.await()
            Result.success(Unit)
        }
        val first = async { g.run({ false }, manual = false, block = block) }
        val second = async { g.run({ false }, manual = false, block = block) }
        testScheduler.advanceUntilIdle() // ambas llegan a la decisión
        latch.complete(Unit)
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, calls)
        assertTrue(outcomes.contains(RefreshOutcome.Completed))
        assertTrue(outcomes.contains(RefreshOutcome.Joined))
    }

    @Test
    fun `el reloj se sella con el instante de termino no el de inicio`() = runTest {
        val g = gate()
        var calls = 0
        g.run({ false }, manual = false) {
            calls++
            now += 20.seconds // la petición tardó 20 s
            Result.success(Unit)
        }
        now += 25.seconds // 45 s del inicio, 25 s del término → fresco
        assertEquals(
            RefreshOutcome.SkippedFresh,
            g.run({ false }, manual = false) { calls++; Result.success(Unit) },
        )
        assertEquals(1, calls)
    }

    @Test
    fun `invalidate durante el vuelo - el exito tardio no sella el reloj`() = runTest {
        val g = gate()
        var calls = 0
        val latch = CompletableDeferred<Unit>()
        val flight = async {
            g.run({ false }, manual = false) {
                calls++
                latch.await()
                Result.success(Unit)
            }
        }
        testScheduler.advanceUntilIdle()
        g.invalidate()
        latch.complete(Unit)
        flight.await()
        // el éxito llegó con versión vieja → el siguiente auto NO está fresco
        assertEquals(
            RefreshOutcome.Completed,
            g.run({ false }, manual = false) { calls++; Result.success(Unit) },
        )
        assertEquals(2, calls)
    }

    @Test
    fun `invalidate fuerza refetch aunque haya exito reciente`() = runTest {
        val g = gate()
        var calls = 0
        g.run({ false }, manual = false) { calls++; Result.success(Unit) }
        g.invalidate()
        assertEquals(
            RefreshOutcome.Completed,
            g.run({ false }, manual = false) { calls++; Result.success(Unit) },
        )
        assertEquals(2, calls)
    }

    @Test
    fun `fallo - no sella arma cooldown y el manual lo salta`() = runTest {
        val g = gate()
        var calls = 0
        assertEquals(
            RefreshOutcome.Failed,
            g.run({ false }, manual = false) { calls++; Result.failure(Exception("red")) },
        )
        assertEquals(
            RefreshOutcome.SkippedCooldown,
            g.run({ false }, manual = false) { calls++; Result.success(Unit) },
        )
        assertEquals(
            RefreshOutcome.Completed,
            g.run({ false }, manual = true) { calls++; Result.success(Unit) },
        )
        assertEquals(2, calls)
    }

    @Test
    fun `pasado el cooldown el auto reintenta`() = runTest {
        val g = gate()
        var calls = 0
        g.run({ false }, manual = false) { calls++; Result.failure(Exception("red")) }
        now += 6.seconds // base 5 s × jitter 1.0
        assertEquals(
            RefreshOutcome.Completed,
            g.run({ false }, manual = false) { calls++; Result.success(Unit) },
        )
        assertEquals(2, calls)
    }

    @Test
    fun `resetCooldown limpia el backoff`() = runTest {
        val g = gate()
        var calls = 0
        g.run({ false }, manual = false) { calls++; Result.failure(Exception("red")) }
        g.resetCooldown()
        assertEquals(
            RefreshOutcome.Completed,
            g.run({ false }, manual = false) { calls++; Result.success(Unit) },
        )
        assertEquals(2, calls)
    }

    @Test
    fun `guard - con trabajo en curso no refresca ni auto ni manual`() = runTest {
        val g = gate()
        var calls = 0
        assertEquals(RefreshOutcome.SkippedBusy, g.run({ true }, manual = false) { calls++; Result.success(Unit) })
        assertEquals(RefreshOutcome.SkippedBusy, g.run({ true }, manual = true) { calls++; Result.success(Unit) })
        assertEquals(0, calls)
    }
}
