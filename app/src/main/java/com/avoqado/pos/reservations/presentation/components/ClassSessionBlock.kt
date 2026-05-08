package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.ClassSessionBorder
import com.avoqado.pos.designsystem.theme.ClassSessionContainerDark
import com.avoqado.pos.designsystem.theme.ClassSessionContainerLight
import com.avoqado.pos.designsystem.theme.ClassSessionContent
import com.avoqado.pos.reservations.data.model.ClassSession

@Composable
fun ClassSessionBlock(
    session: ClassSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isSystemInDarkTheme()) ClassSessionContainerDark else ClassSessionContainerLight
    val fg = ClassSessionContent
    val remaining = (session.capacity - session.enrolled).coerceAtLeast(0)

    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, ClassSessionBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "${session.enrolled}/${session.capacity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
        val hint = when {
            session.isFull -> "Lleno"
            remaining <= 3 -> "$remaining cupos"
            else -> "${session.duration} min"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.88f),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}
