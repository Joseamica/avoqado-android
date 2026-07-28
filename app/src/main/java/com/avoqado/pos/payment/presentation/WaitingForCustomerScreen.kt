package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import kotlinx.coroutines.delay

/**
 * Lo que ve el CAJERO mientras el CLIENTE decide en su pantalla.
 *
 * 🔴 El punto de esta pantalla es la integridad del dato: con doble pantalla,
 * propina y calificación las captura el cliente y el cajero NO puede tocarlas
 * — así la propina y las estrellas son del cliente de verdad, no del mesero.
 *
 * La salida de emergencia aparece a los pocos segundos y NUNCA antes: si el
 * cliente se va, la pantalla falla o simplemente no quiere responder, la caja
 * no se puede quedar trabada. Pero tampoco se ofrece de entrada, porque
 * entonces el atajo se vuelve la costumbre y perdemos el propósito.
 */
@Composable
fun WaitingForCustomerScreen(
    title: String,
    subtitle: String,
    skipLabel: String,
    onSkip: () -> Unit,
    secondsBeforeSkip: Int = 8,
    /** Salir del cobro — sin esto el mesero queda atrapado (ver RatingScreen). */
    onCancel: (() -> Unit)? = null,
) {
    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(secondsBeforeSkip * 1000L)
        canSkip = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    if (onCancel != null) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AvoqadoTheme.spacing.lg)
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cerrar",
                modifier = Modifier.size(18.dp),
            )
        }
    }
    Column(
        // Fondo opaco: esta pantalla SIEMPRE va sola; si algo queda debajo, el
        // cajero ve dos interfaces encimadas.
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (canSkip) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
            TextButton(onClick = onSkip) {
                Text(skipLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    }
}
