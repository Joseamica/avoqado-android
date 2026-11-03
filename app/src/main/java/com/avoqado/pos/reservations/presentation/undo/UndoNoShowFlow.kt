package com.avoqado.pos.reservations.presentation.undo

import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.NoShowCorrectionRef
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.UndoNoShowResult
import com.avoqado.pos.reservations.domain.UndoNoShowTexts
import com.avoqado.pos.core.data.network.ServerErrorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * «Sí llegó» de punta a punta, el mismo en el detalle de la reserva, la lista de la clase y la franja:
 * vista previa → confirmación con sólo lo que aplica → POST → resultado (con el pendiente, si quedó).
 *
 * 🔴 Un solo vuelo: mientras hay una vista previa o un POST en curso, otro toque no hace nada (dos toques = un POST).
 * Sin red, el repositorio contesta [ReservationRepository.OfflineRequiredException] sin llamar ni encolar.
 */
class UndoNoShowFlow(
    private val repository: ReservationRepository,
    private val scope: CoroutineScope,
    /** La zona de una sucursal (null = la de la sesión): el título dice la hora de la sucursal de la reserva. */
    private val timezone: (venueId: String?) -> String?,
    /** El SERVIDOR dijo que ya no aplica (otra persona la corrigió o cambió de estado): la pantalla se vuelve a leer. */
    private val onStale: suspend () -> Unit = {},
    private val onUndone: suspend (UndoNoShowResult) -> Unit = {},
) {
    /** [venueId]: la sucursal contra la que va el POST (la del aviso de la franja; null = la de la sesión). */
    data class Prompt(
        val reservationId: String,
        val title: String,
        val rows: List<String>,
        val pending: List<String>,
        val venueId: String? = null,
    )

    data class Done(val message: String, val pending: List<String>)

    /** Aviso con título y cuerpo, como el `.alert` de iOS. [warning]: sin red, en ámbar (nunca en rojo). */
    data class Notice(val title: String, val message: String?, val warning: Boolean = false)

    data class Ui(
        /** La reserva con una vista previa o un POST en vuelo. */
        val busyId: String? = null,
        val prompt: Prompt? = null,
        /** Reserva de un pase: se dice en vez de la confirmación. */
        val blocked: String? = null,
        val done: Done? = null,
        val notice: Notice? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /**
     * [venueId]: la sucursal del aviso de la franja (su vista previa, su título, su POST y su relectura van contra ella,
     * aunque la sesión ande en otra). El detalle y la lista de la clase no la pasan: la de la sesión.
     */
    fun start(reservationId: String, title: String? = null, venueId: String? = null) {
        val now = _ui.value
        if (now.busyId != null || now.prompt != null) return
        _ui.value = Ui(busyId = reservationId)
        scope.launch {
            val preview = repository.previewUndoNoShow(reservationId, venueId)
            val plan = preview.getOrNull()
            if (plan == null) {
                _ui.value = failed(reservationId, venueId, preview.exceptionOrNull())
                return@launch
            }
            val t = title ?: repository.fetchOne(reservationId, venueId).getOrNull()?.let { UndoNoShowTexts.title(it, timezone(venueId)) }
                ?: UndoNoShowTexts.TITLE_FALLBACK
            _ui.value = Ui(
                prompt = Prompt(reservationId, t, UndoNoShowTexts.rows(plan), plan.pending.map(UndoNoShowTexts::pending), venueId),
            )
        }
    }

    fun confirm() {
        val now = _ui.value
        val prompt = now.prompt ?: return
        if (now.busyId != null) return
        _ui.value = now.copy(busyId = prompt.reservationId)
        scope.launch {
            val r = repository.undoNoShow(prompt.reservationId, venueId = prompt.venueId)
            val result = r.getOrNull()
            _ui.value = if (result == null) failed(prompt.reservationId, prompt.venueId, r.exceptionOrNull()) else done(result)
        }
    }

    private suspend fun done(result: UndoNoShowResult): Ui {
        onUndone(result)
        val pending = if (result.correction.resolvedAt == null) result.correction.pending.map(UndoNoShowTexts::pending) else emptyList()
        return Ui(done = Done(if (result.alreadyUndone) UndoNoShowTexts.ALREADY else UndoNoShowTexts.SUCCESS, pending))
    }

    companion object {
        /**
         * «Marcar resuelto» que no salió: sin red o la red se cortó ⇒ en ámbar con el texto de «Marcar resuelto» sin red
         * (el de corte nombra «Sí llegó»); otro rechazo ⇒ título y texto humanizado. Nunca el texto crudo en rojo.
         */
        fun resolveFailure(e: Throwable?): Notice =
            if (e is IOException || e is ReservationRepository.OfflineRequiredException) {
                Notice(UndoNoShowTexts.OFFLINE_TITLE, UndoNoShowTexts.OFFLINE_RESOLVE, warning = true)
            } else {
                Notice(UndoNoShowTexts.ERROR_TITLE, ServerErrorText.humanize(e))
            }
    }

    /** Cerrar lo que esté a la vista (no mientras algo va en camino: su resultado tiene que verse). */
    fun dismiss() {
        _ui.update { if (it.busyId != null) it else Ui() }
    }

    private suspend fun failed(reservationId: String, venueId: String?, e: Throwable?): Ui {
        if (e is ReservationApiException && e.code == "NO_SHOW_UNDO_PASS_BOOKING") return Ui(blocked = UndoNoShowTexts.PASS_BOOKING)
        if (e is ReservationRepository.OfflineRequiredException) {
            return Ui(notice = Notice(UndoNoShowTexts.OFFLINE_TITLE, e.message, warning = true))
        }
        // Antes de decir nada se vuelve a leer: la respuesta pudo perderse con la corrección ya hecha, u otro aparato o
        // el kiosco la corrigió (el 409 NOT_APPLICABLE). Si ya está «Llegó» con su corrección, eso es lo que pasó.
        val fresh = repository.fetchOne(reservationId, venueId).getOrNull()
        val c = fresh?.noShowCorrection
        if (fresh != null && fresh.status == ReservationStatus.CHECKED_IN && c != null) {
            // Cambió en el SERVIDOR: el calendario y las listas se vuelven a leer, igual que tras un «Sí llegó» bueno.
            repository.notifyChanged()
            // ponytail: el id de la corrección no viene en el detalle; nadie lo usa en este camino (sólo la franja del kiosco).
            return done(UndoNoShowResult("ALREADY_UNDONE", fresh, correction = NoShowCorrectionRef("", c.pending, c.resolvedAt)))
        }
        return when {
            e is IOException -> Ui(notice = Notice(UndoNoShowTexts.ERROR_TITLE, UndoNoShowTexts.CUT, warning = true))
            // Ya no aplica y no fue una corrección (otro estado): sólo el título, nunca el texto del SERVIDOR con su
            // estado en inglés; la pantalla se vuelve a leer y el botón deja de salir.
            e is ReservationApiException && e.status == 409 -> {
                repository.notifyChanged()
                onStale()
                Ui(notice = Notice(UndoNoShowTexts.ERROR_TITLE, null))
            }
            else -> Ui(notice = Notice(UndoNoShowTexts.ERROR_TITLE, ServerErrorText.humanize(e)))
        }
    }
}
