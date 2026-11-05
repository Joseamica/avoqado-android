package com.avoqado.pos.announcements

import com.avoqado.pos.announcements.data.model.ContentBlock
import com.avoqado.pos.announcements.data.model.parseContentBlocks
import com.avoqado.pos.announcements.presentation.anchoDeVentana
import com.avoqado.pos.announcements.presentation.medidaDeFoto
import com.avoqado.pos.announcements.presentation.topeDeAlto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedidasDeFotoTest {

    private fun bloques(json: String) = parseContentBlocks(Json.parseToJsonElement(json).jsonArray)

    @Test
    fun `parsea width y height del bloque image`() {
        val b = bloques("""[{"type":"image","url":"u","width":420,"height":630}]""")[0] as ContentBlock.Image
        assertEquals(420, b.width)
        assertEquals(630, b.height)
    }

    @Test
    fun `parsea width y height de cada foto de la galeria`() {
        val g = bloques(
            """[{"type":"gallery","images":[{"url":"a","width":10,"height":20},{"url":"b"}]}]""",
        )[0] as ContentBlock.Gallery
        assertEquals(10, g.images[0].width)
        assertEquals(20, g.images[0].height)
        assertNull(g.images[1].width)
        assertNull(g.images[1].height)
    }

    @Test
    fun `width como texto, cero, negativo, decimal o enorme queda en null`() {
        val b = bloques(
            """[{"type":"image","url":"u","width":"420","height":0},
               {"type":"image","url":"u","width":-5,"height":1.5},
               {"type":"image","url":"u","width":10001,"height":10000}]""",
        )
        val a = b[0] as ContentBlock.Image
        assertNull(a.width); assertNull(a.height)
        val c = b[1] as ContentBlock.Image
        assertNull(c.width); assertNull(c.height)
        val d = b[2] as ContentBlock.Image
        assertNull(d.width); assertEquals(10000, d.height)
    }

    @Test
    fun `ancho de la ventana`() {
        assertEquals(680f, anchoDeVentana(1280f), 0.001f)
        assertEquals(368f, anchoDeVentana(400f), 0.001f)
    }

    @Test
    fun `medida de la foto`() {
        assertEquals(320f to 480f, medidaDeFoto(640f, 420, 630, 480f))
        assertEquals(640f to 360f, medidaDeFoto(640f, 1600, 900, 480f))
        assertNull(medidaDeFoto(640f, null, 900, 480f))
        assertNull(medidaDeFoto(640f, 0, 0, 480f))
    }

    @Test
    fun `tope de alto`() {
        assertEquals(480f, topeDeAlto(1280f, 720f), 0.001f)
        assertEquals(480f, topeDeAlto(400f, 800f), 0.001f)
    }
}
