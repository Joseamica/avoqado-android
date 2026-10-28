package com.avoqado.pos.giftcards.presentation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import javax.inject.Inject

@HiltViewModel class RecuperacionDeSaldoViewModel @Inject constructor(
    private val store: SolicitudesDeTarjetaStore, private val storage: SecureStorage,
    private val coordinator: CobroConSaldoCoordinator, private val locales: CobrosLocalesDeLaCuenta,
) : ViewModel() {
    val solicitudes = store.solicitudes
    val pendiente = coordinator.pendienteDeConfirmar
    val venue: String? get() = storage.venueId
    fun recuperables() = venue?.let(store::delNegocio).orEmpty().filter { it.nuevaCuenta && it.orderId == null && it.porRecuperar }
    fun avisos() = avisosDeCuentasConSaldo(venue?.let(store::delNegocio).orEmpty())
    suspend fun puedeContinuar(id: String): Boolean {
        val actual = venue ?: return false
        return runCatching { !locales.hayPendiente(actual, id) && venue == actual }.getOrDefault(false)
    }
    suspend fun recuperar(llave: String, cart: CartViewModel): Recuperacion {
        var identidadEntregada: String? = null
        return coordinator.recuperarVenta(llave, { cart.cartState.value.isEmpty },
            destinoVigente = { cart.viewModelScope.isActive && identidadEntregada != null && cart.cartState.value.identidadDeVenta == identidadEntregada }) { foto ->
            cart.restaurarFoto(foto).also { if (it) identidadEntregada = cart.cartState.value.identidadDeVenta }
        }
    }
    fun confirmar(llave: String): Boolean = coordinator.confirmarEntrega(llave)
    fun entregaPendienteDelNegocio(): String? = coordinator.entregaPendienteDelNegocio()
    fun esEntregaDelNegocio(llave: String): Boolean = SolicitudesDeTarjeta.reservas(storage.giftCardRequestsJson)
        .any { it.llave == llave && it.venueId == venue }
}

internal fun avisosDeCuentasConSaldo(rows: List<SolicitudDeTarjeta>): List<SolicitudDeTarjeta> =
    rows.filter { it.tipo == "GIFT_CARD_REDEEM" && it.porTerminar && it.orderId != null }.distinctBy { it.orderId }

@Composable fun VentaPorRecuperar(cart: CartViewModel, vm: RecuperacionDeSaldoViewModel = hiltViewModel()) {
    val rows by vm.solicitudes.collectAsState()
    val pendienteGuardada by vm.pendiente.collectAsState()
    val pendiente = remember(pendienteGuardada, vm.venue) { vm.entregaPendienteDelNegocio()?.takeIf(vm::esEntregaDelNegocio) }
    val cartState by cart.cartState.collectAsState()
    val scope = rememberCoroutineScope()
    var vistas by remember(vm.venue) { mutableStateOf(setOf<String>()) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    if (pendiente != null) AvoqadoDialog(onDismiss = {}, title = TextosDeTarjetas.NO_SE_PUDO_RECUPERAR, dismissOnClickOutside = false) {
        PrimaryButton(text = TextosDeTarjetas.REINTENTAR, onClick = { pendiente?.let(vm::confirmar) })
    } else {
        val fila = remember(rows, vm.venue, vistas) { vm.recuperables().firstOrNull { it.llave !in vistas } }
        if (fila != null) AvoqadoDialog(onDismiss = { vistas += fila.llave }, title = TextosDeTarjetas.VENTA_POR_RECUPERAR) {
            if (!cartState.isEmpty) Text(TextosDeTarjetas.TERMINA_LA_VENTA_ACTUAL)
            mensaje?.let { Text(it) }
            PrimaryButton(text = TextosDeTarjetas.RECUPERAR_VENTA, enabled = cartState.isEmpty, onClick = {
                scope.launch {
                    when (vm.recuperar(fila.llave, cart)) {
                        Recuperacion.RECUPERADA -> vistas += fila.llave
                        Recuperacion.PENDIENTE_DE_CONFIRMAR -> Unit
                        else -> mensaje = TextosDeTarjetas.NO_SE_PUDO_RECUPERAR
                    }
                }
            })
            TextButton(onClick = { vistas += fila.llave }) { Text(TextosDeTarjetas.LO_REVISO_DESPUES) }
        }
    }
}
