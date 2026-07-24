package com.avoqado.pos.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error

@Composable
fun ConnectivityBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** Operaciones offline esperando replay (outbox + cola de pagos). */
    pendingSync: Int = 0,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Naranja, no rojo: offline es estado NORMAL de operación, no
                // una falla — el POS sigue vendiendo (spec offline-first §6).
                .background(com.avoqado.pos.designsystem.theme.Warning),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (pendingSync > 0) {
                    "Sin conexión — $pendingSync por sincronizar (todo se guarda aquí)"
                } else {
                    "Sin conexión — las ventas se guardan en el dispositivo"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.xs,
                    ),
            )
        }
    }
}
