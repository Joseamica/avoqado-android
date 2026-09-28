package com.avoqado.pos.printing.routing

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «La caja decide por estación» (spec 2026-09-27 §5). Mismos casos que `KitchenDeliveryPolicyTests` de avoqado-ios.
 */
class KitchenDeliveryPolicyTest {

    private val cocina = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1")
    private val barraConImpresora = StationInfo(id = "st_barra", name = "Barra", printerId = "pr_2", hasKitchenDisplay = true)
    private val barraSoloPantalla = StationInfo(id = "st_barra", name = "Barra", printerId = null, hasKitchenDisplay = true)
    private val postresSinNada = StationInfo(id = "st_postres", name = "Postres", printerId = null)

    private fun plan(stationId: String?) = TicketPlan(
        stationId = stationId,
        unrouted = stationId == null,
        lines = listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_${stationId ?: "none"}"))),
    )

    private fun config(vararg stations: StationInfo) = PrintConfig(stations = stations.toList(), defaultStationId = "st_cocina")

    @Test
    fun `solo impresora - imprime como hoy con o sin servidor`() {
        val planes = listOf(plan("st_cocina"))
        for (servidor in listOf(true, false)) {
            val r = KitchenDeliveryPolicy.decidir(planes, config(cocina), servidorLaTiene = servidor)
            assertEquals(planes, r.aImprimir)
            assertEquals(emptyList<String>(), r.respaldo)
        }
    }

    @Test
    fun `impresora mas pantalla - imprime como hoy con o sin servidor`() {
        val planes = listOf(plan("st_barra"))
        for (servidor in listOf(true, false)) {
            val r = KitchenDeliveryPolicy.decidir(planes, config(cocina, barraConImpresora), servidorLaTiene = servidor)
            assertEquals(planes, r.aImprimir)
            assertEquals(emptyList<String>(), r.respaldo)
        }
    }

    @Test
    fun `P1 solo pantalla con el servidor al tanto - NO se imprime`() {
        val r = KitchenDeliveryPolicy.decidir(
            listOf(plan("st_cocina"), plan("st_barra")), config(cocina, barraSoloPantalla), servidorLaTiene = true,
        )
        assertEquals(listOf("st_cocina"), r.aImprimir.map { it.stationId })
        assertEquals(emptyList<String>(), r.respaldo)
    }

    @Test
    fun `P1 solo pantalla sin el servidor - sale en papel de respaldo`() {
        val r = KitchenDeliveryPolicy.decidir(
            listOf(plan("st_cocina"), plan("st_barra")), config(cocina, barraSoloPantalla), servidorLaTiene = false,
        )
        assertEquals(listOf("st_cocina", "st_barra"), r.aImprimir.map { it.stationId })
        assertEquals(listOf("st_barra"), r.respaldo)
    }

    @Test
    fun `P1 H4 - estacion sin impresora y SIN pantalla imprime como hoy`() {
        val r = KitchenDeliveryPolicy.decidir(listOf(plan("st_postres")), config(cocina, postresSinNada), servidorLaTiene = true)
        assertEquals(listOf("st_postres"), r.aImprimir.map { it.stationId })
        assertFalse(KitchenDeliveryPolicy.esSoloPantalla(postresSinNada))
    }

    @Test
    fun `sin estacion, estacion borrada o config vacia - como hoy`() {
        val planes = listOf(plan(null), plan("st_borrada"))
        assertEquals(planes, KitchenDeliveryPolicy.decidir(planes, config(cocina, barraSoloPantalla), servidorLaTiene = true).aImprimir)
        assertEquals(planes, KitchenDeliveryPolicy.decidir(planes, PrintConfig(), servidorLaTiene = false).aImprimir)
    }

    @Test
    fun `una estacion inactiva no es solo pantalla`() {
        assertFalse(KitchenDeliveryPolicy.esSoloPantalla(barraSoloPantalla.copy(active = false)))
    }

    @Test
    fun `conRespaldo marca solo esas estaciones y heredarRespaldo lo pasa a la config vigente`() {
        val congelada = KitchenDeliveryPolicy.conRespaldo(config(cocina, barraSoloPantalla), listOf("st_barra"))
        assertEquals(listOf(false, true), congelada.stations.map { it.respaldoLocal })

        val vigente = config(cocina, barraSoloPantalla.copy(name = "Barra nueva"))
        val heredada = KitchenDeliveryPolicy.heredarRespaldo(de = congelada, en = vigente)
        assertEquals(listOf(false, true), heredada.stations.map { it.respaldoLocal })
        assertEquals("Barra nueva", heredada.stations[1].name)
    }

    @Test
    fun `sin respaldo la config es la misma`() {
        val c = config(cocina)
        assertTrue(KitchenDeliveryPolicy.conRespaldo(c, emptyList()) === c)
    }

    @Test
    fun `P1 el folio es el del servidor - sale y round, con estacion o none`() {
        assertEquals("sale:ext-1:st_barra", KitchenDeliveryPolicy.folio("sale:ext-1", "st_barra"))
        assertEquals("round:rk-9:st_barra", KitchenDeliveryPolicy.folio("round:rk-9", "st_barra"))
        assertEquals("sale:ext-1:none", KitchenDeliveryPolicy.folio("sale:ext-1", null))
    }

    @Test
    fun `P1 solo se marca el respaldo que SI salio, y sin folio no se marca nada`() {
        val c = config(cocina, barraSoloPantalla, StationInfo(id = "st_postres", name = "Postres", hasKitchenDisplay = true))
        val marcas = KitchenDeliveryPolicy.marcasDeRespaldo(
            listOf("st_barra", "st_postres"), c, sinPapel = listOf("Postres"), origen = "sale:ext-1", label = "1234",
        )
        assertEquals(listOf(KitchenDeliveryPolicy.MarcaDePapel("sale:ext-1:st_barra", "st_barra", "1234")), marcas)
        assertEquals(
            emptyList<KitchenDeliveryPolicy.MarcaDePapel>(),
            KitchenDeliveryPolicy.marcasDeRespaldo(listOf("st_barra"), c, emptyList(), origen = null, label = "1234"),
        )
    }

    @Test
    fun `el encabezado de respaldo dice la estacion`() {
        assertEquals("RESPALDO · Barra · pantalla sin conexión", KitchenDeliveryPolicy.encabezadoDeRespaldo("Barra"))
    }

    @Test
    fun `el aviso sin red nombra las estaciones solo pantalla en el orden de la config`() {
        assertNull(KitchenDeliveryPolicy.avisoSinRed(config(cocina, barraConImpresora)))
        assertEquals("Las comandas de Barra salen en papel", KitchenDeliveryPolicy.avisoSinRed(config(cocina, barraSoloPantalla)))
        val tres = config(
            barraSoloPantalla,
            StationInfo(id = "st_p", name = "Postres", hasKitchenDisplay = true),
            StationInfo(id = "st_c", name = "Café", hasKitchenDisplay = true),
        )
        assertEquals("Las comandas de Barra, Postres y Café salen en papel", KitchenDeliveryPolicy.avisoSinRed(tres))
    }

    @Test
    fun `respaldoLocal no viene del servidor - una config sin el campo lo deja apagado`() {
        val json = Json { ignoreUnknownKeys = true }
        val estacion = json.decodeFromString(StationInfo.serializer(), """{"id":"st_barra","name":"Barra","hasKitchenDisplay":true}""")
        assertFalse(estacion.respaldoLocal)
    }
}
