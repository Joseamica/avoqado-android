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
        pendingCashSales = mockk(relaxed = true),
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

    /**
     * Una sesión ABIERTA no tiene conteo ni diferencia: el dinero aún no se ha
     * contado. El server los manda como null y `jsonPrimitive.double` intentaba
     * convertir el TEXTO "null", reventando el parseo ENTERO.
     *
     * Efecto medido en la tablet: "Parse current session error: For input
     * string: \"null\"" en cada sincronización, y la caja abierta en el server
     * era invisible para el POS — mientras el historial entraba sin problema,
     * porque sus sesiones están cerradas y sí traen cifra.
     */
    @Test
    fun `una sesion ABIERTA se parsea, aunque no tenga conteo ni diferencia`() {
        val session = parse(
            """
            {
              "id": "s-abierta",
              "startingAmount": 500.0,
              "actualAmount": null,
              "overShort": null,
              "closedAt": null,
              "closedByName": null,
              "openedByName": "Ana",
              "openedAt": "2026-08-03T16:27:00.000Z",
              "status": "OPEN"
            }
            """.trimIndent(),
        )

        assertEquals("s-abierta", session.id)
        assertEquals(50_000, session.startingAmountCents)
        assertNull("sin conteo mientras la caja siga abierta", session.actualAmountCents)
        assertNull("y sin diferencia", session.overShortCents)
        assertEquals("Ana", session.openedByName)
    }

    @Test
    fun `un importe ausente no vale cero disfrazado`() {
        // Que `actualAmount` falte no es lo mismo que contar $0: uno es "todavía
        // no", el otro es "el cajón está vacío". Confundirlos inventa un faltante
        // por el total de la caja.
        val session = parse("""{"id":"s2","startingAmount":100.0,"status":"OPEN"}""")
        assertNull(session.actualAmountCents)
        assertNull(session.overShortCents)
    }
}
