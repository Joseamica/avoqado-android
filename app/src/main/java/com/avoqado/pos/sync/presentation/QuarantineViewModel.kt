package com.avoqado.pos.sync.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.CortesiaPersistida
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationsCapability
import com.avoqado.pos.reservations.domain.UndoNoShowTexts
import com.avoqado.pos.reservations.presentation.undo.UndoNoShowFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val cashPaymentRepository: com.avoqado.pos.payment.data.CashPaymentRepository,
    /** «Pagar después» sin red (27-oct). Default para las pruebas que construyen este ViewModel a mano. */
    private val payLaterQueue: com.avoqado.pos.payment.data.PayLaterQueue? = null,
) : ViewModel() {

    /**
     * Cuentas de «Pagar después» de esta sucursal que una persona tiene que ver: RECHAZADA (el servidor no la creó: «Reintentar»
     * o «Descartar») y REVISAR (existe por otro importe, o no se pudo leer: «Descartar» sólo la quita del aparato).
     */
    val cuentasPagarDespues: StateFlow<List<com.avoqado.pos.payment.data.PayLaterRow>> =
        // Sigue a la sucursal de la sesión (Codex A-8); Reintentar y Descartar la revalidan en la fila.
        (payLaterQueue?.porRevisarEnLaSucursal ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** La cuenta que se está reintentando ahora (el botón espera el desenlace real, hasta 15 s). */
    private val _reintentandoCuenta = MutableStateFlow<String?>(null)
    val reintentandoCuenta: StateFlow<String?> = _reintentandoCuenta.asStateFlow()

    fun reintentarCuenta(id: String) {
        val cola = payLaterQueue ?: return
        if (!canResolve || _reintentandoCuenta.value != null) return
        _reintentandoCuenta.value = id
        viewModelScope.launch {
            try {
                when (val d = cola.reintentar(id)) {
                    is com.avoqado.pos.payment.data.DesenlacePagarDespues.Creada ->
                        if (cola.filas.value.none { it.id == id }) _successMessage.value = "Cuenta registrada"
                        else _errorMessage.value = cola.filas.value.firstOrNull { it.id == id }
                            ?.let { com.avoqado.pos.payment.data.detalleDePagarDespues(it) }
                    is com.avoqado.pos.payment.data.DesenlacePagarDespues.Rechazada -> _errorMessage.value = d.mensaje
                    null -> Unit
                    // Sin red o sin respuesta útil: vuelve a la fila de «por subir» y se sube sola.
                    else -> _errorMessage.value = "Sin conexión: la cuenta sigue guardada en el aparato y se sube sola."
                }
            } finally {
                _reintentandoCuenta.value = null
            }
        }
    }

    /** Una venta que no se dejó para después ni se pudo guardar: a «Carritos guardados», sin mandar nada al servidor. */
    fun pasarACarritosGuardados(id: String) {
        val cola = payLaterQueue ?: return
        if (!canResolve) return
        viewModelScope.launch {
            when (cola.pasarACarritosGuardados(id)) {
                true -> _successMessage.value = "Venta pasada a carritos guardados"
                false -> _errorMessage.value = com.avoqado.pos.pos.presentation.cart.PAGAR_DESPUES_NO_SE_GUARDO
                null -> Unit // ya se está trasladando sola
            }
        }
    }

    /** Ya confirmado en la pantalla. Una REVISAR existe en el servidor: descartarla sólo la quita de este aparato. */
    fun descartarCuenta(id: String) {
        val cola = payLaterQueue ?: return
        if (!canResolve) return
        viewModelScope.launch {
            if (cola.descartar(id)) _successMessage.value = "Cuenta descartada"
        }
    }

    /**
     * Cobros en efectivo que la app guardó ANTES de mandarlos y cuyo intento quedó interrumpido (la
     * app se cerró o murió a media petición). No se sabe si la venta se completó: una persona decide.
     */
    val cobrosInterrumpidos: StateFlow<List<PendingPaymentEntity>> =
        cashPaymentRepository.observarSinConfirmar()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /**
     * «Sí, se cobró»: se manda con la MISMA llave del intento. Si el servidor ya lo tenía, lo reconoce
     * y no crea otra venta; si no, lo registra.
     */
    fun confirmarCobroInterrumpido(id: String) {
        if (!canResolve) return
        viewModelScope.launch {
            if(cashPaymentRepository.confirmarQueSeCobro(id)) {
                paymentSyncService.syncNow();_successMessage.value="Cobro enviado a sincronizar"
            } else {_errorMessage.value="El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar."}
        }
    }

    /** «No se cobró»: el cliente no pagó, así que no hay venta que registrar. */
    fun descartarCobroInterrumpido(id: String) {
        if (!canResolve) return
        viewModelScope.launch {
            cashPaymentRepository.descartarPorqueNoSeCobro(id)
            _errorMessage.value="El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar."
        }
    }

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
    private val _revisionCancelada = MutableStateFlow<PaymentSyncService.PropuestaRevisionCancelada?>(null)
    val revisionCancelada = _revisionCancelada.asStateFlow()

    val rejectedCount: StateFlow<Int> = syncOutbox.rejectedCount

    /**
     * Resolver/descartar conciliaciones altera caja: gerente o superior.
     *
     * 🔴 Tiene gate PROPIO, no el del reembolso. Las 4 acciones de aquí borran
     * de la base LOCAL y no tienen endpoint detrás, así que **este gate del
     * cliente es el único que existe**: no hay 403 del server que corrija un
     * error. Colgarlo de `canIssueRefund` funcionó mientras ése era una lista de
     * roles; al espejarlo de `payments:refund` (arreglo correcto para el
     * reembolso, medido en la D3) el CAJERO se llevó de regalo el poder de
     * borrar para siempre del aparato el registro de un cobro que no cuadró.
     * Ver `RoleManager.canResolveQuarantine`.
     */
    val canResolve: Boolean
        get() = roleManager.canResolveQuarantine

    fun load() {
        val venueId = secureStorage.venueId ?: return
        val actor = secureStorage.userId
        val role = secureStorage.userRole
        val epoch = syncOutbox.contextoDeSesionQ15()
        viewModelScope.launch {
            val items = syncOutbox.rejectedIntents(venueId)
            val failed = paymentSyncService.failedPayments().filter {it.venueId==venueId}
            val reservations = reservationRepository.quarantinedActions()
            if (secureStorage.venueId != venueId || secureStorage.userId != actor ||
                secureStorage.userRole != role || syncOutbox.contextoDeSesionQ15() != epoch) return@launch
            _items.value = items
            _failedPayments.value = failed
            _failedReservationActions.value = reservations
        }
    }

    /** El gerente resolvió el rechazo a mano (recobró, re-comandó) y lo descarta. */
    fun dismiss(id: String) {
        if (!canResolve) return
        val venueId = secureStorage.venueId ?: return
        viewModelScope.launch {
            if(_items.value.firstOrNull {it.id==id}?.type=="PAY_CASH") {
                _errorMessage.value="El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar."
                return@launch
            }
            syncOutbox.dismissRejected(venueId,id)
            _successMessage.value="Operación marcada como resuelta"
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
        _retryingPaymentId.value = id
        viewModelScope.launch {
            try {
            when (val outcome = paymentSyncService.retryFailedPayment(id)) {
                is PaymentSyncService.RetryOutcome.Applied ->
                    _successMessage.value = "Cobro sincronizado"
                is PaymentSyncService.RetryOutcome.StillQueued ->
                    _errorMessage.value = "El pago sigue guardado y su resultado no está confirmado. No vuelvas a cobrar."
                is PaymentSyncService.RetryOutcome.ConsultaSinPrueba ->
                    _errorMessage.value = "No se pudo comprobar que esta cuenta esté cancelada y sin cobros. El intento sigue por revisar. No vuelvas a cobrar."
                is PaymentSyncService.RetryOutcome.Rejected ->
                    _errorMessage.value = outcome.error?.let { detalleRechazo(it) }
                        ?: "El servidor volvió a rechazar el cobro"
                is PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview -> {
                    if (canResolve && secureStorage.venueId == outcome.propuesta.original.venueId &&
                        secureStorage.userId == outcome.propuesta.actor && secureStorage.userRole?.uppercase() == outcome.propuesta.rol &&
                        syncOutbox.contextoDeSesionQ15() == outcome.propuesta.contexto) _revisionCancelada.value = outcome.propuesta
                }
            }
            load()
            } finally {
                _retryingPaymentId.value = null
            }
        }
    }

    fun cerrarRevisionCancelada() {
        if (_retryingPaymentId.value == null) _revisionCancelada.value = null
    }

    fun confirmarRevisionCancelada() {
        val proposal = _revisionCancelada.value ?: return
        if (!canResolve || _retryingPaymentId.value != null) return
        _retryingPaymentId.value = proposal.original.id
        viewModelScope.launch {
            try {
                val saved = paymentSyncService.confirmarRevisionCancelada(proposal)
                _revisionCancelada.value = null
                if (saved) _successMessage.value = "Revisión guardada en el historial."
                else _errorMessage.value = "No se pudo comprobar la revisión. El intento sigue conservado; no vuelvas a cobrar."
                load()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                _revisionCancelada.value = null
                _errorMessage.value = "No se pudo guardar la revisión. El intento sigue conservado."
            } finally { _retryingPaymentId.value = null }
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
            val removed=paymentSyncService.dismissFailedPayment(id)
            if(removed) _successMessage.value="Cobro marcado como resuelto"
            else _errorMessage.value="El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar."
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

    // MARK: - D4 · «Sí llegó» en una llegada rechazada, y el aviso cuando el SERVIDOR la corrigió solo

    /**
     * «Sí llegó» y «Marcar resuelta» del aviso piden lo MISMO que en el detalle de la reserva (`reservations:update`, que
     * recepción tiene por omisión), no el candado de gerente de [canResolve]: van al SERVIDOR, que las juzga con su propio
     * permiso, y la fila sólo se borra cuando él ya contestó. Las demás acciones de aquí siguen con [canResolve].
     */
    val canFixArrival: Boolean
        get() = ReservationsCapability.fromPermissions(secureStorage.venuePermissions).canUpdate

    /** La fila de la llegada rechazada que se está corrigiendo: al salir bien, se va de la lista. */
    private var filaPorCorregir: Long? = null

    /**
     * La zona de la sucursal de una fila del kiosco ([KioskTap.venueOf]): su aviso y el título de su «Sí llegó» dicen la
     * hora de ESA sucursal, aunque la sesión ande en otra (Cancún va una hora adelante de CDMX). La toma de la lista de
     * sucursales del usuario (la del selector). Si no la conoce, o la fila no trae sucursal, la de la activa, como antes:
     * no se inventa una zona.
     */
    fun zonaDe(venueId: String?): String? =
        venueId?.takeIf { it != secureStorage.venueId }
            ?.let { v -> secureStorage.venuesList.firstOrNull { it.id == v }?.timezone?.takeIf { it.isNotBlank() } }
            ?: secureStorage.venueTimezone

    val undo = UndoNoShowFlow(reservationRepository, viewModelScope, ::zonaDe) {
        filaPorCorregir?.let { reservationRepository.dismissQuarantined(it) }
        filaPorCorregir = null
        load()
    }

    /**
     * Una llegada del kiosco que el SERVIDOR rechazó porque su «No asistió» le ganó (y no pudo corregirlo solo). La fila
     * guarda el TEXTO del 409, no su código: se reconoce por él (`kioskCheckIn.service.ts`, «…ya estaba como «No asistió»…»).
     * ponytail: si el SERVIDOR cambia ese texto el botón deja de salir y queda «Revisa la reserva en Reservaciones»
     * (falla segura); entonces, guardar el `code` del rechazo en la fila.
     */
    fun canUndoNoShow(entry: PendingReservationActionEntity): Boolean =
        canFixArrival && entry.action == KioskTap.ACTION && entry.lastError?.contains("«No asistió»") == true

    fun undoNoShow(entry: PendingReservationActionEntity) {
        val ui = undo.ui.value
        if (!canUndoNoShow(entry) || ui.busyId != null || ui.prompt != null) return
        filaPorCorregir = entry.rowId
        // Contra la sucursal DE LA LLEGADA (su toque guardado la trae), aunque la sesión ande en otra.
        undo.start(entry.reservationId, venueId = KioskTap.venueOf(entry.payloadJson))
    }

    fun isCorrectionNotice(entry: PendingReservationActionEntity): Boolean = entry.action == KioskTap.CORRECTION

    /** «Karla R. llegó a las 08:49 sin conexión; su «No se presentó» ya quedó como «Llegó».» y un «Pendiente: …» por cada cosa que falta. */
    fun correctionNoticeLines(entry: PendingReservationActionEntity): List<String> {
        val c = KioskTap.parseCorrection(entry.payloadJson)
        val hora = UndoNoShowTexts.hhmm(c?.tappedAt, zonaDe(KioskTap.venueOf(entry.payloadJson)))
        return listOf(UndoNoShowTexts.kioskNotice(c?.name, hora)) +
            c?.pending.orEmpty().map { "Pendiente: ${UndoNoShowTexts.pending(it)}" }
    }

    private val _resolviendoAviso = MutableStateFlow<Long?>(null)
    val resolviendoAviso: StateFlow<Long?> = _resolviendoAviso.asStateFlow()

    /** «Marcar resuelta» que no salió: sin red en ámbar, un rechazo humanizado; nunca el texto crudo en rojo. */
    private val _resolveNotice = MutableStateFlow<UndoNoShowFlow.Notice?>(null)
    val resolveNotice: StateFlow<UndoNoShowFlow.Notice?> = _resolveNotice.asStateFlow()

    fun consumeResolveNotice() {
        _resolveNotice.value = null
    }

    /** «Marcar resuelta» del aviso: se resuelve en el SERVIDOR (sólo con red) y entonces sale de la franja. */
    fun resolveCorrectionNotice(entry: PendingReservationActionEntity) {
        if (!canFixArrival || !isCorrectionNotice(entry) || _resolviendoAviso.value != null) return
        _resolviendoAviso.value = entry.rowId
        _resolveNotice.value = null
        viewModelScope.launch {
            try {
                // Contra la sucursal DEL AVISO (la de la llegada), aunque la sesión ande en otra; un aviso viejo sin ella va
                // contra la de la sesión, como antes.
                val venueId = KioskTap.venueOf(entry.payloadJson)
                val r = reservationRepository.resolveNoShowCorrection(entry.reservationId, venueId = venueId)
                // 404 en SU sucursal: el SERVIDOR ya no la tiene pendiente. Se quita para que no se atore, sin decir que la
                // resolvimos; allá siguen su registro, la campanita y el correo. Cualquier otro error deja el aviso, y también
                // el 404 de un aviso viejo (sin sucursal): pudo venir de la sucursal equivocada.
                val yaNoEstaba = venueId != null && (r.exceptionOrNull() as? ReservationApiException)?.status == 404
                if (r.isSuccess || yaNoEstaba) {
                    reservationRepository.dismissQuarantined(entry.rowId)
                    _successMessage.value = if (yaNoEstaba) UndoNoShowTexts.NO_LONGER_PENDING else UndoNoShowTexts.RESOLVED
                    load()
                } else {
                    _resolveNotice.value = UndoNoShowFlow.resolveFailure(r.exceptionOrNull())
                }
            } finally {
                _resolviendoAviso.value = null
            }
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
        com.avoqado.pos.kiosk.data.KioskTap.ACTION -> "Llegada en el kiosco (sin conexión)"
        KioskTap.CORRECTION -> "Llegada del kiosco corregida"
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
        // El texto del servidor (renglón de arriba) ya dice quién llegó, a qué hora y qué pasó; aquí sólo a dónde ir.
        com.avoqado.pos.kiosk.data.KioskTap.ACTION -> "Revisa la reserva en Reservaciones."
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
        "KDS_TICKET_MARK" -> "Marca de comanda en papel"
        else -> type
    }

    /** Guía de resolución según la razón del rechazo. */
    fun resolutionHint(errorCode: String?, type: String): String = if(type=="PAY_CASH" && errorCode!="STALE_DEVICE_SEQUENCE") {
        "El resultado de este pago no está confirmado. Conserva su folio y el efectivo registrado. No vuelvas a cobrar."
    } else when (errorCode) {
        // 🔴 Esta cuenta NO se puede anular ni fusionar hasta que su cobro con tarjeta termine:
        // lo que hace falta es resolver el cobro, no volver a intentar la operación.
        com.avoqado.pos.payment.domain.CancelacionDeCobro.ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE ->
            com.avoqado.pos.payment.domain.CancelacionDeCobro.COBRO_EN_CURSO_BLOQUEA_CUENTA
        "TABLE_OWNED_BY_OTHER" -> "Otro mesero ya trabajaba esta mesa. Revisa con él y vuelve a hacer la operación si aplica."
        "FEATURE_LOCKED" -> "El plan del local no tiene esta función activa. Contacta a administración."
        "ORDER_NOT_FOUND" -> "La orden ya no existe (pudo cancelarse). No requiere acción."
        "OUTCOME_UNKNOWN" -> "El servidor evitó repetirla porque su resultado quedó incierto. Revisa la cuenta y la caja antes de hacer cualquier corrección."
        "STALE_DEVICE_SEQUENCE" -> "El dispositivo detectó una numeración anterior. Revisa la cuenta y los cobros antes de repetir esta operación."
        else -> if (type == "PAY_CASH") {
            "El resultado de este pago no está confirmado. Conserva su folio y el efectivo registrado. No vuelvas a cobrar."
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
    fun detalleRechazo(error: String): String {
        if (error == CortesiaPersistida.AVISO) return CortesiaPersistida.AVISO
        // These are the two persisted HTTP formats from PaymentSyncService. Body amounts and folios are not codes.
        val prefix = error.trimStart()
        val code = Regex("""^(?:Error|HTTP)\s+([1-5]\d{2})(?=$|[\s:])""",RegexOption.IGNORE_CASE)
            .find(prefix)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""^Order recreate (?:failed|server error)\s+\(([1-5]\d{2})\)(?=$|:)""",RegexOption.IGNORE_CASE)
                .find(prefix)?.groupValues?.get(1)?.toIntOrNull()
        return when {
        code == 401 ->
            "La sesión expiró. Inicia sesión de nuevo cuando no queden operaciones pendientes y vuelve a intentar."
        code == 403 ->
            "El empleado no tenía permiso para registrar este cobro. Un gerente debe revisar el rol y reintentarlo."
        code == 404 ->
            "La orden vinculada ya no existe. Verifica caja y ventas antes de volver a cobrar."
        error.contains("already paid", ignoreCase = true) ->
            "El servidor informa que la cuenta está pagada. Comprueba este pago por su folio; no vuelvas a cobrar."
        code == 400 -> {
            val fallback = "El servidor rechazó los datos. Verifica la orden y la caja antes de reintentar."
            val start = error.indexOf('{')
            val detail = if (start >= 0) ServerErrorText.fromResponseBody(error.substring(start),fallback) else fallback
            detail.replace(Regex("<[^>]*>"), "").filterNot { it.isISOControl() }
                .replace(Regex("\\s+"), " ").trim().take(500).ifBlank { fallback }
        }
        else ->
            "El cobro no logró confirmarse. Conserva el efectivo registrado y reintenta cuando el servidor esté disponible."
        }
    }
}
