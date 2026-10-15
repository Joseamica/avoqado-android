package com.avoqado.pos.customers.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.customers.domain.AVISO_SIN_RED_CLIENTES
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning

/**
 * Sin red, el buscador de clientes busca sólo entre lo que la tablet ya recibió, y lo DICE: offline es un estado,
 * nunca un error en rojo (regla todo-funciona-sin-red). Mismo estilo que el aviso de cola de «Cobro exitoso».
 */
@Composable
internal fun AvisoSinRedDeClientes(modifier: Modifier = Modifier) {
    Surface(
        color = Warning.copy(alpha = 0.15f),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
            Text(text = AVISO_SIN_RED_CLIENTES, style = MaterialTheme.typography.bodySmall, color = Warning)
        }
    }
}
