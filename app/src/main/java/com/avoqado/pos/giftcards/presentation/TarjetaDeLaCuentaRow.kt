package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.data.SobreDeLaCuenta
import com.avoqado.pos.giftcards.domain.*

@Composable
fun TarjetaDeLaCuentaRow(sobre: SobreDeLaCuenta, activando: Boolean = sobre.status == "PENDING",
    textoDeEntrega: String? = textoDeEntrega(sobre.deliveryChannel, sobre.deliveryStatus),
    habilitada: Boolean = true, sinLiga: Boolean = false, impresa: Boolean = false,
    onAccion: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        Text("•••• ${sobre.ultimos4}", style = MaterialTheme.typography.titleMedium)
        Text(formatMoney(sobre.monto))
        sobre.recipientName?.let { Text(it) }
        sobre.destino?.let { Text(it) }
        when {
            activando -> Text(TextosDeTarjetas.ACTIVANDO)
            sobre.status == "DEACTIVATED" -> Text("Cancelada")
            sobre.status != "ACTIVE" -> Text(TextosDeTarjetas.razon(sobre.status) ?: sobre.status)
            else -> {
                textoDeEntrega?.let { Text(it) }
                if (sobre.deliveryChannel == "PRINTED") Text(if (impresa) "Impresa" else "Impresión disponible")
                if (sobre.deliveryChannel == "HANDED_TO_BUYER") Text(TextosDeTarjetas.ENTREGAR_AL_COMPRADOR)
                if (sobre.deliveryStatus == "FAILED") sobre.deliveryError?.let { Text(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    if (sobre.deliveryChannel == "PRINTED" || sobre.puedeRevelar) TextButton(enabled=habilitada,onClick={onAccion("REPRINT")}) { Text(TextosDeTarjetas.IMPRIMIR) }
                    if (!sinLiga && (sobre.deliveryChannel == "HANDED_TO_BUYER" || sobre.puedeRevelar)) TextButton(enabled=habilitada,onClick={onAccion("SHOW_QR")}) { Text(TextosDeTarjetas.MOSTRAR_QR) }
                }
                if (sobre.puedeReenviar) TextButton(enabled=habilitada,onClick={onAccion("RESEND")}) { Text(TextosDeTarjetas.REENVIAR) }
                if (sobre.puedeCambiarDestino) TextButton(enabled=habilitada,onClick={onAccion("CHANGE_DESTINATION")}) { Text(TextosDeTarjetas.CAMBIAR_DESTINO) }
            }
        }
    }
}

