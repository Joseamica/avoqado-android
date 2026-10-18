package com.avoqado.pos.pos.data.model
import com.avoqado.pos.core.util.formatMoney

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import com.avoqado.pos.core.util.aCentavos

@Serializable
data class Product(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("price") val priceValue: Double = 0.0,
    val priceType: String? = null,
    val imageUrl: String? = null,
    val color: String? = null,
    val categoryId: String? = null,
    val category: ProductCategory? = null,
    val variants: List<ProductVariant>? = null,
    val active: Boolean? = null,
    val featured: Boolean? = null,
    /**
     * Upsell "¿Algo más?": permiso del dueño para que este producto se sugiera al
     * cobrar. Es un VETO, no una regla — en false no lo sugiere NINGUNA capa, ni
     * siquiera la IA. Nullable + default null porque las versiones viejas del server
     * no lo mandan; ausente se trata como apagado.
     *
     * 🔴 NO confundir con `featured` (arriba): ese campo se guarda desde el dashboard
     * pero nadie lo lee, y reusarlo promocionaría productos que el dueño marcó por
     * otra razón.
     */
    val upsellEnabled: Boolean? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val gtin: String? = null,
    val type: String? = null,
    /**
     * Venta por peso (charcutería/granel): cuando es true el producto se cobra por peso —
     * [priceValue] es el precio POR KG y el tap abre el panel de captura de peso en vez de
     * agregarlo con cantidad 1. Parseo leniente/aditivo (default false si el server no lo manda).
     */
    val soldByWeight: Boolean = false,
    /** Unidad de medida ("KILOGRAM" para productos por peso). Etiqueta informativa del server. */
    val unit: String? = null,
    /**
     * Service duration in minutes, populated by the server for SERVICE / APPOINTMENTS_SERVICE /
     * CLASS products. Used by the reservations flow to seed the booking length; null on
     * non-bookable products like RETAIL.
     */
    val duration: Int? = null,
    val maxParticipants: Int? = null,
    val layoutConfig: JsonElement? = null,
    val modifierGroups: List<ProductModifierGroupEntry>? = null,
    val trackInventory: Boolean? = null,
    val availableQuantity: Int? = null,
    /**
     * Existencia SIN truncar. `availableQuantity` es entero por compatibilidad
     * con versiones ya publicadas, y en un producto por kilo eso miente: 8.065
     * kg de jamón llegaban como 8. Opcional: los servidores viejos no lo mandan.
     */
    val availableQuantityExact: Double? = null,
    /** QUANTITY | RECIPE — para que el modal de agotado explique la causa. */
    val inventoryMethod: String? = null,
    /** IVA del producto (IVA por producto). Se guarda con el catálogo para que la ficha lo muestre sin red (plan 7). */
    val ivaTratamiento: String? = null,
    /** Insumo cuello de botella de la receta (null sin receta / backend viejo). */
    val limitingIngredient: IngredientShortage? = null,
    /** Insumos con los que ya no alcanza NI para una porción. */
    val insufficientIngredients: List<IngredientShortage>? = null,
    /** Variantes (spec 2026-10-14 §7): familia a la que pertenece esta variante. null = producto suelto o server viejo. */
    @Serializable(with = TextoTolerante::class)
    val productGroupId: String? = null,
    @Serializable(with = TextoTolerante::class)
    val productGroupName: String? = null,
    @Serializable(with = TextoTolerante::class)
    val productGroupImageUrl: String? = null,
    /** Valores de opción de ESTA variante, ya ordenados por eje. Tolerante: un valor raro da null, nunca tumba el catálogo. */
    @Serializable(with = OpcionesDeVarianteTolerantes::class)
    val variantOptions: List<VariantOption>? = null,
    /**
     * La hora (que sella el SERVIDOR) del catálogo con que se mostró este precio. No viene por producto:
     * la pone `ProductsRepository` desde [ProductsResponse.catalogAt] y viaja al caché. La línea la copia al
     * agregarse para que el servidor reconozca el precio que vio el cliente.
     */
    val catalogAt: String? = null,
) {
    val priceInCents: Int get() = priceValue.aCentavos()

    /**
     * Agotado SOLO cuando el venue rastrea inventario de ESTE producto y el
     * server calculó stock 0 (QUANTITY = currentStock, RECIPE = porciones).
     * Venues sin seguimiento (trackInventory false/null o availableQuantity
     * null) ordenan libre — el inventario es opcional por producto.
     */
    val isOutOfStock: Boolean
        get() = trackInventory == true && (availableQuantity ?: 1) <= 0

    val displayPrice: String
        get() = precioConUnidad(priceValue, soldByWeight)

    val hasModifiers: Boolean
        get() = !modifierGroups.isNullOrEmpty() &&
            modifierGroups.any { it.group != null && it.group.modifiers.isNotEmpty() }

    /** Flattened modifier groups for UI consumption. */
    val sortedModifierGroups: List<ProductModifierGroup>
        get() = modifierGroups
            ?.mapNotNull { entry ->
                entry.group?.let { group ->
                    ProductModifierGroup(
                        id = group.id,
                        name = group.name,
                        required = group.required,
                        multipleSelect = group.allowMultiple,
                        minSelections = group.minSelections,
                        maxSelections = group.maxSelections,
                        sortOrder = entry.displayOrder,
                        modifiers = group.modifiers,
                    )
                }
            }
            ?.sortedWith(compareBy({ -(it.sortOrder ?: 0) }, { it.name }))
            ?: emptyList()
}

/** El precio como lo pinta el tile: `$199.00`, o `$199.00/kg` si se vende por peso. Lo comparten el producto y la familia. */
fun precioConUnidad(precio: Double, porPeso: Boolean): String = formatMoney(precio) + if (porPeso) "/kg" else ""

@Serializable
data class ProductCategory(
    val id: String,
    val name: String,
    val color: String? = null,
    val sortOrder: Int? = null,
)

/** Sin defaults A PROPÓSITO (Codex 5): `[{}]` o un campo faltante no decodifica y el tolerante deja la lista en null (producto suelto). */
@Serializable
data class VariantOption(
    val name: String,
    val value: String,
    val namePosition: Int,
    val valuePosition: Int,
)

/**
 * 🔴 Un campo NUEVO con un tipo inesperado NO debe tumbar el catálogo entero (el `catch` de `ProductsRepository.fetchProducts` lo
 * disfrazaría de «sin red»). Se lee como JSON crudo y, si no es la forma esperada, queda null y el producto se pinta suelto.
 */
private class Tolerante<T : Any>(base: KSerializer<T>) : KSerializer<T?> {
    private val delegado = base.nullable
    override val descriptor: SerialDescriptor = delegado.descriptor
    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return delegado.deserialize(decoder)
        val elemento = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(delegado, elemento) }.getOrNull()
    }
    override fun serialize(encoder: Encoder, value: T?) = delegado.serialize(encoder, value)
}

object TextoTolerante : KSerializer<String?> by Tolerante(String.serializer())
object OpcionesDeVarianteTolerantes : KSerializer<List<VariantOption>?> by Tolerante(ListSerializer(VariantOption.serializer()))

@Serializable
data class ProductVariant(
    val id: String,
    val name: String,
    val price: Double = 0.0,
    val sku: String? = null,
)

/**
 * Matches the server's join-table response: ProductModifierGroup → ModifierGroup.
 * Server returns: { id, productId, groupId, displayOrder, group: { ... } }
 */
@Serializable
data class ProductModifierGroupEntry(
    val id: String? = null,
    val groupId: String? = null,
    val displayOrder: Int? = null,
    val group: ModifierGroupData? = null,
)

/**
 * Matches the server's ModifierGroup model nested inside ProductModifierGroupEntry.
 */
@Serializable
data class ModifierGroupData(
    val id: String,
    val name: String,
    val required: Boolean = false,
    val allowMultiple: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val modifiers: List<Modifier> = emptyList(),
)

/**
 * Flattened modifier group for UI consumption (used by ModifierGroupSection, ProductDetailPanel).
 */
data class ProductModifierGroup(
    val id: String,
    val name: String,
    val required: Boolean = false,
    val multipleSelect: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val sortOrder: Int? = null,
    val modifiers: List<Modifier> = emptyList(),
)

@Serializable
data class Modifier(
    val id: String,
    val name: String,
    val price: Double = 0.0,
    val isDefault: Boolean = false,
    val sortOrder: Int? = null,
) {
    val priceInCents: Int get() = price.aCentavos()
}

@Serializable
data class SelectedModifier(
    val groupId: String,
    val groupName: String,
    val modifierId: String,
    val modifierName: String,
    val priceInCents: Int,
)

// Product catalog response
@Serializable
data class ProductsResponse(
    val success: Boolean = true,
    val message: String? = null,
    val data: List<Product> = emptyList(),
    /** ISO 8601 UTC que sella el servidor ANTES de leer los productos. Servidores viejos no lo mandan. */
    val catalogAt: String? = null,
)

@Serializable
data class CategoriesResponse(
    val success: Boolean = true,
    val data: List<ProductCategory> = emptyList(),
)


/**
 * "Disponible: 8.065 kg" para el panel de captura de peso, o `null` cuando no
 * hay nada honesto que decir (el producto no rastrea inventario, o el server es
 * anterior a `availableQuantityExact`). Preferimos callar antes que inventar una
 * existencia: en una cremería ese número decide si se puede vender.
 */
val Product.availableWeightLabel: String?
    get() {
        if (trackInventory != true) return null
        val exact = availableQuantityExact ?: return null
        if (exact <= 0) return "Sin existencia registrada"
        val amount = String.format(java.util.Locale.US, "%.3f", exact).trimEnd('0').trimEnd('.')
        return "Disponible: $amount kg"
    }

/** Espejo de avoqado-tpv: detalle de un insumo faltante para el modal de agotado. */
@kotlinx.serialization.Serializable
data class IngredientShortage(
    val rawMaterialId: String = "",
    val name: String = "",
    val required: Double = 0.0,
    val available: Double = 0.0,
    val unit: String = "",
    val maxPortions: Int = 0,
)
