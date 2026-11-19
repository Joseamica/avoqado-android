package com.avoqado.pos.pos.presentation.checkout

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.ReservationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** D13 (Codex r2 #3): lo que la reserva dice HOY. `null` = se cobra aquí. La inscripción de mostrador nace CONFIRMED. */
internal fun yaNoSeCobraAqui(status: ReservationStatus, persona: String?): String? {
    val de = persona?.takeIf { it.isNotBlank() }?.let { " de $it" }.orEmpty()
    return when (status) {
        // Codex r3 #1: «llegó» no prueba que ya se cobró (el QR del kiosco la marca sin abrir cuenta). Se cobra aquí; si recepción
        // ya abrió su cuenta, el servidor rechaza una segunda y la cajera lo ve al cobrar.
        // Codex r6 #1: «completada» tampoco prueba que se pagó (recepción la completa sin cobro), y «no se presentó» con la persona
        // en la caja es un error de recepción: se cobran aquí. Sólo una reserva cancelada sale de pendientes.
        ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN,
        ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW -> null
        ReservationStatus.CANCELLED -> "La reserva$de se canceló: ya no se cobra aquí."
    }
}

@HiltViewModel
class ClasePendienteViewModel @Inject constructor(
    private val reservations: ReservationRepository,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {
    sealed interface Revision {
        data object SeCobra : Revision
        data class YaNo(val motivo: String) : Revision
        /** Sin red, sin permiso de agenda o sin respuesta a tiempo: se deja agregar y se dice que no se pudo revisar. */
        data class SinRevisar(val sinRed: Boolean) : Revision
    }

    suspend fun revisar(seed: ClassCheckoutSeed.Seed): Revision {
        val id = seed.reservationId ?: return Revision.SeCobra
        if (!connectivity.isFullyConnected) return Revision.SinRevisar(sinRed = true)
        // Codex r3 #2: la llamada bloquea su hilo y no se deja cancelar; se espera a su resultado sólo 5 s y, si no llega, se
        // sigue sin ella (la llamada termina sola en segundo plano).
        // Codex r7 #2: «de fondo»: sin permiso de agenda, un 403 no abre «No tienes permiso» ni el PIN de gerente a media caja.
        val consulta = viewModelScope.async(Dispatchers.IO) { reservations.fetchOne(id, seed.venueId, background = true).getOrNull() }
        val reserva = withTimeoutOrNull(5_000) { consulta.await() } ?: return Revision.SinRevisar(sinRed = false)
        return yaNoSeCobraAqui(reserva.status, seed.persona)?.let(Revision::YaNo) ?: Revision.SeCobra
    }
}

/**
 * D13 (18-nov): una clase de «Inscribir y cobrar» que no pudo entrar en su momento ya no entra sola. Aquí se enseña y la
 * cajera decide: «Agregar al carrito» (sólo a una venta nueva, tras preguntarle a recepción cómo va la reserva), «La agrego
 * después» (vuelve a salir cuando la caja queda libre) o «Ya no la cobro aquí» (la reserva no se toca).
 */
@Composable
fun ClasePendiente(cart: CartViewModel, vm: ClasePendienteViewModel = hiltViewModel()) {
    val enEspera by cart.clasesPendientes.collectAsState()
    val cartState by cart.cartState.collectAsState()
    val cliente by cart.selectedCustomer.collectAsState()
    val catalogo by cart.products.collectAsState()
    val scope = rememberCoroutineScope()
    val libre = cartState.isEmpty && cliente == null
    var vistas by remember { mutableStateOf(setOf<ClassCheckoutSeed.Seed>()) }
    // Codex r2 #6: vuelve a avisar en cuanto la caja queda libre (también al quitar al cliente con el carrito ya vacío).
    LaunchedEffect(libre) { if (libre) vistas = emptySet() }
    // Codex r7 #1: cerrado con la X o «La agrego después» con la caja ya libre, vuelve a salir a los 2 minutos si sigue
    // pendiente: una clase sin cobrar no se queda escondida hasta la siguiente venta.
    LaunchedEffect(vistas) { if (vistas.isNotEmpty()) { kotlinx.coroutines.delay(120_000); vistas = emptySet() } }
    var aviso by remember { mutableStateOf<String?>(null) }
    // Codex r6 #2: tras quitar una, la siguiente sale con el botón en el mismo lugar; un doble toque no la quita también.
    var ignorarHasta by remember { mutableStateOf(0L) }
    fun libreParaTocar() = System.currentTimeMillis() >= ignorarHasta
    fun marcarToque() { ignorarHasta = System.currentTimeMillis() + 700 }
    aviso?.let { texto ->
        AvoqadoDialog(title = "Clase pendiente", description = texto, onDismiss = { aviso = null },
            actionButton = { PrimaryButton(text = "Entendido", onClick = { aviso = null }, fullWidth = true) }) {}
        return
    }
    val pendiente = remember(enEspera, vistas) { cart.clasesPendientesDeEsteNegocio().firstOrNull { it !in vistas } } ?: return
    var mensaje by remember(pendiente) { mutableStateOf<String?>(null) }
    var revisando by remember(pendiente) { mutableStateOf(false) }
    val clase = catalogo.firstOrNull { it.id == pendiente.productId }?.name ?: "Clase"
    val lugares = if (pendiente.quantity > 1) " (${pendiente.quantity} lugares)" else ""
    // Codex r4 #1: mientras se consulta a recepción, la decisión ya se tomó («Agregar»): no se puede aplazar ni quitar a medias.
    AvoqadoDialog(title = "Clase pendiente de cobrar", onDismiss = { if (!revisando) vistas = vistas + pendiente },
        dismissOnClickOutside = !revisando) {
        Text(listOfNotNull(clase, pendiente.persona).joinToString(" · ") + lugares)
        if (!libre) Text("Va en su propia venta: termina o guarda la venta actual (y quita al cliente) para agregarla.")
        mensaje?.let { Text(it) }
        PrimaryButton(text = "Agregar al carrito", enabled = libre && !revisando, isLoading = revisando, onClick = agregar@{
            if (!libreParaTocar()) return@agregar
            marcarToque()
            revisando = true
            scope.launch {
                val r = vm.revisar(pendiente)
                // Codex r5 #1: un «La agrego después» que entró en el mismo cuadro que «Agregar» gana: no se agrega.
                if (pendiente in vistas) { revisando = false; return@launch }
                when (r) {
                    is ClasePendienteViewModel.Revision.YaNo -> { cart.quitarClasePendiente(pendiente); aviso = r.motivo }
                    else -> {
                        // Se vuelve a revisar en el carrito: mientras se consultaba pudo empezar otra venta.
                        mensaje = cart.agregarClasePendiente(pendiente)
                        if (mensaje == null && r is ClasePendienteViewModel.Revision.SinRevisar) {
                            // Codex r6 #3: sin permiso de agenda la consulta también falla, con internet: no se dice «Sin conexión».
                            aviso = (if (r.sinRed) "Sin conexión: no" else "No") +
                                " se pudo revisar con recepción si ya la canceló. Revisa antes de cobrar."
                        }
                    }
                }
                revisando = false
            }
        })
        TextButton(enabled = !revisando, onClick = { if (libreParaTocar()) { marcarToque(); vistas = vistas + pendiente } }) {
            Text("La agrego después")
        }
        TextButton(enabled = !revisando, onClick = { if (libreParaTocar()) { marcarToque(); cart.quitarClasePendiente(pendiente) } }) {
            Text("Ya no la cobro aquí")
        }
    }
}
