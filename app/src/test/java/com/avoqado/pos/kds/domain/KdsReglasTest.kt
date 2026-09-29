package com.avoqado.pos.kds.domain

import com.avoqado.pos.kds.data.KdsHttpException
import com.avoqado.pos.printing.routing.StationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Las reglas de la pantalla de cocina, sin red ni pantalla. Espejo de `KDSReglasTests` de iOS. */
class KdsReglasTest {

    private val base = EntradaDeCasilla(
        abiertaAClientes = true, esSuperadmin = false, puedeConfigurar = true, tieneAccesoPro = true, prendida = false,
    )

    // MARK: - estadoDeCasilla: el MISMO cuerpo que el dashboard (plan 3.2, Task 1)

    @Test
    fun `antes del lanzamiento el cliente no ve Prender`() =
        assertEquals(EstadoDeCasilla.Oculta, estadoDeCasilla(base.copy(abiertaAClientes = false)))

    @Test
    fun `P1 antes del lanzamiento, una que Avoqado prendio se puede APAGAR con printers manage`() = assertEquals(
        EstadoDeCasilla.SoloApagar(MotivoSoloApagar.LANZAMIENTO),
        estadoDeCasilla(base.copy(abiertaAClientes = false, prendida = true)),
    )

    @Test
    fun `antes del lanzamiento y sin permiso, tampoco se ofrece nada sobre una prendida`() = assertEquals(
        EstadoDeCasilla.Oculta,
        estadoDeCasilla(base.copy(abiertaAClientes = false, prendida = true, puedeConfigurar = false)),
    )

    @Test
    fun `superadmin la prende antes del lanzamiento, marcada solo Avoqado`() =
        assertEquals(EstadoDeCasilla.Editable(soloAvoqado = true), estadoDeCasilla(base.copy(abiertaAClientes = false, esSuperadmin = true)))

    @Test
    fun `superadmin despues del lanzamiento - editable normal`() =
        assertEquals(EstadoDeCasilla.Editable(soloAvoqado = false), estadoDeCasilla(base.copy(esSuperadmin = true)))

    @Test
    fun `P1 KITCHEN sin printers manage sólo mira`() =
        assertEquals(EstadoDeCasilla.SoloLectura, estadoDeCasilla(base.copy(puedeConfigurar = false)))

    @Test
    fun `sin Pro y apagada pide mejorar el plan`() =
        assertEquals(EstadoDeCasilla.RequierePro, estadoDeCasilla(base.copy(tieneAccesoPro = false)))

    @Test
    fun `sin Pro y prendida - solo apagar`() = assertEquals(
        EstadoDeCasilla.SoloApagar(MotivoSoloApagar.PLAN),
        estadoDeCasilla(base.copy(tieneAccesoPro = false, prendida = true)),
    )

    @Test
    fun `con Pro y permiso es editable`() =
        assertEquals(EstadoDeCasilla.Editable(soloAvoqado = false), estadoDeCasilla(base))

    @Test
    fun `prender sólo si esta apagada y es editable, apagar si esta prendida y no es sólo mirar`() {
        assertTrue(puedePrender(EstadoDeCasilla.Editable(false), prendida = false))
        assertFalse(puedePrender(EstadoDeCasilla.Editable(false), prendida = true))
        assertFalse(puedePrender(EstadoDeCasilla.SoloLectura, prendida = false))
        assertTrue(puedeApagar(EstadoDeCasilla.SoloApagar(MotivoSoloApagar.LANZAMIENTO), prendida = true))
        assertTrue(puedeApagar(EstadoDeCasilla.Editable(false), prendida = true))
        assertFalse(puedeApagar(EstadoDeCasilla.SoloLectura, prendida = true))
        assertFalse(puedeApagar(EstadoDeCasilla.Editable(false), prendida = false))
    }

    // MARK: - Estaciones

    private val barra = StationInfo(id = "st-barra", name = "Barra", displayOrder = 1)
    private val cocina = StationInfo(id = "st-cocina", name = "Cocina", displayOrder = 0)
    private val vieja = StationInfo(id = "st-vieja", name = "Vieja", active = false)

    @Test
    fun `se eligen sólo estaciones activas, en su orden`() =
        assertEquals(listOf("st-cocina", "st-barra"), estacionesParaElegir(listOf(barra, vieja, cocina)).map { it.id })

    @Test
    fun `P1 la estacion guardada que se borro o se desactivo ya no cuenta como elegida`() {
        assertEquals("st-barra", estacionElegida("st-barra", listOf(barra, cocina))?.id)
        assertNull("borrada", estacionElegida("st-borrada", listOf(barra, cocina)))
        assertNull("desactivada", estacionElegida("st-vieja", listOf(barra, vieja)))
        assertNull("nada guardado", estacionElegida(null, listOf(barra)))
    }

    @Test
    fun `la etiqueta dice Sin estacion o de que estacion viene - nada si es la propia`() {
        assertNull(etiquetaDeEstacion("st-barra", elegida = "st-barra", estaciones = listOf(barra, cocina)))
        assertEquals("Sin estación", etiquetaDeEstacion(null, elegida = "st-barra", estaciones = listOf(barra)))
        assertEquals("Cocina", etiquetaDeEstacion("st-cocina", elegida = "st-barra", estaciones = listOf(barra, cocina)))
        assertEquals("Sin estación", etiquetaDeEstacion("st-borrada", elegida = "st-barra", estaciones = listOf(barra)))
    }

    // MARK: - Tablero

    private fun comanda(id: String, needsAcceptance: Boolean = false, needsPrint: Boolean = false, sourceKey: String? = null) =
        KDSOrder(
            id = id, orderNumber = id, orderType = "En tienda", needsAcceptance = needsAcceptance, needsPrint = needsPrint,
            items = emptyList(), createdAt = 0L, status = KDSOrderStatus.NEW, sourceKey = sourceKey,
        )

    @Test
    fun `P1 marcar todas deja fuera el delivery que nadie ha aceptado y respeta el tope`() {
        assertEquals(listOf("k1", "k3"), idsParaMarcarTodas(listOf(comanda("k1"), comanda("k2", needsAcceptance = true), comanda("k3"))))
        assertEquals(100, idsParaMarcarTodas((1..150).map { comanda("k$it") }).size)
    }

    @Test
    fun `pendientes en palabras, con el tope del servidor dicho`() {
        assertEquals("1 pendiente", TextosDeCocina.pendientes(1))
        assertEquals("7 pendientes", TextosDeCocina.pendientes(7))
        assertEquals("100+ pendientes", TextosDeCocina.pendientes(100))
    }

    // MARK: - Errores: sin red es un ESTADO, no un error rojo

    @Test
    fun `P1 sin red se dice qué no se hizo, sin rojo`() {
        assertEquals(AvisoDeCocina(AccionDeCocina.LISTO.sinRed, esError = false), avisoDeFallo(IOException("x"), AccionDeCocina.LISTO))
        assertEquals(
            AvisoDeCocina(AccionDeCocina.MARCAR_TODAS.sinRed, esError = false),
            avisoDeFallo(KdsHttpException(503, null, "Service Unavailable"), AccionDeCocina.MARCAR_TODAS),
        )
    }

    @Test
    fun `los rechazos de la casilla se explican con el texto del dashboard`() {
        assertEquals(
            AvisoDeCocina(TextosDeCocina.NO_LANZADA, esError = false),
            avisoDeFallo(KdsHttpException(403, RECHAZO_NO_LANZADA, "x"), AccionDeCocina.PANTALLA),
        )
        assertEquals(
            AvisoDeCocina(TextosDeCocina.REQUIERE_PRO, esError = false),
            avisoDeFallo(KdsHttpException(403, RECHAZO_REQUIERE_PRO, "x"), AccionDeCocina.PANTALLA),
        )
    }

    @Test
    fun `otro no del servidor se dice con su mensaje, y lo desconocido con el generico`() {
        assertEquals(
            AvisoDeCocina("No hay una comanda terminada con ese id para regresar", esError = true),
            avisoDeFallo(KdsHttpException(404, null, "No hay una comanda terminada con ese id para regresar"), AccionDeCocina.DESHACER),
        )
        assertEquals(AvisoDeCocina(AccionDeCocina.DESHACER.generico, esError = true), avisoDeFallo(IllegalStateException(), AccionDeCocina.DESHACER))
    }

    // MARK: - Etapa 3 del KDS (3.5, D9): la mezcla por folio

    private fun delServidor(id: String, folio: String?, creada: Long) = KDSOrder(
        id = id, orderId = "o-$id", orderNumber = id, orderType = "En tienda",
        items = listOf(KDSOrderItem("i-$id", "Taco", 1)), createdAt = creada, status = KDSOrderStatus.NEW,
        sourceKey = folio, printStationId = "st-barra",
    )

    private fun local(folio: String, recibida: Long, lista: Long? = null) = KdsTicketLocal(
        sourceKey = folio, venueId = "v1", stationId = "st-barra", orderNumber = "77", orderType = "Mesa 8 · Aperitivos",
        items = listOf(KDSOrderItem("l-1", "Café", 2)), recibidaEnMillis = recibida, listaEnMillis = lista,
    )

    @Test
    fun `P1 el servidor gana por folio y lo marcado sin red no resucita`() {
        val servidor = listOf(delServidor("k1", "sale:a:st-barra", 1_000), delServidor("k2", "sale:b:st-barra", 2_000))
        val locales = listOf(
            local("sale:a:st-barra", recibida = 900), // ya la mandó el servidor: gana su copia (k1), se descarta la local
            local("sale:b:st-barra", recibida = 1_900, lista = 1_950), // LISTO sin red: NO se muestra aunque el servidor la devuelva
            local("round:c:st-barra", recibida = 3_000), // sólo local: se muestra con id sintético
        )
        val juntas = juntarPorFolio(servidor, locales)
        assertEquals(listOf("k1", "lan:round:c:st-barra"), juntas.map { it.id })
        val soloLocal = juntas.last()
        assertEquals("round:c:st-barra", soloLocal.sourceKey)
        assertEquals("Mesa 8 · Aperitivos", soloLocal.orderType)
        assertEquals("st-barra", soloLocal.printStationId)
        assertNull(soloLocal.orderId)
        assertTrue(esLocal(soloLocal.id))
        assertFalse(esLocal("k1"))
    }

    @Test
    fun `sin copia del servidor (sin red) se muestra lo guardado, en orden de llegada, y lo de Uber sin folio no se toca`() {
        val uber = delServidor("u1", null, 500)
        val juntas = juntarPorFolio(listOf(uber), listOf(local("round:c:st-barra", 3_000), local("sale:d:st-barra", 1_000)))
        assertEquals(listOf("u1", "lan:sale:d:st-barra", "lan:round:c:st-barra"), juntas.map { it.id })
        assertEquals(emptyList<KDSOrder>(), juntarPorFolio(emptyList(), listOf(local("x", 1, lista = 2))))
    }

    @Test
    fun `el texto de la pantalla recibiendo por WiFi es el acordado`() {
        assertEquals("Sin internet: recibiendo por el WiFi del local", TextosDeCocina.SIN_INTERNET_CON_WIFI)
    }

    // MARK: - KDS 3.6: los tiempos de una mesa

    private fun platillo(nombre: String, tiempo: String?) = KDSOrderItem(id = nombre, productName = nombre, quantity = 1, course = tiempo)

    @Test
    fun `sin tiempos la tarjeta se ve como siempre - un grupo sin encabezado`() {
        val items = listOf(platillo("Café", null), platillo("Pan", null))
        assertEquals(listOf(GrupoDeTiempo(null, items)), gruposPorTiempo(items))
    }

    @Test
    fun `con tiempos agrupa en el orden en que aparece cada uno, y lo que no trae va en Inmediato`() {
        val guac = platillo("Guacamole", "Aperitivos")
        val tacos = platillo("Tacos", "Principales")
        val sopa = platillo("Sopa", "Aperitivos")
        val agua = platillo("Agua", null)

        val grupos = gruposPorTiempo(listOf(guac, tacos, sopa, agua))

        assertEquals(listOf("Aperitivos", "Principales", "Inmediato"), grupos.map { it.tiempo })
        assertEquals(listOf(guac, sopa), grupos[0].items)
        assertEquals(listOf(agua), grupos[2].items)
    }

    @Test
    fun `una tarjeta vacia no truena`() {
        assertEquals(listOf(GrupoDeTiempo(null, emptyList())), gruposPorTiempo(emptyList()))
    }
}
