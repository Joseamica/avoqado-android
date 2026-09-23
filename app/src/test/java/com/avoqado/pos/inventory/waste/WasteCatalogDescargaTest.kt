package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.CatalogoDestino
import com.avoqado.pos.inventory.waste.data.PaginaDeCatalogo
import com.avoqado.pos.inventory.waste.data.WasteCatalogItem
import com.avoqado.pos.inventory.waste.data.descargarCatalogo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La descarga del catálogo: cuántas páginas pide y cuándo escribe.
 *
 * El SQL se prueba aparte (`WasteCatalogSqlTest`); aquí se mide la decisión, con un
 * destino falso que sólo apunta qué se le mandó.
 */
class WasteCatalogDescargaTest {

    /** Apunta lo que recibe, sin base de datos. */
    private class DestinoFalso : CatalogoDestino {
        var escrituras = 0
        var ultimoLote: List<WasteCatalogItem>? = null
        override suspend fun reemplazar(venueId: String, items: List<WasteCatalogItem>, actualizadoEn: Long) {
            escrituras++
            ultimoLote = items
        }
    }

    private fun pagina(cuantos: Int, total: Int, page: Int, pageSize: Int, desde: Int = 0) =
        PaginaDeCatalogo(
            items = (0 until cuantos).map {
                WasteCatalogItem("RAW_MATERIAL", "rm${desde + it}", "Item ${desde + it}", "", "kg")
            },
            total = total, page = page, pageSize = pageSize,
        )

    /**
     * 🔴 Se recorre hasta cubrir `total` usando el `pageSize` EFECTIVO que devuelve el
     * servidor, no el que se pidió: el contrato dice que un `pageSize > 200` se RECORTA
     * a 200 en vez de rechazarse, así que pedir 500 y confiar en 500 se saltaría 3 de
     * cada 5 artículos — en silencio, con respuesta 200 y sin ningún error.
     */
    @Test
    fun `recorre hasta cubrir el total con el pageSize que devuelve el servidor`() = runTest {
        val pedidas = mutableListOf<Int>()
        val destino = DestinoFalso()

        val ok = descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { page ->
            pedidas += page
            when (page) {
                1 -> pagina(200, total = 450, page = 1, pageSize = 200, desde = 0)
                2 -> pagina(200, total = 450, page = 2, pageSize = 200, desde = 200)
                else -> pagina(50, total = 450, page = 3, pageSize = 200, desde = 400)
            }
        }

        assertTrue(ok)
        assertEquals(listOf(1, 2, 3), pedidas)
        assertEquals(450, destino.ultimoLote?.size)
    }

    /**
     * 🔴 Escribe UNA sola vez, al final. Guardar página por página dejaría un catálogo a
     * medias indistinguible de uno completo si la descarga se corta.
     */
    @Test
    fun `escribe una sola vez, con todo junto`() = runTest {
        val destino = DestinoFalso()

        descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { page ->
            if (page == 1) pagina(200, 300, 1, 200, 0) else pagina(100, 300, 2, 200, 200)
        }

        assertEquals(1, destino.escrituras)
        assertEquals(300, destino.ultimoLote?.size)
    }

    /**
     * 🔴 Si una página falla, NO se escribe nada: el catálogo anterior sigue intacto y
     * el cajero sigue pudiendo buscar. Un catálogo viejo sirve; uno a medias engaña.
     */
    @Test
    fun `una descarga cortada no escribe nada`() = runTest {
        val destino = DestinoFalso()

        val ok = descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { page ->
            if (page == 1) pagina(200, 450, 1, 200, 0) else null // la 2ª se cae
        }

        assertFalse(ok)
        assertEquals(0, destino.escrituras)
        assertNull(destino.ultimoLote)
    }

    /**
     * 🔴 Un servidor que deja de avanzar —devuelve una página vacía con un total que no
     * se alcanza— no puede colgar la app pidiendo páginas para siempre.
     */
    @Test
    fun `se detiene si el servidor deja de avanzar`() = runTest {
        var pedidas = 0
        val destino = DestinoFalso()

        val ok = descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { page ->
            pedidas++
            pagina(0, total = 450, page = page, pageSize = 200)
        }

        assertFalse(ok)
        assertEquals(0, destino.escrituras)
        assertEquals("una página vacía corta en seco", 1, pedidas)
    }

    /**
     * 🔴 Un servidor que manda MENOS de lo que declara en cada página (una por página contra
     * un total de 450) tampoco puede tenerla pidiendo 450 páginas: el tope sale del total y
     * del pageSize que el propio servidor declaró en la primera página (⌈450/200⌉ + 1 = 4).
     */
    @Test
    fun `se detiene si el servidor avanza menos de lo que declara`() = runTest {
        var pedidas = 0
        val destino = DestinoFalso()

        val ok = descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { page ->
            pedidas++
            pagina(1, total = 450, page = page, pageSize = 200, desde = page)
        }

        assertFalse(ok)
        assertEquals(0, destino.escrituras)
        assertTrue("pidió $pedidas páginas: no se detuvo", pedidas <= 4)
    }

    /**
     * 🔴 Codex r1: un tope FIJO de 60 páginas dejaba sin catálogo a una sucursal de 12 001
     * artículos — bajaba 12 000, abortaba sin escribir y repetía lo mismo al reabrir. El servidor
     * no tiene tope de total: la descarga llega a donde el total declarado diga.
     */
    @Test
    fun `un catalogo de 12001 articulos se baja entero`() = runTest {
        var pedidas = 0
        val destino = DestinoFalso()

        val ok = descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { page ->
            pedidas++
            val desde = (page - 1) * 200
            pagina(minOf(200, 12_001 - desde), total = 12_001, page = page, pageSize = 200, desde = desde)
        }

        assertTrue(ok)
        assertEquals(61, pedidas)
        assertEquals(12_001, destino.ultimoLote?.size)
    }

    /** Un catálogo vacío es un desenlace legítimo: se escribe, y queda vacío. */
    @Test
    fun `un catalogo vacio se escribe vacio, no se trata como fallo`() = runTest {
        val destino = DestinoFalso()

        val ok = descargarCatalogo(venueId = "v1", destino = destino, ahora = 0L) { pagina(0, 0, 1, 200) }

        assertTrue(ok)
        assertEquals(1, destino.escrituras)
        assertEquals(0, destino.ultimoLote?.size)
    }

    /** La marca de tiempo que se guarda es la que se le pasa, no la del reloj interno. */
    @Test
    fun `guarda la hora que se le da`() = runTest {
        var horaGuardada = -1L
        val destino = object : CatalogoDestino {
            override suspend fun reemplazar(venueId: String, items: List<WasteCatalogItem>, actualizadoEn: Long) {
                horaGuardada = actualizadoEn
            }
        }

        descargarCatalogo(venueId = "v1", destino = destino, ahora = 1_700_000_000_000L) { pagina(1, 1, 1, 200) }

        assertEquals(1_700_000_000_000L, horaGuardada)
    }
}
