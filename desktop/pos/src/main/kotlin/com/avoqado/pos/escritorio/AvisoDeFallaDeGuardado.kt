package com.avoqado.pos.escritorio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.FallasDeGuardado
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import java.time.format.DateTimeFormatter

private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * El aviso al cajero cuando el disco de esta computadora NO guardó algo (Ronda 2 de C1): va en la raíz de la ventana,
 * como `CalendarioPendiente`. No se cierra solo ni al tocar fuera; sólo con «Entendido» (o la X / Esc: el cajero lo cerró
 * a propósito). Si después el MISMO archivo sí se guardó, lo dice, pero el aviso se queda hasta que lo lean.
 * Sin valores de las preferencias: sólo el nombre lógico del archivo y la hora.
 */
@Composable
fun AvisoDeFallaDeGuardado() {
    val fallas by FallasDeGuardado.fallas.collectAsState()
    if (fallas.isEmpty()) return
    AvoqadoDialog(
        title = "No se pudo guardar",
        onDismiss = FallasDeGuardado::entendido,
        description = TEXTO_DEL_AVISO,
        dismissOnClickOutside = false,
        actionButton = { PrimaryButton(text = "Entendido", onClick = FallasDeGuardado::entendido, fullWidth = true) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (falla in fallas) {
                val despues = if (falla.despuesSiGuardo) " (después sí se pudo guardar)" else ""
                Text(
                    text = "Archivo: ${falla.archivo} · ${falla.hora.format(HORA)}$despues",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal const val TEXTO_DEL_AVISO = "No se pudo guardar en el disco de esta computadora. Lo último que hiciste (un cobro, " +
    "un retiro, un ingreso o un cierre de caja) puede no haber quedado guardado. No cierres Avoqado POS y avisa a soporte."
