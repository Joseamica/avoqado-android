package com.avoqado.pos.sync.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import com.avoqado.pos.reservations.data.ReservationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cuarentena de sincronización: lista las operaciones offline que el server
 * RECHAZÓ al reconectar (cobros/rondas que el reducer refutó) para que el
 * gerente las vea y resuelva. Esta pantalla es la pieza que evita el "rechazo
 * silencioso" — antes el contador se calculaba pero no lo mostraba nadie.
 */
@HiltViewModel
class QuarantineViewModel @Inject constructor(
    private val syncOutbox: SyncOutbox,
    private val secureStorage: SecureStorage,
    private val paymentSyncService: PaymentSyncService,
    private val reservationRepository: ReservationRepository,
    private val roleManager: RoleManager,
) : ViewModel() {

    private val _items = MutableStateFlow<List<SyncOutbox.QuarantinedIntent>>(emptyList())
    val items: StateFlow<List<SyncOutbox.QuarantinedIntent>> = _items.asStateFlow()

    private val _failedPayments = MutableStateFlow<List<PendingPaymentEntity>>(emptyList())
    val failedPayments: StateFlow<List<PendingPaymentEntity>> = _failedPayments.asStateFlow()

    /**
     * Acciones de reserva que agotaron sus reintentos.
     *
     * Antes se borraban en silencio: la mesa se quedaba sin confirmar, o un
     * cliente sin cancelar, y no había dónde enterarse. Van aquí por la misma
     * razón que los cobros rechazados — alguien tiene que resolverlas a mano.
     */
    private val _failedReservationActions = MutableStateFlow<List<PendingReservationActionEntity>>(emptyList())
    val failedReservationActions: StateFlow<List<PendingReservationActionEntity>> = _failedReservationActions.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    /** Un rechazo NO se pinta con palomita verde. Va por su propio canal. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun consumeError() {
        _errorMessage.value = null
    }

    /**
     * Cobro que está reintentando AHORA. El reintento espera el desenlace real
     * (hasta 12 s), así que sin esto el gerente toca "Reintentar" y la pantalla
     * no hace absolutamente nada durante 12 segundos — en una pantalla de
     * DINERO eso invita a picarle otra vez. Espejo de `retryingPaymentId` de
     * iOS (QuarantineView.swift), que sí lo tenía desde el primer día.
     */
    private val _retryingPaymentId = MutableStateFlow<String?>(null)
    val retryingPaymentId: StateFlow<String?> = _retryingPaymentId.asStateFlow()

    val rejectedCount: StateFlow<Int> = syncOutbox.rejectedCount

    /** Resolver/descartar conciliaciones altera caja: gerente o superior. */
    val canResolve: Boolean
        get() = roleManager.canIssueRefund

    fun load() {
        val venueId = secureStorage.venueId ?: return
        viewModelScope.launch {
            _items.value = syncOutbox.rejectedIntents(venueId)
            _failedPayments.value = paymentSyncService.failedPayments()
            _failedReservationActions.value = reservationRepository.quarantinedActions()
        }
    }

    /** El gerente resolvió el rechazo a mano (recobró, re-comandó) y lo descarta. */
    fun dismiss(id: String) {
        if (!canResolve) return
        val venueId = secureStorage.venueId ?: return
        viewModelScope.launch {
            syncOutbox.dismissRejected(venueId, id)
            _successMessage.value = "Operación marcada como resuelta"
            load()
        }
    }

    /**
     * Un mensaje que NO depende del desenlace es una mentira con palomita
     * verde: el gerente lee "listo", el cobro sigue en la lista, y esta pantalla
     * existe justo para responder "¿este cobro entró?".
     */
    fun retryPayment(id: String) {
        if (!canResolve) return
        if (_retryingPaymentId.value != null) return // ya hay uno en vuelo
        viewModelScope.launch {
            _retryingPaymentId.value = id
            try {
            when (val outcome = paymentSyncService.retryFailedPayment(id)) {
                is PaymentSyncService.RetryOutcome.Applied ->
                    _successMessage.value = "Cobro sincronizado"
                is PaymentSyncService.RetryOutcome.StillQueued ->
                    _successMessage.value = "Sin conexión — el cobro quedó en cola y se reintentará solo"
                is PaymentSyncService.RetryOutcome.Rejected ->
                    _errorMessage.value = outcome.error?.let { detalleRechazo(it) }
                        ?: "El servidor volvió a rechazar el cobro"
            }
            load()
            } finally {
                _retryingPaymentId.value = null
            }
        }
    }

    /**
     * "Marcar resuelta" para un COBRO fallido. Existía para operaciones
     * rechazadas y reservas pero no para cobros: la guía decía "márcalo como
     * resuelto" apuntando a un botón inexistente, y un cobro en "ya está
     * pagada" era IRRESOLUBLE — tarjeta y banner eternos (visto en la T3 el
     * 2026-08-09 con el caso real de $129).
     */
    fun dismissPayment(id: String) {
        if (!canResolve) return
        viewModelScope.launch {
            paymentSyncService.dismissFailedPayment(id)
            _successMessage.value = "Cobro marcado como resuelto"
            load()
        }
    }

    /** Ya se resolvió a mano (se volvió a agendar, se llamó al cliente). */
    fun dismissReservationAction(rowId: Long) {
        if (!canResolve) return
        viewModelScope.launch {
            reservationRepository.dismissQuarantined(rowId)
            _successMessage.value = "Acción de reserva marcada como resuelta"
            load()
        }
    }

    /** Qué intentaba hacer el mesero, en palabras. */
    fun describeReservationAction(action: String): String = when (action) {
        "CONFIRM" -> "Confirmar reserva"
        "CHECK_IN" -> "Registrar llegada"
        "COMPLETE" -> "Completar reserva"
        "NO_SHOW" -> "Marcar no presentado"
        "CANCEL" -> "Cancelar reserva"
        "RESCHEDULE" -> "Reagendar"
        "CREATE" -> "Crear reserva"
        "UPDATE" -> "Editar reserva"
        else -> action
    }

    /**
     * Qué hacer con ella. La diferencia importa: una reserva que nunca se creó
     * deja a alguien sin lugar, y una cancelación que no llegó deja una mesa
     * bloqueada toda la noche.
     */
    /** Lo que dijo el server, si lo dijo. Es más útil que cualquier guía nuestra. */
    fun reservationRejectionReason(entry: PendingReservationActionEntity): String? =
        entry.lastError?.takeIf { it.isNotBlank() }?.let { ServerErrorText.humanize(it) }

    fun reservationResolutionHint(entry: PendingReservationActionEntity): String = when (entry.action) {
        "CREATE" -> "La reserva NUNCA se creó en el sistema. Agéndala de nuevo antes de descartarla, o el cliente llegará sin lugar."
        "CANCEL" -> "La cancelación no llegó: la reserva sigue ocupando el horario. Cancélala a mano para liberarlo."
        "RESCHEDULE" -> "El cambio de horario no se aplicó. La reserva sigue en su hora original — reagéndala o avisa al cliente."
        "NO_SHOW" -> "No quedó marcada como no presentado. Si aplica un cargo, revísalo antes de descartarla."
        "CHECK_IN" -> "La llegada no quedó registrada. Márcala a mano si el cliente sí llegó."
        else -> "La operación nunca llegó al servidor. Revisa la reserva y vuelve a hacerla si sigue aplicando."
    }

    fun consumeSuccess() {
        _successMessage.value = null
    }

    /** Texto humano por tipo de operación. */
    fun describeType(type: String): String = when (type) {
        "OPEN_TABLE" -> "Abrir mesa"
        "ADD_ITEMS" -> "Enviar ronda"
        "PAY_CASH" -> "Cobro en efectivo"
        "APPLY_DISCOUNT" -> "Aplicar descuento"
        "APPLY_SERVICE_CHARGE" -> "Cargo por servicio"
        "COMP_ORDER" -> "Cortesía de cuenta"
        "UPDATE_DETAILS" -> "Editar detalles"
        "CANCEL_ORDER" -> "Anular cuenta"
        "MOVE_ORDER" -> "Mover mesa"
        "ASSIGN_ORDER" -> "Asignar mesero"
        "CLEAR_TABLE" -> "Liberar mesa"
        else -> type
    }

    /** Guía de resolución según la razón del rechazo. */
    fun resolutionHint(errorCode: String?, type: String): String = when (errorCode) {
        "TABLE_OWNED_BY_OTHER" -> "Otro mesero ya trabajaba esta mesa. Revisa con él y vuelve a hacer la operación si aplica."
        "FEATURE_LOCKED" -> "El plan del local no tiene esta función activa. Contacta a administración."
        "ORDER_NOT_FOUND" -> "La orden ya no existe (pudo cancelarse). No requiere acción."
        "OUTCOME_UNKNOWN" -> "El servidor evitó repetirla porque su resultado quedó incierto. Revisa la cuenta y la caja antes de hacer cualquier corrección."
        "STALE_DEVICE_SEQUENCE" -> "El dispositivo intentó reutilizar una secuencia anterior. Revisa la operación y vuelve a iniciar sesión antes de continuar."
        else -> if (type == "PAY_CASH") {
            "El cobro no se registró. El efectivo está en caja: vuelve a cobrar esta cuenta desde el POS y luego descártalo."
        } else {
            "El server rechazó esta operación. Revísala y vuelve a hacerla manualmente si sigue aplicando."
        }
    }

    fun paymentResolutionHint(payment: PendingPaymentEntity): String =
        payment.lastError?.let { detalleRechazo(it) }
            ?: "El cobro no logró confirmarse. Conserva el efectivo registrado y reintenta cuando el servidor esté disponible."

    /**
     * Traduce el error crudo del server a lo que el gerente tiene que DECIDIR.
     *
     * 🔴 El caso que importa es "Order is already paid": NO es un cobro perdido,
     * es un cobro que YA quedó aplicado y cuya respuesta se perdió en el camino
     * (servidor reiniciado, WiFi caído a media respuesta). El texto genérico
     * —"verifica la orden y la caja antes de reintentar"— empuja justo al error
     * contrario: volver a cobrarle al cliente algo que ya pagó.
     *
     * Visto el 2026-08-09: seis reintentos con ese 400 y el dinero ya estaba
     * registrado desde el primero.
     */
    fun detalleRechazo(error: String): String = when {
        error.contains("already paid", ignoreCase = true) ->
            "Esta cuenta YA está pagada en el servidor: el cobro sí quedó registrado. " +
                "NO lo vuelvas a cobrar — márcalo como resuelto."
        error.contains("401") ->
            "La sesión expiró. Inicia sesión de nuevo cuando no queden operaciones pendientes y vuelve a intentar."
        error.contains("403") ->
            "El empleado no tenía permiso para registrar este cobro. Un gerente debe revisar el rol y reintentarlo."
        error.contains("404") ->
            "La orden vinculada ya no existe. Verifica caja y ventas antes de volver a cobrar."
        error.contains("400") ->
            "El servidor rechazó los datos. Verifica la orden y la caja antes de reintentar."
        else ->
            "El cobro no logró confirmarse. Conserva el efectivo registrado y reintenta cuando el servidor esté disponible."
    }
}
