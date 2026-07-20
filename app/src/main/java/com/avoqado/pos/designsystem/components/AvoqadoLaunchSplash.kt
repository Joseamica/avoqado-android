package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Branded cold-start surface that continues the native seed-only launch screen. */
@Composable
fun AvoqadoLaunchSplash(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // The splash is modal: do not let taps reach navigation behind it.
                awaitEachGesture {
                    awaitFirstDown().consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AvoqadoBrandLoader(size = 116.dp)
    }
}
