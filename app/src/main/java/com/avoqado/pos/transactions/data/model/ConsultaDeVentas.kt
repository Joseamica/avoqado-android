package com.avoqado.pos.transactions.data.model

/**
 * De qué es una lista de Ventas (D18, Codex r1 #1 y #3): la sucursal, quién la pidió y la búsqueda, que es lo que decide lo
 * que contesta el servidor. La lista sólo se enseña bajo la consulta vigente: la de otra sucursal, la de otra persona (al
 * cerrar sesión o al entrar otra por PIN) o la de otra búsqueda nunca se ve.
 *
 * Los filtros (método, mesero, importes) no cuentan: se aplican en el aparato sobre la lista de ESTA consulta, sin
 * preguntar al servidor.
 */
data class ConsultaDeVentas(val sucursal: String, val usuario: String, val busqueda: String) {
    companion object {
        fun de(sucursal: String?, usuario: String?, busqueda: String?) =
            ConsultaDeVentas(sucursal.orEmpty(), usuario.orEmpty(), busqueda.orEmpty())
    }
}
