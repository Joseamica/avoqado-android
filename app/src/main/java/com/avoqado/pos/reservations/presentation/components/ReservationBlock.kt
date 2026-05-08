package com.avoqado.pos.reservations.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import kotlin.math.roundToInt

@Composable
fun ReservationBlock(
    reservation: Reservation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDragRescheduleRequested: ((dxPx: Float, dyPx: Float) -> Unit)? = null,
) {
    val (bg, fg) = colorsFor(reservation.status)
    var dragOffset by remember(reservation.id) { mutableStateOf(Offset.Zero) }
    var isDragging by remember(reservation.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isDragging) 1.04f else 1f, label = "drag-scale")

    val dragModifier = if (onDragRescheduleRequested != null) {
        Modifier.pointerInput(reservation.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    isDragging = true
                    dragOffset = Offset.Zero
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragOffset += amount
                },
                onDragCancel = {
                    isDragging = false
                    dragOffset = Offset.Zero
                },
                onDragEnd = {
                    val released = dragOffset
                    isDragging = false
                    dragOffset = Offset.Zero
                    onDragRescheduleRequested(released.x, released.y)
                },
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .scale(scale)
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(6.dp))
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, fg.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .then(dragModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(reservation.displayName, style = MaterialTheme.typography.labelMedium.copy(color = fg), maxLines = 1)
        reservation.displayServiceName?.let {
            Text(it, style = MaterialTheme.typography.labelSmall.copy(color = fg.copy(alpha = 0.8f)), maxLines = 1)
        }
    }
}

// Background uses 0xE6 (~90%) instead of 0x33 (~20%) so calendar hour grid lines
// don't bleed through the event card. Foreground (text + border) stays full
// opacity in the brand-dark variant.
private fun colorsFor(status: ReservationStatus): Pair<Color, Color> = when (status) {
    ReservationStatus.PENDING -> Color(0xE6FFA000) to Color(0xFF6B3D00)
    ReservationStatus.CONFIRMED -> Color(0xE61E88E5) to Color(0xFF0D3D6B)
    ReservationStatus.CHECKED_IN -> Color(0xE643A047) to Color(0xFF14401A)
    ReservationStatus.COMPLETED -> Color(0xE6757575) to Color(0xFF1F1F1F)
    ReservationStatus.CANCELLED -> Color(0xE6E53935) to Color(0xFF5C0F0E)
    ReservationStatus.NO_SHOW -> Color(0xE6FB8C00) to Color(0xFF5A2E00)
}
