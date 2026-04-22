package com.avoqado.pos.pos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that tracks whether the active cart has items.
 * CartViewModel updates this when items are added/removed.
 * Other ViewModels (like MoreMenuViewModel) read it to know
 * if a venue switch should prompt a confirmation dialog.
 */
@Singleton
class ActiveCartState @Inject constructor() {
    private val _itemCount = MutableStateFlow(0)
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    private val _totalDisplay = MutableStateFlow("$0.00")
    val totalDisplay: StateFlow<String> = _totalDisplay.asStateFlow()

    val hasItems: Boolean get() = _itemCount.value > 0

    fun update(itemCount: Int, totalDisplay: String) {
        _itemCount.value = itemCount
        _totalDisplay.value = totalDisplay
    }

    fun clear() {
        _itemCount.value = 0
        _totalDisplay.value = "$0.00"
    }
}
