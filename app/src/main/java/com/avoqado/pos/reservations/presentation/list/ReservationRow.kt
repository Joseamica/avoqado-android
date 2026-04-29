package com.avoqado.pos.reservations.presentation.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.ReservationStatusBadge

@Composable
fun ReservationRow(
    reservation: Reservation,
    isPending: Boolean,
    onClick: () -> Unit,
    formatter: VenueDateTimeFormatter,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    reservation.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                reservation.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            val service = reservation.displayServiceName
                ?: reservation.table?.let { "Mesa ${it.number}" }
            Text(
                service ?: "Sin servicio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatter.formatTime(reservation.startsAt),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            ReservationStatusBadge(reservation.status)
        }
        if (isPending) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
