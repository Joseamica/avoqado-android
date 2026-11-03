package com.avoqado.pos.notifications.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.notifications.data.NotificationsRepository
import com.avoqado.pos.notifications.data.model.AppNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val notifications = repository.notifications

    /** A dónde lleva el aviso, contra la sucursal activa y las del selector ([decidirDestino]). */
    internal fun destinoDe(aviso: AppNotification): DestinoDelAviso? =
        decidirDestino(aviso, secureStorage.venueId, secureStorage.venuesList)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val unreadCount: Int
        get() = repository.unreadCount

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.fetchNotifications()
            _isLoading.value = false
        }
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            repository.markAsRead(notificationId)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            repository.markAllAsRead()
        }
    }
}
