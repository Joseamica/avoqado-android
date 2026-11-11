package com.avoqado.pos.orders.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.model.estaAbierta
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// MARK: - Por qué una cuenta abierta NO se ofrece sin red (D12)

/** La razón de cada gris. iOS espeja estos nombres por nombre EXACTO. */
enum class RazonSinRed { NO_ES_DE_MOSTRADOR, TARJETAS_DE_REGALO, SIN_SALDO_DEL_SERVIDOR, SIN_SALDO, CON_ABONO }

private val ORIGENES_DE_MOSTRADOR = setOf("AVOQADO_ANDROID", "AVOQADO_IOS", "POS")

/** Compuerta de abonos: el conteo del servidor manda; un servidor sin el conteo cae a `PARTIAL`. */
fun OrderSummary.tieneAbono(): Boolean = pagosCompletados?.let { it > 0 } ?: paymentStatus.equals("PARTIAL", ignoreCase = true)

/** null = se puede ofrecer sin red. La primera razón que aplica gana; la elegibilidad sale de la FILA, nunca del detalle. */
fun razonParaNoCobrar(c: OrderSummary): RazonSinRed? = when {
    c.esDeMesa != false || c.esDeVales == true || c.source !in ORIGENES_DE_MOSTRADOR -> RazonSinRed.NO_ES_DE_MOSTRADOR
    c.esCuentaDeTarjetas || (c.cobrosConSaldo ?: 0) >= 1 -> RazonSinRed.TARJETAS_DE_REGALO
    c.saldoPendienteCents == null -> RazonSinRed.SIN_SALDO_DEL_SERVIDOR
    c.saldoPendienteCents <= 0 -> RazonSinRed.SIN_SALDO
    c.tieneAbono() -> RazonSinRed.CON_ABONO
    else -> null
}

fun esCobrableSinRed(c: OrderSummary): Boolean = estaAbierta(c.status, c.paymentStatus, c.refundState) && razonParaNoCobrar(c) == null

// MARK: - La copia

@Serializable
data class CuentaGuardada(val cuenta: OrderSummary, val vistaEn: Long)

/** Cuánto prueba una lectura de la lista (la decide `OrdersRepository`). */
enum class Lectura { SUELTA, PRIMERA_PAGINA, LISTA_COMPLETA }

/** Lo que un DETALLE bueno (o su 404) sabe de la cuenta (Codex r3 H1). */
sealed interface EvidenciaDelDetalle {
    data object NoExiste : EvidenciaDelDetalle
    data class Vigente(
        val abierta: Boolean,
        val saldoCents: Int?,
        val pagosCompletados: Int,
        val cobrosConSaldo: Int,
        val esCuentaDeTarjetas: Boolean,
    ) : EvidenciaDelDetalle
}

/**
 * D12: copia en disco, POR SUCURSAL, de las cuentas abiertas de «Pedidos» (también las grises, para verlas con su razón).
 * - Cada fila caduca a las 24 h de vista; las vencidas no se ofrecen y se BORRAN del disco en la siguiente escritura.
 * - Turnos (Codex r3 H1): por `venue|orderId` se recuerda en memoria el turno de la última evidencia aplicada (también de un
 *   retiro); una evidencia de turno menor o igual se descarta. Así una respuesta vieja que llega tarde no resucita ni pisa a
 *   una más nueva. Tras reiniciar el proceso no hay respuestas viejas en vuelo: lo de disco cede ante cualquier turno nuevo.
 * - Nunca lanza: si no puede escribir, deja la copia anterior.
 */
@Singleton
class CopiaDeCuentas @Inject constructor(private val cache: PayloadCache) {
    internal var ahora: () -> Long = System::currentTimeMillis
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val ultimoTurno = AtomicLong()

    // ponytail: un renglón por cuenta vista en la vida del proceso (KB por miles); podar si algún día pesa.
    private val turnos = HashMap<String, Long>()

    /** Turno MONÓTONO del proceso: se toma cuando la petición SALE. */
    fun abrirLectura(): Long = ultimoTurno.incrementAndGet()

    suspend fun alLeer(venueId: String, cuentas: List<OrderSummary>, lectura: Lectura, turno: Long = abrirLectura()) =
        editar(venueId, turno) { filas, gana ->
            for (c in cuentas) if (gana(c.id)) {
                if (estaAbierta(c.status, c.paymentStatus, c.refundState)) filas[c.id] = CuentaGuardada(c, ahora()) else filas.remove(c.id)
            }
            val vinieron = cuentas.mapTo(HashSet()) { it.id }
            val frontera = cuentas.mapNotNull { VenueDateTimeFormatter.parseIso(it.createdAt) }.minOrNull()
            val ausentes = filas.values.filter { g ->
                g.cuenta.id !in vinieron && when (lectura) {
                    Lectura.SUELTA -> false
                    Lectura.LISTA_COMPLETA -> true
                    // `>` estricto (Codex r3 H2): el servidor ordena sólo por createdAt; un empate con la frontera puede estar en la página 2.
                    Lectura.PRIMERA_PAGINA -> frontera != null && VenueDateTimeFormatter.parseIso(g.cuenta.createdAt)?.isAfter(frontera) == true
                }
            }
            for (g in ausentes) if (gana(g.cuenta.id)) filas.remove(g.cuenta.id)
        }

    /** Un detalle bueno actualiza o retira la fila que ya está; nunca agrega una (la lista trae origen y fecha). */
    suspend fun alLeerDetalle(venueId: String, orderId: String, evidencia: EvidenciaDelDetalle, turno: Long = abrirLectura()) =
        editar(venueId, turno) { filas, gana ->
            if (!gana(orderId)) return@editar
            val fila = filas[orderId]
            if (evidencia !is EvidenciaDelDetalle.Vigente || !evidencia.abierta) {
                filas.remove(orderId)
            } else if (fila != null) {
                filas[orderId] = CuentaGuardada(
                    fila.cuenta.copy(
                        saldoPendienteCents = evidencia.saldoCents,
                        pagosCompletados = evidencia.pagosCompletados,
                        cobrosConSaldo = evidencia.cobrosConSaldo,
                        esCuentaDeTarjetas = evidencia.esCuentaDeTarjetas,
                    ),
                    ahora(),
                )
            }
        }

    /** Las vigentes de esa sucursal (más nueva primero, como el servidor). */
    suspend fun cuentas(venueId: String): List<CuentaGuardada> = leer(venueId)

    suspend fun cuenta(venueId: String, orderId: String): CuentaGuardada? = leer(venueId).firstOrNull { it.cuenta.id == orderId }

    private suspend fun leer(venueId: String): List<CuentaGuardada> {
        val guardado = cache.load(PayloadCache.TYPE_COPIA_DE_PEDIDOS, venueId) ?: return emptyList()
        val desde = ahora() - VIGENCIA_MS
        return runCatching { json.decodeFromString<List<CuentaGuardada>>(guardado.json) }
            .getOrElse { Log.w(TAG, "⚠️ copia ilegible de $venueId: ${it.message}"); emptyList() }
            .filter { it.vistaEn >= desde }
    }

    private suspend fun editar(
        venueId: String,
        turno: Long,
        cambiar: (filas: MutableMap<String, CuentaGuardada>, gana: (orderId: String) -> Boolean) -> Unit,
    ): Unit = mutex.withLock {
        val ganados = HashMap<String, Long>()
        val gana = { orderId: String ->
            val llave = "$venueId|$orderId"
            (turno > (ganados[llave] ?: turnos[llave] ?: Long.MIN_VALUE)).also { if (it) ganados[llave] = turno }
        }
        val filas = leer(venueId).associateByTo(LinkedHashMap()) { it.cuenta.id }
        cambiar(filas, gana)
        try {
            // Disco y turnos van juntos: un turno sólo cuenta como aplicado si su evidencia llegó al disco.
            withContext(NonCancellable) {
                val orden = filas.values.sortedByDescending { VenueDateTimeFormatter.parseIso(it.cuenta.createdAt) }
                cache.saveDurable(PayloadCache.TYPE_COPIA_DE_PEDIDOS, venueId, json.encodeToString(orden))
                turnos.putAll(ganados)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo guardar la copia de $venueId; se queda la anterior: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "CopiaDeCuentas"
        const val VIGENCIA_MS = 24 * 60 * 60 * 1000L
    }
}
