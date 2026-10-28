package com.avoqado.pos.giftcards.presentation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.giftcards.data.CobroConSaldo

internal sealed interface AvisoDeSaldo {
    val llave: String
    data class Cobro(val cobro: CobroConSaldo) : AvisoDeSaldo { override val llave get() = "cobro:${cobro.paymentId}" }
    data class Cuenta(val cuenta: SolicitudDeTarjeta) : AvisoDeSaldo { override val llave get() = "cuenta:${cuenta.llave}" }
}

internal fun siguienteAvisoDeSaldo(contexto: Pair<String?, String?>, actual: Pair<String?, String?>,
    recuperados: List<CobroConSaldo>, cuentas: List<SolicitudDeTarjeta>, vistas: List<String>, otraPantallaAbierta: Boolean = false): AvisoDeSaldo? {
    if (otraPantallaAbierta || contexto != actual || contexto.first == null || contexto.second == null) return null
    recuperados.map { AvisoDeSaldo.Cobro(it) }.firstOrNull { it.llave !in vistas }?.let { return it }
    return cuentas.filter { it.venueId == contexto.second && it.orderId != null && it.porTerminar &&
        recuperados.none { cobro -> cobro.orderId == it.orderId } }.map { AvisoDeSaldo.Cuenta(it) }.firstOrNull { it.llave !in vistas }
}

@Composable internal fun AvisoDeCobroRecuperado(recuperados: List<CobroConSaldo>, cuentas: List<SolicitudDeTarjeta>,
    contexto: Pair<String?, String?>, contextoActual: () -> Pair<String?, String?>, alVer: (AvisoDeSaldo) -> Unit,
    onAbrirCuenta: (String) -> Unit, otraPantallaAbierta: Boolean = false,
    vm: RecuperacionDeSaldoViewModel = hiltViewModel()) {
    val rows by vm.solicitudes.collectAsState()
    var vistas by rememberSaveable(contexto.first, contexto.second) { mutableStateOf(arrayListOf<String>()) }
    val aviso = siguienteAvisoDeSaldo(contexto, contextoActual(), recuperados, cuentas, vistas, otraPantallaAbierta) ?: return
    val orderId = when (aviso) { is AvisoDeSaldo.Cobro -> aviso.cobro.orderId; is AvisoDeSaldo.Cuenta -> aviso.cuenta.orderId!! }
    val restante = when (aviso) { is AvisoDeSaldo.Cobro -> kotlin.math.round(aviso.cobro.orderRemaining * 100).toInt(); is AvisoDeSaldo.Cuenta -> aviso.cuenta.restanteCents }
    var puede by remember(aviso.llave) { mutableStateOf(false) }
    LaunchedEffect(aviso.llave, rows.filter { it.venueId == contexto.second && it.orderId == orderId }.toSet()) {
        puede = vm.puedeContinuar(orderId) && contexto == contextoActual()
    }
    fun visto() {
        if (contexto != contextoActual()) return
        vistas = ArrayList(vistas + aviso.llave)
        alVer(aviso)
    }
    AvoqadoDialog(onDismiss = ::visto, title = if (aviso is AvisoDeSaldo.Cobro) TextosDeTarjetas.COBRO_RECUPERADO else TextosDeTarjetas.VENTA_EN_CUENTAS_ABIERTAS) {
        if (aviso is AvisoDeSaldo.Cobro) aviso.cobro.redeemed.forEach {
            Text(TextosDeTarjetas.cobroRecuperado(kotlin.math.round(it.amount * 100).toInt(), it.last4, aviso.cobro.orderNumber))
        }
        if (restante != null && restante > 0) {
            Text(TextosDeTarjetas.cobroConfirmadoFaltan(restante))
            if (!puede) Text(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
            PrimaryButton(text = TextosDeTarjetas.CONTINUAR, enabled = puede, onClick = {
                if (contexto == contextoActual()) { visto(); onAbrirCuenta(orderId) }
            })
        }
        TextButton(onClick = ::visto) { Text(if (aviso is AvisoDeSaldo.Cobro) TextosDeTarjetas.ENTENDIDO else TextosDeTarjetas.LO_REVISO_DESPUES) }
    }
}
