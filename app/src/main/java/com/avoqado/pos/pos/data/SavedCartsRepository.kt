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

    // 🔴 Codex ANDROID r1 A-6: cada operación lee la lista, la cambia y la escribe ENTERA. Desde «Pagar después» también escribe
    // el reenvío (otro hilo): sin un solo candado, retirar la copia A con una lista vieja borraba el carrito B recién guardado.

    @Synchronized
    fun saveCart(cart: SavedCart): Boolean =
        escribir(listOf(cart) + _savedCarts.value.filterNot { it.id == cart.id })

    @Synchronized
    fun deleteCart(cartId: String): Boolean = escribir(_savedCarts.value.filterNot { it.id == cartId })

    @Synchronized
    fun marcarCobroIniciado(cartId: String, ahora: Long = System.currentTimeMillis()): Boolean {
        if (_savedCarts.value.none { it.id == cartId }) return false
        return escribir(_savedCarts.value.map { if (it.id == cartId) it.copy(cobroIniciadoEn = ahora) else it })
    }

    /**
     * «Pagar después» de una copia restaurada (Codex A-5): la copia lleva la llave de la venta y la marca de cobro iniciado
     * ANTES de mandar nada. Si luego su retiro no se escribe, restaurarla pide confirmación y fiarla reusa la llave: el
     * servidor devuelve la misma cuenta. true si no hay copia que proteger; false sólo si no se pudo escribir.
     */
    @Synchronized
    fun prepararPagarDespues(cartId: String, llave: String, vinculante: Boolean = false, ahora: Long = System.currentTimeMillis()): Boolean {
        if (_savedCarts.value.none { it.id == cartId }) return true
        return escribir(_savedCarts.value.map {
            if (it.id == cartId) it.copy(cobroIniciadoEn = ahora, llavePagarDespues = llave, pagarDespuesSinDesenlace = vinculante) else it
        })
    }

    /** La venta con premio de esta copia NO se dejó para después: la copia vuelve a poderse restaurar. */
    @Synchronized
    fun soltarPagarDespues(cartId: String): Boolean {
        if (_savedCarts.value.none { it.id == cartId && it.pagarDespuesSinDesenlace }) return true
        return escribir(_savedCarts.value.map { if (it.id == cartId) it.copy(pagarDespuesSinDesenlace = false) else it })
    }

    @Synchronized
    fun quitarMarca(cartId: String): Boolean {
        if (_savedCarts.value.none { it.id == cartId }) return false
        return escribir(_savedCarts.value.map { if (it.id == cartId) it.copy(cobroIniciadoEn = null) else it })
    }

    @Synchronized
    fun clearCache() {
        _savedCarts.value = emptyList()
        prefs.edit().clear().apply()
    }

}
