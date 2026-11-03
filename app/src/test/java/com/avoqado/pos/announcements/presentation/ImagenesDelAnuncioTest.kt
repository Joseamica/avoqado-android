package com.avoqado.pos.announcements.presentation

import com.avoqado.pos.pos.presentation.product.EstadoDeFoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D16 (3-nov): una imagen de un anuncio no tiene iniciales que poner en su lugar. Mientras carga guarda su lugar; si la foto
 * falla (sin red falla la que no está guardada) se esconde, la del bloque «Imagen» con todo y su pie, y una galería sin ninguna
 * foto que cargue desaparece completa en vez de dejar un hueco.
 */
class ImagenesDelAnuncioTest {

    @Test
    fun `sin URL la imagen no se pinta`() {
        for (estado in EstadoDeFoto.entries) {
            assertFalse(mostrarImagenDeAnuncio(url = null, estado = estado))
            assertFalse(mostrarImagenDeAnuncio(url = "", estado = estado))
            assertFalse(mostrarImagenDeAnuncio(url = "  ", estado = estado))
        }
    }

    @Test
    fun `mientras carga guarda su lugar y ya lista se ve`() {
        assertTrue(mostrarImagenDeAnuncio(url = FOTO_A, estado = EstadoDeFoto.CARGANDO))
        assertTrue(mostrarImagenDeAnuncio(url = FOTO_A, estado = EstadoDeFoto.LISTA))
    }

    @Test
    fun `si la foto falla la imagen se esconde`() {
        assertFalse(mostrarImagenDeAnuncio(url = FOTO_A, estado = EstadoDeFoto.FALLO))
    }

    @Test
    fun `una galeria esconde solo las fotos que fallan`() {
        val urls = listOf(FOTO_A, FOTO_B, FOTO_C)
        assertEquals(listOf(0, 1, 2), fotosVisiblesDeGaleria(urls, estados = emptyMap()))
        assertEquals(
            listOf(0, 2),
            fotosVisiblesDeGaleria(urls, estados = mapOf(1 to EstadoDeFoto.FALLO, 2 to EstadoDeFoto.LISTA)),
        )
    }

    @Test
    fun `una galeria sin ninguna foto que cargue se esconde completa`() {
        val urls = listOf(FOTO_A, FOTO_B)
        assertEquals(
            emptyList<Int>(),
            fotosVisiblesDeGaleria(urls, estados = mapOf(0 to EstadoDeFoto.FALLO, 1 to EstadoDeFoto.FALLO)),
        )
        assertEquals(emptyList<Int>(), fotosVisiblesDeGaleria(listOf("", " "), estados = emptyMap()))
    }

    private companion object {
        const val FOTO_A = "https://cdn.avoqado.io/anuncios/a.png"
        const val FOTO_B = "https://cdn.avoqado.io/anuncios/b.png"
        const val FOTO_C = "https://cdn.avoqado.io/anuncios/c.png"
    }
}
