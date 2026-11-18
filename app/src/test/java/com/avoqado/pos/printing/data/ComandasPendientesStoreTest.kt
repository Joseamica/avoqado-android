package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

/** Almacén en memoria — la costura que permite probar esto sin Robolectric. */
private class AlmacenFalso(var texto: String? = null) : AlmacenDeTexto {
    var borrados = 0
    override fun leer(): String? = texto
    override fun escribir(texto: String): Boolean { this.texto = texto; return true }
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
        // 🔴 SUSPENDE, no borra: al volver a su sucursal la comanda pendiente sigue ahí.
        // Borrarla haría desaparecer un pedido real sólo porque alguien cambió de sucursal.
        assertNotNull("se BORRÓ un pendiente real al cambiar de sucursal", almacen.texto)
        assertEquals("ORD-42", ComandasPendientesStore(almacen).leer("venue-1", ahora)?.orderNumber)
    }

    @Test
    fun `P1 sin sucursal conocida tampoco se arriesga`() {
        val almacen = AlmacenFalso()
        ComandasPendientesStore(almacen).guardar(fallo, ahora)

        assertNull(ComandasPendientesStore(almacen).leer(null, ahora))
    }

    // MARK: - Revisión final de la 3.4 (I-1, m-8): la guarda vive en el almacén, no en cada llamador

    private val deOtraSucursal = fallo.copy(
        orderNumber = "ORD-9",
        trabajo = fallo.trabajo!!.copy(venueId = "venue-2", orderNumber = "ORD-9"),
    )

    /**
     * I-1: una ronda cuyo respaldo se saltó termina en `NoSalio(trabajo = null)`. Guardarlo pisaba el pendiente
     * recuperable del mostrador —su reintento y su «Volver a imprimir»— con algo que nadie puede reimprimir.
     */
    @Test
    fun `P1 guardar SIN trabajo no pisa un pendiente recuperable`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.cargar("venue-1", ahora)
        store.guardar(fallo, ahora)

        val sinTrabajo = EstadoDeComanda.NoSalio(listOf("Barra"), null, "ORD-43", trabajo = null)

        assertFalse("se guardó un aviso sin nada que reimprimir", store.guardar(sinTrabajo, ahora))
        assertEquals(fallo, store.pendiente.value)
        assertEquals("ORD-42", ComandasPendientesStore(almacen).leer("venue-1", ahora)?.orderNumber)
    }

    /**
     * m-8: el reloj de reintento y Cobrar leen lo PUBLICADO; un trabajo de otra sucursal imprimiría en el local equivocado,
     * así que no se publica. 🔴 D1 (Codex r3 #3): pero tampoco se tira — antes, con la ranura única, se rechazaba entero y
     * la comanda de la sucursal anterior que seguía insistiendo al cambiar de sucursal se perdía. Ahora queda SUSPENDIDA y
     * vuelve al regresar a su sucursal.
     */
    @Test
    fun `P1 un trabajo de OTRA sucursal no se publica pero queda suspendido para su sucursal`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.cargar("venue-1", ahora)

        assertTrue(store.guardar(deOtraSucursal, ahora))

        assertNull("se publicó un trabajo de otra sucursal", store.pendiente.value)
        assertTrue(store.vigentes(ahora).isEmpty())
        store.cargar("venue-2", ahora)
        assertEquals(deOtraSucursal, store.pendiente.value)
        assertEquals("ORD-9", ComandasPendientesStore(almacen).leer("venue-2", ahora)?.orderNumber)
    }

    /** Un cambio de sucursal vuelve a llamar `cargar` (AppState.refreshTabs → startOfflineOutbox): la guarda sigue al venue nuevo. */
    @Test
    fun `tras cambiar de sucursal los trabajos del venue nuevo se guardan`() {
        val store = ComandasPendientesStore(AlmacenFalso())
        store.cargar("venue-1", ahora)
        store.cargar("venue-2", ahora)

        assertTrue(store.guardar(deOtraSucursal, ahora))
        assertEquals(deOtraSucursal, store.pendiente.value)
    }

    @Test
    fun `P1 un trabajo de OTRA sucursal no pisa el pendiente de la sucursal actual`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen)
        store.cargar("venue-1", ahora)
        store.guardar(fallo, ahora)

        store.guardar(deOtraSucursal, ahora)

        assertEquals(fallo, store.pendiente.value)
        assertEquals(listOf(fallo), store.pendientes.value)
        assertEquals("ORD-42", ComandasPendientesStore(almacen).leer("venue-1", ahora)?.orderNumber)
    }

    @Test
    fun `el caso normal sigue igual - y sin sucursal cargada se guarda como antes`() {
        val cargado = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        assertTrue(cargado.guardar(fallo, ahora))
        assertEquals(fallo, cargado.pendiente.value)

        val sinCargar = ComandasPendientesStore(AlmacenFalso())
        assertTrue(sinCargar.guardar(deOtraSucursal, ahora))
        assertEquals(deOtraSucursal, sinCargar.pendiente.value)
    }

    // MARK: - Etapa 3 del KDS (3.5), ronda 3 de la Task 6 (I2 de la revisión de la Task 7)

    /**
     * «Ya la canté»: una PERSONA resolvió la comanda. Además de soltar la libreta, se cierran las entregas por WiFi de ESE
     * papel de respaldo (venue + orden + plan exacto): si no, el reloj del replay la imprimiría sola en el siguiente tic o
     * en la próxima apertura — el duplicado que esta libreta existe para evitar.
     */
    @Test
    fun `P1 ya la cante suelta la libreta y cierra las entregas del respaldo de ESA comanda`() = runBlocking {
        val entrega = mockk<EntregaPorWifi>(relaxed = true)
        val planCocina = TicketPlan("st_cocina", false, listOf(ConsolidatedLine("Taco", 1, emptyList(), null, listOf("oi_9"))))
        val conRespaldo = fallo.copy(
            trabajo = fallo.trabajo!!.copy(
                planes = listOf(plan, planCocina),
                config = KitchenDeliveryPolicy.conRespaldo(config, listOf("st_barra")),
            ),
        )
        val store = ComandasPendientesStore(AlmacenFalso(), entrega)
        store.cargar("venue-1", ahora)
        assertTrue(store.guardar(conRespaldo, ahora))

        store.yaLaCante()?.join()

        assertNull(store.pendiente.value)
        // Sólo el plan de la estación de RESPALDO: las demás nunca tuvieron fila.
        coVerify(exactly = 1) { entrega.cerrarPorPapel("venue-1", "ORD-42", listOf(plan)) }
    }

    /**
     * nab17320 (nube, 17-nov): mientras el cierre de «Ya la canté» borra sus filas (otro hilo), la comanda ya no está en la
     * libreta pero su fila sí: [ComandasPendientesStore.seEstaCerrando] lo dice para que el replay no la imprima. Al terminar
     * el borrado deja de decirlo; un plan que no se cantó (otra estación) nunca lo dice.
     */
    @Test
    fun `P1 ya la cante dice que se esta cerrando hasta que el borrado de sus filas termina`() = runBlocking {
        val entrega = mockk<EntregaPorWifi>(relaxed = true)
        val compuerta = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { entrega.cerrarPorPapel(any(), any(), any()) } coAnswers { compuerta.await(); true }
        val conRespaldo = fallo.copy(
            trabajo = fallo.trabajo!!.copy(config = KitchenDeliveryPolicy.conRespaldo(config, listOf("st_barra"))),
        )
        val store = ComandasPendientesStore(AlmacenFalso(), entrega)
        store.cargar("venue-1", ahora)
        assertTrue(store.guardar(conRespaldo, ahora))
        val delReplay = conRespaldo.trabajo!!.copy(planes = listOf(plan))
        val otraEstacion = delReplay.copy(planes = listOf(TicketPlan("st_cocina", false, listOf(ConsolidatedLine("Taco", 1, emptyList(), null, listOf("oi_9"))))))

        val cierre = store.yaLaCante()!!

        assertNull(store.pendiente.value)
        assertTrue(store.seEstaCerrando(delReplay))
        assertFalse(store.seEstaCerrando(otraEstacion))
        compuerta.complete(Unit); cierre.join()
        assertFalse(store.seEstaCerrando(delReplay))
    }

    /**
     * 🔴 `limpiar()` NO cierra entregas: también lo llama un `Salio` de OTRA venta (y el reloj de la libreta). Cerrar ahí
     * borraría la fila que recupera la comanda de una ronda de mesa en la próxima apertura.
     */
    @Test
    fun `limpiar no cierra entregas`() {
        val entrega = mockk<EntregaPorWifi>(relaxed = true)
        val store = ComandasPendientesStore(AlmacenFalso(), entrega)
        store.cargar("venue-1", ahora)
        store.guardar(fallo.copy(trabajo = fallo.trabajo!!.copy(config = KitchenDeliveryPolicy.conRespaldo(config, listOf("st_barra")))), ahora)

        store.limpiar()

        coVerify(exactly = 0) { entrega.cerrarPorPapel(any(), any(), any()) }
    }

    // MARK: - D1 (hz:033d44ca:D1, 16-nov-2026): la libreta guarda TODAS las comandas que no salieron, no sólo la última

    /** Tres ventas cuya comanda no salió, con la cocina apagada en hora pico. Mismo venue; cada una con su trabajo. */
    private fun venta(n: Int) = fallo.copy(
        orderNumber = "ORD-$n",
        trabajo = fallo.trabajo!!.copy(orderNumber = "ORD-$n", orderId = "order-$n"),
    )

    /**
     * P1 El caso de la tarjeta: la impresora de cocina se apaga, fallan tres comandas seguidas y sólo se guardaba la
     * ÚLTIMA (una sola ranura en disco): las otras dos perdían su reintento y su «Volver a imprimir».
     */
    @Test
    fun `P1 tres comandas que no salen seguidas quedan las tres, en el orden en que fallaron`() {
        val store = ComandasPendientesStore(AlmacenFalso())
        store.cargar("venue-1", ahora)

        assertTrue(store.guardar(venta(1), ahora))
        assertTrue(store.guardar(venta(2), ahora + 60_000))
        assertTrue(store.guardar(venta(3), ahora + 120_000))

        assertEquals("se pisaron comandas", listOf("ORD-1", "ORD-2", "ORD-3"), store.pendientes.value.map { it.orderNumber })
        // El aviso que se ve sigue siendo el más reciente, como antes.
        assertEquals("ORD-3", store.pendiente.value?.orderNumber)
    }

    /** P1 Las tres sobreviven a que la app muera: al abrir vuelven las tres, cada una con su trabajo reenviable. */
    @Test
    fun `P1 tras reiniciar la app vuelven las tres comandas pendientes`() {
        val almacen = AlmacenFalso()
        ComandasPendientesStore(almacen).apply {
            cargar("venue-1", ahora)
            guardar(venta(1), ahora)
            guardar(venta(2), ahora + 60_000)
            guardar(venta(3), ahora + 120_000)
        }

        val tras = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora + 180_000) }

        assertEquals(listOf("ORD-1", "ORD-2", "ORD-3"), tras.pendientes.value.map { it.orderNumber })
        assertEquals(listOf("order-1", "order-2", "order-3"), tras.pendientes.value.map { it.trabajo?.orderId })
        assertEquals("ORD-3", tras.pendiente.value?.orderNumber)
    }

    /** Un aviso guardado por la versión anterior (una sola hoja, sin lista) se sigue leyendo al actualizar la app. */
    @Test
    fun `lo que guardo la version anterior de una sola hoja se sigue leyendo`() {
        val almacen = AlmacenFalso()
        ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora); guardar(fallo, ahora) }
        // El formato viejo era el objeto suelto: la misma entrada, sin los corchetes de la lista.
        almacen.texto = almacen.texto!!.trim().removePrefix("[").removeSuffix("]")

        val tras = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora + 60_000) }

        assertEquals(listOf("ORD-42"), tras.pendientes.value.map { it.orderNumber })
        assertEquals(listOf(plan), tras.pendiente.value?.trabajo?.planes)
    }

    /** El MISMO trabajo guardado dos veces no se duplica (saldría dos veces) y conserva la hora de su primer fallo. */
    @Test
    fun `el mismo trabajo guardado dos veces no se duplica ni renueva su vigencia`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        store.guardar(venta(2), ahora)
        store.guardar(venta(1), ahora + 7L * 60 * 60 * 1000)

        assertEquals(listOf("ORD-1", "ORD-2"), store.pendientes.value.map { it.orderNumber })
        val ochoHorasYUnMinuto = ahora + 8L * 60 * 60 * 1000 + 60_000
        assertEquals("volver a guardarla renovó su vigencia", emptyList<String>(), store.vigentes(ochoHorasYUnMinuto).map { it.orderNumber })
    }

    /** P1 Soltar la que salió no borra las demás, ni en memoria ni en disco. */
    @Test
    fun `P1 soltar una comanda no borra las otras`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        store.guardar(venta(2), ahora)
        store.guardar(venta(3), ahora)

        assertTrue(store.soltarSi(venta(2).trabajo!!))

        assertEquals(listOf("ORD-1", "ORD-3"), store.pendientes.value.map { it.orderNumber })
        assertEquals(
            listOf("ORD-1", "ORD-3"),
            ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }.pendientes.value.map { it.orderNumber },
        )
    }

    /** «Ya la canté» resuelve la que se VE (la más reciente); la anterior aparece en su lugar, con su botón. */
    @Test
    fun `ya la cante resuelve la que se ve y aparece la anterior`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        store.guardar(venta(2), ahora)

        store.yaLaCante()

        assertEquals("ORD-1", store.pendiente.value?.orderNumber)
        assertEquals(
            listOf("ORD-1"),
            ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }.pendientes.value.map { it.orderNumber },
        )
    }

    /** Una comanda que vence (8 h) se va sola; las que siguen vigentes se quedan. */
    @Test
    fun `vence una y las demas siguen`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        store.guardar(venta(2), ahora + 5L * 60 * 60 * 1000)
        val nueveHoras = ahora + 9L * 60 * 60 * 1000

        assertEquals(listOf("ORD-2"), store.vigentes(nueveHoras).map { it.orderNumber })
        assertEquals(listOf("ORD-2"), store.pendientes.value.map { it.orderNumber })
        assertEquals(
            "la vencida siguió en el aparato",
            listOf("ORD-2"),
            ComandasPendientesStore(almacen).apply { cargar("venue-1", nueveHoras) }.pendientes.value.map { it.orderNumber },
        )
    }

    /**
     * P1 Lo pendiente de OTRA sucursal se SUSPENDE, no se borra: guardar en la sucursal actual no lo pisa. Con la ranura
     * única, la comanda de la sucursal A desaparecía en cuanto fallaba una en la B.
     */
    @Test
    fun `P1 guardar en la sucursal actual no pisa lo suspendido de otra sucursal`() {
        val almacen = AlmacenFalso()
        ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora); guardar(fallo, ahora) }
        val enB = ComandasPendientesStore(almacen).apply { cargar("venue-2", ahora) }
        assertTrue(enB.pendientes.value.isEmpty())
        assertTrue(enB.guardar(deOtraSucursal, ahora))

        val deVueltaEnA = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }

        assertEquals(listOf("ORD-42"), deVueltaEnA.pendientes.value.map { it.orderNumber })
    }

    // MARK: - Codex D1 r1

    /** P1 (Codex D1 r1 #1) Sin tope: 250 comandas vigentes sobreviven TODAS a reiniciar (un tope tiraba la primera). */
    @Test
    fun `P1 muchas comandas pendientes sobreviven todas a reiniciar`() {
        val almacen = AlmacenFalso()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        (1..250).forEach { assertTrue(store.guardar(venta(it), ahora)) }

        val tras = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora + 60_000) }

        assertEquals(250, tras.pendientes.value.size)
        assertEquals("ORD-1", tras.pendientes.value.first().orderNumber)
    }

    /** Un almacén cuyo disco se puede «llenar» (no acepta escribir ni borrar) y volver. */
    private class DiscoQueFalla : AlmacenDeTexto {
        var texto: String? = null
        var fallar = false
        override fun leer(): String? = texto
        override fun escribir(texto: String): Boolean { if (fallar) return false; this.texto = texto; return true }
        override fun borrar() { if (fallar) error("disco lleno"); texto = null }
    }

    /**
     * P1 (Codex D1 r1 #2 y r2 #1, #2) El disco no acepta la escritura al quitar una comanda resuelta: la otra NO se pierde
     * (sigue en memoria con su reintento) y, en cuanto el disco responde, el reloj copia la lista buena: tras reiniciar no
     * vuelve la resuelta ni falta la pendiente.
     */
    @Test
    fun `P1 con el disco sin responder al quitar una no se pierde la otra y la copia se repone sola`() {
        val almacen = DiscoQueFalla()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        store.guardar(venta(2), ahora)
        almacen.fallar = true

        store.yaLaCante()

        assertEquals("ORD-1 se perdió de memoria", listOf("ORD-1"), store.pendientes.value.map { it.orderNumber })
        almacen.fallar = false
        store.vigentes(ahora + 60_000) // el tic del reloj
        val tras = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora + 120_000) }
        assertEquals(listOf("ORD-1"), tras.pendientes.value.map { it.orderNumber })
    }

    /**
     * P1 (Codex D1 r2 #4) Si el disco no acepta GUARDAR una comanda nueva, igual queda en memoria —con su aviso y su
     * reintento— y se copia al disco en cuanto responde. Antes se perdía en el acto.
     */
    @Test
    fun `P1 con el disco sin responder al guardar la comanda queda en memoria y se copia despues`() {
        val almacen = DiscoQueFalla()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        almacen.fallar = true

        assertFalse("dijo que quedó en el disco", store.guardar(venta(1), ahora))
        assertEquals(listOf("ORD-1"), store.pendientes.value.map { it.orderNumber })
        assertEquals(listOf("ORD-1"), store.vigentes(ahora + 60_000).map { it.orderNumber })

        almacen.fallar = false
        store.vigentes(ahora + 120_000)
        assertEquals(
            listOf("ORD-1"),
            ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora + 180_000) }.pendientes.value.map { it.orderNumber },
        )
    }

    /**
     * P1 (Codex D1 r3 #1) En Android `borrar` no avisa si falló. Al soltar la ÚLTIMA comanda con el disco sin responder,
     * el reloj tiene que enterarse y reponer la copia vacía cuando el disco vuelve: si no, tras reiniciar regresaba la
     * comanda ya cantada.
     */
    @Test
    fun `P1 soltar la ultima con el disco sin responder se repone y no revive al reiniciar`() {
        val almacen = object : AlmacenDeTexto {
            var texto: String? = null
            var fallar = false
            override fun leer(): String? = texto
            override fun escribir(texto: String): Boolean { if (fallar) return false; this.texto = texto; return true }
            override fun borrar() { if (!fallar) texto = null } // como `PrefsComoAlmacen`: falla callado
        }
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        almacen.fallar = true

        store.yaLaCante()
        almacen.fallar = false
        store.vigentes(ahora + 60_000) // el tic del reloj

        assertTrue(
            "volvió la comanda ya cantada",
            ComandasPendientesStore(almacen).apply { cargar("venue-1", ahora + 120_000) }.pendientes.value.isEmpty(),
        )
    }

    /**
     * P1 (Codex D1 r4 #1) «Ya la canté» tacha la comanda que la persona VIO. Si entre verla y tocar llegó otra, antes se
     * tachaba la nueva (sin haber salido) y la cantada seguía viva para el reloj.
     */
    @Test
    fun `P1 ya la cante tacha la que se vio aunque haya llegado otra`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)
        val vista = store.pendiente.value
        store.guardar(venta(2), ahora) // llega mientras el cajero toca

        store.yaLaCante(vista)

        assertEquals(listOf("ORD-2"), store.pendientes.value.map { it.orderNumber })
    }

    /**
     * P1 (Codex D1 r6 #1) La pantalla pintó A (Cocina + Barra); un reintento parcial la dejó en A′ (sólo Barra) antes del
     * toque. «Ya la canté» sobre A resuelve A′: es la MISMA comanda. Antes se quedaba viva y el reloj imprimía Barra.
     */
    @Test
    fun `P1 ya la cante reconoce la comanda aunque un reintento parcial la haya cambiado`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        val a = venta(1)
        store.guardar(a, ahora)
        store.guardar(venta(2), ahora)
        val pintadoA = store.pendientes.value.first() // lo que la pantalla pintó
        val aPrima = a.copy(causa = "sin papel otra vez", trabajo = a.trabajo!!.copy(copiasPendientes = mapOf("st_barra" to 1)))
        assertTrue(store.reemplazar(a.trabajo!!, aPrima, ahora))

        assertTrue(store.contiene(pintadoA))
        assertTrue(store.mismaComanda(pintadoA, store.pendientes.value.first()))
        assertFalse(store.mismaComanda(pintadoA, store.pendientes.value.last()))
        store.yaLaCante(pintadoA)

        assertEquals(listOf("ORD-2"), store.pendientes.value.map { it.orderNumber })
    }

    /**
     * P2 (Codex D1 r7 #2) La identidad es el aviso PUBLICADO (el mismo objeto), no uno igual: una copia con el mismo
     * contenido no tacha nada. Y dos cursos que quedan sin nada reenviable con avisos idénticos se juntan en uno.
     */
    @Test
    fun `la identidad es el aviso publicado y dos avisos identicos sin trabajo se juntan`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        val cursoA = venta(1)
        val cursoB = venta(1).copy(trabajo = venta(1).trabajo!!.copy(orderType = "En tienda · Postre"))
        store.guardar(cursoA, ahora)
        store.guardar(cursoB, ahora)
        assertFalse("una copia igual no es la comanda publicada", store.contiene(store.pendientes.value.first().copy()))
        assertTrue(store.contiene(store.pendientes.value.first()))

        val sinTrabajo = EstadoDeComanda.NoSalio(listOf("Barra"), ReintentoDeComanda.CAUSA_SIN_IMPRESORA, "ORD-1", trabajo = null)
        store.reemplazar(cursoA.trabajo!!, sinTrabajo, ahora)
        store.reemplazar(cursoB.trabajo!!, sinTrabajo.copy(), ahora)

        assertEquals("dos avisos idénticos sin nada que reimprimir", 1, store.pendientes.value.size)
        store.yaLaCante(store.pendiente.value)
        assertTrue(store.pendientes.value.isEmpty())
    }

    /** Un reintento que falla igual (aviso nuevo, mismo contenido) no cambia la comanda: la que se pintó sigue siendo ella. */
    @Test
    fun `un reintento que falla igual conserva el aviso publicado`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        val a = venta(1)
        store.guardar(a, ahora)
        val pintado = store.pendiente.value

        store.reemplazar(a.trabajo!!, a.copy(), ahora)

        assertTrue("cambió el aviso publicado", store.pendiente.value === pintado)
        store.yaLaCante(pintado)
        assertTrue(store.pendientes.value.isEmpty())
    }

    /**
     * P1 (Codex D1 r8 #1) Guardar otra vez el MISMO trabajo (un aviso nuevo con el mismo contenido) no le cambia la llave:
     * «Ya la canté» sobre lo que se pintó antes sigue tachándola.
     */
    @Test
    fun `P1 guardar otra vez el mismo trabajo no le cambia la llave`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        val a = venta(1)
        store.guardar(a, ahora)
        val pintado = store.pendiente.value!!
        store.guardar(a.copy(), ahora + 1)

        store.yaLaCante(pintado)

        assertTrue("la comanda cantada siguió viva", store.pendientes.value.isEmpty())
    }

    /** P2 (Codex D1 r8 #2) Dos avisos idénticos sin trabajo de sucursales DISTINTAS no se juntan. */
    @Test
    fun `P2 avisos identicos de sucursales distintas no se juntan`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        val enA = venta(1)
        val enB = venta(1).copy(trabajo = venta(1).trabajo!!.copy(venueId = "venue-2"))
        store.guardar(enA, ahora)
        store.guardar(enB, ahora) // llega tarde de B: queda suspendida
        val sinTrabajo = EstadoDeComanda.NoSalio(listOf("Barra"), ReintentoDeComanda.CAUSA_SIN_IMPRESORA, "ORD-1", trabajo = null)
        store.reemplazar(enA.trabajo!!, sinTrabajo, ahora)
        store.reemplazar(enB.trabajo!!, sinTrabajo.copy(), ahora)

        assertEquals(1, store.pendientes.value.size)
        store.cargar("venue-2", ahora)
        assertEquals("se perdió el aviso de la otra sucursal", 1, store.pendientes.value.size)
    }

    /** `sigueVigente`: presente, no vencida y de la sucursal cargada; una vencida se suelta. */
    @Test
    fun `sigue vigente dice si se puede mandar ya`() {
        val store = ComandasPendientesStore(AlmacenFalso()).apply { cargar("venue-1", ahora) }
        store.guardar(venta(1), ahora)

        assertTrue(store.sigueVigente(venta(1).trabajo!!, ahora + 60_000))
        assertFalse("una que no está", store.sigueVigente(venta(2).trabajo!!, ahora))
        assertFalse("vencida", store.sigueVigente(venta(1).trabajo!!, ahora + 9L * 60 * 60 * 1000))
        assertTrue("la vencida no se soltó", store.pendientes.value.isEmpty())
    }
}
