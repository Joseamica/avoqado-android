package com.avoqado.pos.pos.data

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
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
    private val secureStorage: SecureStorage? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("saved_carts", Context.MODE_PRIVATE)

    /** D8: la sucursal de la sesión. La pone al día [alCambiarLaSesion]. */
    private var sucursal: String? = secureStorage?.venueId

    private val _savedCarts = MutableStateFlow<List<SavedCart>>(emptyList())
    /** TODOS los del aparato, de cualquier sucursal: las búsquedas por id (la copia de una venta, la cola de «Pagar después»). */
    val savedCarts: StateFlow<List<SavedCart>> = _savedCarts.asStateFlow()

    private val _deLaSucursal = MutableStateFlow<List<SavedCart>>(emptyList())
    /** D8: los que se enseñan en «Carritos guardados»: los de la sucursal de la sesión y los viejos sin sucursal. */
    val deLaSucursal: StateFlow<List<SavedCart>> = _deLaSucursal.asStateFlow()

    init {
        loadCarts()
    }

    private fun loadCarts() {
        val raw = prefs.getString("carts", null) ?: return
        try {
            publicar(json.decodeFromString(raw))
        } catch (_: Exception) {
            // Ignore corrupt data
        }
        // D8 (Codex r1 #1): los de una versión anterior, sin sucursal, son de la sucursal en que abre la app (cambiar de
        // sucursal los borraba). Se anotan en el disco YA, antes de que un cambio de sucursal a medias los deje sin dueña.
        anotarSinSucursal(sucursal)
    }

    private fun publicar(lista: List<SavedCart>) {
        _savedCarts.value = lista
        _deLaSucursal.value = lista.filter { it.venueId == null || it.venueId == sucursal }
    }

    // commit=false may have changed the process cache; publish only a confirmed disk write.
    private fun escribir(lista: List<SavedCart>): Boolean {
        if (!prefs.edit().putString("carts", json.encodeToString(lista)).commit()) return false
        publicar(lista)
        return true
    }

    // 🔴 Codex ANDROID r1 A-6: cada operación lee la lista, la cambia y la escribe ENTERA. Desde «Pagar después» también escribe
    // el reenvío (otro hilo): sin un solo candado, retirar la copia A con una lista vieja borraba el carrito B recién guardado.

    /**
     * D8 (Codex r2 #1): un carrito sin sucursal conserva la de su copia anterior o toma la última sucursal de la sesión que
     * conoce este almacén. Si la sesión se borró en otro hilo y su aviso todavía no llega, sigue siendo la sucursal en que se armó.
     * Sin ninguna (ya se salió de la sesión) no se guarda: un carrito sin dueña lo adoptaría la siguiente sucursal que entre.
     */
    @Synchronized
    fun saveCart(cart: SavedCart): Boolean {
        val conSucursal = cart.venueId?.let { cart }
            ?: cart.copy(venueId = _savedCarts.value.firstOrNull { it.id == cart.id }?.venueId ?: sucursal)
        if (conSucursal.venueId == null && secureStorage != null) return false
        return escribir(listOf(conSucursal) + _savedCarts.value.filterNot { it.id == cart.id })
    }

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

    /**
     * D8: la llaman el cambio de sucursal y `AppState` (entrar, salir, cambiar). Antes, cambiar de sucursal BORRABA todos los
     * carritos guardados del aparato. Uno sin sucursal (de una versión anterior) es de la sucursal en que se estaba: se anota
     * con la que se deja, o con la que entra si no había sesión (antes, lo guardado antes de salir se veía al volver a entrar).
     */
    @Synchronized
    fun alCambiarLaSesion() {
        val anotarCon = sucursal ?: secureStorage?.venueId
        sucursal = secureStorage?.venueId
        anotarSinSucursal(anotarCon)
    }

    /**
     * Anota con [venueId] los que no tienen sucursal y publica. Si el disco falla quedan anotados en memoria (sólo cambia la
     * etiqueta: por id ninguno aparece ni desaparece) y el siguiente guardado los escribe; si la app muere antes, al volver a
     * abrir se anotan con la sucursal en que abra (límite aceptado: necesita que el disco falle).
     */
    private fun anotarSinSucursal(venueId: String?) {
        if (venueId == null || _savedCarts.value.none { it.venueId == null }) {
            publicar(_savedCarts.value)
            return
        }
        val anotados = _savedCarts.value.map { if (it.venueId == null) it.copy(venueId = venueId) else it }
        if (!escribir(anotados)) publicar(anotados)
    }

}
