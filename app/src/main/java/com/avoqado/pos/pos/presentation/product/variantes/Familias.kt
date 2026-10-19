package com.avoqado.pos.pos.presentation.product.variantes

import com.avoqado.pos.pos.data.model.Product

data class EjeDeFamilia(val nombre: String, val valores: List<String>)

data class Familia(
    val groupId: String,
    val nombre: String,
    val imagenUrl: String?,
    val variantes: List<Product>,
    val ejes: List<EjeDeFamilia>,
)

sealed interface EntradaDeCatalogo {
    data class Suelto(val producto: Product) : EntradaDeCatalogo
    data class DeFamilia(val familia: Familia) : EntradaDeCatalogo
}

sealed interface ResultadoDeBusqueda {
    data class DeProducto(val producto: Product) : ResultadoDeBusqueda
    data class DeFamilia(val familia: Familia) : ResultadoDeBusqueda
}

data class PrecioDeFamilia(val minimo: Double, val todosIguales: Boolean)

/** Valores de opción de una variante: nombre del eje → valor. */
fun valoresDe(p: Product): Map<String, String> = p.variantOptions.orEmpty().associate { it.name to it.value }

private fun firmaDeEjes(p: Product): List<String> =
    p.variantOptions.orEmpty().sortedBy { it.namePosition }.map { it.name }

private fun tieneDatosDeFamilia(p: Product): Boolean =
    !p.productGroupName.isNullOrBlank() && !p.variantOptions.isNullOrEmpty() &&
        p.variantOptions.all { it.name.isNotBlank() && it.value.isNotBlank() }

/**
 * Las familias VÁLIDAS del catálogo COMPLETO, por `groupId`, en UN recorrido (Codex 6). Se calcula una vez por cambio del catálogo.
 * Q1: es familia con 2+ variantes, TODAS con datos completos (nombre de familia, opciones con nombre y valor no vacíos), el mismo
 * nombre de familia y los MISMOS ejes (sin ningún eje repetido). Si no, sus variantes se pintan sueltas.
 */
fun familiasDe(catalogo: List<Product>): Map<String, Familia> =
    catalogo.filter { !it.productGroupId.isNullOrBlank() }.groupBy { it.productGroupId!! }.mapNotNull { (id, vs) ->
        val firma = firmaDeEjes(vs.first())
        val valida = vs.size >= 2 && vs.all(::tieneDatosDeFamilia) && vs.all { firmaDeEjes(it) == firma } &&
            vs.map { it.productGroupName }.distinct().size == 1
        val ejes = if (valida) ejesDe(vs) else emptyList()
        // Un eje con el mismo nombre en dos namePosition saldría repetido (la hoja usa el nombre como llave): la familia queda suelta.
        if (!valida || ejes.distinctBy { it.nombre }.size != ejes.size) null else id to Familia(
            groupId = id,
            nombre = vs.first().productGroupName!!,
            imagenUrl = vs.firstNotNullOfOrNull { it.productGroupImageUrl } ?: vs.firstNotNullOfOrNull { it.imageUrl },
            variantes = vs,
            ejes = ejes,
        )
    }.toMap()

/**
 * Coloca las familias en la lista que se va a PINTAR (ya filtrada por menú/categoría): la familia ocupa el lugar de su primera
 * variante y sale una vez. O(n). Sin familias devuelve `Suelto` por cada producto, en el mismo orden.
 */
fun agruparCatalogo(productos: List<Product>, familias: Map<String, Familia>): List<EntradaDeCatalogo> {
    val vistas = mutableSetOf<String>()
    return buildList {
        for (p in productos) {
            val f = p.productGroupId?.let { familias[it] }
            when {
                f == null -> add(EntradaDeCatalogo.Suelto(p))
                vistas.add(f.groupId) -> add(EntradaDeCatalogo.DeFamilia(f))
            }
        }
    }
}

/** Ejes por `namePosition`; valores por `valuePosition` (empate: orden de aparición), sin repetir. */
fun ejesDe(variantes: List<Product>): List<EjeDeFamilia> {
    val opciones = variantes.flatMap { it.variantOptions.orEmpty() }
    return opciones.map { it.name to it.namePosition }.distinct().sortedBy { it.second }.map { (nombre, _) ->
        EjeDeFamilia(
            nombre = nombre,
            valores = opciones.filter { it.name == nombre }.withIndex()
                .sortedWith(compareBy({ it.value.valuePosition }, { it.index }))
                .map { it.value.value }.distinct(),
        )
    }
}

/** Q2: los ejes de un solo valor vienen elegidos. */
fun seleccionInicial(f: Familia): Map<String, String> =
    f.ejes.filter { it.valores.size == 1 }.associate { it.nombre to it.valores.single() }

/** La variante que corresponde a una elección COMPLETA; null si falta un eje o no existe esa combinación. */
fun varianteElegida(f: Familia, seleccion: Map<String, String>): Product? {
    if (f.ejes.any { it.nombre !in seleccion }) return null
    return f.variantes.firstOrNull { valoresDe(it) == seleccion.filterKeys { k -> f.ejes.any { e -> e.nombre == k } } }
}

/** Q3: ¿existe una variante con eje=valor que respete lo ya elegido en los OTROS ejes? (false ⇒ chip atenuado, pero tocable). */
fun valorDisponible(f: Familia, seleccion: Map<String, String>, eje: String, valor: String): Boolean =
    f.variantes.any { v ->
        val vals = valoresDe(v)
        vals[eje] == valor && seleccion.all { (k, s) -> k == eje || vals[k] == s }
    }

/**
 * Q3: elegir un valor suelta las elecciones de otros ejes que ya no combinan con él. Es codicioso en el ORDEN de los ejes: parte de
 * `{eje: valor}` y recorre `f.ejes`; conserva cada elección previa sólo si existe una variante que calce con lo ya conservado + esa
 * elección. Así, con 3+ ejes, un eje que sí combina con el valor nuevo no se pierde porque otro no combine (S6).
 */
fun elegir(f: Familia, seleccion: Map<String, String>, eje: String, valor: String): Map<String, String> {
    val conservada = linkedMapOf(eje to valor)
    for (e in f.ejes) {
        if (e.nombre == eje) continue
        val s = seleccion[e.nombre] ?: continue
        if (valorDisponible(f, conservada, e.nombre, s)) conservada[e.nombre] = s
    }
    return conservada
}

/** Q4. */
fun precioDeFamilia(f: Familia): PrecioDeFamilia {
    val precios = f.variantes.map { it.priceValue }
    return PrecioDeFamilia(minimo = precios.min(), todosIguales = precios.distinct().size == 1)
}

/** Q5: el botón del padre dice «Agotado» sólo si TODAS lo están. */
fun agotada(f: Familia): Boolean = f.variantes.all { it.isOutOfStock }

/**
 * Q6 — búsqueda local, O(n) con las familias ya calculadas. Texto (nombre) ⇒ la fila de su FAMILIA (una vez); sólo código
 * (sku/barcode/gtin, contiene) ⇒ la fila de la VARIANTE. Vacío ⇒ nada. Sin familias: lo de hoy (nombre o sku) más barcode/gtin.
 * Codex 8: se conserva la normalización de hoy de Android (sin `trim`; vacío = `isBlank`).
 */
fun buscarEnCatalogo(productos: List<Product>, familias: Map<String, Familia>, texto: String): List<ResultadoDeBusqueda> {
    if (texto.isBlank()) return emptyList()
    val q = texto.lowercase()
    val vistas = mutableSetOf<String>()
    return buildList {
        for (p in productos) {
            val porTexto = p.name.lowercase().contains(q) || p.productGroupName?.lowercase()?.contains(q) == true
            val porCodigo = listOfNotNull(p.sku, p.barcode, p.gtin).any { it.lowercase().contains(q) }
            val f = p.productGroupId?.let { familias[it] }
            when {
                porTexto && f != null -> if (vistas.add(f.groupId)) add(ResultadoDeBusqueda.DeFamilia(f))
                porTexto || porCodigo -> add(ResultadoDeBusqueda.DeProducto(p))
            }
        }
    }
}
