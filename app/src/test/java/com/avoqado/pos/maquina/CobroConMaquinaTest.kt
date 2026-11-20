package com.avoqado.pos.maquina

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * El cobro en la QuickPay (C3): la máquina de estados contra un canal falso (generación y conexión a mano), el reloj
 * virtual de `runTest` y el DAO sobre SQLite de verdad ([CobroEnMaquinaSobreSqlite]). Una prueba por regla del plan
 * (`2026-11-20-maquina-de-efectivo-fase-1a.md`, C3 con las enmiendas R3/R4/R5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroConMaquinaTest {

    private class CanalFalso : CanalDeLaMaquina {
        override val conexion = MutableStateFlow(Conexion.CONECTADA)
        override val generacion = MutableStateFlow(1)
        override val recibidos = MutableSharedFlow<Recibido>(extraBufferCapacity = 64)
        val enviados = mutableListOf<Pair<Int, String>>()
        var aceptaEnviar = true
        var alEnviar: (String) -> Unit = {}

        override fun enviar(json: String, generacion: Int): Boolean {
            if (!aceptaEnviar || conexion.value != Conexion.CONECTADA || generacion != this.generacion.value) return false
            alEnviar(json)
            enviados += generacion to json
            return true
        }

        fun llega(json: String, gen: Int = generacion.value) {
            check(recibidos.tryEmit(Recibido(gen, leerMensaje(json)!!, json)))
        }

        fun caer() { conexion.value = Conexion.DESCONECTADA }

        fun reconectar() {
            conexion.value = Conexion.DESCONECTADA
            generacion.value += 1
            conexion.value = Conexion.CONECTADA
        }

        fun comandos(): List<String> = enviados.map { JSONObject(it.second).getString("comando") }
        fun cancels(): List<Pair<Int, String>> = enviados.filter { JSONObject(it.second).getString("comando") == "cancel" }
            .map { it.first to JSONObject(it.second).getString("cancelvalue") }
    }

    private val canal = CanalFalso()
    private val dao = CobroEnMaquinaSobreSqlite()

    @After fun cerrar() = dao.close()

    private val venta = VentaParaLaMaquina(
        venueId = "venue-1", llaveDeVenta = "venta-1", pedidoJson = """{"items":[{"productId":"p1","quantity":1}]}""",
        saleCents = 18_000, tipCents = 750, saleShiftId = "turno-1", quoteId = "cot-1",
    )
    private val due = 18_750

    /** Cada instancia con su semilla: dos con la misma semilla y la misma hora sacarían el mismo folio. */
    private fun TestScope.nuevoCobro(semilla: Long = 7) =
        CobroConMaquina(canal, dao, { testScheduler.currentTime }, backgroundScope, Random(semilla))

    private fun status(
        ref: String?, due: Int = this.due, credit: Int = 0, change: Int = 0, cobrando: Boolean = true, estado: String? = null,
    ): String = JSONObject().put("comando", "status").put("cobrando", cobrando).put("Status", estado ?: JSONObject.NULL)
        .put("referenceCC", ref ?: JSONObject.NULL).put("Credit", credit / 100.0).put("Due", due / 100.0)
        .put("changeDispensed", change / 100.0).put("changeBar", "022220222000").toString()

    private fun pagoCompletado(ref: String, credit: Int, change: Int, due: Int = this.due) =
        status(ref, due, credit, change, cobrando = false, estado = "Pago Completado")

    private fun cancelada(ref: String?, credit: Int, change: Int, due: Int = this.due) =
        status(ref, due, credit, change, cobrando = false, estado = "Transacción Cancelada")

    private fun credito(cents: Int) = """{"comando":"updateCredit","Credit":"$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"}"""

    private val pong = """{"comando":"pong","conexiones":2,"tarjeta":false}"""

    private fun socketsLibre() = """{"comando":"sockets","cobrando":false,"Due":0,"Credit":0,"changeBar":"022220222000"}"""

    /** Inicia el cobro y la máquina lo acepta con nuestro folio y Due en la generación 1: el cobro es nuestro. */
    private suspend fun TestScope.cobroPropio(cobro: CobroConMaquina): String {
        val ref = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC
        canal.llega(status(ref))
        runCurrent()
        assertEquals(EstadoDelCobro.Cobrando(ref, due, 0, propio = true), cobro.estado.value)
        return ref
    }

    // MARK: - Inicio

    @Test
    fun `P1 el EN_CURSO se guarda ANTES de mandar el paycash, con la venta congelada`() = runTest {
        val cobro = nuevoCobro()
        var enDiscoAlMandar: CobroEnMaquinaEntity? = null
        canal.alEnviar = { json -> enDiscoAlMandar = dao.fila(JSONObject(json).getString("referenceCC")) }

        val ref = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC

        val fila = enDiscoAlMandar ?: error("el paycash salió sin la fila en disco")
        assertEquals(EstadoDelIntento.EN_CURSO, fila.estado)
        assertEquals(ref, fila.referenceCC)
        assertEquals(venta.pedidoJson, fila.pedidoJson)
        assertEquals(listOf(18_000, 750, 18_750), listOf(fila.saleCents, fila.tipCents, fila.dueCents))
        assertEquals("turno-1" to "cot-1", fila.saleShiftId to fila.quoteId)
        assertNull(fila.generacionPropia)
        val paycash = JSONObject(canal.enviados.single().second)
        assertEquals("paycash", paycash.getString("comando"))
        assertEquals("187.50", paycash.getString("due"))
        assertEquals(1, canal.enviados.single().first)
        assertTrue(ref.matches(Regex("^AQ[0-9A-Z]{12}$")))
        assertEquals(EstadoDelCobro.Iniciando(ref, due), cobro.estado.value)
    }

    @Test
    fun `un Due que la maquina no puede cobrar no guarda ni manda nada`() = runTest {
        val cobro = nuevoCobro()
        assertEquals(ResultadoDeInicio.NoCobrable, cobro.cobrar(venta.copy(saleCents = 18_000, tipCents = 735)))
        assertEquals(ResultadoDeInicio.NoCobrable, cobro.cobrar(venta.copy(saleCents = 0, tipCents = 0)))
        assertEquals(ResultadoDeInicio.NoCobrable, cobro.cobrar(venta.copy(saleCents = 20_000, tipCents = -500)))
        assertTrue(dao.todas().isEmpty())
        assertTrue(canal.enviados.isEmpty())
    }

    @Test
    fun `P1 sin conexion el paycash no sale y no queda fila`() = runTest {
        val cobro = nuevoCobro()
        canal.caer()
        assertEquals(ResultadoDeInicio.SinConexion, cobro.cobrar(venta))
        assertTrue(dao.todas().isEmpty())
        assertEquals(EstadoDelCobro.Libre, cobro.estado.value)
    }

    @Test
    fun `P1 dos intentos abiertos para la misma venta - el segundo se rechaza`() = runTest {
        val cobro = nuevoCobro()
        val ref = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC
        assertEquals(ResultadoDeInicio.Ocupado, cobro.cobrar(venta.copy(llaveDeVenta = "venta-2")))

        // Otra caja (otro CobroConMaquina sobre la misma base) tampoco abre otro intento de esa venta.
        val otra = CobroConMaquina(canal, dao, { testScheduler.currentTime }, backgroundScope, Random(9))
        assertEquals(ResultadoDeInicio.VentaConIntento, otra.cobrar(venta))
        assertEquals(listOf(ref), dao.todas().map { it.referenceCC })
        assertEquals(1, canal.comandos().count { it == "paycash" })
    }

    @Test
    fun `P1 el DAO deja un solo intento abierto por venta - CANCELADO y GUARDADO dejan reintentar, PAGADO no`() = runTest {
        fun fila(ref: String, llave: String = "venta-1") = CobroEnMaquinaEntity(
            ref, "venue-1", llave, "{}", 100, 0, 100, null, null, creadoEn = 1, estado = EstadoDelIntento.EN_CURSO,
        )
        assertTrue(dao.abrirIntento(fila("AQ000000000001")))
        assertFalse(dao.abrirIntento(fila("AQ000000000002")))
        assertFalse("el mismo folio no se repite", dao.abrirIntento(fila("AQ000000000001", llave = "venta-9")))
        assertTrue(dao.abrirIntento(fila("AQ000000000003", llave = "venta-2")))

        dao.cerrar("AQ000000000001", EstadoDelIntento.CANCELADO, 0, 0, 5, "")
        assertTrue(dao.abrirIntento(fila("AQ000000000004")))
        dao.cerrar("AQ000000000004", EstadoDelIntento.GUARDADO, 0, 0, 6, "")
        assertTrue(dao.abrirIntento(fila("AQ000000000005")))
        dao.cerrar("AQ000000000005", EstadoDelIntento.PAGADO, 100, 0, 7, "")
        assertFalse(dao.abrirIntento(fila("AQ000000000006")))
        assertEquals(1, dao.bloqueanLaVenta("venta-1"))
    }

    // MARK: - Cierres reales

    @Test
    fun `P1 cobro con cambio - el cierre de nuestro folio queda PAGADO y por subir en la misma escritura`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        assertEquals(1, dao.fila(ref)!!.generacionPropia)

        canal.llega(credito(20_000)); runCurrent()
        assertEquals(EstadoDelCobro.Cobrando(ref, due, 20_000, propio = true), cobro.estado.value)
        canal.llega(status(ref, credit = 20_000, estado = "Entregando Cambio")); runCurrent()
        advanceTimeBy(5_000)
        canal.llega(pagoCompletado(ref, credit = 20_000, change = 1_250)); runCurrent()

        assertEquals(EstadoDelCobro.Cerrado(ref, Desenlace.PAGADO, due, 20_000, 1_250), cobro.estado.value)
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.PAGADO, fila.estado)
        assertEquals(20_000 to 1_250, fila.creditCents to fila.changeDispensedCents)
        assertFalse(fila.subido)
        assertTrue(fila.cierreConocido)
        assertEquals(testScheduler.currentTime, fila.cierreEn)
        assertEquals("MAQUINA", fila.resueltoPor)
        assertTrue(fila.mensajesCrudos.contains("Pago Completado"))
        assertEquals(listOf(fila), dao.porSubir())
    }

    @Test
    fun `cobro exacto queda PAGADO sin cambio`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()
        assertEquals(EstadoDelCobro.Cerrado(ref, Desenlace.PAGADO, due, due, 0), cobro.estado.value)
    }

    @Test
    fun `P1 sin cambio suficiente queda PAGADO y lo que no dio queda en la fila`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(pagoCompletado(ref, credit = 20_000, change = 750)); runCurrent()
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.PAGADO, fila.estado)
        assertEquals("se le deben 5 pesos", 500, fila.creditCents - fila.dueCents - fila.changeDispensedCents)
    }

    @Test
    fun `P1 el cierre repetido del mismo folio es una sola transicion`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()
        val primera = dao.fila(ref)!!

        advanceTimeBy(1_000)
        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()

        assertEquals(primera, dao.fila(ref))
        assertEquals(EstadoDelCobro.Cerrado(ref, Desenlace.PAGADO, due, due, 0), cobro.estado.value)
    }

    // MARK: - Cancelar

    @Test
    fun `P1 el cliente cancela - el modo se guarda antes, sale cancel 1 por la conexion propia y el desenlace es el cierre real`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        var modoEnDisco: String? = null
        canal.alEnviar = { modoEnDisco = dao.fila(ref)!!.cancelPedido }

        cobro.cancelarPorElCliente(); runCurrent()

        assertEquals("1", modoEnDisco)
        assertEquals(listOf(1 to "1"), canal.cancels())
        assertEquals(EstadoDelCobro.Cancelando(ref, due, 5_000, "1"), cobro.estado.value)
        assertEquals(EstadoDelIntento.EN_CURSO, dao.fila(ref)!!.estado)

        canal.llega(cancelada(ref, credit = 5_000, change = 5_000)); runCurrent()
        assertEquals(EstadoDelCobro.Cerrado(ref, Desenlace.CANCELADO, due, 5_000, 5_000), cobro.estado.value)
        assertEquals(EstadoDelIntento.CANCELADO, dao.fila(ref)!!.estado)
    }

    @Test
    fun `P1 sin propiedad el boton de cancelar no manda nada`() = runTest {
        val cobro = nuevoCobro()
        cobro.cobrar(venta)
        cobro.cancelarPorElCliente(); runCurrent()
        assertEquals(listOf("paycash"), canal.comandos())
    }

    @Test
    fun `P1 cancelacion ignorada porque entro la ultima moneda - queda PAGADO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        cobro.cancelarPorElCliente(); runCurrent()
        canal.llega(pagoCompletado(ref, credit = 20_000, change = 1_250)); runCurrent()
        assertEquals(EstadoDelIntento.PAGADO, dao.fila(ref)!!.estado)
        assertEquals("1", dao.fila(ref)!!.cancelPedido)
    }

    @Test
    fun `un cancel mandado sin cierre despues queda ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        cobro.cancelarPorElCliente(); runCurrent()
        advanceTimeBy(30_001)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
    }

    // MARK: - Abandono

    @Test
    fun `P1 abandono con propiedad - 120 s sin dinero pregunta, 30 s sin toque guarda el modo y manda siempre cancel 2 - GUARDADO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        val inicio = testScheduler.currentTime

        advanceTimeBy(119_999)
        assertTrue(cobro.estado.value is EstadoDelCobro.Cobrando)
        advanceTimeBy(2)
        assertEquals(EstadoDelCobro.SiguesAhi(ref, due, 0, 30), cobro.estado.value)
        var modoEnDisco: String? = null
        canal.alEnviar = { modoEnDisco = dao.fila(ref)!!.cancelPedido }

        advanceTimeBy(30_000)

        assertEquals("2", modoEnDisco)
        assertEquals(inicio + 150_000, dao.fila(ref)!!.cancelPedidoEn)
        assertEquals("con credito 0 tambien va 2", listOf(1 to "2"), canal.cancels())
        canal.llega(cancelada(ref, credit = 0, change = 0)); runCurrent()
        assertEquals(EstadoDelCobro.Cerrado(ref, Desenlace.GUARDADO, due, 0, 0), cobro.estado.value)
        assertEquals(EstadoDelIntento.GUARDADO, dao.fila(ref)!!.estado)
    }

    @Test
    fun `abandono con dinero adentro tambien es GUARDADO sin cambio`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        assertEquals(5_000, dao.fila(ref)!!.creditCents)
        advanceTimeBy(150_001)
        assertEquals(listOf(1 to "2"), canal.cancels())
        canal.llega(cancelada(ref, credit = 5_000, change = 0)); runCurrent()
        assertEquals(EstadoDelIntento.GUARDADO, dao.fila(ref)!!.estado)
    }

    @Test
    fun `sigo aqui y el dinero nuevo reinician la cuenta del abandono`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        advanceTimeBy(120_001)
        assertTrue(cobro.estado.value is EstadoDelCobro.SiguesAhi)
        cobro.sigoAqui(); runCurrent()
        assertEquals(EstadoDelCobro.Cobrando(ref, due, 0, propio = true), cobro.estado.value)

        advanceTimeBy(100_000)
        canal.llega(credito(2_000)); runCurrent()
        advanceTimeBy(119_000)
        assertTrue(cobro.estado.value is EstadoDelCobro.Cobrando)
        advanceTimeBy(1_001)
        assertTrue(cobro.estado.value is EstadoDelCobro.SiguesAhi)
        assertTrue(canal.cancels().isEmpty())
    }

    @Test
    fun `P1 si la conexion cambia entre guardar el cancel y mandarlo no sale nada y queda ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        dao.alPedirCancel = { canal.reconectar() }

        advanceTimeBy(150_001)

        assertTrue(canal.cancels().isEmpty())
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertEquals("2", fila.cancelPedido)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
    }

    @Test
    fun `P1 si enviar no sale el abandono queda ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        dao.alPedirCancel = { canal.aceptaEnviar = false }
        advanceTimeBy(150_001)
        assertTrue(canal.cancels().isEmpty())
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
    }

    // MARK: - Acuse perdido y reconexión

    @Test
    fun `P1 acuse perdido con la maquina libre - ESPERANDO, sin reintentar y con la venta bloqueada`() = runTest {
        val cobro = nuevoCobro()
        val ref = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC
        canal.llega(socketsLibre()); canal.llega(pong); canal.llega(credito(0)); runCurrent()
        advanceTimeBy(2_999)
        assertEquals(EstadoDelCobro.Iniciando(ref, due), cobro.estado.value)

        advanceTimeBy(2)

        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
        assertEquals(listOf("paycash"), canal.comandos())
        assertEquals("tampoco con tarjeta", 1, dao.bloqueanLaVenta(venta.llaveDeVenta))
        assertEquals(ResultadoDeInicio.VentaConIntento, cobro.cobrar(venta))
        advanceTimeBy(600_000)
        assertEquals(listOf("paycash"), canal.comandos())
    }

    @Test
    fun `P1 lo que la maquina dice despues de la duda se guarda como evidencia pero no la resuelve`() = runTest {
        val cobro = nuevoCobro()
        val ref = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC
        advanceTimeBy(3_001)
        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertTrue(fila.mensajesCrudos.contains("Pago Completado"))
    }

    @Test
    fun `P1 reconexion con cobrando true - no adopta - y el cierre de otra operacion lo deja ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.reconectar(); runCurrent()
        assertEquals(EstadoDelCobro.Reconectando(ref, due, 0), cobro.estado.value)
        canal.llega("""{"comando":"sockets","cobrando":true,"Due":187.5,"Credit":50,"changeBar":"022220222000"}""")
        canal.llega(status(ref, credit = 5_000, estado = "Entregando Cambio")); runCurrent()
        assertEquals(EstadoDelCobro.Cobrando(ref, due, 5_000, propio = false), cobro.estado.value)
        assertEquals("la propiedad no regresa", 1, dao.fila(ref)!!.generacionPropia)

        canal.llega(credito(10_000)); runCurrent()
        assertEquals("sin propiedad sólo se muestra", EstadoDelCobro.Cobrando(ref, due, 10_000, propio = false), cobro.estado.value)
        assertEquals(0, dao.fila(ref)!!.creditCents)
        cobro.cancelarPorElCliente(); runCurrent()
        advanceTimeBy(600_000)
        assertTrue("sin propiedad no hay cancel ni abandono", canal.cancels().isEmpty())

        canal.llega(cancelada("AQOTRAOPERAC1", credit = 0, change = 0, due = 5_000)); runCurrent()
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        assertTrue(dao.fila(ref)!!.mensajesCrudos.contains("AQOTRAOPERAC1"))
    }

    @Test
    fun `P1 reconexion sin noticias de nuestro folio en 3 s - ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.caer(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals("sin conexión espera sin decidir", EstadoDelCobro.Reconectando(ref, due, 0), cobro.estado.value)
        canal.reconectar(); runCurrent()
        canal.llega(socketsLibre()); runCurrent()
        advanceTimeBy(3_001)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        assertTrue(canal.cancels().isEmpty())
    }

    @Test
    fun `tras reconectar el cierre de nuestro folio y Due en la conexion nueva si cierra`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.reconectar(); runCurrent()
        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()
        assertEquals(EstadoDelIntento.PAGADO, dao.fila(ref)!!.estado)
    }

    @Test
    fun `P1 un mensaje atrasado de la generacion vieja no cierra ni cancela`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.reconectar(); runCurrent()

        canal.llega(pagoCompletado(ref, credit = due, change = 0), gen = 1); runCurrent()
        canal.llega(cancelada("AQOTRAOPERAC1", credit = 0, change = 0), gen = 1); runCurrent()

        assertEquals(EstadoDelCobro.Reconectando(ref, due, 0), cobro.estado.value)
        assertEquals(EstadoDelIntento.EN_CURSO, dao.fila(ref)!!.estado)
        advanceTimeBy(3_001)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
        assertTrue(canal.cancels().isEmpty())
    }

    @Test
    fun `P1 DOTACION y otro Due no cierran - dejan la duda`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(cancelada("DOTACION", credit = 0, change = 0)); runCurrent()
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)

        val otro = nuevoCobro(semilla = 11)
        val ref2 = (otro.cobrar(venta.copy(llaveDeVenta = "venta-2")) as ResultadoDeInicio.Iniciado).referenceCC
        canal.llega(status(ref2)); runCurrent()
        canal.llega(pagoCompletado(ref2, credit = 20_000, change = 0, due = 20_000)); runCurrent()
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref2)!!.estado)
    }

    // MARK: - Cierres que no cuadran

    @Test
    fun `P1 un cierre contradictorio queda ESPERANDO con su mensaje crudo`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        val contradictorio = pagoCompletado(ref, credit = 10_000, change = 0)
        canal.llega(contradictorio); runCurrent()
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertTrue(fila.mensajesCrudos.contains(contradictorio))
        assertEquals(listOf(fila), dao.porSubir())
    }

    @Test
    fun `las reglas de cada desenlace son las del SERVIDOR`() {
        // PAGADO: credit ≥ due y 0 ≤ cambio ≤ credit − due.
        assertEquals(Desenlace.PAGADO, desenlaceDelCierre(Cierre.PAGADO, null, 18_750, 20_000, 1_250))
        assertEquals(Desenlace.PAGADO, desenlaceDelCierre(Cierre.PAGADO, "2", 18_750, 18_750, 0))
        assertNull(desenlaceDelCierre(Cierre.PAGADO, null, 18_750, 18_700, 0))
        assertNull(desenlaceDelCierre(Cierre.PAGADO, null, 18_750, 20_000, 1_300))
        assertNull(desenlaceDelCierre(Cierre.PAGADO, null, 18_750, 20_000, -1))
        // Cancelada: credit < due; GUARDADO sólo tras nuestro "2" y sin cambio; CANCELADO tras nuestro "1" y cambio ≤ credit.
        assertEquals(Desenlace.GUARDADO, desenlaceDelCierre(Cierre.CANCELADO, "2", 18_750, 5_000, 0))
        assertNull(desenlaceDelCierre(Cierre.CANCELADO, "2", 18_750, 5_000, 1))
        assertEquals(Desenlace.CANCELADO, desenlaceDelCierre(Cierre.CANCELADO, "1", 18_750, 5_000, 4_000))
        assertNull(desenlaceDelCierre(Cierre.CANCELADO, "1", 18_750, 5_000, 5_001))
        assertNull("con el adeudo cubierto no hay cancelación", desenlaceDelCierre(Cierre.CANCELADO, "1", 18_750, 18_750, 18_750))
        assertNull("cancelada sin nuestro cancel: modo desconocido", desenlaceDelCierre(Cierre.CANCELADO, null, 18_750, 0, 0))
    }

    // MARK: - Recuperar

    @Test
    fun `P1 recuperar - lo final sin subir se entrega, la duda se queda y el EN_CURSO de un proceso muerto pasa a ESPERANDO`() = runTest {
        fun fila(ref: String, llave: String, estado: String, subido: Boolean = false, creado: Long) = CobroEnMaquinaEntity(
            ref, "venue-1", llave, "{}", 18_000, 750, 18_750, null, null, creadoEn = creado, estado = estado,
            subido = subido, cierreConocido = estado != EstadoDelIntento.EN_CURSO,
        )
        dao.insertar(fila("AQ000000000001", "v1", EstadoDelIntento.PAGADO, creado = 1))
        dao.insertar(fila("AQ000000000002", "v2", EstadoDelIntento.CANCELADO, subido = true, creado = 2))
        dao.insertar(fila("AQ000000000003", "v3", EstadoDelIntento.ESPERANDO, creado = 3).copy(mensajesCrudos = "lo que vio"))
        dao.insertar(fila("AQ000000000004", "v4", EstadoDelIntento.EN_CURSO, creado = 4))
        val esperando = dao.fila("AQ000000000003")

        val porSubir = nuevoCobro().recuperar()

        assertEquals(listOf("AQ000000000001", "AQ000000000003", "AQ000000000004"), porSubir.map { it.referenceCC })
        assertEquals("la duda no se toca", esperando, dao.fila("AQ000000000003"))
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila("AQ000000000004")!!.estado)
        assertEquals(EstadoDelIntento.PAGADO, dao.fila("AQ000000000001")!!.estado)
        assertTrue("nada sale a la máquina", canal.enviados.isEmpty())
        assertEquals("y una segunda vez entrega lo mismo", porSubir, nuevoCobro().recuperar())
    }

    @Test
    fun `recuperar con un cobro vivo no lo toca`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        assertTrue(cobro.recuperar().isEmpty())
        assertEquals(EstadoDelIntento.EN_CURSO, dao.fila(ref)!!.estado)
        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()
        assertEquals(EstadoDelIntento.PAGADO, dao.fila(ref)!!.estado)
    }

    // MARK: - Aviso a la subida (C4, R3#12)

    @Test
    fun `cada cierre, cada duda y cada huerfano recuperado avisan a la subida, despues de guardarse`() = runTest {
        val avisos = mutableListOf<String?>()
        val cobro = CobroConMaquina(canal, dao, { testScheduler.currentTime }, backgroundScope, Random(7)) {
            avisos += dao.todas().lastOrNull()?.estado
        }
        val ref = cobroPropio(cobro)
        assertTrue(avisos.isEmpty())

        canal.llega(pagoCompletado(ref, credit = 20_000, change = 1_250)); runCurrent()
        assertEquals("el cierre ya estaba en disco al avisar", listOf<String?>(EstadoDelIntento.PAGADO), avisos)

        val otro = (cobro.cobrar(venta.copy(llaveDeVenta = "venta-2")) as ResultadoDeInicio.Iniciado).referenceCC
        canal.llega(status("AQZZZZZZZZZZZZ")); runCurrent()
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(otro)!!.estado)
        assertEquals(2, avisos.size)

        dao.insertar(
            CobroEnMaquinaEntity("AQ000000000009", "venue-1", "v9", "{}", 18_000, 750, 18_750, null, null, creadoEn = 9, estado = EstadoDelIntento.EN_CURSO),
        )
        cobro.recuperar()
        assertEquals("un huérfano pasó a duda", 3, avisos.size)
        cobro.recuperar()
        assertEquals("sin cambios no avisa", 3, avisos.size)
    }
}
