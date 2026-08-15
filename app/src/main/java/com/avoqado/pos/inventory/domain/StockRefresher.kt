package com.avoqado.pos.inventory.domain

import android.util.Log
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.pos.data.ProductsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El ÚNICO punto por el que se avisa "las existencias cambiaron".
 *
 * Las existencias viven en DOS cachés independientes y hasta 2026-08-12 cada
 * pantalla refrescaba sólo la suya:
 *
 * | Caché                             | Pinta                        |
 * |-----------------------------------|------------------------------|
 * | `InventoryRepository.stockItems`  | "Descripción general"        |
 * | `ProductsRepository.products`     | La cuadrícula de **Cobrar**  |
 *
 * Confirmar un conteo sólo volvía a bajar la lista de conteos, así que la
 * descripción general seguía mostrando el stock de cuando se abrió la pantalla
 * (medido en la DB: Cerveza Corona 89 → 10 → 7, con la app anclada en 89). Y lo
 * más caro: **el POS nunca se enteraba**. Como `Product.isOutOfStock` se deriva
 * de `availableQuantity`, un producto que acabas de reponer contándolo seguía
 * marcado "Agotado" y el tap abría el modal de no-disponible en vez de
 * agregarlo — no se podía cobrar lo que ya había en la bodega. Las pestañas
 * usan `saveState/restoreState`, así que cambiar de pantalla NO recreaba el
 * ViewModel: sobrevivía hasta reiniciar la app.
 *
 * 🔴 Toda operación que mueva existencias —conteo, recepción de mercancía,
 * despacho o recepción de un traslado— llama aquí. Un refresco parcial es
 * justamente el bug que esto cierra.
 *
 * Cada `fetch` se traga sus propios errores (sin red conservan el cache), así
 * que esto NUNCA rompe la operación que ya tuvo éxito en el server.
 */
@Singleton
class StockRefresher @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
) {

    /**
     * Vuelve a bajar TODO lo que un movimiento de stock puede haber cambiado:
     * existencias de productos, catálogo de insumos y el catálogo del POS
     * (donde el server recalcula `availableQuantity` — para productos con
     * receta depende de los insumos, así que contar un insumo también cambia
     * cuántas porciones se pueden vender).
     */
    suspend fun refreshAfterStockChange() {
        inventoryRepository.fetchStockOverview()
        inventoryRepository.fetchRawMaterials()
        productsRepository.fetchProducts()
        Log.d(TAG, "🔄 Existencias refrescadas tras un movimiento de stock")
    }

    private companion object {
        const val TAG = "📦 StockRefresher"
    }
}
