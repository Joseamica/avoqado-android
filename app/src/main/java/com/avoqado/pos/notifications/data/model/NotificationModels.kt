package com.avoqado.pos.notifications.data.model

import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.core.util.VenueTimeZone
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Serializable
data class AppNotification(
    val id: String,
    val title: String,
    val message: String = "",
    val type: String = "GENERAL",
    val createdAt: String? = null,
    val readAt: String? = null,
    val sentAt: String? = null,
    val recipientId: String? = null,
    val venueId: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val priority: String? = null,
    val actionLabel: String? = null,
    val actionUrl: String? = null,
) {
    // UI compatibility: existing code references body and isRead
    val body: String get() = message
    val isRead: Boolean get() = readAt != null

    // MARK: - Notification type for icon display

    val notificationType: NotificationType
        get() = NotificationType.entries.find { it.name == type } ?: NotificationType.GENERAL

    // MARK: - Relative time display (matching iOS: "hace 5 min")

    val timeAgo: String?
        get() {
            val instant: Instant = VenueDateTimeFormatter.parseIso(createdAt) ?: return null
            val now = Instant.now()
            val minutes = ChronoUnit.MINUTES.between(instant, now)
            val hours = ChronoUnit.HOURS.between(instant, now)
            val days = ChronoUnit.DAYS.between(instant, now)

            return when {
                minutes < 1 -> "Ahora"
                minutes < 60 -> "hace $minutes min"
                hours < 24 -> "hace $hours h"
                days < 7 -> "hace $days d"
                else -> instant.atZone(VenueTimeZone.zoneId())
                    .format(DateTimeFormatter.ofPattern("d MMM", Locale("es", "MX")))
            }
        }
}

// MARK: - Notification Types (matching iOS: icon + category)

enum class NotificationType(val iconName: String, val category: NotificationCategory) {
    ORDER("receipt_long", NotificationCategory.UPDATES),
    PAYMENT("credit_card", NotificationCategory.UPDATES),
    REFUND("currency_exchange", NotificationCategory.UPDATES),
    REVIEW("star", NotificationCategory.UPDATES),
    STAFF("group", NotificationCategory.ACCOUNT),
    CONNECTION("wifi_off", NotificationCategory.ACCOUNT),
    ALERT("warning", NotificationCategory.ACCOUNT),
    SYSTEM("settings", NotificationCategory.ACCOUNT),
    PROMOTION("campaign", NotificationCategory.UPDATES),
    TIME_CLOCK("schedule", NotificationCategory.UPDATES),
    GENERAL("notifications", NotificationCategory.UPDATES),
}

enum class NotificationCategory(val label: String) {
    ACCOUNT("Cuenta"),
    UPDATES("Novedades"),
}

@Serializable
data class NotificationsResponse(
    val success: Boolean = true,
    val data: NotificationsData = NotificationsData(),
)

@Serializable
data class NotificationsData(
    val notifications: List<AppNotification> = emptyList(),
    val pagination: NotificationPagination? = null,
    val unreadCount: Int = 0,
)

@Serializable
data class NotificationPagination(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20,
    val pageCount: Int = 1,
)

@Serializable
data class UnreadCountResponse(
    val success: Boolean = true,
    val count: Int = 0,
)

enum class NotificationTab(val label: String) {
    ALL("Todas"),
    ACCOUNT("Cuenta"),
    UPDATES("Novedades"),
}
