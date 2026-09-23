package com.avoqado.pos.settings.domain

/**
 * Cómo se reparten los grupos del menú «Más» cuando la pantalla da para varias columnas.
 *
 * Nació el 2026-09-18, con la reorganización del menú. El menú siempre fue UNA tira, y en una
 * tablet de 1920 px eso deja el 56 % de la pantalla en blanco mientras el cajero arrastra tres
 * veces para llegar al final.
 *
 * 🔴 Dos reglas que no se negocian, y por eso esto es una función pura con pruebas:
 *
 * 1. **El orden de lectura se conserva.** Las columnas se llenan en secuencia, de arriba abajo y
 *    de izquierda a derecha: nunca se reordenan los grupos para que cuadren mejor. Quien aprendió
 *    que «Control diario» va después de «Agenda» lo sigue encontrando ahí, en uno o en tres anchos.
 * 2. **Ninguna columna queda vacía si hay grupos para ella**, y ningún grupo se pierde: lo que
 *    entra sale, completo y una sola vez.
 *
 * El balance se hace por ALTURA estimada (las filas del grupo más su encabezado), no por número
 * de grupos: un grupo de cinco entradas no pesa lo mismo que uno de una.
 */
object RepartoDeGrupos {

    /**
     * Ancho máximo (en dp) que puede ocupar la columna de contenido del menú, según cuántas
     * columnas de grupos se van a pintar.
     *
     * 🔴 El tope existía fijo en 600 dp para que en tablet horizontal la fila no se estirara
     * 2000 px y el chevron acabara a media pantalla del texto. Al pasar a varias columnas ese
     * mismo tope las dejaba en ~190 dp cada una y **el texto se partía a media palabra**
     * («Presupuest/os», «Reservacio/nes») — medido en una Sunmi OrderPAD 3 el 2026-09-18.
     *
     * Se reserva el mismo ancho legible por columna. Si la pantalla no da para tanto, el
     * `fillMaxWidth` de la vista la acota sola: esto es un techo, no un piso.
     */
    fun anchoMaximoDp(columnas: Int, anchoDeUnaColumnaDp: Int = 600): Int {
        val n = columnas.coerceAtLeast(1)
        // 24 dp de separación entre columnas, que es el `spacing.lg` del tema.
        return anchoDeUnaColumnaDp * n + 24 * (n - 1)
    }

    /** Alto que ocupa un grupo: sus filas más el renglón del encabezado. */
    private fun alto(filas: Int): Int = filas + 1

    /**
     * Reparte [grupos] —cada uno representado por su número de filas— en [columnas] columnas,
     * conservando el orden.
     *
     * @return una lista por columna con los ÍNDICES de los grupos que le tocan.
     */
    fun repartir(grupos: List<Int>, columnas: Int): List<List<Int>> {
        if (columnas <= 1 || grupos.isEmpty()) {
            return listOf(grupos.indices.toList()).filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
        }
        val columnasReales = minOf(columnas, grupos.size)
        val total = grupos.sumOf { alto(it) }
        // Objetivo por columna. Se reparte en orden y se corta al alcanzarlo, dejando siempre
        // suficientes grupos para que las columnas que faltan no se queden vacías.
        val objetivo = total.toDouble() / columnasReales

        val resultado = mutableListOf<MutableList<Int>>()
        var actual = mutableListOf<Int>()
        var acumulado = 0

        grupos.forEachIndexed { indice, filas ->
            val restantes = grupos.size - indice
            val columnasPendientes = columnasReales - resultado.size
            val debeCerrar = actual.isNotEmpty() &&
                columnasPendientes > 1 &&
                // se cierra cuando ya se pasó del objetivo…
                (acumulado >= objetivo * (resultado.size + 1) ||
                    // …o cuando quedan justo los grupos necesarios para llenar lo que falta.
                    restantes <= columnasPendientes - 1)

            if (debeCerrar) {
                resultado.add(actual)
                actual = mutableListOf()
            }
            actual.add(indice)
            acumulado += alto(filas)
        }
        if (actual.isNotEmpty()) resultado.add(actual)

        return resultado
    }
}
