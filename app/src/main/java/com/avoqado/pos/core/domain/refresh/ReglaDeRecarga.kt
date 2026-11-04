package com.avoqado.pos.core.domain.refresh

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que una pantalla guarda junto a sus cargas (no la regla): sobrevive a las pausas porque es de los datos, no de la
 * vigilancia.
 */
data class RegistroDeCargas(
    /** Lo que se ve se pidió (o se vio) con algo en la cola sin red: puede no traerlo. */
    val conPendientes: Boolean = false,
    /** Cargas fallidas desde la última buena, vengan de donde vengan (el gesto, una búsqueda, la regla). */
    val fallosSeguidos: Int = 0,
    /** Cuándo falló la última, en el reloj de la regla. */
    val ultimoFalloEn: Long? = null,
)

/** Una pantalla que sólo lee del servidor (Ventas, Informes), vista desde la regla de recarga. */
data class EstadoParaRecargar(
    /** «Sin conexión» o «No se pudo cargar»: no hay datos de la consulta vigente. */
    val noSeCargo: Boolean,
    /** Enseña datos de la consulta vigente (también una lista o un informe vacíos que el servidor sí mandó). */
    val conDatos: Boolean,
    /** Hay una carga en curso. */
    val cargando: Boolean,
    val cargas: RegistroDeCargas,
)

interface PantallaQueSeRecarga {
    /** Su estado, en vivo. */
    val estadoParaRecargar: Flow<EstadoParaRecargar>

    /**
     * Recarga por el gate sin forzarlo (`manual = false`, vuelo único). La espera entre intentos la lleva la regla, así
     * que reinicia el enfriamiento del gate e invalida su TTL.
     */
    suspend fun recargar(): RefreshOutcome

    /** Lo que se ve no trae lo que está en la cola sin red. */
    fun marcarConPendientes()
}

/** La bitácora de cargas de una pantalla: envuelve su `refreshNow`, la pida quien la pida. */
class CargasDeLaPantalla(private val regla: ReglaDeRecarga?) {
    private val _registro = MutableStateFlow(RegistroDeCargas())
    val registro: StateFlow<RegistroDeCargas> = _registro.asStateFlow()

    suspend fun cargar(bloque: suspend () -> Result<Unit>): Result<Unit> {
        val alPedir = regla?.porSubirAhora() ?: 0
        val resultado = try {
            bloque()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
        if (resultado.isSuccess) {
            // Si había algo por subir al pedir, o lo hay al terminar, lo que llegó puede no traerlo.
            val alTerminar = regla?.porSubirAhora() ?: 0
            _registro.value = RegistroDeCargas(conPendientes = alPedir > 0 || alTerminar > 0)
        } else {
            _registro.update { it.copy(fallosSeguidos = it.fallosSeguidos + 1, ultimoFalloEn = regla?.ahora()) }
        }
        return resultado
    }

    fun marcarConPendientes() {
        _registro.update { it.copy(conPendientes = true) }
    }
}

/**
 * La regla de recarga automática de Ventas e Informes (D18 r4, 3-nov-2026). Por NIVEL, no por flancos: Codex tumbó los
 * flancos tres rondas seguidas (r2: H1 a H3; r3: N1 el vaciado de la cola durante un parpadeo del servidor, N2 el servidor
 * que vuelve a mitad de la carga, N3 un fallo tardío que pisaba el rearme). Corre sólo mientras la pantalla se ve —dentro
 * de `repeatOnLifecycle(RESUMED)`— y se evalúa cada vez que cambia una entrada y con su propio reloj:
 *
 * - **Hace falta recargar** si la pantalla está en «no se cargó», o si lo que se ve se pidió con cobros por subir y la
 *   cola de la sucursal ya está en 0.
 * - Si hace falta, el aparato tiene red (`isConnected`) y no hay carga en curso, recarga. Entre intentos espera 5 s,
 *   10 s, 20 s y después 30 s, contando las cargas fallidas de la pantalla vengan de donde vengan; una carga buena la
 *   reinicia, y una carga buena con la cola en 0 apaga la necesidad.
 * - 🔴 La bandera del servidor NO se usa: nuestro propio fallo la apaga (`ConnectivityInterceptor`) y el ping la vuelve a
 *   prender. Internet que vuelve con el WiFi siempre arriba (y WINDOWS, donde la red del aparato no baja) se recupera con
 *   el siguiente reintento.
 * - Con datos a la vista y algo en la cola, marca que lo que se ve no lo trae: al vaciarse la cola, recarga.
 *
 * No recuerda nada entre pausas: al volver a la pantalla se recalcula todo con lo que guardó la pantalla.
 */
@Singleton
class ReglaDeRecarga internal constructor(
    private val conectividad: ConnectivityMonitor,
    private val porSubir: Flow<Int>,
    private val reloj: () -> Long,
) {
    @Inject
    constructor(
        connectivityMonitor: ConnectivityMonitor,
        cobros: PendingPaymentDao,
        operaciones: SyncOutbox,
        secureStorage: SecureStorage,
    ) : this(
        conectividad = connectivityMonitor,
        // La sucursal se lee al empezar a mirar, no al construir: es la de la sesión de ese momento.
        porSubir = flow {
            emitAll(combine(cobros.observarPorSubir(secureStorage.venueId.orEmpty()), operaciones.pendingCount) { c, o -> c + o })
        },
        reloj = { System.nanoTime() / 1_000_000 },
    )

    internal fun ahora(): Long = reloj()

    /** Lo que falta por subir de la sucursal ahora mismo (cobros en cola o subiendo, más la cola de operaciones). */
    suspend fun porSubirAhora(): Int = porSubir.first()

    private data class Entradas(val pantalla: EstadoParaRecargar, val conRed: Boolean, val porSubir: Int)

    /** Mientras la pantalla se ve. Nunca termina sola: la cancela el `repeatOnLifecycle` al pausar. */
    suspend fun mientrasSeVe(pantalla: PantallaQueSeRecarga): Unit = coroutineScope {
        val entradas = combine(
            pantalla.estadoParaRecargar,
            conectividad.isConnected,
            porSubir.distinctUntilChanged(),
        ) { estado, conRed, pendientes -> Entradas(estado, conRed, pendientes) }.stateIn(this)
        while (true) {
            val e = entradas.value
            val p = e.pantalla
            when {
                p.conDatos && e.porSubir > 0 && !p.cargas.conPendientes -> {
                    pantalla.marcarConPendientes()
                    esperarCambio(entradas, e, ESPERA_MAXIMA_MS)
                }
                !haceFalta(e) || !e.conRed || p.cargando -> esperarCambio(entradas, e, null)
                else -> {
                    val falta = faltaParaIntentar(p.cargas)
                    if (falta > 0) {
                        esperarCambio(entradas, e, falta)
                    } else {
                        pantalla.recargar()
                        // Lo que dejó la carga llega por las entradas; si no cambió nada (el gate la saltó), no se insiste.
                        esperarCambio(entradas, e, ESPERA_MAXIMA_MS)
                    }
                }
            }
        }
    }

    private fun haceFalta(e: Entradas): Boolean =
        e.pantalla.noSeCargo || (e.pantalla.conDatos && e.pantalla.cargas.conPendientes && e.porSubir == 0)

    private fun faltaParaIntentar(cargas: RegistroDeCargas): Long {
        val ultimo = cargas.ultimoFalloEn ?: return 0
        if (cargas.fallosSeguidos == 0) return 0
        return ultimo + esperaTras(cargas.fallosSeguidos) - ahora()
    }

    private suspend fun esperarCambio(entradas: StateFlow<Entradas>, vistas: Entradas, hastaMs: Long?) {
        if (hastaMs == null) {
            entradas.first { it != vistas }
        } else {
            withTimeoutOrNull(hastaMs) { entradas.first { it != vistas } }
        }
    }

    companion object {
        /** 5 s, 10 s, 20 s y después cada 30 s. */
        internal fun esperaTras(fallosSeguidos: Int): Long = when (fallosSeguidos) {
            0 -> 0L
            1 -> 5_000L
            2 -> 10_000L
            3 -> 20_000L
            else -> ESPERA_MAXIMA_MS
        }

        internal const val ESPERA_MAXIMA_MS = 30_000L
    }
}
