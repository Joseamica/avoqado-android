package com.avoqado.pos.orders.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.model.estaAbierta
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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

/** Lo que este aparato sabe de UNA cuenta: un DETALLE bueno o su 404 (Codex r3 H1), o un cobro suyo que el servidor ya registró. */
sealed interface EvidenciaDelDetalle {
    data object NoExiste : EvidenciaDelDetalle
    data class Vigente(
        val abierta: Boolean,
        val saldoCents: Int?,
        val pagosCompletados: Int,
        val cobrosConSaldo: Int,
        val esCuentaDeTarjetas: Boolean,
    ) : EvidenciaDelDetalle

    /**
     * Final fix (I-2): este aparato cobró CON RED parte de la cuenta (tarjeta, saldo de regalo, efectivo…). Queda «con abono» (o «de
     * regalo» si [conSaldo]) y con el saldo que dijo el servidor (null = no lo dijo ⇒ «sin saldo»): gris sin red, nunca cobrable.
     */
    data class Abono(val saldoCents: Int?, val conSaldo: Boolean) : EvidenciaDelDetalle
}

/**
 * D12: copia en disco, POR SUCURSAL, de las cuentas abiertas de «Pedidos» (también las grises, para verlas con su razón).
 * - Cada fila caduca a las 24 h de vista; las vencidas no se ofrecen y se BORRAN del disco en la siguiente escritura.
 * - Turnos (Codex r3 H1): por `venue|orderId` se recuerda en memoria el turno de la última evidencia aplicada (también de un
 *   retiro); una evidencia de turno menor o igual se descarta. Así una respuesta vieja que llega tarde no resucita ni pisa a
 *   una más nueva; y una lectura más vieja que la última lista de la sucursal no mete cuentas nuevas. Tras reiniciar el
 *   proceso no hay respuestas viejas en vuelo: lo de disco cede ante cualquier turno nuevo.
 * - Final fix (F1, Codex #2): la copia nunca sabe menos que el aparato. Lo que llega se anota en memoria ANTES de tocar el disco
 *   (anotarlo no falla, ni con la cancelación de quien llama) y toda lectura lo aplica primero: si el disco no se deja leer, no hay
 *   nada que enseñar ni que cobrar; si no se deja escribir, lo nuevo manda desde la memoria hasta que se pueda guardar.
 * - Nunca lanza (salvo la cancelación de quien llama).
 */
@Singleton
class CopiaDeCuentas @Inject constructor(
    private val cache: PayloadCache,
    /** Final fix ronda 2 (R2-1): los cobros no efectivo de este aparato que aún no se aplican. Hilt (y Guice) siempre la pasan. */
    private val cuentasCobradas: CuentasCobradasAqui? = null,
) {
    internal var ahora: () -> Long = System::currentTimeMillis

    /** Seam de pruebas: dónde se lee, decodifica y guarda la copia (nunca en el hilo principal). */
    internal var io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val ultimoTurno = AtomicLong()

    // ponytail: un renglón por cuenta vista en la vida del proceso (KB por miles); podar si algún día pesa.
    private val turnos = HashMap<String, Long>()

    /** Por sucursal, el turno de la última lista que retira ausentes (PRIMERA_PAGINA o LISTA_COMPLETA) ya aplicada. */
    private val ultimaLista = HashMap<String, Long>()

    /**
     * Lo que llegó y todavía no se aplica, por sucursal y en orden de llegada. Menor 3 (ronda 2): bajo su PROPIO candado de JVM, no el
     * de la copia: anotar nunca suspende ni espera al disco, así que lo anotado entra antes que cualquier lectura posterior.
     */
    private val pendientes = HashMap<String, MutableList<Anotada>>()

    /** Las sucursales cuya cola pasó el tope con el disco ilegible: su copia vuelve a empezar vacía (bajo el candado de [pendientes]). */
    private val empiezanVacias = HashSet<String>()

    /** La copia más nueva de una sucursal que no llegó al disco: manda sobre él hasta que se guarde. */
    private val sinGuardar = HashMap<String, List<CuentaGuardada>>()

    private class Anotada(
        val turno: Long,
        val esLista: Boolean,
        val aplicar: (filas: MutableMap<String, CuentaGuardada>, ultimaLista: Long, gana: (orderId: String) -> Boolean) -> Unit,
    )

    /** Turno MONÓTONO del proceso: se toma cuando la petición SALE. */
    fun abrirLectura(): Long = ultimoTurno.incrementAndGet()

    suspend fun alLeer(venueId: String, cuentas: List<OrderSummary>, lectura: Lectura, turno: Long = abrirLectura()) {
        val vista = ahora()
        anotar(venueId, Anotada(turno, esLista = lectura != Lectura.SUELTA) { filas, ultimaLista, gana ->
            // Revisión T4 #1: una lista más nueva ya se aplicó y no trajo esta cuenta (quizá ya se cobró). Una respuesta más vieja
            // no mete cuentas NUEVAS a la copia: lo peor es que falte hasta la siguiente lectura, nunca que se cobre de más.
            // ponytail: también frena las que la lista nueva no podía ver (más viejas que su página); bastaría con su ventana.
            val metenNuevas = turno > ultimaLista
            for (c in cuentas) if (gana(c.id)) {
                when {
                    !estaAbierta(c.status, c.paymentStatus, c.refundState) -> filas.remove(c.id)
                    c.id in filas || metenNuevas -> filas[c.id] = CuentaGuardada(c, vista)
                }
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
        })
    }

    /** Un detalle bueno (o un cobro de este aparato) actualiza o retira la fila que ya está; nunca agrega una (la lista trae origen y fecha). */
    suspend fun alLeerDetalle(venueId: String, orderId: String, evidencia: EvidenciaDelDetalle, turno: Long = abrirLectura()) =
        anotar(venueId, deDetalle(orderId, evidencia, turno, ahora()))

    private fun deDetalle(orderId: String, evidencia: EvidenciaDelDetalle, turno: Long, vista: Long) =
        Anotada(turno, esLista = false) { filas, _, gana ->
            if (!gana(orderId)) return@Anotada
            val fila = filas[orderId]?.cuenta
            when {
                evidencia is EvidenciaDelDetalle.NoExiste ||
                    evidencia is EvidenciaDelDetalle.Vigente && !evidencia.abierta -> filas.remove(orderId)
                fila == null -> Unit
                evidencia is EvidenciaDelDetalle.Vigente -> filas[orderId] = CuentaGuardada(fila.copy(
                    saldoPendienteCents = evidencia.saldoCents,
                    pagosCompletados = evidencia.pagosCompletados,
                    cobrosConSaldo = evidencia.cobrosConSaldo,
                    esCuentaDeTarjetas = evidencia.esCuentaDeTarjetas,
                ), vista)
                evidencia is EvidenciaDelDetalle.Abono -> filas[orderId] = CuentaGuardada(fila.copy(
                    saldoPendienteCents = evidencia.saldoCents,
                    pagosCompletados = (fila.pagosCompletados ?: 0) + 1,
                    cobrosConSaldo = if (evidencia.conSaldo) (fila.cobrosConSaldo ?: 0) + 1 else fila.cobrosConSaldo,
                ), vista)
            }
        }

    /**
     * Menor 3 (ronda 2): anota lo que este aparato acaba de saber de una cuenta SIN suspender (en el hilo de quien llama, en orden):
     * cualquier lectura posterior ya lo aplica. Guardarlo en disco lo hace [aplicarAnotado], fuera del hilo principal.
     */
    fun anotarYa(venueId: String, orderId: String, evidencia: EvidenciaDelDetalle, turno: Long = abrirLectura()) =
        anotarEnMemoria(venueId, deDetalle(orderId, evidencia, turno, ahora()))

    /** Aplica y guarda lo anotado de [venueId] (lee, decodifica y escribe por [io]). Nunca lanza, salvo la cancelación. */
    suspend fun aplicarAnotado(venueId: String) {
        leer(venueId)
    }

    /** Las vigentes de esa sucursal (más nueva primero, como el servidor). */
    suspend fun cuentas(venueId: String): List<CuentaGuardada> = leer(venueId)

    suspend fun cuenta(venueId: String, orderId: String): CuentaGuardada? = leer(venueId).firstOrNull { it.cuenta.id == orderId }

    /** Para enseñar (y cobrar): con todo lo que ya llegó aplicado; si el disco no se deja leer, no hay copia que ofrecer. */
    private suspend fun leer(venueId: String): List<CuentaGuardada> = try {
        aplicar(venueId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ no se pudo leer la copia de $venueId: ${e.message}")
        emptyList()
    }

    /** Anota lo que llegó (sin poder fallar ni cortarse) y trata de aplicarlo; si el disco no se deja leer, se aplica en la siguiente lectura. */
    private suspend fun anotar(venueId: String, a: Anotada) {
        anotarEnMemoria(venueId, a)
        try {
            aplicar(venueId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo leer la copia de $venueId; se aplica en la siguiente lectura: ${e.message}")
        }
    }

    private fun anotarEnMemoria(venueId: String, a: Anotada) = synchronized(pendientes) {
        val cola = pendientes.getOrPut(venueId, ::mutableListOf)
        // ponytail: tope de lo anotado con el disco ilegible. Al pasarlo la copia de la sucursal vuelve a empezar vacía (se ofrece de
        // menos, nunca de más) en vez de crecer sin fin.
        if (cola.size >= MAX_PENDIENTES) {
            Log.w(TAG, "⚠️ el disco lleva $MAX_PENDIENTES lecturas sin leerse: la copia de $venueId vuelve a empezar")
            cola.clear()
            empiezanVacias += venueId
        }
        cola += a
    }

    /**
     * Aplica lo anotado sobre la copia (la de memoria si no se pudo guardar, o la del disco) y regresa las vigentes. LANZA si el
     * disco no se deja leer (revisión T4 #2: «no se pudo leer» no es «no hay copia»): lo anotado se queda para la siguiente. Una
     * copia ilegible sí cuenta como vacía: no hay nada que conservar, y sin reemplazarla la copia de esa sucursal no volvería a servir.
     */
    private suspend fun aplicar(venueId: String): List<CuentaGuardada> = mutex.withLock { withContext(io) { aplicarYa(venueId) } }

    /** El cuerpo de [aplicar], ya con el candado y en [io] (menor 3: leer Room, decodificar y guardar no van en el hilo principal). */
    private suspend fun aplicarYa(venueId: String): List<CuentaGuardada> {
        if (synchronized(pendientes) { empiezanVacias.remove(venueId) }) sinGuardar[venueId] = emptyList()
        val base = sinGuardar[venueId] ?: leerDelDisco(venueId)
        // Final fix ronda 2 (R2-1): lo que ESTE aparato cobró sin efectivo (tarjeta, saldo de regalo) y la copia aún no sabe. Va después
        // de lo anotado y con turnos nuevos: es lo más nuevo que el aparato sabe de esas cuentas. Si no se puede leer, LANZA (falla cerrado).
        val marcas = cuentasCobradas?.marcasDe(venueId).orEmpty()
        val cola = synchronized(pendientes) { pendientes.remove(venueId).orEmpty() } +
            marcas.map { deDetalle(it.orderId, it.comoEvidencia(), abrirLectura(), ahora()) }
        val filas = base.filter { it.vistaEn >= ahora() - VIGENCIA_MS }.associateByTo(LinkedHashMap()) { it.cuenta.id }
        val antes = HashMap(filas)
        val ganados = HashMap<String, Long>()
        var lista = ultimaLista[venueId] ?: Long.MIN_VALUE
        for (a in cola) {
            a.aplicar(filas, lista) { orderId ->
                val llave = "$venueId|$orderId"
                (a.turno > (ganados[llave] ?: turnos[llave] ?: Long.MIN_VALUE)).also { if (it) ganados[llave] = a.turno }
            }
            if (a.esLista) lista = maxOf(lista, a.turno)
        }
        val orden = filas.values.sortedByDescending { VenueDateTimeFormatter.parseIso(it.cuenta.createdAt) }
        // Lo aplicado ya cuenta (en el disco o en la memoria): sus turnos también. Lo que no cambió nada no reescribe el disco.
        withContext(NonCancellable) {
            turnos.putAll(ganados)
            if (lista != Long.MIN_VALUE) ultimaLista[venueId] = lista
            val enDisco = if (filas != antes || venueId in sinGuardar) {
                try {
                    cache.saveDurable(PayloadCache.TYPE_COPIA_DE_PEDIDOS, venueId, json.encodeToString(orden))
                    sinGuardar.remove(venueId)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ no se pudo guardar la copia de $venueId; manda la de memoria: ${e.message}")
                    sinGuardar[venueId] = orden
                    false
                }
            } else {
                true
            }
            // R2-1: las marcas se sueltan sólo cuando lo que dicen ya está en el disco de la copia (si no, se vuelven a aplicar).
            if (enDisco && marcas.isNotEmpty()) runCatching { cuentasCobradas?.olvidar(marcas) }
        }
        return orden
    }

    private suspend fun leerDelDisco(venueId: String): List<CuentaGuardada> {
        val guardado = cache.loadDurable(PayloadCache.TYPE_COPIA_DE_PEDIDOS, venueId) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CuentaGuardada>>(guardado.json) }
            .getOrElse { Log.w(TAG, "⚠️ copia ilegible de $venueId: ${it.message}"); emptyList() }
    }

    private companion object {
        const val TAG = "CopiaDeCuentas"
        const val VIGENCIA_MS = 24 * 60 * 60 * 1000L
        const val MAX_PENDIENTES = 200
    }
}
