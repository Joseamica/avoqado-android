package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.Modifier
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.SelectedModifier

/*
 * Repetir la misma opción en un grupo de extras («2 de Cochinita + 1 de Papa»; encargo a508f4, Flautas Coyoacán), como Square
 * («multiple quantities of a modifier») y el «max.» por opción de Fudo. Lo prende el DASHBOARD (`ModifierGroup.allowRepeat`).
 *
 * Cada vez que se elige es un [SelectedModifier] más en la lista, con el mismo id: así viaja al SERVIDOR (`modifierIds` con el id
 * repetido), que lo cobra a su precio cada vez y guarda un renglón por vez. Nada nuevo en el contrato, también sin red.
 * Cuenta para el mínimo y el máximo del grupo, como Square: 2 de Cochinita + 1 de Papa = 3.
 *
 * Reglas puras: las usan el panel del platillo suelto (`ProductDetailPanel`) y la pieza del combo (`PromotionSheet`). iOS las copiará.
 */

/** ¿Este grupo enseña contador (− 2 +) en cada opción? Sólo si deja elegir varias y el negocio prendió «repetir». */
val ProductModifierGroup.dejaRepetir: Boolean get() = multipleSelect && allowRepeat

/** Cuántas veces está elegida esta opción en ESTE grupo. */
fun vecesElegida(actuales: List<SelectedModifier>, grupo: ProductModifierGroup, extra: Modifier): Int =
    actuales.count { it.groupId == grupo.id && it.modifierId == extra.id }

/** ¿Cabe una vez más? El máximo del grupo (si tiene) y el de la opción (si tiene) mandan los dos. */
fun puedeSumar(actuales: List<SelectedModifier>, grupo: ProductModifierGroup, extra: Modifier): Boolean {
    val enElGrupo = actuales.count { it.groupId == grupo.id }
    val topeDelGrupo = grupo.maxSelections
    if (topeDelGrupo != null && enElGrupo >= topeDelGrupo) return false
    val topeDeLaOpcion = extra.maxQuantity
    return topeDeLaOpcion == null || vecesElegida(actuales, grupo, extra) < topeDeLaOpcion
}

/** Una vez más de esta opción, al final de la lista; si ya no cabe, la lista queda igual. */
fun sumarExtra(actuales: List<SelectedModifier>, grupo: ProductModifierGroup, extra: Modifier): List<SelectedModifier> {
    if (!puedeSumar(actuales, grupo, extra)) return actuales
    return actuales + SelectedModifier(
        groupId = grupo.id,
        groupName = grupo.name,
        modifierId = extra.id,
        modifierName = extra.name,
        priceInCents = extra.priceInCents,
    )
}

/** Una vez menos: quita la ÚLTIMA vez que se eligió esta opción en este grupo. */
fun restarExtra(actuales: List<SelectedModifier>, grupo: ProductModifierGroup, extra: Modifier): List<SelectedModifier> {
    val i = actuales.indexOfLast { it.groupId == grupo.id && it.modifierId == extra.id }
    return if (i < 0) actuales else actuales.toMutableList().also { it.removeAt(i) }
}

/**
 * Cómo se lee en el carrito, la comanda, el ticket y la pantalla de cocina: la misma opción una vez con su número delante
 * («2 × Flautas Cochinita»), en el orden en que apareció por primera vez — como el «Consolidate modifiers» de Toast y el
 * «2 × Nombre» que ya usan los pedidos en línea. Sin repetidos, la lista sale igual que antes.
 */
fun juntarExtrasRepetidos(nombres: List<String>): List<String> {
    if (nombres.size < 2) return nombres
    val cuantos = LinkedHashMap<String, Int>()
    nombres.forEach { cuantos[it] = (cuantos[it] ?: 0) + 1 }
    if (cuantos.size == nombres.size) return nombres
    return cuantos.map { (nombre, n) -> if (n > 1) "$n × $nombre" else nombre }
}
