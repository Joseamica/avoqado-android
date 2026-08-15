# Estrategia de refresco — Plan de implementación (núcleo + rebanada 1: Transacciones)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir el `RefreshGate` compartido (Kotlin + espejo Swift), el envoltorio de pull-to-refresh, y entregar la PRIMERA rebanada vertical completa — Transacciones — en Android e iOS juntos.

**Architecture:** Pieza pura compartida (`RefreshGate`: TTL 30 s, single-flight, guard re-evaluado, cooldown con backoff, reloj monotónico inyectado) + adopción por pantalla. Los triggers ad-hoc existentes se REEMPLAZAN, nunca quedan en paralelo con el gate.

**Tech Stack:** Kotlin/Compose (Material3 `PullToRefreshBox`, `@ExperimentalMaterial3Api`), Swift/SwiftUI (`.refreshable`, actor), JUnit4 + kotlinx-coroutines-test + MockK, XCTest.

**Spec:** `docs/superpowers/specs/2026-08-13-estrategia-de-refresco-design.md` (v3 — leerlo ANTES de ejecutar; §4.3 define los invariantes del gate y §11-12 los errores que NO hay que reintroducir).

**Alcance de ESTE plan (scope check):** el spec cubre ~19 pantallas Android + ~22 vistas iOS. Este plan entrega el núcleo + la rebanada Transacciones de punta a punta. Las rebanadas restantes (Órdenes → Artículos → Informes → Inventario → resto) van en planes hermanos que repiten la misma receta — cada una exige primero el fix §4.1 de su repositorio.

## Global Constraints

- 🔴 **NO commitear ni push** (regla del founder: solo cuando diga "commitea"). Los pasos marcan **[punto de commit lógico]** para cuando lo pida; entonces `git add` por rutas explícitas, nunca `-A`.
- 🔴 **NO crear branches ni worktrees** — trabajar sobre la rama actual de cada repo.
- 🔴 **Paridad Android+iOS en el mismo trabajo**: este plan intercala tareas de ambos repos a propósito; no se declara terminado con una sola plataforma compilando.
- 🔴 Otras sesiones de IA trabajan en paralelo: archivos modificados ajenos en `git status` son normales; no revertir, no matar procesos, no "limpiar".
- Reloj: **`SystemClock.elapsedRealtime()`** (Android) / **`mach_continuous_time()`** (iOS; cuenta suspensión profunda — `ContinuousClock` de iOS 16 no es inyectable como cierre simple, el helper de abajo da la misma semántica). **Nunca** `currentTimeMillis()`/`Date()`.
- TTL 30 s · cooldown base 5 s, tope 60 s, jitter ×[0.8, 1.2) · fallo de red de fondo = **silencioso** (datos previos intactos) · 401/403/409 se propagan como hoy.
- Textos visibles en español. Ningún campo de API se renombra.
- Tier: **core, sin switch** (spec §9) — no hay gating que construir. MCP y presentación de ventas: exentos (sin capacidad nueva).
- Verificación mínima por tarea: compilar/test del proyecto tocado SIEMPRE (la carga de la máquina no lo exime; solo avisa si va a tardar).

---

### Task 1: `RefreshGate` (Kotlin, puro, TDD)

**Files:**
- Create: `avoqado-android/app/src/main/java/com/avoqado/pos/core/domain/refresh/RefreshGate.kt`
- Test: `avoqado-android/app/src/test/java/com/avoqado/pos/core/domain/refresh/RefreshGateTest.kt`

**Interfaces:**
- Consumes: nada (lógica pura; `clock` y `random` inyectados).
- Produces: `enum RefreshOutcome { Completed, Joined, SkippedFresh, SkippedBusy, SkippedCooldown, Failed }` y `class RefreshGate(ttl, clock, cooldownBase, cooldownMax, random)` con `suspend fun run(workInProgress: () -> Boolean, manual: Boolean, block: suspend () -> Result<Unit>): RefreshOutcome`, `fun invalidate()`, `fun resetCooldown()`. Tasks 4-6 dependen de estas firmas EXACTAS.

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
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
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew :app:testDebugUnitTest --tests '*RefreshGateTest*'`
Expected: FALLA en compilación ("Unresolved reference: RefreshGate") — eso ES el rojo de TDD aquí.

- [ ] **Step 3: Implementar `RefreshGate.kt`**

```kotlin
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
```

- [ ] **Step 4: Correr y ver verde**

Run: `./gradlew :app:testDebugUnitTest --tests '*RefreshGateTest*'`
Expected: 11/11 PASS. **[punto de commit lógico: "feat(refresh): RefreshGate con single-flight, TTL y cooldown"]**

---

### Task 2: `RefreshGate` (Swift, espejo exacto, TDD)

**Files:**
- Create: `avoqado-ios/avoqado-ios/Services/RefreshGate.swift`
- Test: `avoqado-ios/avoqado-iosTests/RefreshGateTests.swift`

**Interfaces:**
- Consumes: nada.
- Produces: `enum RefreshOutcome: Equatable { completed, joined, skippedFresh, skippedBusy, skippedCooldown, failed }` y `actor RefreshGate` con `func run(workInProgress: () -> Bool, manual: Bool, block: @escaping @Sendable () async -> Bool) async -> RefreshOutcome`, `func invalidate()`, `func resetCooldown()`. Task 6 depende de estas firmas. `block` devuelve `Bool` (éxito) — es el espejo de `Result<Unit>`.

- [ ] **Step 1: Escribir los tests que fallan** — mismos 11 casos que Kotlin, mismos nombres traducidos. Esqueleto (completar los 11 espejando 1:1 la tabla de Task 1):

```swift
import XCTest
@testable import avoqado_ios

final class RefreshGateTests: XCTestCase {
    /// Reloj mutable inyectado (espejo del `var now` de Kotlin).
    final class ClockBox { var now: TimeInterval = 0 }
    /// Contador mutable capturable por el block @Sendable del test.
    final class Counter { var n = 0 }

    private func makeGate(_ clock: ClockBox, ttl: TimeInterval = 30) -> RefreshGate {
        RefreshGate(ttl: ttl, clock: { clock.now }, random: { 0.5 })
    }

    func test_primerArranqueSiemprePide() async {
        let clock = ClockBox(); let calls = Counter()
        let gate = makeGate(clock)
        let outcome = await gate.run(workInProgress: { false }, manual: false) { calls.n += 1; return true }
        XCTAssertEqual(outcome, .completed)
        XCTAssertEqual(calls.n, 1)
    }

    func test_dentroDelTTLNoPide() async {
        let clock = ClockBox(); let calls = Counter()
        let gate = makeGate(clock)
        _ = await gate.run(workInProgress: { false }, manual: false) { calls.n += 1; return true }
        clock.now += 10
        let outcome = await gate.run(workInProgress: { false }, manual: false) { calls.n += 1; return true }
        XCTAssertEqual(outcome, .skippedFresh)
        XCTAssertEqual(calls.n, 1)
    }

    // … pasadoElTTLVuelveAPedir (clock.now += 31 → .completed, 2 calls)
    // … elRelojSeSellaConElInstanteDeTermino (block hace clock.now += 20; luego +25 → .skippedFresh)
    // … falloNoSellaArmaCooldownYElManualLoSalta (block false → .failed; auto → .skippedCooldown; manual → .completed)
    // … pasadoElCooldownElAutoReintenta (clock.now += 6 → .completed)
    // … resetCooldownLimpiaElBackoff
    // … invalidateFuerzaRefetchAunqueHayaExitoReciente
    // … guardConTrabajoEnCursoNoRefrescaNiAutoNiManual (.skippedBusy ×2, 0 calls)

    /// Para los dos casos concurrentes (join + invalidate en vuelo) hace falta
    /// pausar el block a voluntad:
    actor AsyncLatch {
        private var opened = false
        private var waiters: [CheckedContinuation<Void, Never>] = []
        func wait() async {
            if opened { return }
            await withCheckedContinuation { waiters.append($0) }
        }
        func open() {
            opened = true
            waiters.forEach { $0.resume() }
            waiters.removeAll()
        }
    }

    func test_dosLlamadasSimultaneasCompartenUnaPeticion() async {
        let clock = ClockBox(); let calls = Counter()
        let gate = makeGate(clock)
        let latch = AsyncLatch()
        async let first = gate.run(workInProgress: { false }, manual: false) {
            calls.n += 1
            await latch.wait()
            return true
        }
        for _ in 0..<10 { await Task.yield() } // deja que el primero registre el vuelo
        async let second = gate.run(workInProgress: { false }, manual: false) { calls.n += 1; return true }
        for _ in 0..<10 { await Task.yield() }
        await latch.open()
        let outcomes = await [first, second]
        XCTAssertEqual(calls.n, 1)
        XCTAssertTrue(outcomes.contains(.completed))
        XCTAssertTrue(outcomes.contains(.joined))
    }

    func test_invalidateDuranteElVueloElExitoTardioNoSella() async {
        let clock = ClockBox(); let calls = Counter()
        let gate = makeGate(clock)
        let latch = AsyncLatch()
        async let flight = gate.run(workInProgress: { false }, manual: false) {
            calls.n += 1
            await latch.wait()
            return true
        }
        for _ in 0..<10 { await Task.yield() }
        await gate.invalidate()
        await latch.open()
        _ = await flight
        let outcome = await gate.run(workInProgress: { false }, manual: false) { calls.n += 1; return true }
        XCTAssertEqual(outcome, .completed)
        XCTAssertEqual(calls.n, 2)
    }
}
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios && xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:avoqado-iosTests/RefreshGateTests`
Expected: FALLA en compilación ("cannot find 'RefreshGate' in scope"). Aviso: xcodebuild pasa por el chequeo de capacidad de la máquina (`sysctl -n vm.loadavg vm.swapusage`) — se corre igual, solo avisa si va a tardar.

- [ ] **Step 3: Implementar `RefreshGate.swift`**

```swift
//
//  RefreshGate.swift
//  avoqado-ios
//
//  Espejo EXACTO de avoqado-android core/domain/refresh/RefreshGate.kt.
//  Spec: avoqado-android/docs/superpowers/specs/2026-08-13-estrategia-de-refresco-design.md §4.3.
//  Puro: reloj monotónico y azar inyectados; mismos casos de prueba que Kotlin.
//

import Foundation

enum RefreshOutcome: Equatable {
    case completed        // block corrió y terminó en éxito
    case joined           // había petición en vuelo; esta llamada esperó su resultado
    case skippedFresh     // dentro del TTL — no se pidió
    case skippedBusy      // workInProgress() == true — no se pidió
    case skippedCooldown  // en cooldown tras fallos — no se pidió
    case failed           // block corrió y falló (red)
}

actor RefreshGate {
    private let ttl: TimeInterval
    private let clock: () -> TimeInterval
    private let cooldownBase: TimeInterval
    private let cooldownMax: TimeInterval
    private let random: () -> Double

    private var lastSuccessAt: TimeInterval?
    private var lastFailureAt: TimeInterval?
    private var consecutiveFailures = 0
    private var invalidationVersion: UInt64 = 0
    private var inFlight: Task<Bool, Never>?

    init(
        ttl: TimeInterval = 30,
        clock: @escaping () -> TimeInterval = RefreshGate.continuousNow,
        cooldownBase: TimeInterval = 5,
        cooldownMax: TimeInterval = 60,
        random: @escaping () -> Double = { Double.random(in: 0..<1) }
    ) {
        self.ttl = ttl
        self.clock = clock
        self.cooldownBase = cooldownBase
        self.cooldownMax = cooldownMax
        self.random = random
    }

    /// Reloj monotónico que SÍ cuenta la suspensión profunda — equivale a
    /// SystemClock.elapsedRealtime() de Android. Nunca Date() (spec §4.6).
    static func continuousNow() -> TimeInterval {
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        let nanos = Double(mach_continuous_time()) * Double(timebase.numer) / Double(timebase.denom)
        return nanos / 1_000_000_000
    }

    func run(
        workInProgress: () -> Bool,
        manual: Bool,
        block: @escaping @Sendable () async -> Bool
    ) async -> RefreshOutcome {
        if let flight = inFlight {
            _ = await flight.value
            return .joined
        }
        // El guard se evalúa AQUÍ, en el instante de decidir (spec §4.3 inv. 6),
        // y aplica también al manual (inv. 5).
        if workInProgress() { return .skippedBusy }
        if !manual {
            let now = clock()
            if let sealed = lastSuccessAt, now - sealed < ttl { return .skippedFresh }
            if let failed = lastFailureAt, now - failed < currentCooldown() { return .skippedCooldown }
        }
        let version = invalidationVersion
        let flight = Task { await block() }
        inFlight = flight
        let success = await flight.value
        inFlight = nil
        if success {
            // Sella con el instante de TÉRMINO, y solo si nadie invalidó en vuelo.
            if invalidationVersion == version { lastSuccessAt = clock() }
            lastFailureAt = nil
            consecutiveFailures = 0
        } else {
            lastFailureAt = clock()
            consecutiveFailures += 1
        }
        return success ? .completed : .failed
    }

    /// Los datos cambiaron (búsqueda nueva, mutación local): el próximo auto refetchea.
    func invalidate() {
        invalidationVersion += 1
        lastSuccessAt = nil
    }

    /// Cableado a la recuperación de conectividad (spec §4.3 inv. 4).
    func resetCooldown() {
        lastFailureAt = nil
        consecutiveFailures = 0
    }

    private func currentCooldown() -> TimeInterval {
        guard consecutiveFailures > 0 else { return 0 }
        let capped = min(cooldownBase * pow(2, Double(consecutiveFailures - 1)), cooldownMax)
        return capped * (0.8 + 0.4 * random())
    }
}
```

Nota: el archivo nuevo debe agregarse al target `avoqado-ios` y el test al target `avoqado-iosTests` (con proyecto pbxproj clásico, verificar que el archivo quedó en el target; si el repo usa file-system-synchronized groups basta con crearlo en la carpeta).

- [ ] **Step 4: Correr y ver verde**

Run: `xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:avoqado-iosTests/RefreshGateTests`
Expected: 11/11 PASS. Si `test_dosLlamadasSimultaneas...` resulta flaky por orden de scheduling, subir los bucles de `Task.yield()` a 50 — no meter sleeps. **[punto de commit lógico iOS: "feat(refresh): RefreshGate actor — espejo del gate de Android"]**

---

### Task 3: `AvoqadoRefreshable` + `RefreshGateFactory` (Android)

**Files:**
- Create: `avoqado-android/app/src/main/java/com/avoqado/pos/designsystem/components/AvoqadoRefreshable.kt`
- Create: `avoqado-android/app/src/main/java/com/avoqado/pos/core/domain/refresh/RefreshGateFactory.kt`

**Interfaces:**
- Consumes: `RefreshGate` (Task 1), `ConnectivityMonitor.isConnected: StateFlow<Boolean>` (ya existe en `core/util/ConnectivityMonitor.kt`).
- Produces: `@Composable fun AvoqadoRefreshable(isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier, content: @Composable () -> Unit)` y `class RefreshGateFactory @Inject constructor(...)` con `fun create(scope: CoroutineScope, ttl: Duration = 30.seconds): RefreshGate`. Tasks 4-5 dependen de ambas.

- [ ] **Step 1: Escribir `AvoqadoRefreshable.kt`**

```kotlin
package com.avoqado.pos.designsystem.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Envoltorio único de pull-to-refresh (spec §4.7). isRefreshing = SOLO el gesto
 * manual (nunca la carga inicial). NO es dueño del banner de conectividad —
 * ese sigue siendo el ConnectivityBanner global del NavGraph.
 * PullToRefreshBox es @ExperimentalMaterial3Api en M3 1.3.x: vigilar al subir el BOM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoRefreshable(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        content()
    }
}
```

- [ ] **Step 2: Escribir `RefreshGateFactory.kt`**

```kotlin
package com.avoqado.pos.core.domain.refresh

import android.os.SystemClock
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Crea el RefreshGate de un ViewModel con el reloj monotónico real
 * (elapsedRealtime cuenta deep sleep — spec §4.6) y el cooldown cableado a la
 * recuperación de conectividad (spec §4.3 inv. 4). El gate vive y muere con el
 * viewModelScope: identidad por (user, venue) resuelta por la recreación del
 * árbol con contentKey (spec §4.4).
 */
@Singleton
class RefreshGateFactory @Inject constructor(
    private val connectivityMonitor: ConnectivityMonitor,
) {
    fun create(scope: CoroutineScope, ttl: Duration = 30.seconds): RefreshGate {
        val gate = RefreshGate(
            ttl = ttl,
            clock = { SystemClock.elapsedRealtime().milliseconds },
        )
        scope.launch {
            connectivityMonitor.isConnected.collect { connected ->
                if (connected) gate.resetCooldown()
            }
        }
        return gate
    }
}
```

- [ ] **Step 3: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Sin test unitario propio: son un wrapper de UI de 10 líneas y un factory de wiring; la lógica ya quedó cubierta en Task 1. El wiring de conectividad se cubre en el test de VM de Task 4 solo si sale gratis; si no, queda para el QA en device de Task 7 — decirlo en el reporte.) **[punto de commit lógico]**

---

### Task 4: Transacciones Android — contrato `refreshNow()` + gate en el ViewModel (TDD)

**Files:**
- Modify: `avoqado-android/app/src/main/java/com/avoqado/pos/transactions/data/TransactionRepository.kt:42-90`
- Modify: `avoqado-android/app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionsViewModel.kt`
- Test: `avoqado-android/app/src/test/java/com/avoqado/pos/transactions/TransactionsViewModelRefreshTest.kt`

**Interfaces:**
- Consumes: `RefreshGate`/`RefreshOutcome` (Task 1), `RefreshGateFactory` (Task 3).
- Produces (para Task 5): en `TransactionsViewModel` — `val isManualRefreshing: StateFlow<Boolean>`, `fun autoRefresh()`, `fun manualRefresh()`, `suspend fun refreshNow(): Result<Unit>`. `fun refresh()` queda como shim deprecated hasta Task 5.

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelRefreshTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var now: Duration = Duration.ZERO
    private val repository: TransactionRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): TransactionsViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { now }, random = { 0.5 })
        coEvery { repository.fetchTransactions(any(), any()) } returns Result.success(Unit)
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        return TransactionsViewModel(
            repository = repository,
            refundRepository = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true),
            terminalPaymentService = mockk(relaxed = true),
            roleManager = mockk(relaxed = true),
            orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    @Test
    fun `autoRefresh dentro del TTL no vuelve a pedir`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        now += 10.seconds
        vm.autoRefresh()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
    }

    @Test
    fun `con la devolucion abierta ni el gesto refresca`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.showRefundSheet()
        vm.manualRefresh()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `buscar invalida el TTL y vuelve a pedir con el termino`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        vm.setSearchText("cafe")
        scheduler.advanceTimeBy(500) // pasa el debounce de 400 ms
        scheduler.runCurrent()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = "cafe") }
    }
}
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `./gradlew :app:testDebugUnitTest --tests '*TransactionsViewModelRefreshTest*'`
Expected: FALLA en compilación (el VM aún no recibe `refreshGateFactory` ni expone `autoRefresh`).

- [ ] **Step 3: Cambiar `TransactionRepository.fetchTransactions` al contrato tipado**

Reemplazar la firma y las tres salidas (spec §4.1/§4.2 — el repo YA preserva `_transactions` al fallar, verificado; lo que falta es reportar el fallo):

```kotlin
    suspend fun fetchTransactions(
        page: Int = 1,
        search: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(IllegalStateException("Sin venue activo"))
        val token = secureStorage.accessToken
            ?: return Result.failure(IllegalStateException("Sin sesión"))

        if (page == 1) {
            // Refresh de fondo silencioso: sin skeleton encima de datos buenos (spec §6).
            _isLoading.value = _transactions.value.isEmpty()
            currentPage = 1
            hasMore = true
        } else {
            _isLoadingMore.value = true
        }

        return try {
            // ... (URL, request y ejecución quedan idénticos a hoy) ...
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<TransactionsResponse>(body)
                _transactions.value = if (page == 1) result.data else _transactions.value + result.data
                currentPage = page
                hasMore = result.meta?.let { page < it.pageCount } ?: false
                Log.d("📦", "✅ Loaded ${result.data.size} transactions (page $page)")
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Transactions fetch failed: $responseCode")
                Result.failure(Exception("Transactions HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Transactions fetch error: ${e.message}")
            Result.failure(e)
        } finally {
            _isLoading.value = false
            _isLoadingMore.value = false
        }
    }
```

Los llamadores existentes ignoran el `Result` sin romper compilación.

- [ ] **Step 4: Conectar el gate en `TransactionsViewModel`**

1. Constructor: agregar `refreshGateFactory: RefreshGateFactory` (sin `private val`; solo se usa en init). Imports: `com.avoqado.pos.core.domain.refresh.RefreshGateFactory`, `kotlinx.coroutines.flow.drop`.
2. Debajo de los campos existentes:

```kotlin
    private val gate = refreshGateFactory.create(viewModelScope)

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    /** Guard §4.5 — dinero: con la devolución abierta o corriendo, ni el gesto refresca. */
    private fun workInProgress(): Boolean =
        _showRefundSheet.value || _refundState.value is RefundUiState.Loading

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> =
        repository.fetchTransactions(page = 1, search = _searchText.value.ifBlank { null })

    fun autoRefresh() {
        viewModelScope.launch {
            gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            _isManualRefreshing.value = true
            try {
                gate.run(workInProgress = ::workInProgress, manual = true, block = ::refreshNow)
            } finally {
                _isManualRefreshing.value = false
            }
        }
    }
```

3. `init`: borrar la llamada a `refresh()` (la carga inicial la dispara la UI vía el gate — Task 5).
4. `observeSearch()`: nueva identidad = invalidar (spec §4.4):

```kotlin
    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchText
                .debounce(400)
                .distinctUntilChanged()
                .drop(1) // la emisión inicial no es una búsqueda del usuario
                .collect {
                    gate.invalidate()
                    gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
                }
        }
    }
```

5. En `processUnassociatedRefund`, reemplazar `refresh()` por `gate.invalidate(); autoRefresh()` (mutación local = identidad nueva).
6. Dejar `fun refresh()` como shim para que `TransactionsScreen` siga compilando hasta Task 5:

```kotlin
    @Deprecated("Task 5 lo elimina: la UI dispara autoRefresh()/manualRefresh() vía el gate")
    fun refresh() = autoRefresh()
```

- [ ] **Step 5: Correr y ver verde + typecheck del módulo**

Run: `./gradlew :app:testDebugUnitTest --tests '*TransactionsViewModelRefreshTest*' --tests '*RefreshGateTest*' && ./gradlew :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL. **[punto de commit lógico: "feat(transactions): refreshNow tipado + RefreshGate en el ViewModel"]**

---

### Task 5: Transacciones Android — gesto + reemplazo del trigger ad-hoc

**Files:**
- Modify: `avoqado-android/app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionsScreen.kt` (composable `TransactionListPanel`, bloque `refreshKey` en :196-210 y `when` de la lista que termina en el `else ->` con `LazyColumn` en :371)
- Modify: `avoqado-android/app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionsViewModel.kt` (borrar el shim `refresh()`)

**Interfaces:**
- Consumes: `AvoqadoRefreshable` (Task 3); `autoRefresh()`, `manualRefresh()`, `isManualRefreshing` (Task 4).
- Produces: la pantalla terminada — patrón de referencia para las demás rebanadas.

- [ ] **Step 1: Reemplazar el trigger ad-hoc por los del spec §4.8**

Borrar COMPLETO el bloque de `TransactionListPanel` (líneas ~195-210): el `val refreshKey = remember { mutableIntStateOf(0) }`, su `LaunchedEffect(refreshKey.intValue)` y el `DisposableEffect` con el observer de `ON_RESUME`. En su lugar:

```kotlin
    // Disparadores del spec §4.8: entrada + regreso de background/re-selección de tab.
    // La doble llamada la absorbe el single-flight del gate, no el TTL.
    LaunchedEffect(Unit) { viewModel.autoRefresh() }
    LifecycleResumeEffect(Unit) {
        viewModel.autoRefresh()
        onPauseOrDispose { }
    }
```

Import: `androidx.lifecycle.compose.LifecycleResumeEffect` (el artefacto lifecycle-runtime-compose ya está — `LocalLifecycleOwner` se usa hoy en este mismo archivo). Borrar los imports que queden huérfanos (`mutableIntStateOf`, `DisposableEffect` si nadie más los usa).

- [ ] **Step 2: Envolver la lista con el gesto**

En `TransactionListPanel`, localizar el `when` que pinta skeleton/vacío/lista (su última rama es `else -> { val listState = rememberLazyListState() ... LazyColumn(...)` en ~:368-371) y envolverlo entero:

```kotlin
    val isManualRefreshing by viewModel.isManualRefreshing.collectAsState()

    AvoqadoRefreshable(
        isRefreshing = isManualRefreshing,
        onRefresh = viewModel::manualRefresh,
        modifier = Modifier.fillMaxSize(), // hereda el lugar que hoy ocupa el when en la Column
    ) {
        // ... el when existente, sin tocar sus ramas ...
    }
```

Regla del gesto en vacío (el caso nº1 real es "no veo la venta nueva"): si la rama de estado vacío no es desplazable, envolver su contenido en `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))` — `PullToRefreshBox` solo dispara sobre un hijo scrolleable. Import: `androidx.compose.foundation.verticalScroll` + `rememberScrollState`.

- [ ] **Step 3: Borrar el shim**

Quitar `fun refresh()` del ViewModel (ya no queda ningún llamador: el screen usa `autoRefresh`/`manualRefresh` y los internos usan el gate).

- [ ] **Step 4: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest --tests '*Transactions*'`
Expected: BUILD SUCCESSFUL + tests verdes. Smoke opcional en emulador: entrar a Ventas → jalar → spinner termina; con el sheet de devolución abierto, jalar NO recarga. **[punto de commit lógico: "feat(transactions): pull-to-refresh + revalidación por gate (Android)"]**

---

### Task 6: Transacciones iOS — mismo contrato, retrofit del `.refreshable` existente

**Files:**
- Modify: `avoqado-ios/avoqado-ios/Transactions/ViewModels/TransactionsViewModel.swift` (el `refresh()` de :119 se convierte en `refreshNow()`; el debounce de búsqueda pasa por `invalidate`)
- Modify: `avoqado-ios/avoqado-ios/Transactions/Views/TransactionsView.swift:27-29` (`.task`)
- Modify: `avoqado-ios/avoqado-ios/Transactions/Views/TransactionListView.swift:156-158` (`.refreshable`) y :12/:56 (`showRefundSheet`)
- Modify: `avoqado-ios/avoqado-ios/Transactions/Views/TransactionDetailView.swift:62/:92` (`showRefundSheet`)

**Interfaces:**
- Consumes: `RefreshGate` actor (Task 2).
- Produces: en `TransactionsViewModel` — `@Published var refundFlowActive: Bool`, `func refreshNow() async -> Bool`, `func autoRefresh()`, `func manualRefresh() async`, `func invalidateAndRefresh()`. `func refresh()` se ELIMINA (retrofit §4.8: el `.refreshable` existente migra al contrato, no convive con él).

- [ ] **Step 1: ViewModel — gate + contrato**

```swift
    private let gate = RefreshGate()

    /// Guard §4.5 — el @State de los sheets de devolución vive en las vistas;
    /// ellas lo espejan aquí con .onChange para que el guard lo alcance.
    @Published var refundFlowActive = false

    /// Contrato §4.2: reporta éxito/fallo, preserva la lista, y calla en fondo
    /// (errorMessage solo cuando no hay nada que mostrar — spec §6).
    func refreshNow() async -> Bool {
        let hadData = !transactions.isEmpty
        if !hadData { isLoading = true }
        errorMessage = nil
        defer { isLoading = false }
        do {
            let search = searchText.isEmpty ? nil : searchText
            let page = try await repository.fetchTransactions(
                page: 1,
                pageSize: pageSize,
                search: search
            )
            transactions = page.transactions
            hasMore = page.hasMore
            currentPage = 1
            return true
        } catch {
            if !hadData { errorMessage = ServerErrorText.humanize(error) }
            return false
        }
    }

    func autoRefresh() {
        Task { [weak self] in
            guard let self else { return }
            _ = await self.gate.run(
                workInProgress: { self.refundFlowActive },
                manual: false
            ) { await self.refreshNow() }
        }
    }

    func manualRefresh() async {
        _ = await gate.run(
            workInProgress: { self.refundFlowActive },
            manual: true
        ) { await self.refreshNow() }
    }

    /// Mutación local o búsqueda nueva = identidad nueva (spec §4.4).
    func invalidateAndRefresh() {
        Task { [weak self] in
            guard let self else { return }
            await self.gate.invalidate()
            _ = await self.gate.run(
                workInProgress: { self.refundFlowActive },
                manual: false
            ) { await self.refreshNow() }
        }
    }
```

Luego: (a) borrar el `func refresh() async` viejo de :119 (su cuerpo ya vive en `refreshNow`); (b) actualizar TODOS sus llamadores — listarlos con `rg -n 'refresh\(\)' avoqado-ios/avoqado-ios/Transactions` y mapear: entrada de pantalla → `autoRefresh()`, gesto → `manualRefresh()`, después de mutar datos (devolución procesada, debounce de búsqueda) → `invalidateAndRefresh()`. Nota: el cierre `workInProgress` lee una propiedad @MainActor desde el actor del gate — con el modo Swift 5 del proyecto compila (warning de concurrencia aceptado y documentado con `// swiftlint:disable:next` si el linter protesta); si el build lo trata como error, cambiar la propiedad a `nonisolated(unsafe) var refundFlowActive = false` y anotarlo en el reporte.

- [ ] **Step 2: Vistas — triggers y espejo del guard**

`TransactionsView.swift` (reemplaza el `.task` de :27-29):

```swift
    @Environment(\.scenePhase) private var scenePhase
    // ...
        .task { viewModel.autoRefresh() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { viewModel.autoRefresh() }
        }
```

`TransactionListView.swift`:

```swift
        .refreshable { await viewModel.manualRefresh() }          // :156, retrofit
        .onChange(of: showRefundSheet) { viewModel.refundFlowActive = $0 } // junto al .sheet de :56
```

`TransactionDetailView.swift` (junto al `.sheet` de :92):

```swift
        .onChange(of: showRefundSheet) { viewModel.refundFlowActive = $0 }
```

(En `TransactionDetailView` verificar cómo llega el ViewModel — si la vista no recibe `TransactionsViewModel`, pasarle el mismo objeto que ya usa la lista vía `@ObservedObject`/`@EnvironmentObject`, siguiendo el patrón con el que hoy se abre el detalle; el guard debe ser el MISMO objeto, no una copia.)

- [ ] **Step 3: Verificar**

Run: `xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build && xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:avoqado-iosTests/RefreshGateTests`
Expected: BUILD SUCCEEDED + tests verdes. **[punto de commit lógico iOS: "feat(transactions): retrofit de .refreshable al RefreshGate + guard de devolución"]**

---

### Task 7: Verificación integral y cierre de la rebanada

**Files:** ninguno nuevo — solo corridas y reporte.

- [ ] **Step 1: Chequeo de capacidad y suites**

```bash
sysctl -n hw.ncpu vm.loadavg && sysctl -n vm.swapusage
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew :app:testDebugUnitTest
```
Se corre AUNQUE la máquina esté cargada (solo avisar que tardará). Expected: suite del módulo verde, incluidos los tests preexistentes de otras pantallas.

- [ ] **Step 2: Build de las dos apps**

```bash
./gradlew assembleDebug   # pasa por chequeo de capacidad; nunca junto a otro build pesado PROPIO
cd ../avoqado-ios && xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```
Expected: ambos verdes. La paridad exige los DOS compilando antes de declarar la rebanada terminada.

- [ ] **Step 3: Reporte honesto**

Incluir en el reporte al founder: qué se verificó y qué NO (QA en hardware físico pendiente: gesto en tablet real, regreso de background con reloj, sheet de devolución + gesto, comportamiento con red intermitente — el spec §11 ya lo dejaba para hardware), el estado sin commit de ambos repos, y que las rebanadas Órdenes → Artículos → Informes → Inventario (ojo §10: despachar por `selectedSection`) siguen la misma receta con su fix §4.1 primero.
