package com.avoqado.pos.pos.presentation.promotions

import com.avoqado.pos.pos.data.model.Modifier
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.data.model.SelectedModifier

/*
 * Reglas puras de los extras (modificadores) de cada pieza de un combo. Sin Compose: las usa la hoja del combo.
 * El panel de un platillo suelto (`ProductDetailPanel`) NO las usa ni cambia: ahí el máximo no se respeta, a propósito.
 *
 * `extras` va por `groupId` del grupo del COMBO (no del grupo de modificadores): cada pieza elegida guarda ahí sus extras.
 */

/**
 * Grupos de extras que pide esta pieza, o null si se vende como hoy: sin capacidad del SERVIDOR, cantidad distinta de 1
 * o un producto que la tableta no tiene. Cuentan los grupos que traen extras que elegir y los obligatorios aunque no
 * traigan ninguno (todas sus opciones archivadas): ese grupo bloquea el combo igual que bloquea al platillo suelto.
 * Sólo se omiten los opcionales vacíos.
 */
fun gruposDeLaPieza(opcion: PromotionOption, productos: List<Product>, capacidad: Boolean): List<ProductModifierGroup>? {
    if (!capacidad || opcion.quantity != 1) return null
    val producto = productos.firstOrNull { it.id == opcion.productId } ?: return null
    return producto.sortedModifierGroups.filter { it.modifiers.isNotEmpty() || it.required }
}

/** Cuántos extras caben en el grupo: el máximo si deja elegir varios, uno si no. null = sin tope. */
fun topeDelGrupo(grupo: ProductModifierGroup): Int? = if (grupo.multipleSelect) grupo.maxSelections else 1

/**
 * Marca o desmarca un extra. Grupo de uno solo: elegir otro reemplaza. Grupo de varios: agrega sólo si no llega al tope
 * (y no repite el mismo). Quitar siempre quita.
 */
fun alternarExtra(
    actuales: List<SelectedModifier>,
    grupo: ProductModifierGroup,
    extra: Modifier,
    seleccionado: Boolean,
): List<SelectedModifier> {
    if (!seleccionado) {
        return actuales.filterNot { it.groupId == grupo.id && it.modifierId == extra.id }
    }
    val nuevo = SelectedModifier(
        groupId = grupo.id,
        groupName = grupo.name,
        modifierId = extra.id,
        modifierName = extra.name,
        priceInCents = extra.priceInCents,
    )
    if (!grupo.multipleSelect) {
        return actuales.filterNot { it.groupId == grupo.id } + nuevo
    }
    if (actuales.any { it.groupId == grupo.id && it.modifierId == extra.id }) return actuales
    val tope = topeDelGrupo(grupo)
    val yaElegidos = actuales.count { it.groupId == grupo.id }
    if (tope != null && yaElegidos >= tope) return actuales
    return actuales + nuevo
}

/**
 * Lo que falta elegir antes de agregar el combo: «Falta elegir «Término» de Hamburguesa.». Un grupo obligatorio pide al
 * menos `max(1, minSelections)`. Una pieza sin grupos (null) no pide nada. Una opción aún sin elegir no se revisa.
 */
fun faltantesDelCombo(
    promocion: Promotion,
    selecciones: Map<String, String>,
    extras: Map<String, List<SelectedModifier>>,
    grupos: (PromotionOption) -> List<ProductModifierGroup>?,
): List<String> {
    val faltantes = mutableListOf<String>()
    promocion.groups.forEach { grupoDelCombo ->
        val opcion = opcionElegida(grupoDelCombo.options, selecciones[grupoDelCombo.id]) ?: return@forEach
        val gruposDeExtras = grupos(opcion) ?: return@forEach
        val elegidos = extras[grupoDelCombo.id].orEmpty()
        gruposDeExtras.filter { it.required }.forEach { g ->
            val minimo = maxOf(1, g.minSelections ?: 0)
            if (elegidos.count { it.groupId == g.id } < minimo) {
                faltantes += "Falta elegir «${g.name}» de ${opcion.productName}."
            }
        }
    }
    return faltantes
}

/** Lo que suman los extras elegidos, en centavos. */
fun extrasCents(extras: Map<String, List<SelectedModifier>>): Int =
    extras.values.sumOf { lista -> lista.sumOf { it.priceInCents } }

/** ¿Alguna pieza del combo (elegida o fija) tiene grupos de extras? Decide si la hoja se abre aunque no haya que escoger. */
fun comboPideExtras(promocion: Promotion, grupos: (PromotionOption) -> List<ProductModifierGroup>?): Boolean =
    promocion.groups.any { g -> g.options.any { grupos(it).orEmpty().isNotEmpty() } }

/** Los grupos del combo cuya pieza elegida (o única) manda su lista de extras: lo que se marca en `comboConExtras`. */
fun piezasConExtras(
    promocion: Promotion,
    selecciones: Map<String, String>,
    grupos: (PromotionOption) -> List<ProductModifierGroup>?,
): Set<String> = promocion.groups.mapNotNull { g ->
    val opcion = opcionElegida(g.options, selecciones[g.id]) ?: return@mapNotNull null
    if (grupos(opcion) != null) g.id else null
}.toSet()

/** La opción elegida de un grupo; si el grupo sólo tiene una, es esa. */
private fun opcionElegida(opciones: List<PromotionOption>, optionId: String?): PromotionOption? =
    opciones.firstOrNull { it.id == optionId } ?: opciones.singleOrNull()
