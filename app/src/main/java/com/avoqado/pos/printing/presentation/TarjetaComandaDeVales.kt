package com.avoqado.pos.printing.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.areatickets.presentation.LocalPapelDelAparato
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * D2 de vales (La Galeterie, 3-nov-2026): «Manda comanda a cocina» del área que ESTE aparato emite. El aviso «No salió la
 * comanda» manda aquí. Es del área (aplica a todos sus aparatos), igual que el interruptor «Comanda» del panel. No se
 * enseña si el aparato no emite vales o el SERVIDOR no trae el dato. Sin permiso o sin red se ve, apagado, con la razón.
 */
@Composable
fun TarjetaComandaDeVales() {
    val papel = LocalPapelDelAparato.current ?: return
    val ajustes by papel.ajustes.collectAsState()
    val comanda = papel.comandaDelAreaActual(ajustes) ?: return
    val hayRed by papel.hayRed.collectAsState()
    val guardando by papel.guardandoComanda.collectAsState()
    val error by papel.errorDeComanda.collectAsState()
    val motivo = papel.motivoComanda(hayRed)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.xl))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        Text(
            text = "Vales de ${comanda.areaNombre}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Manda comanda a cocina", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (comanda.manda) {
                        "Con cada vale sale también la comanda para quien prepara."
                    } else {
                        "Apagada: al emitir sale sólo el vale, sin comanda ni aviso."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Switch(
                checked = comanda.manda,
                enabled = motivo == null && !guardando,
                onCheckedChange = { papel.cambiarComanda(it) },
                modifier = Modifier.semantics { contentDescription = "Manda comanda a cocina en ${comanda.areaNombre}" },
            )
        }
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
        Text(
            text = (error ?: motivo)?.let { "$it · " }.orEmpty() + "Aplica a todos los aparatos de ${comanda.areaNombre}.",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
