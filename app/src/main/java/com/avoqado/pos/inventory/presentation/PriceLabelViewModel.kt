package com.avoqado.pos.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.printing.data.EtiquetaDePrecio
import com.avoqado.pos.printing.data.ListaDeInventario
import com.avoqado.pos.printing.data.PrinterService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// MARK: - Impresión desde Inventario — espejo de `PriceLabelView` en iOS
//
// Dos cosas salen del botón de impresora de Inventario: las etiquetas de precio (PRO
// `PRICE_LABELS`) y la «Lista de inventario» (gratis, founder 2026-09-23).

/**
 * Sin red funciona igual: el catálogo sale del espejo en disco de [ProductsRepository] y la
 * impresora está en el local. No escribe nada en el servidor, así que no hay cola ni conflicto.
 */
@HiltViewModel
class PriceLabelViewModel @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    private val planManager: PlanManager,
) : ViewModel() {

    val products: StateFlow<List<Product>> = productsRepository.products

    val locked: Boolean get() = !planManager.hasFeature(FEATURE_CODE)
    val tierLabel: String get() = planManager.requiredTierLabel(FEATURE_CODE) ?: "Pro"

    /** Copias por `productId`. Sólo los que tienen > 0 se imprimen. */
    private val _copias = MutableStateFlow<Map<String, Int>>(emptyMap())
    val copias: StateFlow<Map<String, Int>> = _copias.asStateFlow()

    private val _imprimiendo = MutableStateFlow(false)
    val imprimiendo: StateFlow<Boolean> = _imprimiendo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Cuántas etiquetas salieron, para el toast de éxito. */
    private val _impresas = MutableStateFlow<Int?>(null)
    val impresas: StateFlow<Int?> = _impresas.asStateFlow()

    /** Al abrir la hoja: arranca limpia, con [preseleccion] en 1 copia (desde Artículos). */
    fun abrir(preseleccion: List<String>) {
        _copias.value = preseleccion.associateWith { 1 }
        _error.value = null
        _impresas.value = null
        if (products.value.isEmpty()) viewModelScope.launch { productsRepository.fetchProducts() }
    }

    fun cambiarCopias(productId: String, delta: Int) {
        val nuevo = ((_copias.value[productId] ?: 0) + delta).coerceIn(0, MAX_COPIAS)
        _copias.value = if (nuevo == 0) _copias.value - productId else _copias.value + (productId to nuevo)
    }

    fun limpiarAviso() {
        _impresas.value = null
    }

    fun imprimir() {
        if (_imprimiendo.value || locked) return
        val porId = products.value.associateBy { it.id }
        val etiquetas = _copias.value.mapNotNull { (id, copias) ->
            porId[id]?.let { p ->
                EtiquetaDePrecio(
                    nombre = p.name,
                    precio = p.displayPrice,
                    codigo = EtiquetaDePrecio.codigoDe(gtin = p.gtin, barcode = p.barcode, sku = p.sku),
                    negocio = secureStorage.venueDisplayName,
                    copias = copias,
                )
            }
        }
        if (etiquetas.isEmpty()) return
        _imprimiendo.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = printerService.printPriceLabels(etiquetas)) {
                is PrinterService.PrintOutcome.Printed -> {
                    _impresas.value = etiquetas.sumOf { it.copias }
                    _copias.value = emptyMap()
                }
                PrinterService.PrintOutcome.NoPrinter ->
                    _error.value = "No hay impresora configurada. Ve a Más › Impresora para agregar una."
                PrinterService.PrintOutcome.OutOfPaper ->
                    _error.value = "La impresora no tiene papel. Cambia el rollo y vuelve a imprimir."
                is PrinterService.PrintOutcome.Failed ->
                    _error.value = "No se pudo imprimir: ${r.reason}"
            }
            _imprimiendo.value = false
        }
    }

    // MARK: - Lista de inventario (gratis)

    /** Desenlace de imprimir la lista, para el toast de Inventario. */
    sealed interface AvisoLista {
        data class Impresa(val articulos: Int) : AvisoLista
        data class Error(val mensaje: String) : AvisoLista
    }

    private val _avisoLista = MutableStateFlow<AvisoLista?>(null)
    val avisoLista: StateFlow<AvisoLista?> = _avisoLista.asStateFlow()

    fun limpiarAvisoLista() {
        _avisoLista.value = null
    }

    /**
     * Imprime la relación COMPLETA (no la búsqueda de la pantalla), en el orden elegido. Son las
     * existencias que este equipo bajó por última vez: sin red, las que ya tenía.
     */
    fun imprimirLista(productos: List<StockItem>, insumos: List<StockItem>) {
        if (_imprimiendo.value) return
        fun renglones(items: List<StockItem>) =
            items.map { ListaDeInventario.Renglon(it.name, it.currentQuantityDisplay, it.sku) }
        val lista = ListaDeInventario(
            negocio = secureStorage.venueDisplayName,
            fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
            secciones = listOf(
                ListaDeInventario.Seccion("Productos", renglones(productos)),
                ListaDeInventario.Seccion("Insumos", renglones(insumos)),
            ),
        )
        val total = productos.size + insumos.size
        if (total == 0) {
            _avisoLista.value = AvisoLista.Error("No hay existencias cargadas en este equipo. Conéctate a internet para bajarlas.")
            return
        }
        _imprimiendo.value = true
        viewModelScope.launch {
            _avisoLista.value = when (val r = printerService.printInventoryList(lista)) {
                is PrinterService.PrintOutcome.Printed -> AvisoLista.Impresa(total)
                PrinterService.PrintOutcome.NoPrinter ->
                    AvisoLista.Error("No hay impresora configurada. Ve a Más › Impresora para agregar una.")
                PrinterService.PrintOutcome.OutOfPaper ->
                    AvisoLista.Error("La impresora no tiene papel. Cambia el rollo y vuelve a imprimir.")
                is PrinterService.PrintOutcome.Failed -> AvisoLista.Error("No se pudo imprimir: ${r.reason}")
            }
            _imprimiendo.value = false
        }
    }

    companion object {
        const val FEATURE_CODE = "PRICE_LABELS"
        const val MAX_COPIAS = 99
    }
}
