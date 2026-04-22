package com.avoqado.pos.pos.data

import android.content.Context
import com.avoqado.pos.pos.data.model.SavedCart
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedCartsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("saved_carts", Context.MODE_PRIVATE)

    private val _savedCarts = MutableStateFlow<List<SavedCart>>(emptyList())
    val savedCarts: StateFlow<List<SavedCart>> = _savedCarts.asStateFlow()

    init {
        loadCarts()
    }

    private fun loadCarts() {
        val raw = prefs.getString("carts", null) ?: return
        try {
            _savedCarts.value = json.decodeFromString(raw)
        } catch (_: Exception) {
            // Ignore corrupt data
        }
    }

    fun saveCart(cart: SavedCart) {
        val updated = _savedCarts.value.toMutableList()
        updated.removeAll { it.id == cart.id }
        updated.add(0, cart)
        _savedCarts.value = updated
        persistCarts()
    }

    fun deleteCart(cartId: String) {
        _savedCarts.value = _savedCarts.value.filter { it.id != cartId }
        persistCarts()
    }

    fun clearCache() {
        _savedCarts.value = emptyList()
        prefs.edit().clear().apply()
    }

    private fun persistCarts() {
        prefs.edit().putString("carts", json.encodeToString(_savedCarts.value)).apply()
    }
}
