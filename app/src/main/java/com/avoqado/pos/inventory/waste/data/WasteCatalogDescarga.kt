package com.avoqado.pos.inventory.waste.data

/** Una página tal como la devuelve `GET …/inventory/waste-items`. */
data class PaginaDeCatalogo(
    val items: List<WasteCatalogItem>,
    val total: Int,
    val page: Int,
    /** El `pageSize` EFECTIVO que aplicó el servidor, que puede ser menor que el pedido. */
    val pageSize: Int,
)

/** A dónde va el catálogo una vez bajado entero. Lo implementa el repositorio sobre Room. */
interface CatalogoDestino {
    suspend fun reemplazar(venueId: String, items: List<WasteCatalogItem>, actualizadoEn: Long)
}

/**
 * Las páginas que exige el total que declaró el servidor, más una de holgura por si el catálogo crece a
 * media descarga. Es la red contra un servidor que manda menos de lo que declara.
 *
 * 🔴 Codex r1: el tope era FIJO (60 páginas = 12 000 artículos) y una sucursal más grande nunca tenía
 * catálogo — bajaba 12 000, abortaba sin escribir y repetía lo mismo al reabrir. El servidor no tiene
 * tope de total: la descarga llega a donde el total declarado diga.
 */
private fun paginasNecesarias(total: Int, pageSize: Int): Int {
    val porPagina = maxOf(pageSize, 1).toLong()
    return ((maxOf(total, 0) + porPagina - 1) / porPagina + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Baja el catálogo entero y lo entrega de una sola vez.
 *
 * 🔴 Tres decisiones, cada una con su prueba:
 *
 * 1. **Termina por el `total` que declara el servidor**, nunca porque una página llegó más
 *    corta de lo pedido. El contrato recorta un `pageSize > 200` a 200 en vez de rechazarlo:
 *    un cliente que pidiera 500 y leyera «llegaron menos de 500» como «era la última»
 *    guardaría 200 de 450 como si fuera el catálogo entero — en silencio, con respuesta 200.
 * 2. **Se escribe UNA sola vez, al final.** Guardar página por página dejaría un catálogo
 *    a medias que no se distingue de uno completo si la descarga se corta.
 * 3. **Una página fallida aborta sin escribir.** El catálogo anterior sigue intacto y el
 *    cajero sigue pudiendo buscar. Un catálogo viejo —que la pantalla etiqueta como
 *    viejo— es mucho menos dañino que uno incompleto.
 *
 * @param pedirPagina devuelve la página, o `null` si no se pudo traer.
 * @return `true` si se bajó entero y se escribió.
 */
suspend fun descargarCatalogo(
    venueId: String,
    destino: CatalogoDestino,
    ahora: Long,
    pedirPagina: suspend (page: Int) -> PaginaDeCatalogo?,
): Boolean {
    val acumulado = mutableListOf<WasteCatalogItem>()
    var page = 1
    var tope = 1 // lo fija la primera página, con lo que el servidor declara

    while (page <= tope) {
        val pagina = pedirPagina(page) ?: return false
        if (page == 1) tope = paginasNecesarias(pagina.total, pagina.pageSize)
        acumulado += pagina.items

        // Ya se cubrió el total que el servidor declaró: listo.
        if (acumulado.size >= pagina.total) {
            destino.reemplazar(venueId, acumulado, ahora)
            return true
        }
        // El servidor dice que faltan, pero dejó de mandar: no se puede completar, y
        // seguir pidiendo sería un bucle. Se aborta SIN escribir.
        if (pagina.items.isEmpty()) return false

        page++
    }
    return false
}
