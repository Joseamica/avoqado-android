package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.ReservationStatus

val ReservationStatus.displayLabel: String
    get() = when (this) {
        ReservationStatus.PENDING -> "Pendiente"
        ReservationStatus.CONFIRMED -> "Confirmada"
        ReservationStatus.CHECKED_IN -> "En curso"
        ReservationStatus.COMPLETED -> "Completada"
        ReservationStatus.CANCELLED -> "Cancelada"
        ReservationStatus.NO_SHOW -> "No-show"
    }

val ReservationStatus.accentColor: Color
    get() = when (this) {
        ReservationStatus.PENDING -> Color(0xFFB07000)
        ReservationStatus.CONFIRMED -> Color(0xFF1565C0)
        ReservationStatus.CHECKED_IN -> Color(0xFF2E7D32)
        ReservationStatus.COMPLETED -> Color(0xFF424242)
        ReservationStatus.CANCELLED -> Color(0xFFC62828)
        ReservationStatus.NO_SHOW -> Color(0xFFE65100)
    }

val ReservationStatus.softColor: Color
    get() = when (this) {
        ReservationStatus.PENDING -> Color(0x33FFA000)
        ReservationStatus.CONFIRMED -> Color(0x331E88E5)
        ReservationStatus.CHECKED_IN -> Color(0x3343A047)
        ReservationStatus.COMPLETED -> Color(0x33616161)
        ReservationStatus.CANCELLED -> Color(0x33E53935)
        ReservationStatus.NO_SHOW -> Color(0x33FB8C00)
    }

@Composable
fun ReservationStatusBadge(status: ReservationStatus, modifier: Modifier = Modifier) {
    Text(
        status.displayLabel,
        modifier = modifier
            .background(status.softColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(color = status.accentColor),
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewBadges() {
    androidx.compose.foundation.layout.Column {
        ReservationStatus.entries.forEach { ReservationStatusBadge(it, Modifier.padding(4.dp)) }
    }
}
