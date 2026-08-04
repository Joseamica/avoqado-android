package com.avoqado.pos.core.util

/**
 * Plurales en español para lo que se cuenta en pantalla.
 *
 * Existe porque "1 artículos" aparecía en SIETE sitios —pedidos, conteos,
 * órdenes de compra, transferencias, categorías, paquetes— y cada uno lo
 * escribía a mano. Es de esas cosas que nadie reporta como bug pero que hacen
 * que la app se lea como sin terminar, justo en la pantalla donde alguien está
 * decidiendo si aprueba una compra.
 */
object Plurales {

    /** "1 artículo" · "3 artículos" · "0 artículos" */
    fun articulos(cantidad: Int): String =
        if (cantidad == 1) "1 artículo" else "$cantidad artículos"

    /** "1 persona" · "2 personas" */
    fun personas(cantidad: Int): String =
        if (cantidad == 1) "1 persona" else "$cantidad personas"

    /** "1 cuenta" · "2 cuentas" */
    fun cuentas(cantidad: Int): String =
        if (cantidad == 1) "1 cuenta" else "$cantidad cuentas"

    /**
     * Concuerda un sustantivo cualquiera cuyo plural sea sólo añadir "s".
     * Para los que no lo son (mes/meses, luz/luces) hay que escribirlos aparte.
     */
    fun contar(cantidad: Int, singular: String, plural: String = singular + "s"): String =
        if (cantidad == 1) "$cantidad $singular" else "$cantidad $plural"
}
