package com.avoqado.pos.notifications.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.notifications.data.model.AppNotification
import com.avoqado.pos.notifications.data.model.NotificationCategory
import com.avoqado.pos.notifications.data.model.NotificationTab
import com.avoqado.pos.notifications.data.model.NotificationType

@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val notifications by viewModel.notifications.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val filteredNotifications = when (NotificationTab.entries[selectedTabIndex]) {
        NotificationTab.ALL -> notifications
        NotificationTab.ACCOUNT -> notifications.filter {
            it.notificationType.category == NotificationCategory.ACCOUNT
        }
        NotificationTab.UPDATES -> notifications.filter {
            it.notificationType.category == NotificationCategory.UPDATES
        }
    }

    val unreadCount = notifications.count { !it.isRead }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with "Marcar todo como leido" button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Notificaciones",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
            )
            if (unreadCount > 0) {
                TextButton(onClick = { viewModel.markAllAsRead() }) {
                    Text("Marcar todo como leido")
                }
            }
        }

        // Tab selector (matching iOS: pill style)
        Row(
            modifier = Modifier
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
        ) {
            NotificationTab.entries.forEachIndexed { index, tab ->
                val isSelected = selectedTabIndex == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surface
                            else Color.Transparent,
                        )
                        .clickable { selectedTabIndex = index }
                        .padding(vertical = AvoqadoTheme.spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        when {
            isLoading && notifications.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                        Text(
                            text = "Cargando notificaciones...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            filteredNotifications.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                        Text(
                            text = "No hay notificaciones",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Vuelve pronto para nuevas actualizaciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                ) {
                    items(filteredNotifications, key = { it.id }) { notification ->
                        NotificationRow(
                            notification = notification,
                            onClick = {
                                if (!notification.isRead) {
                                    viewModel.markAsRead(notification.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Notification Row (matching iOS: icon circle + title + body + time + unread dot)

@Composable
private fun NotificationRow(
    notification: AppNotification,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        // Type icon circle (matching iOS: blue when unread, gray when read)
        val iconTint = if (!notification.isRead) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        val iconBg = if (!notification.isRead) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = notification.notificationType.toIcon(),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (!notification.isRead) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = notification.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            // Action label (matching iOS: blue tappable text)
            notification.actionLabel?.let { label ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

        // Time + unread dot
        Column(horizontalAlignment = Alignment.End) {
            notification.timeAgo?.let { time ->
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Unread dot (matching iOS: small blue circle)
            if (!notification.isRead) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

// MARK: - Icon mapping

private fun NotificationType.toIcon(): ImageVector = when (this) {
    NotificationType.ORDER -> Icons.AutoMirrored.Filled.ReceiptLong
    NotificationType.PAYMENT -> Icons.Filled.CreditCard
    NotificationType.REFUND -> Icons.Filled.CurrencyExchange
    NotificationType.REVIEW -> Icons.Filled.Star
    NotificationType.STAFF -> Icons.Filled.Group
    NotificationType.CONNECTION -> Icons.Filled.WifiOff
    NotificationType.ALERT -> Icons.Filled.Warning
    NotificationType.SYSTEM -> Icons.Filled.Settings
    NotificationType.PROMOTION -> Icons.Filled.Campaign
    NotificationType.TIME_CLOCK -> Icons.Filled.Schedule
    NotificationType.GENERAL -> Icons.Filled.Notifications
}
