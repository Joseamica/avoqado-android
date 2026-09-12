package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.DesenlaceDeCancelacion
import com.avoqado.pos.payment.domain.PasoTrasBorrar
import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** El transporte de la cancelación durable. Inyectable para poder probar el ORDEN sin red. */
interface CancelacionDeCobroTransport {
    suspend fun pedirCancelacion(venueId: String, terminalId: String, requestId: String): RespuestaDeCancelacion
    suspend fun consultarEstado(venueId: String, requestId: String): ConsultaDeEstado
    suspend fun cancelarOrden(venueId: String, orderId: String): ResultadoDeCancelarOrden

    /** Suelta la llave durable del cobro SÓLO si es la de esta solicitud. */
    suspend fun soltarLlaveSiEs(requestId: String)

    /** Deja el cobro cargado en la llave durable para que la próxima venta lo muestre. */
    suspend fun armarLlaveSiLibre(requestId: String): Boolean
}

/** Lo que la pantalla necesita saber de una cancelación. */
sealed class EstadoDeCancelacion {
    data object EnCurso : EstadoDeCancelacion()

    /** No consta el desenlace del cobro: la intención sigue viva y se vuelve a preguntar sola. */
    data class Pendiente(val sinRed: Boolean) : EstadoDeCancelacion()

    /** Consta que no se cobró; falta cancelar la orden, que sigue en cola. */
    data class NoSeCobroOrdenPendiente(val sinRed: Boolean) : EstadoDeCancelacion()

    /** No se cobró y no queda nada por hacer. */
    data object Cerrada : EstadoDeCancelacion()

    /** La terminal sí cobró: la venta queda pagada y la orden NO se borra. */
    data class SeCobro(val paymentId: String?) : EstadoDeCancelacion()
}

/**
 * Reproduce las cancelaciones de cobro hasta que consten, aunque la pantalla muera.
 *
 * 🔴 **El borrado de la orden es BARRERA**: cancel → desenlace acreditado → DELETE, en ese orden y
 * nunca en paralelo. El defecto que lo origina (producción, 11-sep-2026) mandaba el cancel y el
 * DELETE a la vez: cuando el DELETE llegaba primero, el servidor contestaba 409
 * `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE`, se mostraba un toast genérico y la orden quedaba
 * huérfana — nadie volvía a intentarlo.
 *
 * Vive fuera del ViewModel (scope propio) por la misma razón que la llave durable del cobro: el
 * cajero se va de la pantalla, la app muere, el proceso se reinicia, y la cancelación tiene que
 * seguir su camino.
 */
@Singleton
class CancelacionDeCobroCoordinator internal constructor(
    private val store: CancelacionesDeCobroStore,
    private val transporte: CancelacionDeCobroTransport,
    private val conectado: StateFlow<Boolean>,
    private val servidorAlcanzable: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val reloj: () -> Long = { System.currentTimeMillis() },
    private val esperasDeSondeo: List<Long> = listOf(1_000L, 2_000L),
    private val tickMs: Long = 30_000L,
) {
    @Inject
    constructor(
        store: CancelacionesDeCobroStore,
        transporte: CancelacionDeCobroTransport,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(
        store = store,
        transporte = transporte,
        conectado = connectivityMonitor.isConnected,
        servidorAlcanzable = connectivityMonitor.isServerReachable,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val _pendientes = MutableStateFlow(store.todas())

    /** Lo que queda por cancelar, para el banner de Cobrar y la hoja de pendientes. */
    val pendientes: StateFlow<List<IntencionDeCancelarCobro>> = _pendientes.asStateFlow()

    private val _enCurso = MutableStateFlow<Set<String>>(emptySet())
    val enCurso: StateFlow<Set<String>> = _enCurso.asStateFlow()

    private val _estados = MutableStateFlow<Map<String, EstadoDeCancelacion>>(emptyMap())

    /** Desenlaces ya conocidos EN ESTE PROCESO, para que un resultado tardío no los contradiga. */
    private val desenlaces = ConcurrentHashMap<String, DesenlaceDeCancelacion>()

    private val candados = ConcurrentHashMap<String, Mutex>()
    private val cicloDeVida = Any()
    private var sesion: Job? = null

    fun estado(id: String): Flow<EstadoDeCancelacion?> = _estados.map { it[id] }.distinctUntilChanged()

    fun estadoActual(id: String): EstadoDeCancelacion? = _estados.value[id]

    /** Lo que ya se supo de este cobro. Un resultado tardío del POST no puede contradecirlo. */
    fun desenlaceConocido(requestId: String): DesenlaceDeCancelacion? = desenlaces[requestId]

    /**
     * Guarda la intención en DISCO. Devuelve `false` si no quedó guardada: entonces no sale una
     * sola petición — quien llama tiene que decirlo en pantalla.
     */
    fun registrar(intencion: IntencionDeCancelarCobro): Boolean {
        val guardada = store.registrar(intencion)
        if (guardada) refrescarPendientes()
        return guardada
    }

    /** Corre YA esta intención, sin esperar al reloj. Una sola corrida a la vez por intención. */
    fun procesarAhora(id: String) {
        scope.launch { procesar(id, ignorarEspera = true) }
    }

    /** El cobro que la terminal sí hizo ya se aplicó en pantalla: el aviso deja de hacer falta. */
    fun cobroAplicado(id: String) {
        if (store.leer(id) != null) {
            store.quitar(id)
            refrescarPendientes()
        }
    }

    /** Arranca los disparadores: al reconectar (con estabilización) y un reloj de seguridad. */
    fun start() = synchronized(cicloDeVida) {
        if (sesion?.isActive == true) return
        sesion = scope.launch {
            launch {
                var estabaCaido = false
                combine(conectado, servidorAlcanzable) { red, servidor -> red && servidor }
                    .distinctUntilChanged()
                    .collect { disponible ->
                        if (!disponible) {
                            estabaCaido = true
                            return@collect
                        }
                        if (estabaCaido) {
                            estabaCaido = false
                            delay(ESTABILIZACION_MS)
                        }
                        procesarTodas(ignorarEspera = true)
                    }
            }
            launch {
                while (isActive) {
                    delay(tickMs)
                    if (conectado.value && servidorAlcanzable.value) procesarTodas(ignorarEspera = false)
                }
            }
        }
        Unit
    }

    fun stop() = synchronized(cicloDeVida) {
        sesion?.cancel()
        sesion = null
    }

    private suspend fun procesarTodas(ignorarEspera: Boolean) {
        for (intencion in store.todas()) {
            procesar(intencion.id, ignorarEspera)
        }
    }

    /**
     * Una corrida completa de una intención. `internal` para que las pruebas la corran sin relojes.
     *
     * Una sola a la vez por intención (`tryLock`): un «Volver a consultar» encima de una corrida en
     * vuelo no dispara otra — duplicaría el cancel y, peor, el borrado.
     */
    internal suspend fun procesar(id: String, ignorarEspera: Boolean = true) {
        val candado = candadoDe(id)
        if (!candado.tryLock()) return
        try {
            var intencion = store.leer(id) ?: return
            if (!ignorarEspera && intencion.proximoIntentoEn > reloj()) return
            marcarEnCurso(id, true)
            publicar(id, EstadoDeCancelacion.EnCurso)
            publicar(id, correr(intencion))
        } finally {
            marcarEnCurso(id, false)
            candado.unlock()
        }
    }

    private suspend fun correr(inicial: IntencionDeCancelarCobro): EstadoDeCancelacion {
        var intencion = inicial
        var estadoDeLaRespuesta: ChargeStatusProbe? = null
        var consultas = 0

        repeat(MAX_PASOS) {
            when (intencion.faseActual) {
                FaseDeCancelacion.PEDIR_CANCEL -> {
                    val requestId = intencion.requestId
                    val terminalId = intencion.terminalId
                    if (requestId == null) {
                        // Nunca hubo cobro: sólo queda la orden que creó el flujo.
                        intencion = avanzar(intencion, FaseDeCancelacion.BORRAR_ORDEN)
                        return@repeat
                    }
                    if (terminalId == null) {
                        // Sin terminal no hay a quién pedirle que se detenga; el estado dirá cuál es.
                        intencion = avanzar(intencion, FaseDeCancelacion.ESPERAR_DESENLACE)
                        return@repeat
                    }
                    val respuesta = transporte.pedirCancelacion(intencion.venueId, terminalId, requestId)
                    if (respuesta.esTransitoria) {
                        return quedarPendiente(intencion, sinRed = respuesta.http == null, motivo = respuesta.mensaje)
                    }
                    estadoDeLaRespuesta = respuesta.estado
                    if (respuesta.estado != null) consultas++
                    intencion = avanzar(intencion, FaseDeCancelacion.ESPERAR_DESENLACE)
                }

                FaseDeCancelacion.ESPERAR_DESENLACE -> {
                    val requestId = intencion.requestId
                        ?: return@repeat run { intencion = avanzar(intencion, FaseDeCancelacion.BORRAR_ORDEN) }
                    val desdeLaRespuesta = estadoDeLaRespuesta
                    estadoDeLaRespuesta = null
                    val sinRed: Boolean
                    val probe: ChargeStatusProbe
                    if (desdeLaRespuesta != null) {
                        probe = desdeLaRespuesta
                        sinRed = false
                    } else {
                        val consulta = transporte.consultarEstado(intencion.venueId, requestId)
                        consultas++
                        probe = consulta.probe
                        sinRed = consulta.sinRespuesta
                    }
                    intencion = completarDesde(intencion, probe)

                    when (val desenlace = CancelacionDeCobro.decidir(probe)) {
                        is DesenlaceDeCancelacion.SeCobro -> {
                            desenlaces[requestId] = desenlace
                            return entregarElCobro(intencion, desenlace.paymentId)
                        }
                        is DesenlaceDeCancelacion.NoSeCobro -> {
                            desenlaces[requestId] = desenlace
                            transporte.soltarLlaveSiEs(requestId)
                            if (intencion.borrarOrden && intencion.orderId != null) {
                                intencion = avanzar(intencion, FaseDeCancelacion.BORRAR_ORDEN)
                            } else {
                                return cerrar(intencion)
                            }
                        }
                        is DesenlaceDeCancelacion.Pendiente -> {
                            if (sinRed) return quedarPendiente(intencion, sinRed = true)
                            // El cancel pudo llegar ANTES que la fila: mientras siga viva, se reenvía
                            // con el MISMO requestId (el servidor lo trata como la misma intención).
                            if (desenlace.enVuelo && desdeLaRespuesta == null) {
                                intencion.terminalId?.let { transporte.pedirCancelacion(intencion.venueId, it, requestId) }
                            }
                            val puedeInsistir = (desenlace.enVuelo || desenlace.sinFila) && consultas <= esperasDeSondeo.size
                            if (!puedeInsistir) return quedarPendiente(intencion, sinRed = false)
                            delay(esperasDeSondeo[consultas - 1])
                        }
                    }
                }

                FaseDeCancelacion.BORRAR_ORDEN -> {
                    val orderId = intencion.orderId
                    if (!intencion.borrarOrden || orderId == null) return cerrar(intencion)
                    val resultado = transporte.cancelarOrden(intencion.venueId, orderId)
                    when (val paso = CancelacionDeCobro.trasBorrar(resultado, intencion.requestId)) {
                        is PasoTrasBorrar.Cerrar -> return cerrar(intencion)
                        is PasoTrasBorrar.CerrarConDinero -> {
                            Log.w(TAG, "La cuenta ${intencion.orderId} ya tiene dinero: no se cancela (${paso.mensaje})")
                            return cerrar(intencion)
                        }
                        is PasoTrasBorrar.VolverAEsperar -> {
                            intencion = avanzar(intencion, FaseDeCancelacion.ESPERAR_DESENLACE)
                            return quedarPendiente(intencion, sinRed = false, motivo = (resultado as? ResultadoDeCancelarOrden.BloqueadaPorCobro)?.mensaje)
                        }
                        is PasoTrasBorrar.Reintentar -> return quedarPendiente(intencion, sinRed = paso.sinRed, motivo = motivoDe(resultado))
                    }
                }

                FaseDeCancelacion.SE_COBRO -> return entregarElCobro(intencion, intencion.paymentId)
            }
        }
        return quedarPendiente(intencion, sinRed = false)
    }

    /**
     * El cobro sí ocurrió: se entrega a la llave durable, que es lo que hace que la próxima venta
     * lo muestre aunque nadie estuviera mirando. Si la llave la tiene OTRO cobro vivo, el aviso se
     * conserva en disco hasta poder entregarlo — nada se descarta en silencio.
     */
    private suspend fun entregarElCobro(intencion: IntencionDeCancelarCobro, paymentId: String?): EstadoDeCancelacion {
        val requestId = intencion.requestId
        if (requestId != null) {
            desenlaces[requestId] = DesenlaceDeCancelacion.SeCobro(paymentId)
            val entregado = transporte.armarLlaveSiLibre(requestId)
            if (!entregado) {
                guardar(
                    intencion.copy(
                        fase = FaseDeCancelacion.SE_COBRO.name,
                        paymentId = paymentId,
                        intentos = intencion.intentos + 1,
                        proximoIntentoEn = reloj() + espera(intencion.intentos + 1),
                        sinRed = false,
                    ),
                )
                return EstadoDeCancelacion.SeCobro(paymentId)
            }
        }
        store.quitar(intencion.id)
        refrescarPendientes()
        return EstadoDeCancelacion.SeCobro(paymentId)
    }

    private fun cerrar(intencion: IntencionDeCancelarCobro): EstadoDeCancelacion {
        store.quitar(intencion.id)
        refrescarPendientes()
        return EstadoDeCancelacion.Cerrada
    }

    private fun quedarPendiente(
        intencion: IntencionDeCancelarCobro,
        sinRed: Boolean,
        motivo: String? = null,
    ): EstadoDeCancelacion {
        val intentos = intencion.intentos + 1
        guardar(
            intencion.copy(
                intentos = intentos,
                sinRed = sinRed,
                motivo = motivo ?: intencion.motivo,
                proximoIntentoEn = reloj() + espera(intentos),
            ),
        )
        return if (intencion.faseActual == FaseDeCancelacion.BORRAR_ORDEN) {
            EstadoDeCancelacion.NoSeCobroOrdenPendiente(sinRed)
        } else {
            EstadoDeCancelacion.Pendiente(sinRed)
        }
    }

    private fun avanzar(intencion: IntencionDeCancelarCobro, fase: FaseDeCancelacion): IntencionDeCancelarCobro =
        guardar(intencion.copy(fase = fase.name))

    /** La consulta puede traer datos que la intención no tenía (la orden, la terminal). */
    private fun completarDesde(intencion: IntencionDeCancelarCobro, probe: ChargeStatusProbe): IntencionDeCancelarCobro {
        if (probe !is ChargeStatusProbe.Known) return intencion
        val orderId = intencion.orderId ?: probe.orderId?.takeIf { it.isNotBlank() }
        val terminalId = intencion.terminalId ?: probe.terminalId?.takeIf { it.isNotBlank() }
        if (orderId == intencion.orderId && terminalId == intencion.terminalId) return intencion
        return guardar(intencion.copy(orderId = orderId, terminalId = terminalId))
    }

    /**
     * Guarda el avance y devuelve la intención vigente.
     *
     * Si el disco no acepta, la corrida SIGUE con la copia en memoria: cada paso es idempotente
     * (el cancel se puede repetir, el DELETE de una orden ya cancelada contesta 200), así que
     * avanzar es mejor que dejar la cancelación parada por una escritura perdida.
     */
    private fun guardar(intencion: IntencionDeCancelarCobro): IntencionDeCancelarCobro {
        if (!store.actualizar(intencion)) {
            Log.w(TAG, "No se pudo guardar el avance de la cancelación ${intencion.id}")
        }
        refrescarPendientes()
        return intencion
    }

    private fun motivoDe(resultado: ResultadoDeCancelarOrden): String? = when (resultado) {
        is ResultadoDeCancelarOrden.BloqueadaPorCobro -> resultado.mensaje
        is ResultadoDeCancelarOrden.RechazoDeNegocio -> resultado.mensaje
        else -> null
    }

    private fun refrescarPendientes() {
        _pendientes.value = store.todas()
    }

    private fun publicar(id: String, estado: EstadoDeCancelacion) {
        _estados.value = _estados.value + (id to estado)
    }

    private fun marcarEnCurso(id: String, activo: Boolean) {
        _enCurso.value = if (activo) _enCurso.value + id else _enCurso.value - id
    }

    private fun candadoDe(id: String): Mutex = candados.getOrPut(id) { Mutex() }

    /** Espera creciente entre intentos: insistir cada 30 s a una terminal muda sólo hace ruido. */
    private fun espera(intentos: Int): Long {
        val factor = 1L shl (intentos - 1).coerceIn(0, 10)
        return (ESPERA_BASE_MS * factor).coerceAtMost(ESPERA_MAXIMA_MS)
    }

    private companion object {
        const val TAG = "CancelacionDeCobro"
        const val ESTABILIZACION_MS = 2_000L
        const val ESPERA_BASE_MS = 30_000L
        const val ESPERA_MAXIMA_MS = 300_000L

        /** Tope de pasos por corrida: ninguna cancelación puede girar sin fin. */
        const val MAX_PASOS = 12
    }
}
