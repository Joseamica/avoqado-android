package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Almacén en memoria — la costura que permite probar esto sin Robolectric. */
private class AlmacenFalso(var texto: String? = null) : AlmacenDeTexto {
    var borrados = 0
    override fun leer(): String? = texto
    override fun escribir(texto: String) { this.texto = texto }
    override fun borrar() { texto = null; borrados++ }
}

/**
 * P1 #7 de la auditoría de Codex (2026-09-07): el reintento vivía SÓLO en memoria, así que una
 * app que muere a media espera perdía la comanda sin dejar rastro — y la recuperación manual
 * (abrir el pedido en Pedidos) depende de un GET al servidor, justo lo que no hay sin internet.
 */
class ComandasPendientesStoreTest {

    private val plan = TicketPlan(
        stationId = "st_barra",
        unrouted = false,
        lines = listOf(ConsolidatedLine("Cerveza", 1, emptyList(), null, listOf("oi_1"))),
    )
    private val config = PrintConfig(
        printers = listOf(PrinterInfo("pr_barra", "Barra", "NETWORK", "192.168.1.60:9100")),
        stations = listOf(StationInfo(id = "st_barra", name = "Barra", printerId = "pr_barra", copies = 1)),
    )
    private val fallo = EstadoDeComanda.NoSalio(
        estaciones = listOf("Barra"),
        causa = "sin papel",
        orderNumber = "ORD-42",
        trabajo = TrabajoPendiente(
            planes = listOf(plan),
            config = config,
            orderNumber = "ORD-42",
            orderType = "En tienda",
            serverName = "Ana",
            comboNames = emptyMap(),
            venueId = "venue-1",
            orderId = "order-1",
        ),
    )

    private val ahora = 1_700_000_000_000L

    @Test
    fun `P1 el aviso vuelve COMPLETO tras morir la app, con su trabajo reenviable`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)

        store.guardar(fallo, ahora)
        // Un arranque nuevo: otra instancia, mismo almacén.
        val recuperado = ComandasPendientesStore(almacen).leer("venue-1", ahora + 60_000)

        assertEquals(listOf("Barra"), recuperado?.estaciones)
        assertEquals("sin papel", recuperado?.causa)
        assertEquals("ORD-42", recuperado?.orderNumber)
        // 🔴 Lo que de verdad importa: sin el trabajo, el aviso vuelve sin botón y el cajero
        // no puede hacer nada con él.
        assertEquals(listOf(plan), recuperado?.trabajo?.planes)
        assertEquals("Ana", recuperado?.trabajo?.serverName)
        assertEquals("order-1", recuperado?.trabajo?.orderId)
    }

    /**
     * Una comanda de ayer ya se resolvió de alguna forma —la cocina la preparó, alguien la
     * cantó, el cliente se fue—. Ofrecer reimprimirla invita a mandar comida que nadie pidió.
     */
    @Test
    fun `P1 un aviso viejo NO vuelve, y se limpia solo`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.guardar(fallo, ahora)

        val nueveHorasDespues = ahora + 9L * 60 * 60 * 1000
        assertNull(store.leer("venue-1", nueveHorasDespues))
        assertNull("quedó basura vencida en el aparato", almacen.texto)
    }

    @Test
    fun `un aviso de hace siete horas SI vuelve — sigue siendo del mismo turno`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.guardar(fallo, ahora)

        assertEquals("ORD-42", store.leer("venue-1", ahora + 7L * 60 * 60 * 1000)?.orderNumber)
    }

    /**
     * Un formato viejo o corrupto no puede dejar la app arrastrándolo para siempre: se descarta
     * y se sigue. Lo peor que puede pasar es perder UN aviso; atorarse los perdería todos.
     */
    @Test
    fun `basura en el almacen se descarta sin reventar`() {
        val almacen = AlmacenFalso(texto = "{esto no es json")
        val store = ComandasPendientesStore(almacen)

        assertNull(store.leer("venue-1", ahora))
        assertNull(almacen.texto)
    }

    @Test
    fun `un reloj que se fue hacia atras tampoco resucita un aviso`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.guardar(fallo, ahora)

        assertNull("un aviso 'del futuro' no es de fiar", store.leer("venue-1", ahora - 60_000))
    }

    @Test
    fun `limpiar lo borra del aparato`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.guardar(fallo, ahora)

        store.limpiar()

        assertNull(almacen.texto)
        assertNull(store.leer("venue-1", ahora))
    }

    /**
     * P1 #5 de la 2ª auditoría de Codex (2026-09-07). La app cambia de sucursal y de sesión sin
     * reinstalarse. Un aviso guardado en A reaparecía en B — y si las dos redes usan la misma IP
     * privada (lo normal en un 192.168.1.x), el papel podía salir FÍSICAMENTE en el local
     * equivocado.
     */
    @Test
    fun `P1 un aviso de OTRA sucursal no vuelve, y se limpia`() {
        val almacen = AlmacenFalso()
        ComandasPendientesStore(almacen).guardar(fallo, ahora)

        assertNull(
            "volvió un aviso de otra sucursal: se puede imprimir en el local equivocado",
            ComandasPendientesStore(almacen).leer("venue-OTRO", ahora),
        )
        assertNull(almacen.texto)
    }

    @Test
    fun `P1 sin sucursal conocida tampoco se arriesga`() {
        val almacen = AlmacenFalso()
        ComandasPendientesStore(almacen).guardar(fallo, ahora)

        assertNull(ComandasPendientesStore(almacen).leer(null, ahora))
    }
}
