package com.avoqado.pos.core.data.lan

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.avoqado.pos.tables.data.TableSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MesasDeEstaCaja"

/** Un intento de abrir una mesa LIBRE (spec §3.1). Su único dueño es quien lo abrió; se termina por [id], nunca por mesa. */
data class Apertura(val id: String, val tableId: String, val staffId: String, val staffName: String, val venueId: String, val creadaEnMs: Long)

/**
 * El número de VIDA del proceso (spec §3.2): al primer uso, lo guardado + 1, escrito con `commit()` ANTES del primer mensaje. Así un
 * mensaje de una vida anterior siempre pierde, aunque la anterior muriera sin mandar nada. Si no se puede guardar, `null`: la caja no
 * declara presencia y trabaja como isla.
 */
@Singleton
class ContadorDeVidas internal constructor(private val leer: () -> Long, private val guardar: (Long) -> Boolean) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences("hub_presencia", Context.MODE_PRIVATE))

    private constructor(prefs: SharedPreferences) : this({ prefs.getLong("gen", 0L) }, { prefs.edit().putLong("gen", it).commit() })

    val vida: Long? by lazy {
        runCatching { (leer() + 1).takeIf(guardar) }.getOrNull()
            ?: run { Log.w(TAG, "🏝️ No se pudo guardar el número de vida: esta caja no declara presencia (isla)"); null }
    }
}

/**
 * Objeto del PROCESO con lo que esta caja declara al Hub (spec §3.1–3.2): su mesa actual (de [TableSession]) y sus aperturas con
 * dueño, más los contadores `seq` y época. Vive fuera del coordinador para que un rearme no lo pierda. Lo tocan la UI (Main) y el
 * reconciliador (IO): todo el estado de las aperturas va en un `MutableStateFlow` que se cambia con `update` (atómico).
 */
@Singleton
class MesasDeEstaCaja internal constructor(
    private val mesaActual: () -> String?,
    cambiosDeMesa: Flow<String?>,
    private val vidas: ContadorDeVidas,
    private val relojMs: () -> Long,
) {
    @Inject constructor(tableSession: TableSession, vidas: ContadorDeVidas) : this(
        { tableSession.current()?.tableId },
        tableSession.active.map { it?.tableId },
        vidas,
        { System.nanoTime() / 1_000_000 },
    )

    /**
     * Lo que esta caja le dice al árbitro: las mesas donde está y sus aperturas vivas. Anidada porque el paquete ya tiene la
     * `Declaracion` de D12 (`RegistroDeCuentas`).
     */
    data class Declaracion(val mesas: Set<String>, val aperturas: List<Apertura>)

    /** Un ticket vivo: su apertura, la PRIMERA resolución (null = pendiente) y si el árbitro pidió esperar. */
    private data class Ticket(val apertura: Apertura, val resultado: LeaseOutcome? = null, val esperando: Boolean = false)

    /** id → ticket, en orden de apertura. */
    private val tickets = MutableStateFlow<Map<String, Ticket>>(emptyMap())
    private val seq = AtomicLong(0)
    private val epoca = AtomicLong(0)

    val gen: Long? get() = vidas.vida

    fun siguienteSeq(): Long = seq.incrementAndGet()
    fun epoca(): Long = epoca.get()
    fun nuevaEpoca(): Long = epoca.incrementAndGet()

    /** Suena cuando cambia la mesa actual o el conjunto de aperturas; una resolución no cambia lo declarado. */
    val cambios: Flow<Unit> = combine(
        cambiosDeMesa.distinctUntilChanged(),
        tickets.map { it.keys }.distinctUntilChanged(),
    ) { _, _ -> }

    /**
     * PRIMERO la foto de aperturas, DESPUÉS la mesa actual (Codex plan r2 R2-1): el éxito arranca la sesión y luego termina el ticket,
     * así que en este orden la mesa sale por una de las dos. Al revés podía no salir por ninguna.
     */
    fun declaracion(venueId: String): Declaracion {
        val aperturas = tickets.value.values.map { it.apertura }.filter { it.venueId == venueId }
        return Declaracion(setOfNotNull(mesaActual()), aperturas)
    }

    fun abrirApertura(tableId: String, staffId: String, staffName: String, venueId: String): Apertura {
        val a = Apertura(UUID.randomUUID().toString(), tableId, staffId, staffName, venueId, relojMs())
        tickets.update { it + (a.id to Ticket(a)) }
        return a
    }

    /** Idempotente. Un ticket terminado no revive ni con una resolución tardía; su espera da `NoHub`. */
    fun terminarApertura(a: Apertura) = tickets.update { it - a.id }

    fun marcarEsperando(id: String) = cambiar(id) { if (it.resultado == null) it.copy(esperando = true) else it }

    /** Sólo la PRIMERA resolución de un id vivo cuenta. No quita el ticket: sólo su dueño lo termina. */
    fun resolver(id: String, resultado: LeaseOutcome) = cambiar(id) { it.copy(resultado = it.resultado ?: resultado) }

    fun resolverPendientes(venueId: String, resultado: LeaseOutcome) = tickets.update { m ->
        m.mapValues { (_, t) -> if (t.apertura.venueId == venueId && t.resultado == null) t.copy(resultado = resultado) else t }
    }

    /** Cambio de sucursal: las aperturas de otro venue se sueltan (su espera da `NoHub`). */
    fun fijarVenue(venueId: String) = tickets.update { m -> m.filterValues { it.apertura.venueId == venueId } }

    /** Lo usa `stop()`: suelta TODAS las aperturas de ese venue (su espera da `NoHub`). */
    fun soltarAperturas(venueId: String) = tickets.update { m -> m.filterValues { it.apertura.venueId != venueId } }

    /**
     * Espera la resolución de [a]. `NoHub` (isla) si el ticket ya no está, si vencen [PLAZO_PEDIR_MESA_MS] contados desde
     * `a.creadaEnMs` o si [sigue] da `false` (se revisa cada 250 ms). [alEsperar] suena una sola vez, cuando el árbitro pide esperar.
     */
    suspend fun esperar(a: Apertura, alEsperar: () -> Unit = {}, sigue: () -> Boolean): LeaseOutcome {
        var avisado = false
        while (true) {
            val t = tickets.value[a.id] ?: return LeaseOutcome.NoHub
            t.resultado?.let { return it }
            if (t.esperando && !avisado) { avisado = true; alEsperar() }
            val restante = a.creadaEnMs + PLAZO_PEDIR_MESA_MS - relojMs()
            if (restante <= 0 || !sigue()) return LeaseOutcome.NoHub
            withTimeoutOrNull(minOf(restante, REVISAR_SIGUE_MS)) { tickets.first { it[a.id] != t } }
        }
    }

    private fun cambiar(id: String, f: (Ticket) -> Ticket) = tickets.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    companion object {
        const val PLAZO_PEDIR_MESA_MS = 15_000L
        private const val REVISAR_SIGUE_MS = 250L
    }
}
