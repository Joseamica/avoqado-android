package com.avoqado.pos.reservations.presentation.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class ReservationDetailViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val capabilityProvider: Provider<ReservationsCapability>,
    private val secureStorage: SecureStorage,
    private val printConfigRepository: PrintConfigRepository,
    private val comandaPrinter: ComandaPrinter,
    private val productsRepository: ProductsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val reservationId: String = checkNotNull(savedStateHandle["reservationId"])

    private val _state = MutableStateFlow(ReservationDetailUiState(capability = capabilityProvider.get()))
    val state: StateFlow<ReservationDetailUiState> = _state.asStateFlow()

    private val _overCapacityConfirmation = MutableStateFlow<String?>(null)
    val overCapacityConfirmation: StateFlow<String?> = _overCapacityConfirmation.asStateFlow()
    private var pendingOverCapacityReschedule: ReservationRepository.ActionPayload.Reschedule? = null

    init {
        reload()
        viewModelScope.launch {
            repository.changes.collect { silentRefresh() }
        }
    }

    fun reload() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = repository.fetchOne(reservationId)
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, reservation = r.getOrNull())
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando reserva")
            }
        }
    }

    private fun silentRefresh() {
        viewModelScope.launch {
            val r = repository.fetchOne(reservationId)
            if (r.isSuccess) {
                _state.update { it.copy(reservation = r.getOrNull()) }
            }
        }
    }

    fun runAction(action: ReservationAction, payload: ReservationRepository.ActionPayload? = null) {
        if (!_state.value.isAllowed(action)) return
        // Global in-flight lock: PENDING allows CONFIRM+CHECK_IN+NO_SHOW+CANCEL
        // simultaneously; without this a 2nd tap fired two concurrent mutations
        // on the same reservation (e.g. confirm + no-show racing).
        if (_state.value.pendingAction != null) return
        val before = _state.value.reservation
        _state.update { it.copy(pendingAction = action, error = null, queuedMessage = null, justCompletedAction = null) }
        viewModelScope.launch {
            val r = repository.runAction(reservationId, action, payload)
            val apiError = r.exceptionOrNull() as? ReservationApiException
            if (
                action == ReservationAction.RESCHEDULE &&
                payload is ReservationRepository.ActionPayload.Reschedule &&
                apiError?.code == "OVER_CAPACITY_CONFIRMATION_REQUIRED"
            ) {
                pendingOverCapacityReschedule = payload
                _overCapacityConfirmation.value = buildString {
                    append(apiError.message)
                    apiError.preview?.let { append("\nOcupación: $it.") }
                }
                _state.update { it.copy(reservation = before, pendingAction = null, error = null) }
                return@launch
            }
            val queued = r.exceptionOrNull() as? ReservationRepository.OfflineEnqueuedException
            _state.update { current ->
                when {
                    r.isSuccess -> {
                        val updated = r.getOrNull() ?: before
                        current.copy(reservation = updated, pendingAction = null, justCompletedAction = action)
                    }
                    // Sin red la acción SÍ se guardó: se avisa, no se acusa un error.
                    queued != null -> current.copy(
                        reservation = before,
                        pendingAction = null,
                        queuedMessage = queued.message,
                    )
                    else -> current.copy(
                        reservation = before,
                        pendingAction = null,
                        error = r.exceptionOrNull()?.message ?: "Error",
                    )
                }
            }
            if (r.isSuccess && action == ReservationAction.CHECK_IN) {
                r.getOrNull()?.let { updated -> printCheckInComandas(updated) }
            }
        }
    }

    fun confirmOverCapacityReschedule() {
        val payload = pendingOverCapacityReschedule ?: return
        pendingOverCapacityReschedule = null
        _overCapacityConfirmation.value = null
        runAction(ReservationAction.RESCHEDULE, payload.copy(allowOverCapacity = true))
    }

    fun dismissOverCapacityReschedule() {
        pendingOverCapacityReschedule = null
        _overCapacityConfirmation.value = null
    }

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeQueuedMessage() = _state.update { it.copy(queuedMessage = null) }
    fun consumeJustCompleted() = _state.update { it.copy(justCompletedAction = null) }

    /**
     * PRINT_STATIONS — fire-and-forget side effect of a successful CHECK_IN, mirroring
     * [com.avoqado.pos.payment.presentation.PaymentFlowViewModel.autoPrintAfterPayment].
     *
     * SAFETY: printing must NEVER affect the check-in outcome (already committed by the time
     * this runs). Every failure mode — no venue, no active stations, product cache miss,
     * printer/network exception — is swallowed here and only logged.
     */
    private suspend fun printCheckInComandas(reservation: Reservation) {
        try {
            val venueId = secureStorage.venueId ?: return

            printConfigRepository.refresh(venueId)
            val cfg = printConfigRepository.getCurrentConfig()
            if (cfg.stations.none { it.active }) return

            // Warm the product cache so categoryId resolves below; safe no-op if already loaded,
            // and if it fails/stays empty the mapper falls back to the venue default station.
            productsRepository.fetchProducts(venueId)

            val services = reservation.services
            val items = if (!services.isNullOrEmpty()) {
                services.map { service ->
                    RoutableItem(
                        orderItemId = service.id,
                        productId = service.id,
                        categoryId = productsRepository.getProduct(service.id)?.categoryId,
                        productName = service.name,
                        quantity = 1,
                    )
                }
            } else {
                val productId = reservation.productId ?: return
                listOf(
                    RoutableItem(
                        orderItemId = productId,
                        productId = productId,
                        categoryId = productsRepository.getProduct(productId)?.categoryId,
                        productName = reservation.product?.name ?: reservation.displayServiceName ?: "Servicio",
                        quantity = 1,
                    ),
                )
            }

            val plans = PrintRoutingMapper.buildComandas(items, cfg)
            comandaPrinter.printComandas(
                plans = plans,
                config = cfg,
                orderNumber = reservation.confirmationCode.takeLast(4).ifBlank { "RES" },
                orderType = "Reservación",
            )
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Check-in comanda print failed (check-in already succeeded): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReservationDetailVM"
    }
}
