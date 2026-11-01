package com.avoqado.pos.pos.presentation.product

import com.avoqado.pos.tables.data.VenueMenu

/**
 * Regla PURA de la cuadrícula de «Menú del aparato» (sin red ni UI). Gemela de `MenuDelAparato` de iOS: mismas firmas,
 * mismos casos de prueba. `null` y `[]` significan «todo»: el SERVIDOR nunca manda `[]`, pero la app los trata igual.
 */
object MenuDelAparato {
    /** Categorías que muestra Cobrar, o `null` = todo el catálogo. Nunca deja la cuadrícula en blanco. */
    fun filtroDeCobrar(visibles: List<String>?, verTodo: Boolean, categoriasConProductos: Set<String>): Set<String>? {
        if (verTodo || visibles.isNullOrEmpty()) return null
        val set = visibles.toSet()
        return if (set.none { it in categoriasConProductos }) null else set
    }

    /** Los menús que el aparato ofrece; ids viejos que no existen no lo dejan sin menús. */
    fun menusDelAparato(menus: List<VenueMenu>, visibleMenuIds: List<String>?): List<VenueMenu> {
        if (visibleMenuIds.isNullOrEmpty()) return menus
        val propios = menus.filter { it.id in visibleMenuIds }
        return propios.ifEmpty { menus }
    }

    /** Categorías que muestra Mesas: las del menú elegido (si es del aparato y trae productos) o las del aparato. */
    fun filtroDeMesas(
        menuElegidoId: String?, menus: List<VenueMenu>, visibleMenuIds: List<String>?,
        visibles: List<String>?, verTodo: Boolean, categoriasConProductos: Set<String>,
    ): Set<String>? {
        val elegido = menuElegidoId?.let { id -> menusDelAparato(menus, visibleMenuIds).firstOrNull { it.id == id } }
        if (elegido != null && elegido.categoryIds.any { it in categoriasConProductos }) return elegido.categoryIds.toSet()
        return filtroDeCobrar(visibles, verTodo, categoriasConProductos)
    }

    /** `false` = la categoría abierta quedó fuera del filtro y la cuadrícula regresa a la raíz. */
    fun categoriaAbiertaSigue(abiertaId: String?, filtro: Set<String>?): Boolean =
        abiertaId == null || filtro == null || abiertaId in filtro

    /** Menú con el que arranca Mesas. «Tiene horario» = `scheduleDisplay != null`, como en Mesas hoy. */
    fun menuPreseleccionado(menus: List<VenueMenu>, activeMenuId: String?, visibleMenuIds: List<String>?): String? {
        val candidatos = menusDelAparato(menus, visibleMenuIds)
        candidatos.firstOrNull { it.id == activeMenuId && it.scheduleDisplay != null }?.let { return it.id }
        if (!visibleMenuIds.isNullOrEmpty()) {
            return candidatos.firstOrNull { it.appliesNow && it.scheduleDisplay != null }?.id
        }
        return null
    }

    /** «Todo ▸» sólo si el filtro viene de los ajustes del aparato (no de un menú elegido) y de verdad filtra. */
    fun muestraVerTodo(filtroVieneDeVisibles: Boolean, verTodo: Boolean, filtro: Set<String>?): Boolean =
        filtroVieneDeVisibles && !verTodo && filtro != null

    /** «Todo ▸» de Mesas, como iOS: decide por el filtro EFECTIVO (¿es el del aparato?), no por si hay un menú elegido. */
    fun muestraVerTodoEnMesas(
        menuElegidoId: String?, menus: List<VenueMenu>, visibleMenuIds: List<String>?,
        visibles: List<String>?, verTodo: Boolean, categoriasConProductos: Set<String>,
    ): Boolean {
        val efectivo = filtroDeMesas(menuElegidoId, menus, visibleMenuIds, visibles, verTodo, categoriasConProductos)
        val delAparato = filtroDeCobrar(visibles, verTodo, categoriasConProductos)
        return muestraVerTodo(efectivo == delAparato, verTodo, efectivo)
    }
}
