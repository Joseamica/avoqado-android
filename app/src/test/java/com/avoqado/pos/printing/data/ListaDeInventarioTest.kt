package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Lista de inventario» impresa (gratis): toda la relación de existencias en una tira, para contar
 * a mano en el anaquel. Lo que no puede fallar: que no se coma ningún artículo ni una sección, y
 * que la existencia salga con su unidad y su signo (un −11 es justo lo que hay que ir a revisar).
 */
class ListaDeInventarioTest {

    private fun ByteArray.texto() = toString(Charsets.ISO_8859_1)

    private val lista = ListaDeInventario(
        negocio = "Testarudo",
        fecha = "23/09/2026 09:30",
        secciones = listOf(
            ListaDeInventario.Seccion(
                "Productos",
                listOf(
                    ListaDeInventario.Renglon("Coca-Cola 600ml", "12", "BEBIDAS-001"),
                    ListaDeInventario.Renglon("Hamburguesa BBQ", "-11", null),
                ),
            ),
            ListaDeInventario.Seccion("Insumos", listOf(ListaDeInventario.Renglon("Jamon", "8.065 kg", null))),
        ),
    )

    private fun imprimir() = ESCPOSPrinter(PaperWidth.MM58).generateInventoryList(lista).texto()

    @Test
    fun `la lista trae encabezado, secciones con su cuenta y total`() {
        val t = imprimir()
        assertTrue(t.contains("Testarudo"))
        assertTrue(t.contains("23/09/2026 09:30"))
        assertTrue(t.contains("Productos (2)"))
        assertTrue(t.contains("Insumos (1)"))
        assertTrue(t.contains("Total: 3 articulos") || t.contains("Total: 3 artículos"))
    }

    @Test
    fun `cada renglon lleva su existencia con unidad y signo, y el SKU si hay`() {
        val lineas = imprimir().lines()
        assertTrue(lineas.any { it.startsWith("Coca-Cola 600ml") && it.trimEnd().endsWith("12") })
        assertTrue(lineas.any { it.startsWith("Hamburguesa BBQ") && it.trimEnd().endsWith("-11") })
        assertTrue(lineas.any { it.startsWith("Jamon") && it.trimEnd().endsWith("8.065 kg") })
        assertTrue(lineas.any { it.contains("SKU: BEBIDAS-001") })
    }

    @Test
    fun `una seccion vacia no se imprime y la tira se corta una vez`() {
        val vacia = lista.copy(secciones = lista.secciones + ListaDeInventario.Seccion("Otros", emptyList()))
        val bytes = ESCPOSPrinter(PaperWidth.MM58).generateInventoryList(vacia)
        assertTrue(!bytes.texto().contains("Otros"))
        val corte = ESCPOSPrinter.PARTIAL_CUT
        val cortes = (0..bytes.size - corte.size).count { i -> corte.indices.all { bytes[i + it] == corte[it] } }
        assertEquals(1, cortes)
    }
}
