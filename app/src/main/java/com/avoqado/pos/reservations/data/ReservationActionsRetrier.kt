package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.data.model.UpdateReservationRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationActionsRetrier @Inject constructor(
    private val pendingDao: PendingReservationActionDao,
    private val api: ReservationApi,
    private val connectivity: ConnectivityMonitor,
    private val reservationRepository: ReservationRepository,
    private val kioskApi: KioskCheckInApi,
    private val secureStorage: SecureStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    /**
     * 🔴 Un solo mensajero (Modo Autoservicio 0.e, Codex r2 R1-7): el toque del kiosco, la vuelta de la red y la lista
     * del kiosco llaman a [drain]; con el candado una misma fila nunca va dos veces a la vez.
     */
    private val mutex = Mutex()

    /** Hora de pared del aparato (sólo para el tope de 72 h). Las pruebas la cambian; no va en el constructor (Dagger/Guice). */
    internal var ahora: () -> Long = System::currentTimeMillis
    // Misma constante que la cuarentena: si se separan, una acción agotada
    // dejaría de listarse o se listaría antes de tiempo.
    private val maxAttempts = ReservationRepository.MAX_ATTEMPTS

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            connectivity.isOnlineFlow.collectLatest { online ->
                if (online) drain()
            }
        }
    }

    /**
     * Manda lo pendiente, en orden. Con [only] sólo las filas de esa acción (el kiosco vacía las suyas sin tocar las del
     * personal). Devuelve el desenlace de las llegadas del kiosco que mandó en esta vuelta, por `rowId`.
     */
    suspend fun drain(only: String? = null): Map<Long, KioskTap.Outcome> = mutex.withLock {
        val pending = pendingDao.all().filter { only == null || it.action == only }
        val kiosk = mutableMapOf<Long, KioskTap.Outcome>()
        var kioskSinRed = false
        var anySuccess = false
        for (entry in pending) {
            if (entry.attemptCount >= maxAttempts) {
                // Agotó los reintentos: se DEJA en la tabla, no se borra.
                //
                // Antes se borraba aquí y la acción del mesero desaparecía sin que
                // nadie se enterara — la mesa quedaba sin confirmar, o un cliente
                // sin cancelar, y no había dónde mirarlo. Conservarla es lo que
                // permite listarla en la cuarentena, igual que los cobros
                // rechazados: visible hasta que un gerente la resuelva a mano.
                android.util.Log.e(
                    "📅",
                    "Acción de reserva EN CUARENTENA tras $maxAttempts intentos: " +
                        "${entry.action} sobre ${entry.reservationId} — nunca llegó al server",
                )
                continue
            }
            // 🔴 ANTES de `valueOf`: una acción desconocida se BORRA ahí abajo, y una llegada no se puede perder.
            if (entry.action == KioskTap.ACTION) {
                val saved = KioskTap.parse(entry.payloadJson)
                if (saved == null) {
                    pendingDao.incrementAttempt(entry.rowId, "Llegada sin datos guardados")
                    continue
                }
                // De otra sucursal: se queda, sin gastar intento, para cuando vuelva esa sucursal.
                if (saved.venueId != secureStorage.venueId) continue
                // ponytail: tras un corte de red las demás llegadas esperan a la siguiente vuelta — una espera de red por
                // vuelta y no una por fila (con WiFi sin datos cada intento tarda hasta el tope de 10 s).
                if (kioskSinRed) continue
                val result = kioskApi.checkInReservation(saved.venueId, entry.reservationId, saved.closesAt, saved.msBeforeClose)
                val outcome = KioskTap.outcome(result)
                when (outcome) {
                    KioskTap.Outcome.Sent -> {
                        // Ya está registrada: una cuarentena vieja de esta reserva (p. ej. 5xx agotados) quedó resuelta, y
                        // dejarla le quitaría el ✓ a una llegada que el servidor SÍ aceptó (Codex, código r3). Los dos
                        // borrados van juntos y sin cancelar: un toque de la de atrás no puede cortar entre uno y otro (r4).
                        withContext(NonCancellable) {
                            pendingDao.delete(entry.rowId)
                            pendingDao.deleteQuarantined(KioskTap.ACTION, entry.reservationId, maxAttempts)
                        }
                        anySuccess = true
                    }
                    // Sin red no gasta intento (la red vuelve); un 5xx sí, para que no se reintente para siempre.
                    KioskTap.Outcome.Kept -> {
                        val error = result.exceptionOrNull()
                        if (error !is IOException) {
                            pendingDao.incrementAttempt(entry.rowId, error?.message)
                        } else if (masDe72h(entry, saved.closesAt)) {
                            // Tope de edad, el mismo del SERVIDOR (`KIOSK_MAX_OFFLINE_MS`): sólo cuando ESTA fila tampoco
                            // salió. Cualquier respuesta del servidor decide sola (él también tiene sus 72 h). Una fila así
                            // no cuenta como «sin red»: las de atrás sí se intentan.
                            pendingDao.quarantine(entry.rowId, maxAttempts, KIOSK_TOO_OLD)
                            kiosk[entry.rowId] = KioskTap.Outcome.Refused(KIOSK_TOO_OLD)
                            continue
                        } else {
                            kioskSinRed = true
                        }
                    }
                    is KioskTap.Outcome.Refused -> pendingDao.quarantine(entry.rowId, maxAttempts, outcome.message)
                }
                kiosk[entry.rowId] = outcome
                continue
            }
            val action = runCatching { ReservationAction.valueOf(entry.action) }.getOrNull()
            if (action == null) {
                pendingDao.delete(entry.rowId)
                continue
            }
            // 🔴 TODO reintento va marcado como de fondo: lo dispara la vuelta de la
            // conexión, no un dedo. Un 403 aquí ya tiene su sitio —la cuarentena, que
            // guarda el motivo y la enseña— y sacarlo además como modal lo pintaba
            // encima de la pantalla en la que estuviera el mesero, una vez por acción
            // pendiente. Es el mismo defecto que el replay del outbox ya resolvió.
            val result: Result<Unit> = when (action) {
                ReservationAction.CONFIRM -> api.confirm(entry.reservationId, background = true).map { Unit }
                ReservationAction.CHECK_IN -> api.checkIn(entry.reservationId, background = true).map { Unit }
                ReservationAction.COMPLETE -> api.complete(entry.reservationId, background = true).map { Unit }
                ReservationAction.NO_SHOW -> api.noShow(entry.reservationId, background = true).map { Unit }
                ReservationAction.CANCEL -> {
                    val req = entry.payloadJson
                        ?.let { json.decodeFromString(CancelReservationRequest.serializer(), it) }
                        ?: CancelReservationRequest()
                    api.cancel(entry.reservationId, req, background = true)
                }
                ReservationAction.RESCHEDULE -> {
                    val req = json.decodeFromString(
                        RescheduleRequest.serializer(),
                        entry.payloadJson ?: error("Missing reschedule payload for ${entry.rowId}"),
                    )
                    api.reschedule(entry.reservationId, req, background = true).map { Unit }
                }
                ReservationAction.CREATE -> {
                    val req = json.decodeFromString(
                        CreateReservationRequest.serializer(),
                        entry.payloadJson ?: error("Missing create payload for ${entry.rowId}"),
                    )
                    api.create(req, background = true).map { Unit }
                }
                ReservationAction.UPDATE -> {
                    val req = json.decodeFromString(
                        UpdateReservationRequest.serializer(),
                        entry.payloadJson ?: error("Missing update payload for ${entry.rowId}"),
                    )
                    api.update(entry.reservationId, req, background = true).map { Unit }
                }
            }
            if (result.isSuccess) {
                pendingDao.delete(entry.rowId)
                anySuccess = true
            } else {
                // El motivo se guarda para que la cuarentena pueda enseñarlo. Sin
                // esto sólo quedaba una guía genérica por tipo de acción, y el
                // "requiere al menos 60 minutos de anticipación" que devuelve el
                // server se perdía en un log que nadie lee.
                pendingDao.incrementAttempt(entry.rowId, result.exceptionOrNull()?.message)
            }
        }
        // Coalesce into a single change signal per drain — mirrors iOS's
        // ReservationActionsRetrier (post once per drain with >=1 success; an
        // all-failure drain posts nothing) so stale screens refresh exactly once.
        if (anySuccess) reservationRepository.notifyChanged()
        kiosk
    }

    /**
     * 🔴 Nunca manda a cuarentena una llegada que el SERVIDOR aceptaría (Codex código r2 #2). Pide las DOS edades contra la
     * hora del aparato AHORA: desde el cierre que vio la persona (hora del SERVIDOR) y desde `createdAt` (hora del aparato
     * al guardarla). Un reloj atrasado al guardar y luego corregido falla la primera; uno adelantado todo el tiempo, la
     * segunda.
     *
     * ponytail: queda un caso — el reloj estaba bien al guardar y luego salta más de 72 h hacia ADELANTE, y ese envío falla
     * por transporte: va a cuarentena antes de tiempo (visible, el gerente la registra a mano). Si pesa, medir «ahora» con
     * la hora del SERVIDOR (el encabezado `Date` de la última respuesta) en vez del reloj del aparato.
     */
    private fun masDe72h(entry: PendingReservationActionEntity, closesAt: String): Boolean {
        val cierre = runCatching { java.time.Instant.parse(closesAt).toEpochMilli() }.getOrNull() ?: return false
        val ahora = ahora()
        return ahora - cierre > KIOSK_MAX_AGE_MS && ahora - entry.createdAt > KIOSK_MAX_AGE_MS
    }

    private companion object {
        const val KIOSK_MAX_AGE_MS = 72 * 60 * 60_000L
        const val KIOSK_TOO_OLD = "La llegada lleva más de 72 horas sin poder enviarse."
    }
}
