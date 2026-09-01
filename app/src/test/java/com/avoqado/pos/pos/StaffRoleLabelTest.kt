package com.avoqado.pos.pos

import com.avoqado.pos.core.util.RoleDisplay
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.soloVendedores
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El rol que se PINTA en los selectores (Vendedor, checador) — nunca el enum
 * crudo (founder, 2026-09-01: salía "WAITER" en el mostrador). El custom del
 * venue gana; sin server nuevo se traduce en local; un rol desconocido se
 * enseña tal cual en vez de esconderse.
 */
class StaffRoleLabelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `el nombre custom del venue gana sobre la traduccion`() {
        assertEquals("Investor", RoleDisplay.label("Investor", "VIEWER"))
    }

    @Test
    fun `sin custom se traduce el enum al espanol`() {
        assertEquals("Mesero", RoleDisplay.label(null, "WAITER"))
        assertEquals("Observador", RoleDisplay.label(null, "VIEWER"))
        assertEquals("Propietario", RoleDisplay.label("", "OWNER"))
    }

    @Test
    fun `un rol desconocido se ensena tal cual, no se esconde`() {
        assertEquals("PROMOTER", RoleDisplay.label(null, "PROMOTER"))
    }

    @Test
    fun `soloVendedores filtra la perilla apagada y conserva null (server viejo) y true`() {
        val vende = json.decodeFromString<StaffMember>("""{"id":"a","role":"WAITER","showAsSeller":true}""")
        val apagado = json.decodeFromString<StaffMember>("""{"id":"b","role":"VIEWER","showAsSeller":false}""")
        val serverViejo = json.decodeFromString<StaffMember>("""{"id":"c","role":"KITCHEN"}""")

        val filtrados = listOf(vende, apagado, serverViejo).soloVendedores()

        // Perilla apagada fuera; prendida dentro; y AUSENTE (server viejo)
        // dentro — fail-open: un server desactualizado enseña a todos, nunca
        // esconde al equipo entero.
        assertEquals(listOf("a", "c"), filtrados.map { it.id })
    }

    @Test
    fun `StaffMember parsea roleDisplayName y cae a la traduccion si falta`() {
        val nuevo = json.decodeFromString<StaffMember>(
            """{"id":"s1","firstName":"Ana","role":"VIEWER","roleDisplayName":"Investor"}""",
        )
        assertEquals("Investor", nuevo.roleLabel)

        // Server viejo: sin el campo, la etiqueta sale traducida.
        val viejo = json.decodeFromString<StaffMember>("""{"id":"s2","role":"WAITER"}""")
        assertEquals("Mesero", viejo.roleLabel)
    }
}
