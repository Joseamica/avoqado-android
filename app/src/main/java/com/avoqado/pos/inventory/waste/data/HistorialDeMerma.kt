package com.avoqado.pos.inventory.waste.data

/** Un folio del historial, tal como lo manda el servidor. Sin pesos: la ruta nunca los entrega. */
data class FolioDeHistorial(
    val id: String,
    val name: String,
    val unit: String,
    val reasonCode: String,
    /** La etiqueta del catálogo del servidor: el gerente ve también motivos del dashboard que no son chips del POS. */
    val reasonLabel: String?,
    /** Lo declarado; si el servidor no lo trae, lo descontado. */
    val declared: String,
    val unrecorded: String,
    val note: String?,
    val createdAt: String,
    val reportedByName: String?,
)

/** `todos` = el servidor dijo `scope: ALL` (gerente). Cualquier otra cosa es «sólo lo mío». */
data class PaginaDeHistorial(val todos: Boolean, val folios: List<FolioDeHistorial>, val total: Int, val page: Int)

/** Online-only a propósito (spec 2026-09-23): sin red se DICE, no se inventa. */
sealed interface ResultadoDeHistorial {
    data class Pagina(val pagina: PaginaDeHistorial) : ResultadoDeHistorial
    data object SinRed : ResultadoDeHistorial
    data object SinPlan : ResultadoDeHistorial
    data object Fallo : ResultadoDeHistorial
}

fun interface HistorialDeMerma {
    suspend fun historial(venueId: String, page: Int): ResultadoDeHistorial
}
