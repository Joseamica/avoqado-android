package com.avoqado.pos.areatickets.domain

import com.avoqado.pos.areatickets.data.AreaOpcion
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3 (2-nov): las reglas del papel del aparato en los vales. Espejo: avoqado-ios `PapelDelAparatoTests`. */
class PapelDelAparatoTest {

    private val cremeria = AreaTicketArea(id = "area-cremeria", name = "Cremería", fulfillmentMode = "HOLD_UNTIL_PAID")

    private fun terminal(
        emite: Boolean = false,
        cobra: Boolean = false,
        entrega: Boolean = false,
        area: AreaTicketArea? = null,
    ) = AreaTicketTerminalCapabilities(
        id = "t1",
        name = "PC",
        fulfillmentArea = area,
        canIssueAreaTickets = emite,
        canCheckoutAreaTickets = cobra,
        canDeliverAreaTickets = entrega,
    )

    // MARK: - papelActual

    @Test
    fun `emite con área y cobra es EmiteVales que también cobra`() {
        assertEquals(
            PapelDelAparato.EmiteVales("area-cremeria", "Cremería", tambienCobra = true),
            papelActual(terminal(emite = true, cobra = true, area = cremeria)),
        )
    }

    @Test
    fun `emite con área sin cobrar es EmiteVales sin cobro`() {
        assertEquals(
            PapelDelAparato.EmiteVales("area-cremeria", "Cremería", tambienCobra = false),
            papelActual(terminal(emite = true, area = cremeria)),
        )
    }

    @Test
    fun `sólo cobra es Cobra`() {
        assertEquals(PapelDelAparato.Cobra, papelActual(terminal(cobra = true)))
    }

    @Test
    fun `sólo entrega es Entrega`() {
        assertEquals(PapelDelAparato.Entrega, papelActual(terminal(entrega = true)))
    }

    @Test
    fun `nada es SinElegir`() {
        assertEquals(PapelDelAparato.SinElegir, papelActual(terminal()))
    }

    // MARK: - etiquetaDelRecuadro

    @Test
    fun `el recuadro dice los cinco textos exactos`() {
        assertEquals("Cobra (caja)", etiquetaDelRecuadro(PapelDelAparato.Cobra))
        assertEquals("Emite · Cremería", etiquetaDelRecuadro(PapelDelAparato.EmiteVales("a", "Cremería", false)))
        assertEquals("Emite · Cremería y cobra", etiquetaDelRecuadro(PapelDelAparato.EmiteVales("a", "Cremería", true)))
        assertEquals("Entrega", etiquetaDelRecuadro(PapelDelAparato.Entrega))
        assertEquals("Sin elegir", etiquetaDelRecuadro(PapelDelAparato.SinElegir))
    }

    // MARK: - motivoParaNoGuardar

    @Test
    fun `P1 sin permiso gana sobre la red y la venta`() {
        assertEquals(
            "Pídele a tu administrador que lo configure",
            motivoParaNoGuardar(puedeConfigurar = false, hayRed = false, ventaAMedias = true),
        )
    }

    @Test
    fun `sin red gana sobre la venta a medias`() {
        assertEquals(
            "Necesita internet para cambiarlo",
            motivoParaNoGuardar(puedeConfigurar = true, hayRed = false, ventaAMedias = true),
        )
    }

    @Test
    fun `P1 con una venta a medias no se guarda`() {
        assertEquals(
            "Termina la venta antes de cambiar",
            motivoParaNoGuardar(puedeConfigurar = true, hayRed = true, ventaAMedias = true),
        )
    }

    @Test
    fun `con permiso, red y sin venta sí se guarda`() {
        assertNull(motivoParaNoGuardar(puedeConfigurar = true, hayRed = true, ventaAMedias = false))
    }

    // MARK: - ventaAMedias

    @Test
    fun `P1 cada señal sola es una venta a medias`() {
        val señales = 5
        repeat(señales) { i ->
            val s = BooleanArray(señales) { it == i }
            assertTrue("señal $i", ventaAMedias(s[0], s[1], s[2], s[3], s[4]))
        }
    }

    @Test
    fun `sin ninguna señal no hay venta a medias`() {
        assertFalse(ventaAMedias(false, false, false, false, false))
    }

    // MARK: - Servidor viejo vs lista vacía

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun respuesta(extra: String) = """
        {"venueId":"v1",$extra
         "areaTickets":{"entitled":true,"enabled":true},
         "terminal":{"id":"t1","name":"PC"},
         "scaleIntegration":{"entitled":false,"enabled":false}}
    """.trimIndent()

    @Test
    fun `P1 un servidor viejo no trae areas y queda null`() {
        val ajustes = json.decodeFromString(AreaTicketSettingsData.serializer(), respuesta(""))
        assertNull(ajustes.areas)
        assertEquals(0, ajustes.areasTotal)
        assertFalse(ajustes.terminal.roleChoicePending)
    }

    @Test
    fun `una lista vacía es lista vacía, no null`() {
        val ajustes = json.decodeFromString(AreaTicketSettingsData.serializer(), respuesta(""""areas":[],"areasTotal":0,"""))
        assertEquals(emptyList<AreaOpcion>(), ajustes.areas)
    }

    @Test
    fun `trae las áreas, el total y si debe preguntar`() {
        val ajustes = json.decodeFromString(
            AreaTicketSettingsData.serializer(),
            """
            {"venueId":"v1","areas":[{"id":"a1","name":"Cremería"}],"areasTotal":3,
             "areaTickets":{"entitled":true,"enabled":true},
             "terminal":{"id":"t1","name":"PC","roleChoicePending":true},
             "scaleIntegration":{"entitled":false,"enabled":false}}
            """.trimIndent(),
        )
        assertEquals(listOf(AreaOpcion("a1", "Cremería")), ajustes.areas)
        assertEquals(3, ajustes.areasTotal)
        assertTrue(ajustes.terminal.roleChoicePending)
    }
}
