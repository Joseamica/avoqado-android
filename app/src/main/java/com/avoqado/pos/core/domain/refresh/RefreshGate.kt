package com.avoqado.pos.core.domain.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class RefreshOutcome {
    /** block corrió y terminó en éxito */
    Completed,
    /** había petición en vuelo; esta llamada esperó su resultado */
    Joined,
    /** dentro del TTL — no se pidió */
    SkippedFresh,
    /** workInProgress() == true — no se pidió */
    SkippedBusy,
    /** en cooldown tras fallos — no se pidió */
    SkippedCooldown,
    /** block corrió y falló (red) */
    Failed,
}

/**
 * Decide si una pantalla vuelve a pedir sus datos.
 * Spec: docs/superpowers/specs/2026-08-13-estrategia-de-refresco-design.md §4.3.
 * Puro: reloj monotónico y azar (jitter) inyectados. Espejo EXACTO en Swift:
 * avoqado-ios/Services/RefreshGate.swift, con los mismos casos de prueba.
 */
class RefreshGate(
    private val ttl: Duration = 30.seconds,
    private val clock: () -> Duration,
    private val cooldownBase: Duration = 5.seconds,
    private val cooldownMax: Duration = 60.seconds,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val lock = Any()
    private var lastSuccessAt: Duration? = null
    private var lastFailureAt: Duration? = null
    private var consecutiveFailures = 0
    private var invalidationVersion = 0L
    private var inFlight: CompletableDeferred<Result<Unit>>? = null

    private sealed interface Decision {
        data class Join(val flight: CompletableDeferred<Result<Unit>>) : Decision
        data class Skip(val outcome: RefreshOutcome) : Decision
        data class Launch(val flight: CompletableDeferred<Result<Unit>>, val version: Long) : Decision
    }

    suspend fun run(
        workInProgress: () -> Boolean,
        manual: Boolean,
        block: suspend () -> Result<Unit>,
    ): RefreshOutcome {
        val decision: Decision = synchronized(lock) {
            inFlight?.let { return@synchronized Decision.Join(it) }
            // El guard se evalúa AQUÍ, en el instante de decidir (spec §4.3 inv. 6),
            // y aplica también al manual (inv. 5).
            if (workInProgress()) return@synchronized Decision.Skip(RefreshOutcome.SkippedBusy)
            if (!manual) {
                val nowAt = clock()
                lastSuccessAt?.let {
                    if (nowAt - it < ttl) return@synchronized Decision.Skip(RefreshOutcome.SkippedFresh)
                }
                lastFailureAt?.let {
                    if (nowAt - it < currentCooldown()) return@synchronized Decision.Skip(RefreshOutcome.SkippedCooldown)
                }
            }
            val flight = CompletableDeferred<Result<Unit>>()
            inFlight = flight
            Decision.Launch(flight, invalidationVersion)
        }
        return when (decision) {
            is Decision.Skip -> decision.outcome
            is Decision.Join -> {
                decision.flight.await()
                RefreshOutcome.Joined
            }
            is Decision.Launch -> {
                val result = try {
                    block()
                } catch (c: CancellationException) {
                    synchronized(lock) { inFlight = null }
                    decision.flight.cancel()
                    throw c
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                synchronized(lock) {
                    inFlight = null
                    if (result.isSuccess) {
                        // Sella con el instante de TÉRMINO, y solo si nadie invalidó en vuelo.
                        if (invalidationVersion == decision.version) lastSuccessAt = clock()
                        lastFailureAt = null
                        consecutiveFailures = 0
                    } else {
                        lastFailureAt = clock()
                        consecutiveFailures += 1
                    }
                }
                decision.flight.complete(result)
                if (result.isSuccess) RefreshOutcome.Completed else RefreshOutcome.Failed
            }
        }
    }

    /** Los datos cambiaron (búsqueda nueva, mutación local): el próximo auto refetchea. */
    fun invalidate() {
        synchronized(lock) {
            invalidationVersion += 1
            lastSuccessAt = null
        }
    }

    /** Cableado a la recuperación de conectividad (spec §4.3 inv. 4). */
    fun resetCooldown() {
        synchronized(lock) {
            lastFailureAt = null
            consecutiveFailures = 0
        }
    }

    private fun currentCooldown(): Duration {
        if (consecutiveFailures == 0) return Duration.ZERO
        val capped = (cooldownBase * 2.0.pow(consecutiveFailures - 1)).coerceAtMost(cooldownMax)
        return capped * (0.8 + 0.4 * random())
    }
}
