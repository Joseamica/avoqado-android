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

/** Tope de páginas: una red de seguridad contra un servidor que nunca termina de avanzar. */
private const val TOPE_DE_PAGINAS = 60

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

    while (page <= TOPE_DE_PAGINAS) {
        val pagina = pedirPagina(page) ?: return false
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
