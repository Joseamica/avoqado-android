package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.printing.data.PrinterService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import java.io.IOException
import java.time.Instant
import java.util.Random

/**
 * C5: «Cobrar» con la máquina en la PC (R4-2, R4-4, R5#4, R5#8). La máquina contra un canal falso, la API de cotizar y de
 * resolver falsa (mockk) y el DAO sobre SQLite de verdad ([CobroEnMaquinaSobreSqlite]). Sin red real: el reloj es el de
 * `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaquinaDeEfectivoTest {

    internal class CanalFalso : CanalDeLaMaquina {
        override val conexion = MutableStateFlow(Conexion.CONECTADA)
        override val generacion = MutableStateFlow(1)
        override val recibidos = MutableSharedFlow<Recibido>(extraBufferCapacity = 64)
        val enviados = mutableListOf<String>()
        var alEnviar: (String) -> Unit = {}
        var conectadaA: String? = null

        override fun enviar(json: String, generacion: Int): Boolean {
            if (conexion.value != Conexion.CONECTADA || generacion != this.generacion.value) return false
            alEnviar(json)
            enviados += json
            return true
        }

        override fun conectar(ip: String) { conectadaA = ip }
        override fun desconectar() { conectadaA = null }

        fun llega(json: String) = check(recibidos.tryEmit(Recibido(generacion.value, leerMensaje(json)!!, json)))
        fun paycash(): JSONObject? = enviados.map(::JSONObject).lastOrNull { it.getString("comando") == "paycash" }
    }

    private val canal = CanalFalso()
    private val dao = CobroEnMaquinaSobreSqlite()
    private val contexto = AjustesDeLaMaquinaTest.contextoConPreferencias()
    private val prefs = MaquinaDeEfectivoPrefs(contexto).apply { guardar("192.168.1.50", activa = true) }
    private val boveda = object : BovedaDeLaMaquina {
        var credencial: String? = "cred-1"
        override fun guardar(credencial: String) { this.credencial = credencial }
        override fun leer(): String? = credencial
        override fun borrar() { credencial = null }
    }
    private val api = mockk<CierresDeLaMaquinaApi>()
    private val subida = mockk<SubidaDeLaMaquina>(relaxed = true) { every { aviso } returns MutableStateFlow(null) }
    private val turno = mockk<TurnoDelNegocio> {
        coEvery { refrescar() } returns "turno-1"
        every { turnoActual() } returns "turno-1"
    }
    private val sesion = mockk<SecureStorage>(relaxed = true) {
        every { venueId } returns "venue-1"
        every { venueName } returns "La Galeterie"
        every { venuePermissions } returns listOf("payments:create", "cash-machine:resolve")
    }
    private val impresora = mockk<PrinterService>(relaxed = true)

    @After fun cerrar() = dao.close()

    private fun TestScope.maquina() = MaquinaDeEfectivo(
        prefs, boveda, dao, api, subida, turno, RoleManager(sesion), impresora, sesion, canal, backgroundScope,
        { testScheduler.currentTime }, Random(7),
    )

    private val pedido = JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("productId", "p1").put("quantity", 1)))
        .put("staffId", "staff-1").put("orderType", "TAKEOUT").put("tip", 750)

    private fun venta(p: JSONObject = pedido, sale: Int = 18_000, tip: Int = 750) =
        VentaPorCobrar(venueId = "venue-1", llaveDeVenta = "venta-1", pedidoJson = p.toString(), saleCents = sale, tipCents = tip)

    private fun cotizacion(sale: Int, tip: Int, quoteId: String? = "q-1", cobrable: Boolean = true, motivo: String? = null) =
        RespuestaDelServidor(200, JSONObject().put("success", true).put("quoteId", quoteId ?: JSONObject.NULL)
            .put("totalCents", sale + tip).put("saleCents", sale).put("tipCents", tip).put("cobrableEnMaquina", cobrable)
            .apply { motivo?.let { put("motivo", it) } }.toString())

    private fun status(ref: String, due: Int, credit: Int = 0, change: Int = 0, cobrando: Boolean = true, estado: String? = null) =
        JSONObject().put("comando", "status").put("cobrando", cobrando).put("Status", estado ?: JSONObject.NULL)
            .put("referenceCC", ref).put("Credit", credit / 100.0).put("Due", due / 100.0)
            .put("changeDispensed", change / 100.0).put("changeBar", "022220222000").toString()

    // MARK: - ¿Está activa?

    @Test
    fun `solo con la casilla prendida y una credencial que esta PC lee`() = runTest {
        val m = maquina()
        assertTrue(m.activa())
        boveda.credencial = null
        assertFalse("perfil copiado de otra PC: todo como hoy", m.activa())
        boveda.credencial = "cred-1"
        prefs.guardar("192.168.1.50", activa = false)
        assertFalse(m.activa())
    }

    @Test
    fun `al arrancar se conecta a la IP guardada y el EN_CURSO de un proceso muerto pasa a duda`() = runTest {
        dao.insertar(
            CobroEnMaquinaEntity("AQ000000000009", "venue-1", "venta-9", "{}", 1_000, 0, 1_000, null, null, 1, EstadoDelIntento.EN_CURSO),
        )
        val m = maquina()

        m.arrancar()
        runCurrent()

        assertEquals("192.168.1.50", canal.conectadaA)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila("AQ000000000009")!!.estado)
    }

    // MARK: - Antes de la red (R5#4, decisión «a»)

    @Test
    fun `P1 un carrito con descuento no se cotiza ni se guarda ni se manda nada`() = runTest {
        val conDescuento = JSONObject(pedido.toString()).apply {
            getJSONArray("items").getJSONObject(0).put("discountId", "desc-1")
        }

        val r = maquina().cobrar(venta(conDescuento))

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_CON_DESCUENTO), r)
        coVerify(exactly = 0) { api.cotizar(any(), any()) }
        assertTrue(dao.todas().isEmpty())
        assertTrue(canal.enviados.isEmpty())
    }

    @Test
    fun `P1 R5#4 un premio de sellos pendiente no se cobra en la maquina`() = runTest {
        val r = maquina().cobrar(venta(JSONObject(pedido.toString()).put("stampRewardId", "premio-1")))

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_CON_PREMIO), r)
        coVerify(exactly = 0) { api.cotizar(any(), any()) }
        assertTrue(dao.todas().isEmpty() && canal.enviados.isEmpty())
    }

    @Test
    fun `cortesia, promocion, cupon y ajuste de precio tambien son descuento`() {
        fun con(f: JSONObject.() -> Unit) = JSONObject(pedido.toString()).apply(f)
        fun renglon(f: JSONObject.() -> Unit) = con { getJSONArray("items").getJSONObject(0).f() }
        assertEquals(TEXTO_CON_DESCUENTO, motivoSinMaquinaDelPedido(renglon { put("isCortesia", true) }))
        assertEquals(TEXTO_CON_DESCUENTO, motivoSinMaquinaDelPedido(renglon { put("adjustedUnitPrice", 900) }))
        assertEquals(TEXTO_CON_DESCUENTO, motivoSinMaquinaDelPedido(con { getJSONArray("items").put(JSONObject().put("promotionRef", JSONObject())) }))
        assertEquals(TEXTO_CON_DESCUENTO, motivoSinMaquinaDelPedido(con { put("couponCode", "HOLA") }))
        assertEquals(TEXTO_CON_DESCUENTO, motivoSinMaquinaDelPedido(con { put("discount", 500) }))
        assertNull(motivoSinMaquinaDelPedido(pedido))
    }

    // MARK: - La cotización (R4-2)

    @Test
    fun `P1 si la cotizacion dice centavos no se guarda ni se manda nada`() = runTest {
        coEvery { api.cotizar("venue-1", any()) } returns cotizacion(18_035, 0, quoteId = null, cobrable = false, motivo = "CENTAVOS")

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS), maquina().cobrar(venta()))
        assertTrue(dao.todas().isEmpty() && canal.enviados.isEmpty())
    }

    @Test
    fun `un producto que ya no esta se explica y no se cobra`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns cotizacion(0, 0, quoteId = null, cobrable = false, motivo = "PRODUCTO_NO_DISPONIBLE")

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_PRODUCTO_NO_DISPONIBLE), maquina().cobrar(venta()))
        assertTrue(canal.enviados.isEmpty())
    }

    @Test
    fun `un rechazo del servidor al cotizar se dice tal cual y no se cobra`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns
            RespuestaDelServidor(409, """{"message":"El precio de Café cambió","code":"ORDER_PRICE_CHANGED"}""")

        assertEquals(InicioEnLaMaquina.NoSeCobra("El precio de Café cambió"), maquina().cobrar(venta()))
        assertTrue(dao.todas().isEmpty() && canal.enviados.isEmpty())
    }

    @Test
    fun `P1 con red el Due sale de la cotizacion y el carrito se congela ANTES del paycash`() = runTest {
        coEvery { api.cotizar("venue-1", any()) } returns cotizacion(18_000, 750)
        val m = maquina()
        var enDisco: CobroEnMaquinaEntity? = null
        canal.alEnviar = { json -> enDisco = dao.fila(JSONObject(json).getString("referenceCC")) }

        val r = m.cobrar(venta(sale = 17_000))   // el carrito local decía otro precio: manda el del SERVIDOR

        val ref = (r as InicioEnLaMaquina.Iniciado).referenceCC
        val fila = enDisco ?: error("el paycash salió sin la fila en disco")
        assertEquals(EstadoDelIntento.EN_CURSO, fila.estado)
        assertEquals(ref, fila.referenceCC)
        assertEquals(pedido.toString(), fila.pedidoJson)
        assertEquals(listOf(18_000, 750, 18_750), listOf(fila.saleCents, fila.tipCents, fila.dueCents))
        assertEquals("turno-1" to "q-1", fila.saleShiftId to fila.quoteId)
        assertEquals("187.50", canal.paycash()!!.getString("due"))
        coVerify { turno.refrescar() }
    }

    @Test
    fun `P1 sin red cobra el total local, sin cotizacion, con el turno guardado`() = runTest {
        coEvery { api.cotizar(any(), any()) } throws IOException("sin red")
        coEvery { turno.refrescar() } throws IOException("sin red")

        val r = maquina().cobrar(venta(sale = 17_000, tip = 500))

        val fila = dao.fila((r as InicioEnLaMaquina.Iniciado).referenceCC)!!
        assertEquals(listOf(17_000, 500, 17_500), listOf(fila.saleCents, fila.tipCents, fila.dueCents))
        assertNull(fila.quoteId)
        assertEquals("turno-1", fila.saleShiftId)
        assertEquals("175.00", canal.paycash()!!.getString("due"))
    }

    @Test
    fun `sin red y con centavos raros no se manda nada`() = runTest {
        coEvery { api.cotizar(any(), any()) } throws IOException("sin red")

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS), maquina().cobrar(venta(sale = 18_735, tip = 0)))
        assertTrue(dao.todas().isEmpty() && canal.enviados.isEmpty())
    }

    @Test
    fun `el servidor caido cuenta como sin red`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns RespuestaDelServidor(503, "")

        assertTrue(maquina().cobrar(venta()) is InicioEnLaMaquina.Iniciado)
        assertNull(dao.todas().single().quoteId)
    }

    @Test
    fun `la maquina desconectada no recibe el cobro y no queda fila`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns cotizacion(18_000, 750)
        canal.conexion.value = Conexion.DESCONECTADA

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_NO_RESPONDE), maquina().cobrar(venta()))
        assertTrue(dao.todas().isEmpty())
    }

    @Test
    fun `la misma venta no abre un segundo intento`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns cotizacion(18_000, 750)
        val m = maquina()
        val ref = (m.cobrar(venta()) as InicioEnLaMaquina.Iniciado).referenceCC
        advanceTimeBy(3_001)   // acuse perdido: queda en duda
        runCurrent()
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)

        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_VENTA_CON_INTENTO), m.cobrar(venta()))
        assertTrue(m.ventaBloqueada("venta-1"))
    }

    // MARK: - Lo que se ve de la máquina

    @Test
    fun `ocupada y con poco cambio segun sus mensajes, y sin conexion no responde`() = runTest {
        val m = maquina()
        runCurrent()
        canal.llega("""{"comando":"sockets","cobrando":true,"Due":50,"Credit":0,"changeBar":"012220222000"}""")
        runCurrent()
        assertEquals(LaMaquinaAhora(Conexion.CONECTADA, ocupada = true, cambio = NivelDeCambio.POCO), m.ahora.value)

        canal.llega("""{"comando":"sockets","cobrando":false,"Due":0,"Credit":0,"changeBar":"022220222000"}""")
        canal.conexion.value = Conexion.DESCONECTADA
        runCurrent()
        assertEquals(Conexion.DESCONECTADA, m.ahora.value.conexion)
        assertEquals(NivelDeCambio.SUFICIENTE, m.ahora.value.cambio)
    }

    @Test
    fun `la fila de la maquina`() {
        val bien = LaMaquinaAhora(Conexion.CONECTADA, ocupada = false, cambio = NivelDeCambio.SUFICIENTE)
        assertEquals(FilaDeLaMaquina(visible = true, habilitada = true, motivo = null, pocoCambio = false),
            filaDeLaMaquina(bien, conProductos = true, ventaNueva = true, ventaEnDuda = false, cobroVivo = false))
        assertFalse("sin productos no aparece", filaDeLaMaquina(bien, false, true, false, false).visible)
        assertEquals(TEXTO_NO_RESPONDE, filaDeLaMaquina(bien.copy(conexion = Conexion.CONECTANDO), true, true, false, false).motivo)
        assertEquals(TEXTO_OCUPADA, filaDeLaMaquina(bien.copy(ocupada = true), true, true, false, false).motivo)
        assertEquals(TEXTO_OCUPADA, filaDeLaMaquina(bien, true, true, false, cobroVivo = true).motivo)
        assertEquals(TEXTO_VENTA_EN_DUDA, filaDeLaMaquina(bien, true, true, ventaEnDuda = true, cobroVivo = false).motivo)
        assertEquals(TEXTO_SOLO_VENTA_COMPLETA, filaDeLaMaquina(bien, true, ventaNueva = false, false, false).motivo)
        listOf(NivelDeCambio.POCO, NivelDeCambio.DESCONOCIDO).forEach { nivel ->
            val fila = filaDeLaMaquina(bien.copy(cambio = nivel), true, true, false, false)
            assertTrue("$nivel avisa", fila.pocoCambio)
            assertTrue("$nivel deja seguir", fila.habilitada)
        }
        assertFalse(filaDeLaMaquina(bien.copy(ocupada = true), true, true, false, false).habilitada)
    }

    @Test
    fun `la pantalla de resultado lee la fila - subida, con conflicto o pendiente`() = runTest {
        val m = maquina()
        val base = CobroEnMaquinaEntity("AQ000000000001", "venue-1", "venta-1", "{}", 1_000, 0, 1_000, null, null, 1, EstadoDelIntento.PAGADO)
        dao.insertar(base)
        dao.insertar(base.copy(referenceCC = "AQ000000000002", subido = true))
        dao.insertar(base.copy(referenceCC = "AQ000000000003", conflicto = "El pedido debe ser un objeto"))
        suspend fun estado(ref: String) = m.sincronizacion(ref).first()
        assertEquals(SincronizacionDelCobro.PENDIENTE, estado("AQ000000000001"))
        assertEquals(SincronizacionDelCobro.SINCRONIZADA, estado("AQ000000000002"))
        assertEquals(SincronizacionDelCobro.RECHAZADA, estado("AQ000000000003"))
    }

    // MARK: - El gerente resuelve (R4-4, R5#8)

    @Test
    fun `R5#8 la hora del comprobante es de hoy o de ayer en la zona del negocio, y sin hora es null`() {
        val ahora = java.time.ZonedDateTime.parse("2026-11-20T18:30:00-06:00[America/Mexico_City]")
        assertEquals(Instant.parse("2026-11-21T00:05:00Z"), com.avoqado.pos.maquina.ui.horaDelComprobante("18:05", ahora))
        assertEquals("todavía no llega: es de ayer", Instant.parse("2026-11-20T01:15:00Z"),
            com.avoqado.pos.maquina.ui.horaDelComprobante("19:15", ahora))
        assertEquals(Instant.parse("2026-11-20T15:07:00Z"), com.avoqado.pos.maquina.ui.horaDelComprobante("9:07", ahora))
        assertNull(com.avoqado.pos.maquina.ui.horaDelComprobante("", ahora))
        assertNull(com.avoqado.pos.maquina.ui.horaDelComprobante("las seis", ahora))
    }

    @Test
    fun `lo que teclea el gerente en pesos son centavos exactos`() {
        assertEquals(20_000, com.avoqado.pos.maquina.ui.centavosDeTexto("200"))
        assertEquals(18_750, com.avoqado.pos.maquina.ui.centavosDeTexto("$187.50"))
        assertEquals(150_000, com.avoqado.pos.maquina.ui.centavosDeTexto("1,500.00"))
        assertNull(com.avoqado.pos.maquina.ui.centavosDeTexto("12.345"))
        assertNull(com.avoqado.pos.maquina.ui.centavosDeTexto("-5"))
    }

    private suspend fun TestScope.dudaDe(m: MaquinaDeEfectivo): String {
        coEvery { api.cotizar(any(), any()) } returns cotizacion(18_000, 750)
        val ref = (m.cobrar(venta()) as InicioEnLaMaquina.Iniciado).referenceCC
        advanceTimeBy(3_001)
        runCurrent()
        return ref
    }

    @Test
    fun `sin cash-machine resolve en la sesion no se manda nada y se dice como entrar`() = runTest {
        every { sesion.venuePermissions } returns listOf("payments:create")
        val m = maquina()
        val ref = dudaDe(m)

        val r = m.resolver(ref, ResolucionDelGerente(Desenlace.PAGADO, 20_000, 1_250, null, null, "Ticket 0042"))

        assertEquals(ResultadoDeResolver.NoSe(TEXTO_SIN_PERMISO_RESOLVER), r)
        coVerify(exactly = 0) { api.resolverDuda(any(), any(), any()) }
    }

    @Test
    fun `cancelado sin saber si devolvio o guardo sigue esperando`() = runTest {
        val m = maquina()
        val ref = dudaDe(m)

        val r = m.resolver(ref, ResolucionDelGerente(Desenlace.CANCELADO, 5_000, 0, null, cancelMode = null, evidencia = "pantalla"))

        assertEquals(ResultadoDeResolver.NoSe(TEXTO_NO_SE_SABE_EL_MODO), r)
        coVerify(exactly = 0) { api.resolverDuda(any(), any(), any()) }
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
    }

    @Test
    fun `sin red la duda sigue esperando`() = runTest {
        val m = maquina()
        val ref = dudaDe(m)
        coEvery { api.resolverDuda(any(), any(), any()) } throws IOException("sin red")

        val r = m.resolver(ref, ResolucionDelGerente(Desenlace.PAGADO, 20_000, 1_250, null, null, "Ticket 0042"))

        assertEquals(ResultadoDeResolver.NoSe(TEXTO_SIN_RED_RESOLVER), r)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
    }

    @Test
    fun `P1 el gerente teclea lo que dice la maquina con la hora del comprobante y la subida se dispara`() = runTest {
        val m = maquina()
        val ref = dudaDe(m)
        val cuerpo = slot<JSONObject>()
        coEvery { api.resolverDuda("venue-1", ref, capture(cuerpo)) } returns RespuestaDelServidor(200, """{"success":true}""")
        val hora = Instant.parse("2026-11-20T18:05:00Z")

        val r = m.resolver(ref, ResolucionDelGerente(Desenlace.PAGADO, 20_000, 1_250, hora, null, " Ticket 0042 "))

        assertEquals(ResultadoDeResolver.Resuelto(Desenlace.PAGADO, 20_000, 1_250), r)
        val b = cuerpo.captured
        assertEquals("PAGADO", b.getString("outcome"))
        assertEquals(20_000 to 1_250, b.getInt("creditCents") to b.getInt("changeDispensedCents"))
        assertEquals("2026-11-20T18:05:00Z", b.getString("occurredAt"))
        assertEquals("Ticket 0042", b.getString("evidence"))
        assertTrue(b.isNull("cancelMode"))
        verify { subida.disparar() }
    }

    @Test
    fun `R5#8 sin la hora del comprobante va null y un GUARDADO lleva el modo 2`() = runTest {
        val m = maquina()
        val ref = dudaDe(m)
        val cuerpo = slot<JSONObject>()
        coEvery { api.resolverDuda(any(), any(), capture(cuerpo)) } returns RespuestaDelServidor(200, """{"success":true}""")

        m.resolver(ref, ResolucionDelGerente(Desenlace.GUARDADO, 5_000, 0, null, cancelMode = "2", evidencia = "se quedó con $50"))

        assertTrue("sin hora: hora y turno por conciliar, nunca la de inicio", cuerpo.captured.isNull("occurredAt"))
        assertEquals("2", cuerpo.captured.getString("cancelMode"))
        assertEquals("GUARDADO", cuerpo.captured.getString("outcome"))
    }

    @Test
    fun `un 422 del servidor se dice y la duda sigue`() = runTest {
        val m = maquina()
        val ref = dudaDe(m)
        coEvery { api.resolverDuda(any(), any(), any()) } returns RespuestaDelServidor(
            422, """{"message":"Los importes de este cierre de la máquina no son posibles.","code":"CASH_MACHINE_CLOSE_IMPOSSIBLE"}""",
        )

        val r = m.resolver(ref, ResolucionDelGerente(Desenlace.PAGADO, 100, 0, null, null, "x"))

        assertEquals(ResultadoDeResolver.NoSe("Los importes de este cierre de la máquina no son posibles."), r)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
    }
}
