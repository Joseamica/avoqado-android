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

private fun colorsFor(status: ReservationStatus): Pair<Color, Color> = when (status) {
    ReservationStatus.PENDING -> Color(0x33FFA000) to Color(0xFFB07000)
    ReservationStatus.CONFIRMED -> Color(0x331E88E5) to Color(0xFF1565C0)
    ReservationStatus.CHECKED_IN -> Color(0x3343A047) to Color(0xFF2E7D32)
    ReservationStatus.COMPLETED -> Color(0x33616161) to Color(0xFF424242)
    ReservationStatus.CANCELLED -> Color(0x33E53935) to Color(0xFFC62828)
    ReservationStatus.NO_SHOW -> Color(0x33FB8C00) to Color(0xFFE65100)
}
