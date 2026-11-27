package com.avoqado.pos.pos.presentation.checkout

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.orders.data.model.PaymentStatus
import com.avoqado.pos.orders.presentation.DetalleDeUnaCuenta
import com.avoqado.pos.orders.presentation.OrdersViewModel
import com.avoqado.pos.payment.domain.PaymentOverlayState
import com.avoqado.pos.payment.presentation.CierreDeCuenta
import com.avoqado.pos.payment.presentation.LocalAlSalirDelCobroDeCuenta
import com.avoqado.pos.pos.presentation.cart.avisoCobradaSinConfirmar
import com.avoqado.pos.pos.presentation.cart.avisoCobradaSinRed
import com.avoqado.pos.pos.presentation.cart.avisoCuentaEnPedidos
import com.avoqado.pos.pos.presentation.cart.cierraLaVentana
import com.avoqado.pos.pos.presentation.cart.cuentaCerrada
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * D39 v3 «C» (founder, 26-nov): la cuenta que dejó el atajo «Usar puntos», ENCIMA de Cobrar, como Square y Toast, que canjean sin salir
 * de la venta. Es el mismo detalle de Pedidos ([DetalleDeUnaCuenta]) con su «Recompensas», que se abre solo
 * (`PuertaDeRecompensas.porAbrir`), y se porta igual que Pedidos sin red. Con un cobro abierto no se cierra por fuera (Codex v3 #4); cobrada
 * o cancelada después de verla abierta, se cierra sola cuando la cajera termina con el cobro (Codex v3 #5). Con Atrás se cierra y
 * [onCerrar] recibe dónde quedó la cuenta (null = no hay nada que decir).
 */
@Composable
fun CuentaDelAtajo(
    orderId: String,
    cliente: String,
    /** El número visible (`#ORD-…`) que mandó el SERVIDOR al crearla; "" = no llegó (se usa el de la cuenta leída). */
    numero: String,
    /** El cobro de esta ventana ya terminó («Listo»); vive en el carrito para sobrevivir a girar la pantalla (Codex C1-r2 #4). */
    cobrada: Boolean,
    onCobrada: () -> Unit,
    onCerrar: (aviso: String?) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(key = "atajo-$orderId"),
) {
    // C1 (founder, «memoria propia»): su propia libreta de la cuenta; Pedidos no se entera ni la pisa (Codex v3-r2 #2).
    remember(viewModel) { viewModel.usarLibretaPropia() }
    LaunchedEffect(orderId) { viewModel.selectOrder(orderId) }
    // Como Pedidos (D22, Codex v3 #6): sin red se respalda con la copia y al volver la red se vuelve a pedir la cuenta.
    val ciclo = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, ciclo) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            com.avoqado.pos.onlineorders.data.VentanaALaVista.aLaVista.collectLatest { seVe ->
                if (seVe) coroutineScope {
                    launch { viewModel.copiaAlPerderLaRed() }
                    viewModel.reabrirAlVolverLaRed()
                }
            }
        }
    }
    val pedido by viewModel.selectedOrder.collectAsState()
    val cobrando by PaymentOverlayState.isPaying.collectAsState()
    val cuenta = pedido?.takeIf { it.id == orderId }
    val cerrada = cuenta?.let { cuentaCerrada(it.status, it.orderPaymentStatus == PaymentStatus.PAID) }
    var vioAbierta by remember(orderId) { mutableStateOf(false) }
    // Codex C1-r1 #5: el cobro dijo «pagada» al tocar «Listo»; se cierra cuando la relectura confirma que quedó cerrada (si queda saldo,
    // la ventana sigue con lo que falta).
    val cobroTerminado = cobrada
    LaunchedEffect(cerrada, cobrando, cobroTerminado) {
        if (cerrada == false) vioAbierta = true
        if (cerrada == true && !cobrando && cierraLaVentana(vioAbierta || cobroTerminado, cerrada)) onCerrar(null)
    }
    fun folioVisible() = cuenta?.orderNumber?.takeIf { it.isNotBlank() }?.let { "#$it" } ?: numero
    // Atrás: con un cobro abierto no hace nada; si la cuenta sigue abierta, se dice dónde quedó; si ya estaba cerrada, sólo se cierra.
    val salir = {
        val folio = folioVisible()
        if (!cobrando) onCerrar(when {
            cerrada == true -> null
            cobroTerminado -> avisoCobradaSinConfirmar(cliente, folio)
            else -> avisoCuentaEnPedidos(cliente, folio)
        })
    }
    Dialog(
        onDismissRequest = salir,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        ImmersiveWindow()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // Codex v3-r2 #5: se cierra con lo que dice el cobro al terminar («Listo»), sin esperar a releer la cuenta.
            CompositionLocalProvider(LocalAlSalirDelCobroDeCuenta provides { id, cierre, sinRed ->
                if (id == orderId && cierre == CierreDeCuenta.CANCELADA) onCerrar(null)
                // Codex C1-r2 #5: cobrada desde la copia sin red: no hay SERVIDOR que confirme; se cierra y se dice que se sube sola.
                if (id == orderId && cierre == CierreDeCuenta.PAGADA && sinRed) onCerrar(avisoCobradaSinRed(cliente, folioVisible()))
                if (id == orderId && cierre == CierreDeCuenta.PAGADA && !sinRed) onCobrada()
            }) {
                DetalleDeUnaCuenta(viewModel, onBack = salir, onCuentaCancelada = { onCerrar(null) }) {}
            }
        }
    }
}
