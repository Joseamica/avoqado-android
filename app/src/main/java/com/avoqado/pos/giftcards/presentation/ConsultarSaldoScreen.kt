package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConsultarSaldoScreen(
    venueId: String, ajustes: AjustesDeTarjetas?, prueba: PruebaDeTarjeta?, hayVentaAbierta: Boolean,
    onCerrar: () -> Unit, onCobrarConEsta: ((TarjetaIdentificada) -> Unit)? = null,
    viewModel: IdentificarTarjetaViewModel = hiltViewModel(),
) {
    val identificada by viewModel.identificada.collectAsState()
    val conectado by viewModel.conexion.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    IdentificarTarjetaSheet(venueId, ajustes, onIdentificada = {}, onCerrar, prueba, viewModel) { consulta, hayMas ->
        SaldoDeTarjetas(consulta, hayMas)
        if (hayVentaAbierta && onCobrarConEsta != null && identificada?.tarjeta?.usable == true) {
            com.avoqado.pos.designsystem.components.PrimaryButton(text = TextosDeTarjetas.COBRAR_CON_ESTA, enabled = conectado && servidor && viewModel.puedeCobrar(hayVentaAbierta, true), onClick = { identificada?.let { onCobrarConEsta?.invoke(it) } })
        }
    }
}

@Composable
private fun SaldoDeTarjetas(consulta: ConsultaDeSaldo, hayMas: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
        Text(if (hayMas) TextosDeTarjetas.SALDO_DE_TARJETAS_MOSTRADAS else TextosDeTarjetas.SALDO_DISPONIBLE, style = MaterialTheme.typography.titleMedium)
        Text(formatMoney(consulta.usableBalance), style = MaterialTheme.typography.headlineMedium)
        if (hayMas) Text(TextosDeTarjetas.hayMasTarjetas(consulta.cards.size))
        consulta.cards.forEach { card ->
            Text("•••• ${card.last4} · ${formatMoney(card.balance)}")
            Text(venceTarjeta(card.expiresAt))
            if (!card.usable) TextosDeTarjetas.razon(card.notUsableReason)?.let { Text(it) }
        }
    }
}
internal fun venceTarjeta(iso: String?): String = iso?.let {
    runCatching { TextosDeTarjetas.venceEl(DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of(com.avoqado.pos.core.util.VenueTimeZone.current)).format(Instant.parse(it))) }.getOrNull()
} ?: TextosDeTarjetas.SIN_VENCIMIENTO
