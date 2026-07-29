package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El historial de caja mostraba literalmente **"null"** debajo de una sesión
 * cerrada, donde va la nota de cierre.
 *
 * La causa no es que faltara un guard —`isNullOrBlank()` estaba puesto— sino que
 * el valor NO era null: el server manda `"closingNote": null` y
 * `jsonPrimitive.content` sobre un JsonNull devuelve la CADENA "null", de cuatro
 * letras. Ningún `?:` la atrapa y ningún chequeo de nulidad la ve.
 */
class CashDrawerSessionParsingTest {

    private val repo = CashDrawerRepository(
        dao = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true),
        client = mockk(relaxed = true),
    )

    private fun parse(json: String) =
        repo.parseSessionFromApi(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun `un null explicito del server NO se vuelve la cadena null`() {
        val s = parse(
            """
            {
              "id": "sess-1",
              "startingAmount": 150.0,
              "actualAmount": 170.0,
              "overShort": 20.0,
              "status": "CLOSED",
              "deviceName": null,
              "closedByStaffId": null,
              "closedByName": null,
              "closingNote": null
            }
            """.trimIndent(),
        )
        assertNull("la nota debe quedar en null, no en \"null\"", s.closingNote)
        assertNull(s.deviceName)
        assertNull(s.closedByStaffId)
        assertEquals("", s.closedByName ?: "")
    }

    @Test
    fun `una nota de verdad se conserva`() {
        val s = parse(
            """
            {"id":"sess-2","startingAmount":100.0,"status":"CLOSED","closingNote":"Faltó cambio de 50"}
            """.trimIndent(),
        )
        assertEquals("Faltó cambio de 50", s.closingNote)
    }

    @Test
    fun `un closedAt nulo no se convierte en una fecha basura`() {
        // parseTimestamp("null") habría devuelto un timestamp inventado y el
        // historial mostraría una caja "cerrada" en una fecha imposible.
        val s = parse("""{"id":"sess-3","startingAmount":100.0,"status":"OPEN","closedAt":null}""")
        assertNull(s.closedAt)
    }
}
