package com.avoqado.pos.inventory.waste.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.waste.domain.WasteOutcome
import com.avoqado.pos.inventory.waste.domain.WasteReason
import com.avoqado.pos.inventory.waste.domain.clasificarRespuestaDeMerma
import com.avoqado.pos.inventory.waste.domain.fechaDelAparato
import com.avoqado.pos.inventory.waste.domain.normalizarCantidad
import com.avoqado.pos.inventory.waste.domain.recortarNota
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que el motor necesita de la red: mandar o anular UNA fila y traer la respuesta cruda. */
interface TransporteDeMerma {
    suspend fun enviar(fila: PendingWasteEntity): RespuestaHttp
    suspend fun anular(fila: PendingWasteEntity): RespuestaHttp
}

/** Cómo terminó un intento de descartar una merma de la cola. */
enum class DesenlaceDeDescarte { ANULADA, YA_APLICADA, SIN_RED, EN_CAMINO, SIN_PERMISO, FALLO }

/** La merma no se puede registrar así: el servidor la rechazaría con un 422 permanente. */
class EntradaDeMermaInvalida(mensaje: String) : IllegalArgumentException(mensaje)

private const val PRIMERA_ESPERA = 30_000L
private const val TOPE_DE_ESPERA = 15 * 60_000L

/** Un bloqueo de plan no se levanta en segundos: se vuelve a preguntar cada 15 min. */
private const val ESPERA_DE_PLAN = 15 * 60_000L

/** Cada cuánto se revisa la cola aunque nada la despierte: son las filas que esperan su reintento. */
private const val PULSO = 60_000L

/** El plazo para subir lo propio al cerrar sesión (spec §5). Nunca retiene el cierre más que esto. */
const val PLAZO_AL_CERRAR_SESION = 5_000L

/**
 * Espera antes del reintento N: 30 s, 1, 2, 4, 8 min… con tope de 15 min. Ni ráfaga contra un
 * servidor caído, ni horas de silencio. Espejo de `esperaAntesDeReintentar` de iOS.
 */
fun esperaAntesDeReintentar(intentos: Int): Long =
    minOf(PRIMERA_ESPERA shl (intentos - 1).coerceIn(0, 10), TOPE_DE_ESPERA)

/**
 * El motor de la merma: registra (siempre en disco ANTES de la red) y drena la cola.
 *
 * - Un solo trabajador ([candado]): nunca hay dos envíos en el aire.
 * - Cada fila sube con la sesión de QUIEN LA CAPTURÓ y al venue donde se capturó (spec §5,
 *   Review Focus 3).
 * - El desenlace lo decide el CÓDIGO del servidor (`clasificarRespuestaDeMerma`), no la clase de
 *   la excepción. Nada se descarta solo.
 *
 * NO es el outbox de órdenes (`SyncOutbox`): una merma atorada no detiene las ventas (spec §5).
 * Espejo de `WasteSyncCoordinator.swift` de avoqado-ios.
 */
@Singleton
class WasteSyncCoordinator @Inject constructor(
    private val dao: PendingWasteDao,
    private val transporte: TransporteDeMerma,
    private val secureStorage: SecureStorage,
    private val connectivityMonitor: ConnectivityMonitor,
    private val bloqueo: BloqueoDeMermaPorPlan,
) {
    /** Inyectable para las pruebas: la espera entre reintentos corre sobre este reloj. */
    internal var reloj: () -> Long = System::currentTimeMillis
    internal var zona: ZoneId = ZoneId.systemDefault()

    private val candado = Mutex()
    private val solicitudes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val ciclo = Any()
    private var trabajo: Job? = null

    /**
     * Valida, escribe la fila y devuelve su folio — SIN tocar la red. Sólo después pide un drenado.
     *
     * 🔴 La pregunta 2 de `todo-funciona-sin-red.md`: si el proceso muere entre el toque y el POST,
     * la merma ya está en disco. Lo que el servidor rechazaría con un 422 permanente (cantidad
     * ambigua, OTHER sin nota) se rechaza AQUÍ, antes de que exista una fila sin salida.
     */
    suspend fun registrar(
        venueId: String,
        staffId: String,
        item: WasteCatalogItem,
        cantidadTecleada: String,
        motivo: WasteReason,
        nota: String?,
    ): Result<String> {
        val cantidad = normalizarCantidad(cantidadTecleada)
            ?: return Result.failure(EntradaDeMermaInvalida("Escribe una cantidad válida."))
        val notaLimpia = nota?.let(::recortarNota)?.takeIf { it.isNotEmpty() }
        if (motivo.exigeNota && notaLimpia == null) {
            return Result.failure(EntradaDeMermaInvalida("Con «${motivo.etiqueta}» escribe una nota."))
        }
        val ahora = reloj()
        val folio = UUID.randomUUID().toString()
        dao.encolar(
            PendingWasteEntity(
                idempotencyKey = folio,
                venueId = venueId,
                staffId = staffId,
                itemType = item.itemType,
                itemId = item.itemId,
                itemName = item.name,
                unit = item.unit,
                quantity = cantidad,
                reasonCode = motivo.codigo,
                note = notaLimpia,
                clientOccurredAt = fechaDelAparato(Instant.ofEpochMilli(ahora), zona),
                estado = EstadoMerma.PENDING,
                creadaEn = ahora,
            ),
        )
        solicitarDrenado()
        return Result.success(folio)
    }

    fun solicitarDrenado() {
        solicitudes.tryEmit(Unit)
    }

    /** Drena lo de la sesión actual. Si otro drenado está en curso, espera su turno. */
    suspend fun drenarAhora() = candado.withLock { drenar(secureStorage.userId) }

    /**
     * Al cerrar sesión o cambiar de usuario: intenta subir lo PROPIO de [staffId], con un plazo.
     * 🔴 El plazo NO retiene el cierre: lo que no alcance se queda, con su dueño, y sube cuando esa
     * persona vuelva a entrar en este aparato.
     */
    suspend fun vaciarAntesDeCerrarSesion(staffId: String?, plazoMs: Long = PLAZO_AL_CERRAR_SESION) {
        if (staffId.isNullOrBlank()) return
        withTimeoutOrNull(plazoMs) { candado.withLock { drenar(staffId) } }
    }

    /**
     * Descartar una merma de la cola = anular su folio en el servidor (spec §4.3).
     *
     * 🔴 EXIGE red: si el POST sí había llegado, borrarla sólo aquí dejaría registrada una merma que el
     * negocio cree descartada. Sólo el servidor sabe.
     *
     * Envío y descarte se excluyen por el ESTADO de la fila (spec §5), con el MISMO reclamo atómico del
     * drenado: la que va en camino no se toca, y la que se está descartando no sale.
     */
    suspend fun descartar(folio: String): DesenlaceDeDescarte {
        // La única forma de salir de la cola es el 201 del drenado: si ya no está, se registró.
        val fila = dao.porFolio(folio) ?: return DesenlaceDeDescarte.YA_APLICADA
        if (fila.estado == EstadoMerma.VOIDED) return DesenlaceDeDescarte.ANULADA
        if (fila.estado == EstadoMerma.APPLIED) return DesenlaceDeDescarte.YA_APLICADA
        if (!connectivityMonitor.isConnected.value || !connectivityMonitor.isServerReachable.value) {
            return DesenlaceDeDescarte.SIN_RED
        }
        // El drenado nunca toma una fila en revisión: ésa no necesita reclamarse.
        val reclamada = fila.estado != EstadoMerma.NEEDS_REVIEW
        if (reclamada && dao.reclamarFolio(folio) == 0) return DesenlaceDeDescarte.EN_CAMINO
        val respuesta = try {
            transporte.anular(fila)
        } catch (cancelada: CancellationException) {
            if (reclamada) withContext(NonCancellable) { dao.devolver(folio) }
            throw cancelada
        }
        val anulacion = if (respuesta.code in 200..299) WasteApi.leerAnulacion(respuesta.body) else null
        return when (anulacion) {
            is Anulacion.Anulada -> {
                dao.cerrar(folio, EstadoMerma.VOIDED, anulacion.porStaffId, reloj())
                DesenlaceDeDescarte.ANULADA
            }
            Anulacion.YaAplicada -> {
                dao.cerrar(folio, EstadoMerma.APPLIED, porStaffId = null, cuando = reloj())
                DesenlaceDeDescarte.YA_APLICADA
            }
            null -> {
                // No se supo o no se pudo: la fila vuelve a la cola tal cual, con su folio.
                if (reclamada) dao.devolver(folio)
                if (respuesta.code == 403) DesenlaceDeDescarte.SIN_PERMISO else DesenlaceDeDescarte.FALLO
            }
        }
    }

    /**
     * Arranca el motor de la sesión: primero devuelve a la cola lo que quedó en `SENDING` (el proceso
     * murió entre el POST y la respuesta — Review Focus 4), y después drena al recuperar la red, al
     * pedírselo, y cada [PULSO] para lo que espera su reintento.
     */
    fun start(scope: CoroutineScope): Unit = synchronized(ciclo) {
        trabajo?.cancel()
        trabajo = scope.launch {
            dao.sanarSending()
            launch {
                combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { red, servidor ->
                    red && servidor
                }
                    .distinctUntilChanged()
                    .collect { disponible -> if (disponible) drenarAhora() }
            }
            launch { solicitudes.collect { drenarAhora() } }
            launch {
                while (isActive) {
                    delay(PULSO)
                    drenarAhora()
                }
            }
        }
    }

    fun stop(): Unit = synchronized(ciclo) {
        trabajo?.cancel()
        trabajo = null
    }

    private suspend fun drenar(staffId: String?) {
        if (staffId.isNullOrBlank()) return
        while (true) {
            currentCoroutineContext().ensureActive()
            val fila = dao.reclamar(reloj(), staffId) ?: return
            // 🔴 Spec §5: cada envío se ata a la sesión EN EL MOMENTO de la petición. El servidor toma
            // el autor del token; si el usuario cambió a media vuelta (el relevo por PIN no espera a
            // que el drenado termine), esta fila saldría a nombre de quien no la registró. Vuelve a la
            // cola, sin contarse como intento, y espera a su dueño.
            if (secureStorage.userId != fila.staffId) {
                dao.devolver(fila.idempotencyKey)
                return
            }
            val respuesta = try {
                transporte.enviar(fila)
            } catch (cancelada: CancellationException) {
                // El envío se abandonó (p. ej. el plazo del cierre de sesión): la fila no puede
                // quedarse en SENDING, que sólo se sana al reiniciar la app. Vuelve a la cola con
                // su folio; si el POST sí llegó, el servidor deduplica el reenvío.
                withContext(NonCancellable) { dao.devolver(fila.idempotencyKey) }
                throw cancelada
            }
            val fallo = WasteApi.leerFallo(respuesta.body)
            when (val desenlace = clasificarRespuestaDeMerma(respuesta.code, fallo.code, fallo.featureCode)) {
                WasteOutcome.Sincronizada -> dao.borrarSincronizada(fila.idempotencyKey)
                WasteOutcome.Anulada ->
                    dao.cerrar(fila.idempotencyKey, EstadoMerma.VOIDED, porStaffId = null, cuando = reloj())
                WasteOutcome.BloqueoDePlan -> {
                    dao.marcar(fila.idempotencyKey, EstadoMerma.PLAN_BLOCKED, fallo.featureCode, reloj() + ESPERA_DE_PLAN)
                    bloqueo.bloquear(fila.venueId)
                }
                is WasteOutcome.NecesitaRevision ->
                    dao.marcar(fila.idempotencyKey, EstadoMerma.NEEDS_REVIEW, desenlace.motivo, 0L)
                WasteOutcome.Reintentable -> {
                    dao.marcar(
                        fila.idempotencyKey,
                        EstadoMerma.PENDING,
                        fallo.code ?: "HTTP ${respuesta.code}",
                        reloj() + esperaAntesDeReintentar(fila.intentos + 1),
                    )
                    // Sin red o con el servidor caído, las demás también fallarían: se espera.
                    return
                }
            }
        }
    }
}
