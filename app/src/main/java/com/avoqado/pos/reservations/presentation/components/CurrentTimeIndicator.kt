package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CurrentTimeIndicator(yOffsetDp: Float, label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(2.dp).offset(y = yOffsetDp.dp)
        ) {
            drawLine(
                color = Color.Red,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 2f,
            )
        }
        Text(
            label,
            modifier = Modifier.offset(y = (yOffsetDp - 8).dp).padding(start = 4.dp),
            color = Color.Red,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
