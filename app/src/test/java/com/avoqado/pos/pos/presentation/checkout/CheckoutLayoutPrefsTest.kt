package com.avoqado.pos.pos.presentation.checkout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutLayoutPrefsTest {

    // MARK: - Tamaño de tiles

    @Test
    fun `sin nada guardado el tamano es el predeterminado`() {
        assertEquals(TileSize.MEDIANO, resolverTamano(null))
        assertEquals(TileSize.MEDIANO, resolverTamano(""))
    }

    @Test
    fun `P2 un tamano que esta version ya no conoce cae al default en vez de reventar`() {
        // Un aparato que bajó de una versión con más tamaños no puede quedarse
        // sin cuadrícula: se degrada al default, no se cae.
        assertEquals(TileSize.MEDIANO, resolverTamano("EXTRA_GRANDE"))
    }

    @Test
    fun `los tres tamanos van de mas denso a menos denso`() {
        val anchos = TileSize.entries.map { it.minTileWidthDp }
        assertEquals(anchos.sorted(), anchos)
        // Y el alto acompaña: compacto es el más acostado, grande el más cuadrado.
        assertTrue(TileSize.COMPACTO.categoryAspect > TileSize.MEDIANO.categoryAspect)
        assertTrue(TileSize.MEDIANO.categoryAspect > TileSize.GRANDE.categoryAspect)
    }

    // MARK: - Orden de pestañas

    @Test
    fun `sin orden guardado manda el orden natural`() {
        val disponibles = InputTab.entries.toList()
        assertEquals(disponibles, ordenarPestanas(emptyList(), disponibles))
    }

    @Test
    fun `el orden guardado se respeta`() {
        val guardado = listOf("PRODUCTS", "KEYPAD", "SHORTCUTS", "PROMOS", "MOSAIC")
        val resultado = ordenarPestanas(guardado, InputTab.entries.toList())
        assertEquals(InputTab.PRODUCTS, resultado.first())
        assertEquals(guardado, resultado.map { it.name })
    }

    @Test
    fun `P1 una pestana nueva NO desaparece con un orden viejo guardado`() {
        // El aparato guardó su orden antes de que existiera MOSAIC. Si la
        // filtráramos, el cajero perdería la entrada sin manera de recuperarla.
        val ordenViejo = listOf("PRODUCTS", "KEYPAD", "SHORTCUTS")
        val resultado = ordenarPestanas(ordenViejo, InputTab.entries.toList())

        assertEquals(InputTab.entries.size, resultado.size)
        assertTrue(InputTab.MOSAIC in resultado)
        assertEquals(listOf("PRODUCTS", "KEYPAD", "SHORTCUTS"), resultado.take(3).map { it.name })
    }

    @Test
    fun `P1 una pestana que hoy no es visible no se cuela por el orden guardado`() {
        // Promociones apagada desde el dashboard: el orden guardado la nombra,
        // pero no puede resucitarla — el gating manda sobre la preferencia.
        val guardado = listOf("PROMOS", "PRODUCTS", "KEYPAD")
        val visibles = InputTab.entries.filter { it != InputTab.PROMOS }

        val resultado = ordenarPestanas(guardado, visibles)

        assertTrue(InputTab.PROMOS !in resultado)
        assertEquals(InputTab.PRODUCTS, resultado.first())
    }

    @Test
    fun `un nombre repetido o basura en el orden guardado no duplica pestanas`() {
        val guardado = listOf("KEYPAD", "KEYPAD", "NO_EXISTE", "PRODUCTS")
        val resultado = ordenarPestanas(guardado, InputTab.entries.toList())

        assertEquals(InputTab.entries.size, resultado.size)
        assertEquals(resultado.distinct(), resultado)
    }

    // MARK: - Mover

    @Test
    fun `mover una pestana la reacomoda sin perder ninguna`() {
        val orden = InputTab.entries.toList()
        val movida = moverPestana(orden, desde = 2, hacia = 0)

        assertEquals(InputTab.PRODUCTS, movida.first())
        assertEquals(orden.size, movida.size)
        assertEquals(orden.toSet(), movida.toSet())
    }

    @Test
    fun `mover fuera de rango devuelve la misma lista`() {
        val orden = InputTab.entries.toList()
        assertEquals(orden, moverPestana(orden, desde = 0, hacia = -1))
        assertEquals(orden, moverPestana(orden, desde = 99, hacia = 0))
        assertEquals(orden, moverPestana(orden, desde = 1, hacia = 1))
    }
}
