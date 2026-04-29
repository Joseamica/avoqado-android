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

@Composable
fun ReservationStatusBadge(status: ReservationStatus, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (status) {
        ReservationStatus.PENDING -> Triple(Color(0x33FFA000), Color(0xFFB07000), "Pendiente")
        ReservationStatus.CONFIRMED -> Triple(Color(0x331E88E5), Color(0xFF1565C0), "Confirmada")
        ReservationStatus.CHECKED_IN -> Triple(Color(0x3343A047), Color(0xFF2E7D32), "En curso")
        ReservationStatus.COMPLETED -> Triple(Color(0x33616161), Color(0xFF424242), "Completada")
        ReservationStatus.CANCELLED -> Triple(Color(0x33E53935), Color(0xFFC62828), "Cancelada")
        ReservationStatus.NO_SHOW -> Triple(Color(0x33FB8C00), Color(0xFFE65100), "No-show")
    }
    Text(
        label,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(color = fg),
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewBadges() {
    androidx.compose.foundation.layout.Column {
        ReservationStatus.entries.forEach { ReservationStatusBadge(it, Modifier.padding(4.dp)) }
    }
}
