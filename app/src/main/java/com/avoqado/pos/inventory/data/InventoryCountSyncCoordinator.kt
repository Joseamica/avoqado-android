package com.avoqado.pos.inventory.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.model.StockCountItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

data class ResultadoDeAvanceCoordinado(
    val respuesta: RespuestaHttp? = null,
    val borrador: BorradorDeConteo? = null,
    val sellosEnviados: Map<String, Pair<Double, String?>> = emptyMap(),
    val persistido: Boolean = true,
)

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
    private val memoriaNoPersistida = ConcurrentHashMap<String, BorradorDeConteo>()
    private val solicitudes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _cambios = MutableSharedFlow<CambioDeSyncDeInventario>(extraBufferCapacity = 32)
    val cambios: SharedFlow<CambioDeSyncDeInventario> = _cambios.asSharedFlow()

    private var sessionJob: Job? = null
    private var generation = 0L

    /** Reinicia la generación: login y cambio de sesión cancelan cualquier cola anterior. */
    fun start(scope: CoroutineScope): Unit = synchronized(lifecycleLock) {
        generation += 1
        val estaGeneracion = generation
        sessionJob?.cancel()
        val nuevaSesion = scope.launch(start = CoroutineStart.LAZY) {
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
        // `viewModelScope` usa Main.immediate: si el job arranca en el lado derecho de la
        // asignación, el primer `combine(true, true)` puede llamar `drainAll` antes de que
        // `sessionJob` apunte a esta generación. `generacionVigente` la descarta y ya no hay otra
        // emisión hasta una reconexión. Publicar el job LAZY primero conserva ese arranque inicial.
        sessionJob = nuevaSesion
        nuevaSesion.start()
        Unit
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
        val venuesEnMemoria = memoriaNoPersistida.values.map(BorradorDeConteo::venueId)
        for (venueId in (store.venuesConTrabajo().toList() + venuesEnMemoria).distinct().sorted()) {
            currentCoroutineContext().ensureActive()
            if (!generacionVigente(expectedGeneration)) return@withLock
            // Foto estable: reconocer una cancelación puede mutar la colección respaldante del
            // store mientras la recorremos (el contrato no exige una lista inmutable).
            val cancelaciones = store.cancelacionesPendientes(venueId).toList()
            val conteosEnMemoria = memoriaNoPersistida.values
                .filter { it.venueId == venueId }
                .mapNotNull(BorradorDeConteo::countId)
            val countIds = (listOfNotNull(store.leer(venueId)?.countId) + conteosEnMemoria).distinct()
            for (countId in countIds) {
                if (cancelaciones.none { it.countId == countId }) {
                    sincronizarAvance(venueId, countId, expectedGeneration)
                }
            }
            for (cancelacion in cancelaciones) {
                currentCoroutineContext().ensureActive()
                if (!generacionVigente(expectedGeneration)) return@withLock
                sincronizarCancelacion(cancelacion, expectedGeneration)
            }
        }
    }

    /** También lo usa el VM para conservar feedback inmediato; sigue pasando por el mismo lock. */
    suspend fun sincronizarAvanceAhora(
        venueId: String,
        countId: String,
        snapshotEnMemoria: BorradorDeConteo? = null,
    ): ResultadoDeAvanceCoordinado? {
        val g = synchronized(lifecycleLock) { generation.takeIf { sessionJob?.isActive == true } }
            ?: return null
        return sincronizarAvance(venueId, countId, g, snapshotEnMemoria, emitir = false)
    }

    /** Mantiene una revisión ya reconocida mientras el disco sigue rechazando el snapshot. */
    fun recordarSnapshotNoPersistido(borrador: BorradorDeConteo) {
        val countId = borrador.countId ?: return
        if (borrador.venueId.isBlank()) return
        val revision = borrador.revision
        memoriaNoPersistida.compute(clave(borrador.venueId, countId)) { _, anterior ->
            val candidato = if (anterior?.conflictoRevision != null && borrador.conflictoRevision == null) {
                borrador.copy(conflictoRevision = anterior.conflictoRevision)
            } else {
                borrador
            }
            when {
                anterior == null -> candidato
                (anterior.revision ?: -1) > (revision ?: -1) -> anterior
                anterior.revision == revision && anterior.actualizadoEn > candidato.actualizadoEn -> anterior
                else -> candidato
            }
        }
    }

    /** Copia volátil vigente para que un VM recreado no vuelva a la revisión atrasada del disco. */
    fun borradorNoPersistido(venueId: String): BorradorDeConteo? = memoriaNoPersistida.values
        .filter { it.venueId == venueId }
        .maxWithOrNull(compareBy<BorradorDeConteo>({ it.revision ?: -1 }, { it.actualizadoEn }))

    /** Quita la barrera volátil sólo cuando el disco ya cubre esa misma revisión y generación. */
    fun reconocerSnapshotPersistido(borrador: BorradorDeConteo) {
        val countId = borrador.countId ?: return
        memoriaNoPersistida.computeIfPresent(clave(borrador.venueId, countId)) { _, volatil ->
            if (esIgualOMasReciente(borrador, volatil)) null else volatil
        }
    }

    /**
     * La barrera de descarte toma el mismo lock que cualquier PUT. Captura la revisión DESPUÉS
     * del ACK en vuelo y borra+encola atómicamente; desde ese instante ningún drain manda líneas.
     */
    suspend fun descartarManualmente(venueId: String, countId: String?): Boolean {
        val key = countId ?: "ciclo-local"
        val guardado = lockPara(venueId, key).withLock {
            val disco = store.leer(venueId)
            if (disco != null && disco.countId != countId) return@withLock false
            val actual = countId?.let { seleccionarBorrador(venueId, it, null)?.borrador } ?: disco
            // El PUT que tenía el lock puede haber terminado en 409 mientras esta intención de
            // descarte esperaba. Se relee aquí, dentro del mismo lock, incluyendo el snapshot RAM
            // cuando el marcador de conflicto no pudo persistirse.
            if (actual?.conflictoRevision != null) return@withLock false
            store.descartarYEncolarCancelacion(
                venueId = venueId,
                countId = countId.orEmpty(),
                expectedRevision = actual?.revision,
            )
        }
        if (guardado) {
            if (countId != null) memoriaNoPersistida.remove(clave(venueId, countId))
            solicitarSync()
        }
        return guardado
    }

    private suspend fun sincronizarAvance(
        venueId: String,
        countId: String,
        expectedGeneration: Long,
        snapshotEnMemoria: BorradorDeConteo? = null,
        emitir: Boolean = true,
    ): ResultadoDeAvanceCoordinado? = lockPara(venueId, countId).withLock {
        if (!generacionVigente(expectedGeneration) || !hayConexion()) return@withLock null
        if (store.cancelacionesPendientes(venueId).any { it.countId == countId }) return@withLock null
        val seleccionado = seleccionarBorrador(venueId, countId, snapshotEnMemoria) ?: return@withLock null
        val draft = seleccionado.borrador
        if (draft.conflictoRevision != null) return@withLock null
        val items = ConteoEnCurso.lineasParaEnviar(draft.lineas, draft.pendientesDeEnviar)
        if (items.isEmpty()) return@withLock null
        val revision = draft.revision
        if (revision == null) {
            val marcado = draft.copy(
                conflictoRevision = ConflictoRevision(
                    code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
                    message = ConteoEnCurso.REVISION_DESCONOCIDA,
                    venueId = venueId,
                    countId = countId,
                ),
            )
            val persistido = store.guardarConflicto(venueId, countId, marcado.conflictoRevision!!)
            if (!persistido || seleccionado.desdeMemoria) {
                memoriaNoPersistida[clave(venueId, countId)] = marcado
            }
            if (emitir) emitirCambio(venueId, countId, null)
            return@withLock ResultadoDeAvanceCoordinado(borrador = marcado, persistido = persistido)
        }
        val sellos = items.associate { it.id to (it.counted to it.countedAt) }
        val respuesta = remote.enviarAvance(venueId, countId, items, revision)
        if (!generacionVigente(expectedGeneration)) {
            return@withLock ResultadoDeAvanceCoordinado(respuesta = respuesta, borrador = draft, sellosEnviados = sellos, persistido = false)
        }
        val resultado = procesarRespuestaDeAvance(
            venueId = venueId,
            countId = countId,
            expectedRevision = revision,
            respuesta = respuesta,
            snapshot = draft,
            sellos = sellos,
            desdeMemoria = seleccionado.desdeMemoria,
            durableAlSeleccionar = seleccionado.durableAlSeleccionar,
        )
        if (emitir) emitirCambio(venueId, countId, respuesta)
        resultado
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
            respuesta.code in 200..299 && esRevisionSiguiente(vigente.expectedRevision, respuesta.revision) ->
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
            val confirmReconocido = confirm.code in 200..299 &&
                esRevisionSiguiente(baseDeConfirmacion, confirm.revision)
            when {
                confirmReconocido -> Unit
                confirm.code in 200..299 -> guardarRevisionDesconocida(venueId, countId)
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
                completado = confirmReconocido,
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
                if (!esRevisionSiguiente(expectedRevision, nuevaRevision)) {
                    guardarRevisionDesconocida(venueId, countId)
                } else {
                    val revisionReconocida = requireNotNull(nuevaRevision)
                    store.reconocerPut(
                        venueId = venueId,
                        countId = countId,
                        expectedRevision = expectedRevision,
                        nuevaRevision = revisionReconocida,
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

    private fun procesarRespuestaDeAvance(
        venueId: String,
        countId: String,
        expectedRevision: Int,
        respuesta: RespuestaHttp,
        snapshot: BorradorDeConteo,
        sellos: Map<String, Pair<Double, String?>>,
        desdeMemoria: Boolean,
        durableAlSeleccionar: BorradorDeConteo?,
    ): ResultadoDeAvanceCoordinado {
        val key = clave(venueId, countId)
        when {
            respuesta.code in 200..299 -> {
                val nuevaRevision = respuesta.revision
                if (!esRevisionSiguiente(expectedRevision, nuevaRevision)) {
                    val conflicto = ConflictoRevision(
                        code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
                        message = ConteoEnCurso.REVISION_DESCONOCIDA,
                        venueId = venueId,
                        countId = countId,
                    )
                    val estado = guardarConflictoDeAvance(
                        key,
                        venueId,
                        countId,
                        expectedRevision,
                        snapshot,
                        conflicto,
                        desdeMemoria,
                        durableAlSeleccionar,
                    )
                    return ResultadoDeAvanceCoordinado(respuesta, estado.borrador, sellos, estado.persistido)
                }
                val revisionReconocida = requireNotNull(nuevaRevision)

                val ackPersistido = if (desdeMemoria) {
                    store.reconocerPutDesdeMemoria(
                        venueId,
                        countId,
                        expectedRevision,
                        revisionReconocida,
                        snapshot,
                        durableAlSeleccionar,
                        sellos,
                    )
                } else {
                    store.reconocerPut(
                        venueId = venueId,
                        countId = countId,
                        expectedRevision = expectedRevision,
                        nuevaRevision = revisionReconocida,
                        sellos = sellos,
                        notaEnviada = null,
                        esFinal = false,
                    )
                }
                val estado = fusionarAckEnMemoria(
                    key = key,
                    snapshotEnviado = snapshot,
                    expectedRevision = expectedRevision,
                    nuevaRevision = revisionReconocida,
                    sellos = sellos,
                    durable = store.leer(venueId)?.takeIf { it.countId == countId },
                    durableAlSeleccionar = durableAlSeleccionar,
                    ackPersistido = ackPersistido,
                )
                return ResultadoDeAvanceCoordinado(respuesta, estado.borrador, sellos, estado.persistido)
            }

            respuesta.codigo == ConteoEnCurso.CODIGO_CONFLICTO_REVISION -> {
                val conflicto = respuesta.conflictoRevision ?: ConflictoRevision(
                    code = ConteoEnCurso.CODIGO_CONFLICTO_REVISION,
                    message = ConteoEnCurso.CONFLICTO_REVISION,
                    venueId = venueId,
                    countId = countId,
                    expectedRevision = expectedRevision,
                )
                val estado = guardarConflictoDeAvance(
                    key,
                    venueId,
                    countId,
                    expectedRevision,
                    snapshot,
                    conflicto,
                    desdeMemoria,
                    durableAlSeleccionar,
                )
                return ResultadoDeAvanceCoordinado(respuesta, estado.borrador, sellos, estado.persistido)
            }

            else -> {
                // APPLYING, 403, transporte y 5xx conservan exactamente la foto RAM para el
                // siguiente intento. Ninguno reconoce líneas ni inventa una revisión.
                val estado = conservarFotoMasReciente(
                    key,
                    snapshot,
                    desdeMemoria,
                    durableAlSeleccionar,
                    store.leer(venueId)?.takeIf { it.countId == countId },
                )
                return ResultadoDeAvanceCoordinado(respuesta, estado.borrador, sellos, estado.persistido)
            }
        }
    }

    private data class EstadoVolatil(
        val borrador: BorradorDeConteo,
        val persistido: Boolean,
    )

    /**
     * El PUT viaja sin impedir que la UI siga contando. El ACK actualiza atómicamente la foto que
     * exista AL VOLVER: avanza su revisión, pero sólo quita sellos que todavía sean idénticos.
     */
    private fun fusionarAckEnMemoria(
        key: String,
        snapshotEnviado: BorradorDeConteo,
        expectedRevision: Int,
        nuevaRevision: Int,
        sellos: Map<String, Pair<Double, String?>>,
        durable: BorradorDeConteo?,
        durableAlSeleccionar: BorradorDeConteo?,
        ackPersistido: Boolean,
    ): EstadoVolatil {
        var efectivo = durable ?: aplicarAckASnapshot(snapshotEnviado, expectedRevision, nuevaRevision, sellos)
        var requiereMemoria = !ackPersistido
        memoriaNoPersistida.compute(key) { _, actual ->
            val concurrente = actual != null && actual != snapshotEnviado &&
                (actual.conflictoRevision != null || esIgualOMasReciente(actual, snapshotEnviado))
            val durablePosterior = durable != durableAlSeleccionar
            val base = when {
                concurrente -> requireNotNull(actual)
                ackPersistido && durable != null -> durable
                durable != null && (durable.revision ?: -1) > expectedRevision -> durable
                durablePosterior && durable != null -> durable
                else -> snapshotEnviado
            }
            efectivo = aplicarAckASnapshot(base, expectedRevision, nuevaRevision, sellos)
            requiereMemoria = !ackPersistido || concurrente
            efectivo.takeIf { requiereMemoria }
        }
        return EstadoVolatil(efectivo, persistido = !requiereMemoria)
    }

    private fun aplicarAckASnapshot(
        borrador: BorradorDeConteo,
        expectedRevision: Int,
        nuevaRevision: Int,
        sellos: Map<String, Pair<Double, String?>>,
    ): BorradorDeConteo {
        if ((borrador.revision ?: -1) > expectedRevision) return borrador
        if (borrador.revision != expectedRevision) return borrador
        val vigentes = borrador.lineas.associate { it.id to (it.counted to it.countedAt) }
        val confirmadas = sellos.filter { (id, sello) -> vigentes[id] == sello }.keys
        return borrador.copy(
            revision = nuevaRevision,
            pendientesDeEnviar = borrador.pendientesDeEnviar - confirmadas,
            // Si apareció un conflicto mientras viajaba, un ACK anterior no lo borra.
            conflictoRevision = borrador.conflictoRevision,
            revisionConPutFinalConfirmado = null,
        )
    }

    private fun guardarConflictoDeAvance(
        key: String,
        venueId: String,
        countId: String,
        expectedRevision: Int,
        snapshot: BorradorDeConteo,
        conflicto: ConflictoRevision,
        desdeMemoria: Boolean,
        durableAlSeleccionar: BorradorDeConteo?,
    ): EstadoVolatil {
        val durableAhora = store.leer(venueId)?.takeIf { it.countId == countId }
        var habiaMemoria = false
        var marcado = snapshot.copy(conflictoRevision = conflicto)
        memoriaNoPersistida.compute(key) { _, actual ->
            habiaMemoria = actual != null
            val concurrente = actual != null && actual != snapshot &&
                (actual.conflictoRevision != null || esIgualOMasReciente(actual, snapshot))
            val base = when {
                concurrente -> requireNotNull(actual)
                durableAhora != durableAlSeleccionar && durableAhora != null -> durableAhora
                else -> snapshot
            }
            marcado = base.copy(conflictoRevision = conflicto)
            marcado
        }
        val guardado = if (marcado.revision != expectedRevision) {
            false
        } else if (desdeMemoria || habiaMemoria) {
            store.guardarSnapshotReconocido(
                venueId,
                countId,
                expectedRevision,
                marcado,
                durableAlSeleccionar,
            )
        } else {
            store.guardarConflicto(venueId, countId, conflicto)
        }
        if (guardado) memoriaNoPersistida.remove(key, marcado)
        val pendiente = memoriaNoPersistida[key]
        val efectivo = pendiente ?: store.leer(venueId)?.takeIf { it.countId == countId } ?: marcado
        return EstadoVolatil(efectivo, persistido = guardado && pendiente == null)
    }

    private fun conservarFotoMasReciente(
        key: String,
        snapshot: BorradorDeConteo,
        desdeMemoria: Boolean,
        durableAlSeleccionar: BorradorDeConteo?,
        durableAhora: BorradorDeConteo?,
    ): EstadoVolatil {
        var efectivo = snapshot
        var requiereMemoria = desdeMemoria
        memoriaNoPersistida.compute(key) { _, actual ->
            val concurrente = actual != null && actual != snapshot &&
                (actual.conflictoRevision != null || esIgualOMasReciente(actual, snapshot))
            efectivo = when {
                concurrente -> requireNotNull(actual)
                durableAhora != durableAlSeleccionar && durableAhora != null -> durableAhora
                else -> snapshot
            }
            requiereMemoria = desdeMemoria || actual != null
            efectivo.takeIf { requiereMemoria }
        }
        return EstadoVolatil(efectivo, persistido = !requiereMemoria)
    }

    private data class BorradorSeleccionado(
        val borrador: BorradorDeConteo,
        val desdeMemoria: Boolean,
        val durableAlSeleccionar: BorradorDeConteo?,
    )

    private fun seleccionarBorrador(
        venueId: String,
        countId: String,
        propuesto: BorradorDeConteo?,
    ): BorradorSeleccionado? {
        val disco = store.leer(venueId)?.takeIf { it.countId == countId }
        var memoria = memoriaNoPersistida[clave(venueId, countId)]?.takeIf { it.countId == countId }
        val ram = propuesto?.takeIf { it.venueId == venueId && it.countId == countId }

        // Cuando el VM consiguió reintentar la persistencia, la copia volátil deja de ser una
        // barrera. Se poda aquí también para cubrir persistencias hechas fuera del coordinador.
        if (disco != null && memoria != null && disco == memoria) {
            memoriaNoPersistida.remove(clave(venueId, countId), memoria)
            memoria = null
        }

        // Un conflicto durable siempre bloquea; una foto RAM sin conflicto no puede borrarlo.
        disco?.takeIf { it.conflictoRevision != null }?.let { return BorradorSeleccionado(it, false, disco) }
        memoria?.takeIf { it.conflictoRevision != null }?.let { return BorradorSeleccionado(it, true, disco) }
        ram?.takeIf { it.conflictoRevision != null }?.let { return BorradorSeleccionado(it, true, disco) }

        var elegido: BorradorSeleccionado? = disco?.let { BorradorSeleccionado(it, false, disco) }
        listOfNotNull(
            memoria?.let { BorradorSeleccionado(it, true, disco) },
            ram?.let { BorradorSeleccionado(it, true, disco) },
        ).forEach { candidato ->
            val actual = elegido
            if (actual == null || esIgualOMasReciente(candidato.borrador, actual.borrador)) {
                elegido = candidato
            }
        }
        return elegido
    }

    private fun esIgualOMasReciente(candidato: BorradorDeConteo, actual: BorradorDeConteo): Boolean {
        val revisionCandidata = candidato.revision ?: -1
        val revisionActual = actual.revision ?: -1
        return revisionCandidata > revisionActual ||
            (revisionCandidata == revisionActual && candidato.actualizadoEn >= actual.actualizadoEn)
    }

    private fun esRevisionSiguiente(base: Int, recibida: Int?): Boolean =
        base < Int.MAX_VALUE && recibida == base + 1

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
        val borrador = memoriaNoPersistida[clave(venueId, countId)]
            ?: store.leer(venueId)?.takeIf { it.countId == countId }
        _cambios.tryEmit(CambioDeSyncDeInventario(venueId, countId, borrador, respuesta))
    }

    private fun lockPara(venueId: String, countId: String): Mutex =
        locksPorConteo.computeIfAbsent(clave(venueId, countId)) { Mutex() }

    private fun clave(venueId: String, countId: String): String = "$venueId\u0000$countId"

    private fun generacionVigente(expected: Long): Boolean = synchronized(lifecycleLock) {
        expected == generation && sessionJob?.isActive == true
    }

    private fun hayConexion(): Boolean =
        connectivityMonitor.isConnected.value && connectivityMonitor.isServerReachable.value
}
