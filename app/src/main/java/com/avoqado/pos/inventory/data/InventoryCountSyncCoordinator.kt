package com.avoqado.pos.inventory.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.model.StockCountItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transporte de las cuatro mutaciones que comparten orden por conteo. */
interface InventoryCountTransport {
    suspend fun enviarAvance(
        venueId: String,
        countId: String,
        items: List<StockCountItem>,
        expectedRevision: Int,
    ): RespuestaHttp

    suspend fun enviarFinal(
        venueId: String,
        countId: String,
        items: List<StockCountItem>,
        note: String?,
        expectedRevision: Int,
    ): RespuestaHttp

    suspend fun confirmarConteo(
        venueId: String,
        countId: String,
        expectedRevision: Int,
    ): RespuestaHttp

    suspend fun cancelStockCount(
        venueId: String,
        countId: String,
        expectedRevision: Int,
    ): RespuestaHttp
}

data class CambioDeSyncDeInventario(
    val venueId: String,
    val countId: String,
    val borrador: BorradorDeConteo?,
    val respuesta: RespuestaHttp? = null,
)

data class ResultadoDeCierreCoordinado(
    val put: RespuestaHttp? = null,
    val confirm: RespuestaHttp? = null,
    val completado: Boolean = false,
) {
    val ultimaRespuesta: RespuestaHttp? get() = confirm ?: put
}

/**
 * Dueño de sesión de toda escritura remota de un conteo. El replay no depende de abrir Inventario;
 * el cierre nunca se reproduce solo y requiere una llamada explícita de la pantalla.
 */
@Singleton
class InventoryCountSyncCoordinator @Inject constructor(
    private val store: BorradorDeConteoStore,
    private val remote: InventoryCountTransport,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    private val lifecycleLock = Any()
    private val drainLock = Mutex()
    private val locksPorConteo = ConcurrentHashMap<String, Mutex>()
    private val solicitudes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _cambios = MutableSharedFlow<CambioDeSyncDeInventario>(extraBufferCapacity = 32)
    val cambios: SharedFlow<CambioDeSyncDeInventario> = _cambios.asSharedFlow()

    private var sessionJob: Job? = null
    private var generation = 0L

    /** Reinicia la generación: login y cambio de sesión cancelan cualquier cola anterior. */
    fun start(scope: CoroutineScope) = synchronized(lifecycleLock) {
        generation += 1
        val estaGeneracion = generation
        sessionJob?.cancel()
        sessionJob = scope.launch {
            launch {
                combine(
                    connectivityMonitor.isConnected,
                    connectivityMonitor.isServerReachable,
                ) { red, servidor -> red && servidor }
                    .distinctUntilChanged()
                    .collect { disponible ->
                        if (disponible) drainAll(estaGeneracion)
                    }
            }
            launch {
                solicitudes.collect {
                    if (hayConexion()) drainAll(estaGeneracion)
                }
            }
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        generation += 1
        sessionJob?.cancel()
        sessionJob = null
    }

    fun solicitarSync() {
        solicitudes.tryEmit(Unit)
    }

    /** Cuenta trabajo que no puede perder su contexto al cerrar sesión. */
    fun blockingWorkCount(): Int = store.venuesConTrabajo().sumOf { venueId ->
        val b = store.leer(venueId)
        val borradorBloqueante = b != null && (
            b.pendientesDeEnviar.isNotEmpty() ||
                ConteoEnCurso.notaPendienteDeEnviar(b) ||
                b.conflictoRevision != null ||
                b.revisionConPutFinalConfirmado != null ||
                (b.countId == null && ConteoEnCurso.contadas(b.lineas) > 0)
            )
        (if (borradorBloqueante) 1 else 0) + store.cancelacionesPendientes(venueId).size
    }

    private suspend fun drainAll(expectedGeneration: Long) = drainLock.withLock {
        if (!generacionVigente(expectedGeneration)) return@withLock
        // El contrato permite cualquier List; se toma una foto porque un ACK puede mutar la
        // colección respaldante que entregue una implementación del store.
        for (venueId in store.venuesConTrabajo().toList()) {
            currentCoroutineContext().ensureActive()
            if (!generacionVigente(expectedGeneration)) return@withLock
            // Foto estable: reconocer una cancelación puede mutar la colección respaldante del
            // store mientras la recorremos (el contrato no exige una lista inmutable).
            val cancelaciones = store.cancelacionesPendientes(venueId).toList()
            val countId = store.leer(venueId)?.countId
            if (countId != null && cancelaciones.none { it.countId == countId }) {
                sincronizarAvance(venueId, countId, expectedGeneration)
            }
            for (cancelacion in cancelaciones) {
                currentCoroutineContext().ensureActive()
                if (!generacionVigente(expectedGeneration)) return@withLock
                sincronizarCancelacion(cancelacion, expectedGeneration)
            }
        }
    }

    /** También lo usa el VM para conservar feedback inmediato; sigue pasando por el mismo lock. */
    suspend fun sincronizarAvanceAhora(venueId: String, countId: String): RespuestaHttp? {
        val g = synchronized(lifecycleLock) { generation.takeIf { sessionJob?.isActive == true } }
            ?: return null
        return sincronizarAvance(venueId, countId, g)
    }

    /**
     * La barrera de descarte toma el mismo lock que cualquier PUT. Captura la revisión DESPUÉS
     * del ACK en vuelo y borra+encola atómicamente; desde ese instante ningún drain manda líneas.
     */
    suspend fun descartarManualmente(venueId: String, countId: String?): Boolean {
        val key = countId ?: "ciclo-local"
        val guardado = lockPara(venueId, key).withLock {
            val actual = store.leer(venueId)
            if (actual != null && actual.countId != countId) return@withLock false
            store.descartarYEncolarCancelacion(
                venueId = venueId,
                countId = countId.orEmpty(),
                expectedRevision = actual?.revision,
            )
        }
        if (guardado) solicitarSync()
        return guardado
    }

    private suspend fun sincronizarAvance(
        venueId: String,
        countId: String,
        expectedGeneration: Long,
    ): RespuestaHttp? = lockPara(venueId, countId).withLock {
        if (!generacionVigente(expectedGeneration) || !hayConexion()) return@withLock null
        if (store.cancelacionesPendientes(venueId).any { it.countId == countId }) return@withLock null
        val draft = store.leer(venueId)?.takeIf { it.countId == countId } ?: return@withLock null
        if (draft.conflictoRevision != null) return@withLock null
        val items = ConteoEnCurso.lineasParaEnviar(draft.lineas, draft.pendientesDeEnviar)
        if (items.isEmpty()) return@withLock null
        val revision = draft.revision
        if (revision == null) {
            guardarRevisionDesconocida(venueId, countId)
            return@withLock null
        }
        val sellos = items.associate { it.id to (it.counted to it.countedAt) }
        val respuesta = remote.enviarAvance(venueId, countId, items, revision)
        if (!generacionVigente(expectedGeneration)) return@withLock respuesta
        procesarRespuestaPut(
            venueId = venueId,
            countId = countId,
            expectedRevision = revision,
            respuesta = respuesta,
            sellos = sellos,
            notaEnviada = null,
            esFinal = false,
        )
        respuesta
    }

    private suspend fun sincronizarCancelacion(
        cancelacion: CancelacionPendienteDeConteo,
        expectedGeneration: Long,
    ) = lockPara(cancelacion.venueId, cancelacion.countId).withLock {
        if (!generacionVigente(expectedGeneration) || !hayConexion()) return@withLock
        val vigente = store.cancelacionesPendientes(cancelacion.venueId)
            .firstOrNull { it.countId == cancelacion.countId } ?: return@withLock
        if (vigente.conflictoRevision != null || vigente.expectedRevision == null) return@withLock
        val respuesta = remote.cancelStockCount(
            vigente.venueId,
            vigente.countId,
            vigente.expectedRevision,
        )
        if (!generacionVigente(expectedGeneration)) return@withLock
        when {
            respuesta.code in 200..299 ->
                store.quitarCancelacionPendiente(vigente.venueId, vigente.countId)
            respuesta.codigo == ConteoEnCurso.CODIGO_CONFLICTO_REVISION -> {
                val conflicto = respuesta.conflictoRevision ?: return@withLock
                store.agregarCancelacionPendiente(vigente.copy(conflictoRevision = conflicto))
            }
            // APPLYING y todo 409 sin código permanecen en cola. El servidor todavía no ofrece
            // un machine-code seguro para «ya cancelado», por lo que el texto jamás se usa como ACK.
            else -> Unit
        }
        _cambios.tryEmit(CambioDeSyncDeInventario(vigente.venueId, vigente.countId, null, respuesta))
    }

    /** PUT final + confirm, únicamente después de una acción explícita del usuario. */
    suspend fun cerrarManualmente(venueId: String, countId: String): ResultadoDeCierreCoordinado {
        val g = synchronized(lifecycleLock) { generation.takeIf { sessionJob?.isActive == true } }
            ?: return ResultadoDeCierreCoordinado()
        return lockPara(venueId, countId).withLock {
            if (!generacionVigente(g) || !hayConexion()) {
                return@withLock ResultadoDeCierreCoordinado()
            }
            if (store.cancelacionesPendientes(venueId).any { it.countId == countId }) {
                return@withLock ResultadoDeCierreCoordinado()
            }
            var draft = store.leer(venueId)?.takeIf { it.countId == countId }
                ?: return@withLock ResultadoDeCierreCoordinado()
            if (draft.conflictoRevision != null) return@withLock ResultadoDeCierreCoordinado()

            var put: RespuestaHttp? = null
            if (!ConteoEnCurso.puedeReintentarConfirmacion(draft)) {
                val revision = draft.revision
                if (revision == null) {
                    guardarRevisionDesconocida(venueId, countId)
                    return@withLock ResultadoDeCierreCoordinado()
                }
                val items = draft.lineas.filter { it.yaSeConto }
                val nota = draft.nota.takeIf { ConteoEnCurso.notaPendienteDeEnviar(draft) }
                val sellos = items.associate { it.id to (it.counted to it.countedAt) }
                put = remote.enviarFinal(venueId, countId, items, nota, revision)
                if (!generacionVigente(g)) return@withLock ResultadoDeCierreCoordinado(put = put)
                procesarRespuestaPut(
                    venueId = venueId,
                    countId = countId,
                    expectedRevision = revision,
                    respuesta = put,
                    sellos = sellos,
                    notaEnviada = nota,
                    esFinal = true,
                )
                draft = store.leer(venueId)?.takeIf { it.countId == countId }
                    ?: return@withLock ResultadoDeCierreCoordinado(put = put)
                if (!ConteoEnCurso.puedeReintentarConfirmacion(draft)) {
                    return@withLock ResultadoDeCierreCoordinado(put = put)
                }
            }

            val baseDeConfirmacion = draft.revisionConPutFinalConfirmado!!
            val confirm = remote.confirmarConteo(venueId, countId, baseDeConfirmacion)
            if (!generacionVigente(g)) return@withLock ResultadoDeCierreCoordinado(put, confirm)
            val conflicto = confirm.conflictoRevision
            when {
                confirm.code in 200..299 -> Unit
                confirm.codigo == ConteoEnCurso.CODIGO_CONFLICTO_REVISION && conflicto != null -> {
                    store.guardarConflicto(venueId, countId, conflicto)
                    emitirCambio(venueId, countId, confirm)
                }
                // STOCK_COUNT_APPLYING es transitorio: conserva stage y no crea conflicto.
                else -> emitirCambio(venueId, countId, confirm)
            }
            ResultadoDeCierreCoordinado(
                put = put,
                confirm = confirm,
                completado = confirm.code in 200..299,
            )
        }
    }

    private fun procesarRespuestaPut(
        venueId: String,
        countId: String,
        expectedRevision: Int,
        respuesta: RespuestaHttp,
        sellos: Map<String, Pair<Double, String?>>,
        notaEnviada: String?,
        esFinal: Boolean,
    ) {
        when {
            respuesta.code in 200..299 -> {
                val nuevaRevision = respuesta.revision
                if (nuevaRevision == null) {
                    guardarRevisionDesconocida(venueId, countId)
                } else {
                    store.reconocerPut(
                        venueId = venueId,
                        countId = countId,
                        expectedRevision = expectedRevision,
                        nuevaRevision = nuevaRevision,
                        sellos = sellos,
                        notaEnviada = notaEnviada,
                        esFinal = esFinal,
                    )
                    emitirCambio(venueId, countId, respuesta)
                }
            }
            respuesta.codigo == ConteoEnCurso.CODIGO_CONFLICTO_REVISION -> {
                respuesta.conflictoRevision?.let { store.guardarConflicto(venueId, countId, it) }
                emitirCambio(venueId, countId, respuesta)
            }
            // Incluye STOCK_COUNT_APPLYING, 403, fallos de transporte y 5xx: nunca se reconoce.
            else -> emitirCambio(venueId, countId, respuesta)
        }
    }

    private fun guardarRevisionDesconocida(venueId: String, countId: String) {
        store.guardarConflicto(
            venueId,
            countId,
            ConflictoRevision(
                code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
                message = ConteoEnCurso.REVISION_DESCONOCIDA,
                venueId = venueId,
                countId = countId,
            ),
        )
        emitirCambio(venueId, countId, null)
    }

    private fun emitirCambio(venueId: String, countId: String, respuesta: RespuestaHttp?) {
        _cambios.tryEmit(CambioDeSyncDeInventario(venueId, countId, store.leer(venueId), respuesta))
    }

    private fun lockPara(venueId: String, countId: String): Mutex =
        locksPorConteo.computeIfAbsent("$venueId\u0000$countId") { Mutex() }

    private fun generacionVigente(expected: Long): Boolean = synchronized(lifecycleLock) {
        expected == generation && sessionJob?.isActive == true
    }

    private fun hayConexion(): Boolean =
        connectivityMonitor.isConnected.value && connectivityMonitor.isServerReachable.value
}
