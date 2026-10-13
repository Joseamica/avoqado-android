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

    // commit=false may have changed the process cache; publish only a confirmed disk write.
    private fun escribir(lista: List<SavedCart>): Boolean {
        if (!prefs.edit().putString("carts", json.encodeToString(lista)).commit()) return false
        _savedCarts.value = lista
        return true
    }

    fun saveCart(cart: SavedCart): Boolean =
        escribir(listOf(cart) + _savedCarts.value.filterNot { it.id == cart.id })

    fun deleteCart(cartId: String): Boolean = escribir(_savedCarts.value.filterNot { it.id == cartId })

    fun marcarCobroIniciado(cartId: String, ahora: Long = System.currentTimeMillis()): Boolean {
        if (_savedCarts.value.none { it.id == cartId }) return false
        return escribir(_savedCarts.value.map { if (it.id == cartId) it.copy(cobroIniciadoEn = ahora) else it })
    }

    fun quitarMarca(cartId: String): Boolean {
        if (_savedCarts.value.none { it.id == cartId }) return false
        return escribir(_savedCarts.value.map { if (it.id == cartId) it.copy(cobroIniciadoEn = null) else it })
    }

    fun clearCache() {
        _savedCarts.value = emptyList()
        prefs.edit().clear().apply()
    }

}
