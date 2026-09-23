package com.avoqado.pos.settings

import com.avoqado.pos.settings.domain.RepartoDeGrupos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candado del reparto en columnas del menú «Más».
 *
 * Lo que protege no es la estética: es que el cajero encuentre lo mismo en el mismo sitio sin
 * importar el ancho del aparato. Un reparto que reordena grupos, pierde uno o deja una columna
 * vacía rompe la ubicación aprendida, y eso cuesta más que el scroll que veníamos a quitar.
 */
class RepartoDeGruposTest {

    /** Los cinco grupos reales del menú hoy, con su número de filas. */
    private val menuReal = listOf(4, 1, 4, 2, 2)

    @Test
    fun `P1 en una columna todo queda junto y en orden`() {
        assertEquals(listOf(listOf(0, 1, 2, 3, 4)), RepartoDeGrupos.repartir(menuReal, 1))
    }

    @Test
    fun `P1 ningun grupo se pierde ni se repite, en cualquier numero de columnas`() {
        for (columnas in 1..5) {
            val plano = RepartoDeGrupos.repartir(menuReal, columnas).flatten()
            assertEquals(
                "con $columnas columnas se perdió o repitió un grupo",
                menuReal.indices.toList(),
                plano.sorted(),
            )
        }
    }

    @Test
    fun `P1 el orden de lectura se conserva - los indices nunca retroceden`() {
        for (columnas in 1..5) {
            val plano = RepartoDeGrupos.repartir(menuReal, columnas).flatten()
            assertEquals(
                "con $columnas columnas se reordenaron los grupos",
                plano.sorted(),
                plano,
            )
        }
    }

    @Test
    fun `P1 ninguna columna queda vacia si hay grupos de sobra`() {
        for (columnas in 2..5) {
            RepartoDeGrupos.repartir(menuReal, columnas).forEachIndexed { i, col ->
                assertTrue("columna $i vacía con $columnas columnas", col.isNotEmpty())
            }
        }
    }

    /**
     * 🔴 El HUECO que destapó el sabotaje del 2026-09-18 (en el port a iOS, con el MISMO
     * algoritmo): comprobar que ninguna columna DEVUELTA viene vacía no basta — al quitar la
     * guarda del reparto no salía una columna vacía, salía UNA COLUMNA MENOS. Con 5 grupos y 5
     * columnas el reparto devolvía 4, y la pantalla pintaba una franja en blanco al final. El
     * invariante de verdad es el CONTEO.
     */
    @Test
    fun `P1 se devuelven exactamente las columnas que caben`() {
        for (columnas in 1..5) {
            val reparto = RepartoDeGrupos.repartir(menuReal, columnas)
            assertEquals(
                "se pidieron $columnas columnas y salieron ${reparto.size}",
                minOf(columnas, menuReal.size),
                reparto.size,
            )
        }
        // Un grupo enorme en medio es el caso que más tienta al reparto a quedarse corto.
        for (columnas in 2..4) {
            assertEquals(
                "con un grupo enorme faltaron columnas",
                columnas,
                RepartoDeGrupos.repartir(listOf(1, 1, 40, 1), columnas).size,
            )
        }
    }

    @Test
    fun `P2 con mas columnas que grupos no se inventan columnas vacias`() {
        val reparto = RepartoDeGrupos.repartir(listOf(3, 2), 5)
        assertEquals(2, reparto.size)
        assertEquals(listOf(listOf(0), listOf(1)), reparto)
    }

    @Test
    fun `P2 el balance usa la ALTURA, no el numero de grupos`() {
        // Un grupo enorme seguido de tres chicos: la primera columna se queda con el grande sola.
        val reparto = RepartoDeGrupos.repartir(listOf(20, 1, 1, 1), 2)
        assertEquals(listOf(0), reparto.first())
        assertEquals(listOf(1, 2, 3), reparto.last())
    }

    @Test
    fun `P1 el ancho maximo crece con las columnas o el texto se parte`() {
        // Una columna conserva el tope histórico que evita la fila estirada en tablet.
        assertEquals(600, RepartoDeGrupos.anchoMaximoDp(1))
        // Con varias, cada columna conserva su ancho legible más la separación entre ellas.
        assertEquals(1224, RepartoDeGrupos.anchoMaximoDp(2))
        assertEquals(1848, RepartoDeGrupos.anchoMaximoDp(3))
    }

    @Test
    fun `P2 el ancho maximo nunca baja de una columna`() {
        assertEquals(600, RepartoDeGrupos.anchoMaximoDp(0))
        assertEquals(600, RepartoDeGrupos.anchoMaximoDp(-3))
    }

    @Test
    fun `P2 sin grupos no revienta`() {
        assertEquals(listOf(emptyList<Int>()), RepartoDeGrupos.repartir(emptyList(), 3))
    }

    @Test
    fun `P2 un solo grupo cabe en una sola columna aunque se pidan tres`() {
        assertEquals(listOf(listOf(0)), RepartoDeGrupos.repartir(listOf(4), 3))
    }
}
