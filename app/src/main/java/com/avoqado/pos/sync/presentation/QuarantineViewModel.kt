package com.avoqado.pos.sync.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
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
) : ViewModel() {

    private val _items = MutableStateFlow<List<SyncOutbox.QuarantinedIntent>>(emptyList())
    val items: StateFlow<List<SyncOutbox.QuarantinedIntent>> = _items.asStateFlow()

    val rejectedCount: StateFlow<Int> = syncOutbox.rejectedCount

    fun load() {
        val venueId = secureStorage.venueId ?: return
        viewModelScope.launch { _items.value = syncOutbox.rejectedIntents(venueId) }
    }

    /** El gerente resolvió el rechazo a mano (recobró, re-comandó) y lo descarta. */
    fun dismiss(id: String) {
        val venueId = secureStorage.venueId ?: return
        viewModelScope.launch {
            syncOutbox.dismissRejected(venueId, id)
            load()
        }
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
        else -> if (type == "PAY_CASH") {
            "El cobro no se registró. El efectivo está en caja: vuelve a cobrar esta cuenta desde el POS y luego descártalo."
        } else {
            "El server rechazó esta operación. Revísala y vuelve a hacerla manualmente si sigue aplicando."
        }
    }
}
