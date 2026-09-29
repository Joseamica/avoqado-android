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
    fun `solo impresora - imprime como hoy con o sin acuse`() {
        val planes = listOf(plan("st_cocina"))
        for (acusadas in listOf(emptySet(), setOf("st_cocina"))) {
            val r = KitchenDeliveryPolicy.decidir(planes, config(cocina), acusadas)
            assertEquals(planes, r.aImprimir)
            assertEquals(emptyList<String>(), r.respaldo)
        }
    }

    @Test
    fun `impresora mas pantalla - imprime como hoy con o sin acuse`() {
        val planes = listOf(plan("st_barra"))
        for (acusadas in listOf(emptySet(), setOf("st_barra"))) {
            val r = KitchenDeliveryPolicy.decidir(planes, config(cocina, barraConImpresora), acusadas)
            assertEquals(planes, r.aImprimir)
            assertEquals(emptyList<String>(), r.respaldo)
        }
    }

    @Test
    fun `P1 solo pantalla con acuse de su pantalla - NO se imprime`() {
        val r = KitchenDeliveryPolicy.decidir(listOf(plan("st_cocina"), plan("st_barra")), config(cocina, barraSoloPantalla), acusadas = setOf("st_barra"))
        assertEquals(listOf("st_cocina"), r.aImprimir.map { it.stationId })
        assertEquals(emptyList<String>(), r.respaldo)
    }

    @Test
    fun `P1 solo pantalla sin acuse - sale en papel de respaldo, con o sin internet`() {
        val r = KitchenDeliveryPolicy.decidir(listOf(plan("st_cocina"), plan("st_barra")), config(cocina, barraSoloPantalla), acusadas = emptySet())
        assertEquals(listOf("st_cocina", "st_barra"), r.aImprimir.map { it.stationId })
        assertEquals(listOf("st_barra"), r.respaldo)
    }

    @Test
    fun `P1 el acuse decide POR ESTACION - Barra acusa y Postres no`() {
        val postres = StationInfo(id = "st_postres", name = "Postres", printerId = null, hasKitchenDisplay = true)
        val r = KitchenDeliveryPolicy.decidir(
            listOf(plan("st_barra"), plan("st_postres")), config(cocina, barraSoloPantalla, postres), acusadas = setOf("st_barra"),
        )
        assertEquals(listOf("st_postres"), r.aImprimir.map { it.stationId })
        assertEquals(listOf("st_postres"), r.respaldo)
    }

    @Test
    fun `planesConPantalla son los de estaciones activas con pantalla - sin estacion y sin pantalla no se empujan`() {
        val postresSinNada = StationInfo(id = "st_postres", name = "Postres", printerId = null)
        val planes = listOf(plan("st_cocina"), plan("st_barra"), plan("st_postres"), plan(null))
        val conPantalla = KitchenDeliveryPolicy.planesConPantalla(planes, config(cocina, barraConImpresora, postresSinNada))
        assertEquals(listOf("st_barra"), conPantalla.map { it.stationId })
        assertEquals(emptyList<TicketPlan>(), KitchenDeliveryPolicy.planesConPantalla(planes, config(cocina, barraSoloPantalla.copy(active = false))))
    }

    @Test
    fun `mensajeParaPantalla lleva el folio del servidor, el id del renglon o uno sintetico`() {
        val plan = TicketPlan(
            stationId = "st_barra", unrouted = false,
            lines = listOf(
                ConsolidatedLine("Café", 2, listOf("Sin azúcar"), "caliente", listOf("oi_2", "oi_3")),
                ConsolidatedLine("Té", 1, emptyList(), null, emptyList()),
            ),
        )
        val m = KitchenDeliveryPolicy.mensajeParaPantalla(plan, "venue-1", "tablet-1", "sale:ext-1", "1234", "En tienda", "ord-1", 5L)
        assertEquals("sale:ext-1:st_barra", m.sourceKey)
        assertEquals("st_barra", m.stationId)
        assertEquals("tablet-1", m.deviceId)
        assertEquals("ord-1", m.orderId)
        assertEquals(5L, m.createdAtMillis)
        assertEquals(listOf("oi_2", "sale:ext-1:st_barra#1"), m.items.map { it.id })
        assertEquals(listOf("Sin azúcar"), m.items[0].modifiers)
        assertEquals("caliente", m.items[0].notes)
        assertEquals(1, m.version)
        assertEquals("comanda", m.op)
    }

    @Test
    fun `P1 H4 - estacion sin impresora y SIN pantalla imprime como hoy`() {
        val r = KitchenDeliveryPolicy.decidir(listOf(plan("st_postres")), config(cocina, postresSinNada), acusadas = emptySet())
        assertEquals(listOf("st_postres"), r.aImprimir.map { it.stationId })
        assertFalse(KitchenDeliveryPolicy.esSoloPantalla(postresSinNada))
    }

    @Test
    fun `sin estacion, estacion borrada o config vacia - como hoy`() {
        val planes = listOf(plan(null), plan("st_borrada"))
        assertEquals(planes, KitchenDeliveryPolicy.decidir(planes, config(cocina, barraSoloPantalla), acusadas = emptySet()).aImprimir)
        assertEquals(planes, KitchenDeliveryPolicy.decidir(planes, PrintConfig(), acusadas = emptySet()).aImprimir)
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
    fun `el aviso sin red nombra las estaciones solo pantalla en el orden de la config - y dice que es si la pantalla no contesta`() {
        assertNull(KitchenDeliveryPolicy.avisoSinRed(config(cocina, barraConImpresora)))
        assertEquals("Las comandas de Barra salen en papel si la pantalla no contesta", KitchenDeliveryPolicy.avisoSinRed(config(cocina, barraSoloPantalla)))
        val tres = config(
            barraSoloPantalla,
            StationInfo(id = "st_p", name = "Postres", hasKitchenDisplay = true),
            StationInfo(id = "st_c", name = "Café", hasKitchenDisplay = true),
        )
        assertEquals("Las comandas de Barra, Postres y Café salen en papel si la pantalla no contesta", KitchenDeliveryPolicy.avisoSinRed(tres))
    }

    @Test
    fun `el aviso de racha nombra las pantallas que no se alcanzan, y nada si ninguna`() {
        val c = config(cocina, barraSoloPantalla, StationInfo(id = "st_p", name = "Postres", hasKitchenDisplay = true))
        assertNull(KitchenDeliveryPolicy.avisoDeRacha(emptySet(), c))
        assertEquals("La pantalla de Barra no se alcanza por el WiFi", KitchenDeliveryPolicy.avisoDeRacha(setOf("st_barra"), c))
        assertEquals("Las pantallas de Barra y Postres no se alcanzan por el WiFi", KitchenDeliveryPolicy.avisoDeRacha(setOf("st_p", "st_barra"), c))
        assertNull("una estación que ya no existe en la config no se nombra", KitchenDeliveryPolicy.avisoDeRacha(setOf("st_borrada"), c))
    }

    @Test
    fun `respaldoLocal no viene del servidor - una config sin el campo lo deja apagado`() {
        val json = Json { ignoreUnknownKeys = true }
        val estacion = json.decodeFromString(StationInfo.serializer(), """{"id":"st_barra","name":"Barra","hasKitchenDisplay":true}""")
        assertFalse(estacion.respaldoLocal)
    }
}
