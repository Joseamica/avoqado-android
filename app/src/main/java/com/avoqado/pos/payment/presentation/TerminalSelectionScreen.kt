package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.payment.data.OnlineTerminal

@Composable
fun TerminalSelectionScreen(
    terminals: List<OnlineTerminal>,
    onTerminalSelected: (String) -> Unit,
    onCancel: () -> Unit,
    /** Un cobro sin confirmar (o que SÍ pasó) de OTRA venta: se avisa y NO frena este cobro (founder, 25-sep). */
    avisoDeOtroCobro: AvisoDeOtroCobro?,
    onRevisarAviso: (requestId: String) -> Unit,
    onEntendidoAviso: (requestId: String) -> Unit,
    /** I-2: la duda vigente más nueva de otra venta, debajo de un «SÍ pasó» — nunca la tapa. */
    segundoAviso: AvisoDeOtroCobro? = null,
    /** B7b-5 / N6: la lista no cargó (sin red —estado normal, no error— o error del servidor) o vino vacía. Se dice con «Reintentar». */
    sinLista: String? = null,
    onReintentar: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        Icon(
            Icons.Filled.PointOfSale,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        Text(
            text = "Seleccionar terminal",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = "Selecciona la terminal donde se procesará el pago",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Arriba de la lista: se lee ANTES de tocar una terminal.
        listOfNotNull(avisoDeOtroCobro, segundoAviso).forEach { aviso ->
            AvisoDeOtroCobroLinea(
                aviso = aviso,
                onClick = { if (aviso.yaCobrado) onEntendidoAviso(aviso.requestId) else onRevisarAviso(aviso.requestId) },
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        }

        if (sinLista != null) {
            // B7b-5: tranquilo, sin rojo y sin «Error en el pago»; las líneas ámbar de arriba se quedan.
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = sinLista,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 400.dp),
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
            PrimaryButton(text = "Reintentar", onClick = onReintentar)
            Spacer(modifier = Modifier.weight(1f))
        } else if (terminals.isEmpty()) {
            // Loading state
            Spacer(modifier = Modifier.weight(1f))
            AvoqadoBrandLoader(size = 56.dp)
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "Buscando terminales...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                items(terminals) { terminal ->
                    TerminalCard(
                        terminal = terminal,
                        onClick = { onTerminalSelected(terminal.terminalId) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        TextButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

/**
 * Aviso ÁMBAR —nunca rojo de error: no detuvo nada— de un cobro con tarjeta de OTRA venta. Toda la línea se toca:
 * «Revisar» abre la pantalla de siempre sobre ese cobro; «Entendido» cierra el de un cobro que SÍ pasó.
 */
@Composable
private fun AvisoDeOtroCobroLinea(aviso: AvisoDeOtroCobro, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        // Mismo ancho que la lista de terminales, para que se lea como parte de ella. Y se anuncia como BOTÓN: el
        // lector de pantalla tiene que saber que tocarla hace algo.
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().semantics { role = Role.Button },
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        color = Warning.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                tint = Warning,
            )
            Text(
                text = aviso.texto,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (aviso.yaCobrado) "Entendido" else "Revisar",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Warning,
            )
        }
    }
}

@Composable
private fun TerminalCard(
    terminal: OnlineTerminal,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(
            Icons.Filled.PointOfSale,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = terminal.name.ifEmpty { "Terminal ${terminal.terminalId}" },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = terminal.terminalId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            Icons.Filled.Wifi,
            contentDescription = "Conectada",
            modifier = Modifier.size(20.dp),
            tint = Success,
        )
    }
}
