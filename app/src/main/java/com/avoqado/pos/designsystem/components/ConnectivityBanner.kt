package com.avoqado.pos.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ConnectivityBanner(
    isConnectedFlow: StateFlow<Boolean>,
    modifier: Modifier = Modifier,
) {
    val isConnected by isConnectedFlow.collectAsState()

    AnimatedVisibility(
        visible = !isConnected,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Error)
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Sin conexión a internet",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}
