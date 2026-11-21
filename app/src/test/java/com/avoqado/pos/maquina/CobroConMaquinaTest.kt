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

        /** Lo último que llegó: la MISMA regla del cliente real ([UltimaSenal]), que la anota con su candado antes de avisar. */
        val ultimaSenal = UltimaSenal()

        override fun enviar(json: String, generacion: Int): Boolean {
            if (!aceptaEnviar || conexion.value != Conexion.CONECTADA || generacion != this.generacion.value) return false
            alEnviar(json)
            enviados += generacion to json
            return true
        }

        override fun enviarSiSigueSiendoNuestro(json: String, generacion: Int, referenceCC: String, dueCents: Int): Boolean =
            ultimaSenal.esNuestra(generacion, referenceCC, dueCents) && enviar(json, generacion)

        fun llega(json: String, gen: Int = generacion.value) {
            val r = Recibido(gen, leerMensaje(json)!!, json)
            ultimaSenal.anotar(r)
            check(recibidos.tryEmit(r))
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
    private fun TestScope.nuevoCobro(semilla: Long = 7, alTerminar: () -> Unit = {}) = CobroConMaquina(
        canal, dao, { testScheduler.currentTime }, backgroundScope, Random(semilla), alTerminar, monotonico = { testScheduler.currentTime },
    )

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

    /** Codex código r2 H14: el `updateCredit` de APS con el `Due` de su operación. */
    private fun creditoDe(cents: Int, dueDeLaOperacion: Int) =
        JSONObject(credito(cents)).put("Due", dueDeLaOperacion / 100.0).toString()

    private val pong = """{"comando":"pong","conexiones":2,"tarjeta":false}"""

    private fun socketsLibre() = """{"comando":"sockets","cobrando":false,"Due":0,"Credit":0,"changeBar":"022220222000"}"""

    /** Inicia el cobro y la máquina lo acepta con nuestro folio y Due en la generación 1: el cobro es nuestro. */
    private suspend fun TestScope.cobroPropio(cobro: CobroConMaquina, v: VentaParaLaMaquina = venta): String {
        val ref = (cobro.cobrar(v) as ResultadoDeInicio.Iniciado).referenceCC
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
        val otra = nuevoCobro(semilla = 9)
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

        dao.cerrar("AQ000000000001", EstadoDelIntento.CANCELADO, 0, 0, 5)
        assertTrue(dao.abrirIntento(fila("AQ000000000004")))
        dao.cerrar("AQ000000000004", EstadoDelIntento.GUARDADO, 0, 0, 6)
        assertTrue(dao.abrirIntento(fila("AQ000000000005")))
        dao.cerrar("AQ000000000005", EstadoDelIntento.PAGADO, 100, 0, 7)
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
        assertFalse("Codex r1 #15: el status sin fecha no da hora física; la de la PC sólo es para enseñar", fila.cierreConocido)
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
        assertEquals("Codex r1 #13: queda anotado lo que se debe", 500, fila.aDeberCents)
    }

    @Test
    fun `P1 Codex r1 #15 el cierre guarda la hora de la maquina como hora conocida`() = runTest {
        val cobro = CobroConMaquina(
            canal, dao, { testScheduler.currentTime }, backgroundScope, Random(7), {}, { java.time.ZoneId.of("America/Mexico_City") },
            monotonico = { testScheduler.currentTime },
        )
        val ref = cobroPropio(cobro)
        val cierre = JSONObject(pagoCompletado(ref, credit = due, change = 0)).put("fecha", "2026-11-18 15:48:20").toString()

        canal.llega(cierre); runCurrent()

        val fila = dao.fila(ref)!!
        assertTrue(fila.cierreConocido)
        assertEquals(java.time.Instant.parse("2026-11-18T21:48:20Z").toEpochMilli(), fila.cierreEn)
    }

    @Test
    fun `P1 Codex r1 #14 con la cotizacion vencida no se guarda ni se manda nada`() = runTest {
        val cobro = nuevoCobro()
        advanceTimeBy(10_000)

        assertEquals(ResultadoDeInicio.CotizacionVencida, cobro.cobrar(venta.copy(vigenteHasta = testScheduler.currentTime - 1)))
        assertTrue(dao.todas().isEmpty())
        assertTrue(canal.enviados.isEmpty())
        assertTrue(cobro.cobrar(venta.copy(vigenteHasta = testScheduler.currentTime + 1, renglonesJson = "[]")) is ResultadoDeInicio.Iniciado)
        assertEquals("[]", dao.todas().single().renglonesJson)
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
    fun `P1 Codex r1 #3 si mientras se guarda el cancel llega el status de otra operacion, no sale y queda ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        // Llega al cliente (con su candado) mientras la PC guarda el modo; el cobro todavía no lo procesa.
        dao.alPedirCancel = { canal.llega(status("AQOTRAOPERAC12", due = 5_000)) }

        cobro.cancelarPorElCliente(); runCurrent()

        assertTrue("un cancel sin folio no puede tocar la operación de otro", canal.cancels().isEmpty())
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertEquals("1", fila.cancelPedido)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
    }

    @Test
    fun `Codex r1 #3 el abandono no cancela si mientras guarda el modo llego el cierre de nuestro folio`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        dao.alPedirCancel = { canal.llega(pagoCompletado(ref, credit = due, change = 0)) }

        advanceTimeBy(150_001)

        assertTrue("la máquina ya estaba libre: el cancel le tocaría a otro", canal.cancels().isEmpty())
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertNull("Codex r2 H2: el cierre que llega con la fila en duda es evidencia, nunca un desenlace", fila.cierreTardio)
        assertTrue(fila.mensajesCrudos.contains("Pago Completado"))
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
        assertNull("sin propiedad nunca ganada, es sólo evidencia de la duda", fila.cierreTardio)
    }

    // MARK: - Codex código r1 #6: la duda sube siempre lo último

    @Test
    fun `P1 Codex r1 #6 el dinero que entra despues del acuse queda en disco junto con lo que vio la PC`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)

        canal.llega(credito(5_000)); runCurrent()

        val fila = dao.fila(ref)!!
        assertEquals(5_000, fila.creditCents)
        assertTrue("si el proceso muere aquí, la duda sube los $50", fila.mensajesCrudos.contains("updateCredit"))
    }

    @Test
    fun `P1 Codex r2 H2 el cierre que llega con la fila ya en duda es evidencia de la duda, nunca un desenlace`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        cobro.cancelarPorElCliente(); runCurrent()
        advanceTimeBy(30_001)   // el cierre no llegó a tiempo
        dao.marcarDudaSubida(ref, true)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)

        canal.llega(cancelada(ref, credit = 5_000, change = 5_000)); runCurrent()

        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertNull(fila.cierreTardio)
        assertEquals("el cambio lo teclea el gerente: el diario lo guarda tal cual", 0, fila.changeDispensedCents)
        assertTrue(fila.mensajesCrudos.endsWith(cancelada(ref, credit = 5_000, change = 5_000)))
        assertFalse("evidencia nueva: la duda vuelve a subir", fila.dudaSubida)
        assertEquals("la tarjeta sigue frenada hasta que el gerente decida", 1, dao.bloqueanLaVenta(venta.llaveDeVenta))
        assertTrue(dao.porSubir().all { it.estado == EstadoDelIntento.ESPERANDO })
    }

    @Test
    fun `P1 Codex r2 H2 tras ver otra operacion, el cierre de nuestro folio por la MISMA conexion tampoco es desenlace`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(status("DOTACION", due = 50_000)); runCurrent()   // otra operación: la nuestra ya no se sabe
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)

        canal.llega(pagoCompletado(ref, credit = 20_000, change = 1_250)); runCurrent()

        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertNull(fila.cierreTardio)
        assertEquals(listOf("DOTACION", "Pago Completado"), listOf("DOTACION", "Pago Completado").filter { fila.mensajesCrudos.contains(it) })
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
    }

    @Test
    fun `P1 Codex r2 H2 un cancel 2 que se guardo pero no salio nunca clasifica un Transaccion Cancelada como GUARDADO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        dao.alPedirCancel = { canal.aceptaEnviar = false }
        advanceTimeBy(150_001)   // abandono: el modo «2» quedó en disco, pero el `cancel` no salió
        assertTrue(canal.cancels().isEmpty())
        assertEquals("2", dao.fila(ref)!!.cancelPedido)
        canal.aceptaEnviar = true

        canal.llega(cancelada(ref, credit = 5_000, change = 0)); runCurrent()

        val fila = dao.fila(ref)!!
        assertEquals("lo canceló alguien en la pantalla de la máquina: no se sabe si guardó o devolvió", EstadoDelIntento.ESPERANDO, fila.estado)
        assertNull(fila.cierreTardio)
        assertEquals(1, dao.bloqueanLaVenta(venta.llaveDeVenta))
    }

    @Test
    fun `Codex r1 #6 una observacion nueva de un folio en duda vuelve a subir la duda`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(status("AQOTRAOPERAC12", due = 5_000)); runCurrent()   // otra operación: duda
        dao.marcarDudaSubida(ref, true)
        val antes = dao.fila(ref)!!.mensajesCrudos

        canal.llega(status(ref, credit = 10_000)); runCurrent()

        val fila = dao.fila(ref)!!
        assertFalse("el SERVIDOR la agrega a la duda en la siguiente subida", fila.dudaSubida)
        assertTrue(fila.mensajesCrudos.length > antes.length && fila.mensajesCrudos.endsWith(status(ref, credit = 10_000)))
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
        assertEquals("Codex r2 H3: pero queda en disco, como evidencia", 10_000, dao.fila(ref)!!.creditCents)
        cobro.cancelarPorElCliente(); runCurrent()
        advanceTimeBy(20_000)
        assertTrue("sin propiedad no hay cancel ni abandono", canal.cancels().isEmpty())

        canal.llega(cancelada("AQOTRAOPERAC1", credit = 0, change = 0, due = 5_000)); runCurrent()
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        assertTrue("la señal que dejó la duda es evidencia de ella", dao.fila(ref)!!.mensajesCrudos.contains("AQOTRAOPERAC1"))
    }

    @Test
    fun `P1 Codex r2 H3 sin la propiedad, 30 s sin noticias de nuestro folio desde la ULTIMA dejan la duda aunque el enlace este arriba`() = runTest {
        var avisos = 0
        val cobro = nuevoCobro(alTerminar = { avisos++ })
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        canal.caer(); runCurrent()
        advanceTimeBy(10_000)
        canal.reconectar(); runCurrent()
        canal.llega(status(ref, credit = 5_000)); runCurrent()   // un estado intermedio de nuestro folio: no se adopta
        assertEquals(EstadoDelCobro.Cobrando(ref, due, 5_000, propio = false), cobro.estado.value)

        advanceTimeBy(20_000)
        canal.llega(pong); canal.llega(socketsLibre()); runCurrent()   // sólo respuestas de conexión: no son noticias
        canal.llega(credito(10_000)); runCurrent()                    // dinero de nuestro Due: sí es noticia, y va a disco
        assertEquals(10_000, dao.fila(ref)!!.creditCents)
        advanceTimeBy(29_999)
        assertEquals(EstadoDelIntento.EN_CURSO, dao.fila(ref)!!.estado)

        advanceTimeBy(2)

        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertEquals("el gerente recibe la duda con lo que entró", 10_000, fila.creditCents)
        assertEquals(1, avisos)
        assertEquals(ResultadoDeInicio.Iniciado::class, cobro.cobrar(venta.copy(llaveDeVenta = "venta-2"))::class)
    }

    @Test
    fun `P1 reconexion sin noticias de nuestro folio en 3 s - ESPERANDO`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.caer(); runCurrent()
        advanceTimeBy(10_000)
        assertEquals("sin conexión espera sin decidir", EstadoDelCobro.Reconectando(ref, due, 0), cobro.estado.value)
        canal.reconectar(); runCurrent()
        canal.llega(socketsLibre()); runCurrent()
        advanceTimeBy(3_001)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        assertTrue(canal.cancels().isEmpty())
    }

    @Test
    fun `P1 Codex r1 #4 sin enlace con dinero adentro, a los 30 s queda ESPERANDO aunque el enlace no vuelva`() = runTest {
        var avisos = 0
        val cobro = nuevoCobro(alTerminar = { avisos++ })
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        canal.caer(); runCurrent()

        advanceTimeBy(29_999)
        assertEquals(EstadoDelCobro.Reconectando(ref, due, 5_000), cobro.estado.value)
        assertEquals(EstadoDelIntento.EN_CURSO, dao.fila(ref)!!.estado)

        advanceTimeBy(2)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertEquals("la duda sube con el dinero que entró", 5_000, fila.creditCents)
        assertEquals("la subida se entera sin esperar a que vuelva el enlace", 1, avisos)
        assertTrue(canal.cancels().isEmpty())
        assertEquals(1, dao.bloqueanLaVenta(venta.llaveDeVenta))
    }

    @Test
    fun `un enlace que parpadea no alarga el plazo sin enlace`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.caer(); runCurrent()
        advanceTimeBy(20_000)
        canal.reconectar(); runCurrent()
        advanceTimeBy(1_000)
        canal.caer(); runCurrent()

        advanceTimeBy(8_999)
        assertEquals(EstadoDelCobro.Reconectando(ref, due, 0), cobro.estado.value)
        advanceTimeBy(2)
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
    }

    @Test
    fun `P1 Codex r1 #5 tras reconectar, el cierre de nuestro folio en la conexion nueva no es nuestro - ESPERANDO con su evidencia`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.reconectar(); runCurrent()

        canal.llega(pagoCompletado(ref, credit = due, change = 0)); runCurrent()

        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertNull("no se sube como cierre: la propiedad era de otra conexión", fila.cierreTardio)
        assertTrue(fila.mensajesCrudos.contains("Pago Completado"))
        assertEquals(EstadoDelCobro.Esperando(ref, due), cobro.estado.value)
    }

    @Test
    fun `Codex r1 #5 una cancelacion de nuestro folio en otra conexion tampoco libera la venta`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(credito(5_000)); runCurrent()
        cobro.cancelarPorElCliente(); runCurrent()
        canal.reconectar(); runCurrent()

        canal.llega(cancelada(ref, credit = 5_000, change = 5_000)); runCurrent()

        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
        assertEquals("la tarjeta sigue frenada: lo decide el gerente", 1, dao.bloqueanLaVenta(venta.llaveDeVenta))
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
        val cobro = nuevoCobro(alTerminar = { avisos += dao.todas().lastOrNull()?.estado })
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

    // MARK: - Codex código r2, bloque A: el diario por folio

    @Test
    fun `P1 Codex r2 H4 el cierre tardio de A va al diario de A aunque B sea el intento vivo, y B no se lo queda`() = runTest {
        val avisos = mutableListOf<String>()
        val cobro = nuevoCobro(alTerminar = { avisos += "subida" })
        val refA = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC
        advanceTimeBy(3_001)   // el acuse de A no llegó: A en duda
        dao.marcarDudaSubida(refA, true)
        val refB = cobroPropio(cobro, venta.copy(llaveDeVenta = "venta-2"))
        avisos.clear()

        canal.llega(pagoCompletado(refA, credit = 20_000, change = 1_250)); runCurrent()

        val a = dao.fila(refA)!!
        assertEquals(EstadoDelIntento.ESPERANDO, a.estado)
        assertTrue("A conserva su evidencia", a.mensajesCrudos.contains("Pago Completado"))
        assertEquals(20_000, a.creditCents)
        assertFalse("y vuelve a subir", a.dudaSubida)
        val b = dao.fila(refB)!!
        assertFalse("B nunca toma el dinero de A", b.estado == EstadoDelIntento.PAGADO)
        assertEquals(0, b.creditCents)
        assertTrue("la subida se entera de la evidencia nueva de A", avisos.isNotEmpty())
    }

    @Test
    fun `P1 Codex r2 H4 tras reiniciar, sin intento vivo, el mensaje de un folio en duda llega a su fila`() = runTest {
        dao.insertar(
            CobroEnMaquinaEntity(
                "AQ000000000007", "venue-1", "venta-7", "{}", 18_000, 750, due, null, null, creadoEn = 1,
                estado = EstadoDelIntento.ESPERANDO, dudaSubida = true,
            ),
        )
        var avisos = 0
        nuevoCobro(alTerminar = { avisos++ })

        canal.llega(cancelada("AQ000000000007", credit = 5_000, change = 5_000)); runCurrent()

        val fila = dao.fila("AQ000000000007")!!
        assertTrue(fila.mensajesCrudos.contains("Transacción Cancelada"))
        assertEquals(5_000, fila.creditCents)
        assertFalse(fila.dudaSubida)
        assertEquals(1, avisos)
    }

    @Test
    fun `P1 Codex r2 H13 el mismo mensaje fisico repetido no es evidencia nueva ni vuelve a subir la duda`() = runTest {
        val cobro = nuevoCobro()
        val ref = (cobro.cobrar(venta) as ResultadoDeInicio.Iniciado).referenceCC
        advanceTimeBy(3_001)
        val intermedio = JSONObject(status(ref, credit = 5_000)).put("fecha", "2026-11-18 15:48:20").toString()
        canal.llega(intermedio); runCurrent()
        dao.marcarDudaSubida(ref, true)
        val antes = dao.fila(ref)!!

        advanceTimeBy(60_000)
        canal.llega(intermedio); runCurrent()                 // APS repite el MISMO status, con la misma fecha
        canal.reconectar(); runCurrent()
        canal.llega(intermedio); runCurrent()                 // y otra vez por otra conexión

        assertEquals("ni la hora de llegada ni la conexión lo vuelven nuevo", antes, dao.fila(ref))
        assertEquals(1, antes.mensajesCrudos.lines().count { it.endsWith(intermedio) })
    }

    @Test
    fun `P1 Codex r2 H14 un updateCredit de otra operacion deja la duda y su dinero no se le atribuye a la nuestra`() = runTest {
        val cobro = nuevoCobro()
        val ref = cobroPropio(cobro)
        canal.llega(creditoDe(5_000, dueDeLaOperacion = due)); runCurrent()
        assertEquals(5_000, dao.fila(ref)!!.creditCents)

        canal.llega(creditoDe(30_000, dueDeLaOperacion = 50_000)); runCurrent()

        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.ESPERANDO, fila.estado)
        assertEquals("lo de otra operación nunca es nuestro", 5_000, fila.creditCents)
        assertTrue("la señal ajena queda como evidencia de la duda", fila.mensajesCrudos.endsWith(creditoDe(30_000, dueDeLaOperacion = 50_000)))
        cobro.cancelarPorElCliente(); advanceTimeBy(600_000)
        assertTrue("y ningún cancel sin folio", canal.cancels().isEmpty())
    }

    @Test
    fun `P1 Codex r2 H14 el cancel sin folio no sale si despues de nuestro status llego dinero de otro Due, hasta un status nuestro nuevo`() {
        val s = UltimaSenal()
        fun llega(json: String, gen: Int = 1) = s.anotar(Recibido(gen, leerMensaje(json)!!, json))
        llega(status("AQ00000000000A"))
        assertTrue(s.esNuestra(1, "AQ00000000000A", due))
        llega(creditoDe(5_000, dueDeLaOperacion = due))
        assertTrue("dinero de nuestro Due no la quita", s.esNuestra(1, "AQ00000000000A", due))
        llega(credito(6_000))
        assertTrue("un updateCredit sin Due tampoco", s.esNuestra(1, "AQ00000000000A", due))
        llega(creditoDe(5_000, dueDeLaOperacion = 50_000))
        assertFalse("otro Due: otra operación", s.esNuestra(1, "AQ00000000000A", due))
        llega(socketsLibre())
        assertFalse("una máquina libre no la regresa", s.esNuestra(1, "AQ00000000000A", due))
        llega(status("AQ00000000000A", credit = 6_000))
        assertTrue("sólo un status nuestro nuevo", s.esNuestra(1, "AQ00000000000A", due))
        llega("""{"comando":"sockets","cobrando":true,"Due":500,"Credit":0,"changeBar":"022220222000"}""")
        assertFalse(s.esNuestra(1, "AQ00000000000A", due))
        llega(status("AQ00000000000A"), gen = 2)
        assertFalse("en otra conexión no", s.esNuestra(1, "AQ00000000000A", due))
    }

    // MARK: - Codex código r2, bloque A: la matriz de [decidir]

    /**
     * La regla completa, sobre TODO el producto (tipo de mensaje × folio propio/ajeno × misma/otra conexión × con/sin propiedad × fase),
     * con sus invariantes; y debajo, la tabla de los casos que importan con el paso esperado.
     */
    @Test
    fun `P1 Codex r2 la matriz de decidir - invariantes sobre todo el producto`() {
        val ref = "AQ00000000000A"
        val ajeno = "AQ00000000000Z"
        data class Caso(val nombre: String, val m: MensajeDeLaMaquina, val propio: Boolean)
        val mensajes = listOf(
            Caso("status abierto propio", leerMensaje(status(ref))!!, true),
            Caso("status abierto ajeno", leerMensaje(status(ajeno))!!, false),
            Caso("status propio con otro Due", leerMensaje(status(ref, due = 500))!!, false),
            Caso("pagado propio", leerMensaje(pagoCompletado(ref, credit = due, change = 0))!!, true),
            Caso("pagado ajeno", leerMensaje(pagoCompletado(ajeno, credit = due, change = 0))!!, false),
            Caso("cancelada propia", leerMensaje(cancelada(ref, credit = 0, change = 0))!!, true),
            Caso("DOTACION", leerMensaje(cancelada("DOTACION", credit = 0, change = 0))!!, false),
            Caso("credito sin Due", leerMensaje(credito(5_000))!!, true),
            Caso("credito de nuestro Due", leerMensaje(creditoDe(5_000, due))!!, true),
            Caso("credito de otro Due", leerMensaje(creditoDe(5_000, 500))!!, false),
            Caso("sockets libre", leerMensaje(socketsLibre())!!, true),
            Caso("sockets cobrando otro Due", leerMensaje("""{"comando":"sockets","cobrando":true,"Due":5,"Credit":0}""")!!, false),
            Caso("pong", leerMensaje(pong)!!, true),
        )
        val fases = listOf<FaseDelIntento?>(null) + FaseDelIntento.entries
        var combinaciones = 0
        for (c in mensajes) for (fase in fases) for (mismaConexion in listOf(true, false)) for (conPropiedad in listOf(true, false)) {
            combinaciones++
            val paso = decidir(c.m, generacion = if (mismaConexion) 2 else 1, ref, due, fase, vigilada = 2, propia = if (conPropiedad) 2 else 1)
            val donde = "${c.nombre} · $fase · misma=$mismaConexion · propiedad=$conPropiedad → $paso"
            val vivo = fase != null && fase != FaseDelIntento.CERRADO && fase != FaseDelIntento.ESPERANDO
            if (!vivo || !mismaConexion) assertEquals("sin intento vivo o de otra conexión sólo es diario: $donde", Paso.NADA, paso)
            if (paso == Paso.CERRAR) assertTrue("sólo cierra lo propio, en la conexión de la propiedad: $donde", c.propio && conPropiedad)
            if (paso == Paso.GANAR) assertTrue("sólo gana el acuse propio al iniciar: $donde", c.propio && fase == FaseDelIntento.INICIANDO)
            if (!c.propio && vivo && mismaConexion) assertEquals("lo ajeno deja la duda: $donde", Paso.DUDA, paso)
            if (c.m is MensajeDeLaMaquina.UpdateCredit || c.m is MensajeDeLaMaquina.Sockets || c.m is MensajeDeLaMaquina.Pong) {
                assertTrue("nada sin folio cierra ni gana: $donde", paso != Paso.CERRAR && paso != Paso.GANAR)
            }
        }
        assertEquals(13 * 8 * 2 * 2, combinaciones)
    }

    @Test
    fun `Codex r2 la tabla de decidir - los casos que importan`() {
        val ref = "AQ00000000000A"
        fun d(json: String, fase: FaseDelIntento, gen: Int = 2, propia: Int? = 2) = decidir(leerMensaje(json)!!, gen, ref, due, fase, 2, propia)
        val tabla = listOf(
            d(status(ref), FaseDelIntento.INICIANDO, propia = null) to Paso.GANAR,
            d(status(ref), FaseDelIntento.COBRANDO) to Paso.NOTICIA,
            d(status(ref), FaseDelIntento.COBRANDO, propia = 1) to Paso.NOTICIA,           // tras reconectar: sólo se enseña
            d(pagoCompletado(ref, credit = due, change = 0), FaseDelIntento.COBRANDO) to Paso.CERRAR,
            d(pagoCompletado(ref, credit = due, change = 0), FaseDelIntento.CANCELANDO) to Paso.CERRAR,
            d(pagoCompletado(ref, credit = due, change = 0), FaseDelIntento.COBRANDO, propia = 1) to Paso.DUDA,   // H2
            d(pagoCompletado(ref, credit = due, change = 0), FaseDelIntento.INICIANDO, propia = null) to Paso.DUDA,
            d(cancelada(ref, credit = 0, change = 0), FaseDelIntento.RECONECTANDO, propia = 1) to Paso.DUDA,
            d(status("DOTACION"), FaseDelIntento.COBRANDO) to Paso.DUDA,
            d(creditoDe(1_000, 500), FaseDelIntento.SIGUES_AHI) to Paso.DUDA,                // H14
            d(credito(1_000), FaseDelIntento.SIGUES_AHI) to Paso.NOTICIA,
            d(socketsLibre(), FaseDelIntento.INICIANDO, propia = null) to Paso.NADA,       // una máquina libre nunca decide
            d(pagoCompletado(ref, credit = due, change = 0), FaseDelIntento.ESPERANDO) to Paso.NADA,   // la duda: sólo diario
            d(pagoCompletado(ref, credit = due, change = 0), FaseDelIntento.COBRANDO, gen = 1) to Paso.NADA,   // otra conexión
        )
        tabla.forEachIndexed { i, (paso, esperado) -> assertEquals("renglón $i", esperado, paso) }
    }
}
