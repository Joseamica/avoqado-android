package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus

@Composable
fun ReservationBlock(
    reservation: Reservation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = colorsFor(reservation.status)
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, fg.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
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
