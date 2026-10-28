package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*

@Composable fun MontoConSaldoSheet(tarjeta: TarjetaIdentificada, oferta: MontoOfrecido, ocupado: Boolean,
    resultado: DesenlaceDeCobro?, puedeReintentar: Boolean, onPagar: (Int) -> Unit,
    onReintentar: () -> Unit, onCerrar: () -> Unit, conectado: Boolean, servidor: Boolean) {
    var texto by remember(tarjeta, oferta.montoCents) { mutableStateOf(java.math.BigDecimal.valueOf(oferta.montoCents.toLong(), 2).toPlainString()) }
    val cents = runCatching { texto.toBigDecimal().movePointRight(2).intValueExact() }.getOrNull()
    val comprobando = resultado is DesenlaceDeCobro.Incierto || resultado is DesenlaceDeCobro.Reintentable
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AvoqadoFullscreenHeader(title = TextosDeTarjetas.MONTO_CON_SALDO, onNav = { if (!ocupado) onCerrar() },
            primaryActionText = TextosDeTarjetas.PAGAR_CON_SALDO,
            primaryActionEnabled = controlDeMontoConSaldo(conectado, servidor, !ocupado && !comprobando && cents != null && cents in 1..oferta.montoCents),
            primaryActionDisabledReason = if (!conectado || !servidor) TextosDeTarjetas.SIN_RED else null,
            onPrimaryAction = { cents?.takeIf { it in 1..oferta.montoCents }?.let(onPagar) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
            if (!conectado || !servidor) Text(TextosDeTarjetas.SIN_RED)
            Text("•••• ${tarjeta.tarjeta.last4}", style = MaterialTheme.typography.titleMedium)
            Text("${TextosDeTarjetas.SALDO_DISPONIBLE}: ${formatMoneyFromCents(kotlin.math.round(saldoMostrado(tarjeta, resultado) * 100).toInt())}")
            oferta.aviso?.let { Text(it) }
            if (oferta.propinaCents > 0) Text("Propina: ${formatMoneyFromCents(oferta.propinaCents)}")
            AvoqadoPillTextField(value = texto, onValueChange = { texto = it }, placeholder = TextosDeTarjetas.MONTO_CON_SALDO,
                enabled = !ocupado && !comprobando, keyboardType = KeyboardType.Decimal)
            if (ocupado) AvoqadoBrandLoader()
            when (resultado) {
                is DesenlaceDeCobro.Incierto -> Text(TextosDeTarjetas.COMPROBANDO_COBRO)
                is DesenlaceDeCobro.Reintentable -> Text(resultado.texto)
                is DesenlaceDeCobro.Rechazado -> Text(resultado.texto)
                is DesenlaceDeCobro.NoSalio -> Text(resultado.texto)
                is DesenlaceDeCobro.SaldoInsuficiente -> Text(resultado.texto)
                else -> Unit
            }
            if (comprobando) {
                PrimaryButton(text = TextosDeTarjetas.REINTENTAR, enabled = controlDeMontoConSaldo(conectado, servidor, puedeReintentar && !ocupado), onClick = onReintentar)
                TextButton(onClick = onCerrar, enabled = !ocupado) { Text(TextosDeTarjetas.LO_REVISO_DESPUES) }
            }
        }
    }
}

internal fun controlDeMontoConSaldo(conectado: Boolean, servidor: Boolean, disponible: Boolean): Boolean = conectado && servidor && disponible

internal fun saldoMostrado(tarjeta: TarjetaIdentificada, resultado: DesenlaceDeCobro?): Double = when (resultado) {
    is DesenlaceDeCobro.SaldoInsuficiente -> resultado.saldoDisponible
    is DesenlaceDeCobro.Cobrado -> resultado.cobro.redeemed.firstOrNull { it.giftCardId == tarjeta.tarjeta.giftCardId }?.balanceAfter ?: tarjeta.tarjeta.balance
    else -> tarjeta.tarjeta.balance
}

internal fun hojaDeSaldoVigente(resultado: DesenlaceDeCobro?, ocupado: Boolean, llave: String?, venue: String?,
    solicitudes: List<SolicitudDeTarjeta>): Boolean = when (resultado) {
        is DesenlaceDeCobro.Cobrado -> false
        is DesenlaceDeCobro.Incierto, is DesenlaceDeCobro.Reintentable -> ocupado || solicitudes.any {
            it.llave == llave && it.venueId == venue && it.mandado && !it.porTerminar && !it.porRecuperar
        }
        else -> true
    }

internal fun resultadoDeSaldoVigente(orderId: String?, cerradas: List<String>): Boolean = orderId == null || orderId !in cerradas
