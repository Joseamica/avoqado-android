package com.avoqado.pos.pos.presentation.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 (2-nov): en la tableta, con la cuenta de prueba, las tarjetas de producto salían grises y sin iniciales. Sus fotos son de
 * loremflickr, que responde 500: la foto falla y Android no tenía respaldo. iOS pinta el color y las iniciales mientras carga y si
 * falla (`ProductTile.colorBackground`, `FamilyTile.fondo`), con un indicador mientras carga. Las iniciales son las de
 * `Product.initials` de iOS.
 */
class FotoDelTileTest {

    // MARK: - Qué se ve en el recuadro de la foto

    @Test
    fun `sin foto se ven el color y las iniciales`() {
        for (estado in EstadoDeFoto.entries) {
            assertTrue(mostrarRespaldoDeFoto(conFoto = false, estado = estado))
            assertFalse(mostrarCargaDeFoto(conFoto = false, estado = estado))
        }
    }

    @Test
    fun `mientras la foto carga se ven las iniciales y el indicador`() {
        assertTrue(mostrarRespaldoDeFoto(conFoto = true, estado = EstadoDeFoto.CARGANDO))
        assertTrue(mostrarCargaDeFoto(conFoto = true, estado = EstadoDeFoto.CARGANDO))
    }

    @Test
    fun `si la foto falla se quedan el color y las iniciales sin indicador`() {
        assertTrue(mostrarRespaldoDeFoto(conFoto = true, estado = EstadoDeFoto.FALLO))
        assertFalse(mostrarCargaDeFoto(conFoto = true, estado = EstadoDeFoto.FALLO))
    }

    @Test
    fun `solo la foto lista tapa las iniciales`() {
        assertFalse(mostrarRespaldoDeFoto(conFoto = true, estado = EstadoDeFoto.LISTA))
        assertFalse(mostrarCargaDeFoto(conFoto = true, estado = EstadoDeFoto.LISTA))
    }

    // MARK: - Iniciales (espejo de Product.initials de iOS)

    @Test
    fun `dos palabras o mas dan la primera letra de las dos primeras`() {
        assertEquals("AM", inicialesDeTile("Agua mineral"))
        assertEquals("CA", inicialesDeTile("Café americano grande"))
        assertEquals("TV", inicialesDeTile("  té  verde "))
    }

    @Test
    fun `una sola palabra da sus dos primeras letras`() {
        assertEquals("LA", inicialesDeTile("Latte"))
        assertEquals("Ñ", inicialesDeTile("ñ"))
        assertEquals("", inicialesDeTile(""))
    }

    @Test
    fun `un emoji no se parte en dos`() {
        assertEquals("🍔H", inicialesDeTile("🍔 Hamburguesa"))
        assertEquals("🍔", inicialesDeTile("🍔"))
    }

    // MARK: - La letra de las iniciales en una miniatura (D16, 3-nov)

    @Test
    fun `la letra de las iniciales crece con la miniatura`() {
        assertEquals(13.2f, letraDeIniciales(anchoDp = 44f, altoDp = 44f) ?: -1f, 0.001f)
        assertEquals(31.2f, letraDeIniciales(anchoDp = 104f, altoDp = 104f) ?: -1f, 0.001f)
    }

    @Test
    fun `en una miniatura que no es cuadrada manda el lado menor`() {
        assertEquals(12f, letraDeIniciales(anchoDp = 64f, altoDp = 40f) ?: -1f, 0.001f)
        assertEquals(16.8f, letraDeIniciales(anchoDp = Float.POSITIVE_INFINITY, altoDp = 56f) ?: -1f, 0.001f)
    }

    @Test
    fun `sin tamano la miniatura no inventa una letra`() {
        assertNull(letraDeIniciales(anchoDp = Float.POSITIVE_INFINITY, altoDp = Float.POSITIVE_INFINITY))
        assertNull(letraDeIniciales(anchoDp = 0f, altoDp = 44f))
        assertNull(letraDeIniciales(anchoDp = Float.NaN, altoDp = 44f))
    }
}
