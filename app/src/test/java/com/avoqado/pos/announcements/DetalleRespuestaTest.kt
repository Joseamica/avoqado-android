package com.avoqado.pos.announcements

import com.avoqado.pos.announcements.data.model.AnnouncementDetailResponse
import com.avoqado.pos.announcements.data.model.ContentBlock
import com.avoqado.pos.announcements.data.model.bloquesDe
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El detalle del anuncio se quedaba girando para siempre en la tablet.
 *
 * 🔴 El servidor responde `{ data: { announcement: {...} } }` y los tres clientes leían
 * `data` como si FUERA el anuncio. En Android reventaba con
 * `MissingFieldException: Fields [id, title] are required`, que el repositorio atrapa
 * devolviendo null — y un detalle nulo pinta el cargador, así que el error era invisible.
 * Ni el compilador ni las pruebas lo veían: sólo salió abriendo el aviso en el aparato.
 */
class DetalleRespuestaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val respuestaReal = """
        {"success":true,"data":{"announcement":{
          "id":"a1","title":"Terminal nueva","body":"Ya disponible",
          "actionLabel":null,"actionUrl":null,
          "contentBlocks":[
            {"type":"heading","text":"Lo nuevo"},
            {"type":"specs","rows":[{"label":"Pantalla","value":"5.5"}]},
            {"type":"button","label":"Quiero una","url":"https://avoqado.io/hardware"}
          ]}}}
    """.trimIndent()

    @Test
    fun `el anuncio viene envuelto en data punto announcement`() {
        val r = json.decodeFromString<AnnouncementDetailResponse>(respuestaReal)
        assertEquals("a1", r.data?.announcement?.id)
        assertEquals("Terminal nueva", r.data?.announcement?.title)
    }

    @Test
    fun `los bloques se leen del anuncio, no de data`() {
        val bloques = bloquesDe(respuestaReal, json)
        assertEquals(3, bloques.size)
        assertTrue(bloques[0] is ContentBlock.Heading)
        assertTrue(bloques.any { it is ContentBlock.ActionButton })
    }

    @Test
    fun `un tipo de bloque que esta app no conoce se ignora, no truena`() {
        val conDesconocido = """
            {"success":true,"data":{"announcement":{"id":"a1","title":"T",
            "contentBlocks":[{"type":"holograma","x":1},{"type":"heading","text":"Sí"}]}}}
        """.trimIndent()
        val bloques = bloquesDe(conDesconocido, json)
        assertEquals(1, bloques.size)
        assertTrue(bloques[0] is ContentBlock.Heading)
    }

    @Test
    fun `sin bloques devuelve lista vacia`() {
        val sinBloques = """{"success":true,"data":{"announcement":{"id":"a1","title":"T"}}}"""
        assertTrue(bloquesDe(sinBloques, json).isEmpty())
    }
}
