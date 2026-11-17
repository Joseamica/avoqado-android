package com.avoqado.pos.timeclock.presentation

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Reemplazo de escritorio: la misma firma, sin cámara (CameraX no existe en la PC). La entrada se registra igual y el
 * panel la marca «Sin foto» para el gerente, que es lo que promete «Foto de entrada» para un aparato sin cámara
 * (encargo b0c1a1, 15-nov). La cámara web de la PC queda pendiente: se anota, no se hace aquí.
 */
@Composable
fun SelfieDeEntrada(
    enviando: Boolean,
    @Suppress("UNUSED_PARAMETER") onFoto: (ByteArray) -> Unit,
    onSinCamara: () -> Unit,
    onCancelar: () -> Unit,
) {
    LaunchedEffect(Unit) {
        Log.w("Escritorio", "No disponible en Windows todavía: cámara para la foto de entrada")
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Text(
            text = "Esta computadora no toma la foto. Tu entrada se registra y tu gerente la verá marcada «Sin foto».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(text = "Marcar entrada sin foto", onClick = onSinCamara, isLoading = enviando, fullWidth = true)
        TextButton(onClick = onCancelar, enabled = !enviando) { Text("Cancelar") }
    }
}
