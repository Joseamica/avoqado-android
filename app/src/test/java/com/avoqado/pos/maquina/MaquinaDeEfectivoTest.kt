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
import kotlinx.coroutines.async
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
 * C5: «Cobrar» con la máquina en la PC (R4-2, R5#4). La máquina contra un canal falso, la API de cotizar falsa (mockk) y el DAO sobre
 * SQLite de verdad ([CobroEnMaquinaSobreSqlite]). Sin red real: el reloj es el de `runTest`. Las dudas se resuelven en el panel (recorte
 * R2): aquí no hay «resolver».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaquinaDeEfectivoTest {

    private companion object {
        /** El `deviceId` de esta PC para el SERVIDOR. */
        const val ESTA_PC = "cterm00000000000000esta1"
    }

    internal class CanalFalso : CanalDeLaMaquina {
        override val conexion = MutableStateFlow(Conexion.CONECTADA)
        override val generacion = MutableStateFlow(1)
        override val recibidos = MutableSharedFlow<Recibido>(extraBufferCapacity = 64)
        val enviados = mutableListOf<String>()
        var alEnviar: (String) -> Unit = {}
        var conectadaA: String? = null

        val ultimaSenal = UltimaSenal()

        override fun enviar(json: String, generacion: Int): Boolean {
            if (conexion.value != Conexion.CONECTADA || generacion != this.generacion.value) return false
            alEnviar(json)
            enviados += json
            return true
        }

        override fun enviarSiSigueSiendoNuestro(json: String, generacion: Int, referenceCC: String, dueCents: Int): Boolean =
            ultimaSenal.esNuestra(generacion, referenceCC, dueCents) && enviar(json, generacion)

        override fun conectar(ip: String) { conectadaA = ip }
        override fun desconectar() { conectadaA = null }

        fun llega(json: String) {
            val r = Recibido(generacion.value, leerMensaje(json)!!, json)
            ultimaSenal.anotar(r)
            check(recibidos.tryEmit(r))
        }
        fun paycash(): JSONObject? = enviados.map(::JSONObject).lastOrNull { it.getString("comando") == "paycash" }
    }

    private val canal = CanalFalso()
    private val dao = CobroEnMaquinaSobreSqlite()
    private val contexto = AjustesDeLaMaquinaTest.contextoConPreferencias()
    private val prefs = MaquinaDeEfectivoPrefs(contexto).apply {
        guardar("192.168.1.50", activa = true, venueId = "venue-1")
        recordarDispositivo(ESTA_PC)   // lo aprendió el primer POST de esta PC (r3 #7)
    }
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
        every { venuePermissions } returns listOf("payments:create")
    }
    private val impresora = mockk<PrinterService>(relaxed = true)

    @After fun cerrar() = dao.close()

    private fun TestScope.maquina() = MaquinaDeEfectivo(
        prefs, boveda, dao, api, subida, turno, RoleManager(sesion), impresora, sesion, canal, backgroundScope,
        { testScheduler.currentTime }, Random(7), monotonico = { testScheduler.currentTime },
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
    fun `P1 Codex r1 #10 con la sesion en otra sucursal la maquina no cobra ni se conecta`() = runTest {
        every { sesion.venueId } returns "venue-2"
        val m = maquina()

        assertFalse("la credencial y la máquina son de la sucursal 1", m.activa())
        m.arrancar()
        runCurrent()
        assertNull(canal.conectadaA)
    }

    @Test
    fun `Codex r1 #7 el intento abierto o en duda de una venta, para que su pantalla lo recupere`() = runTest {
        val base = CobroEnMaquinaEntity("AQ000000000001", "venue-1", "venta-1", "{}", 1_000, 0, 1_000, null, null, 1, EstadoDelIntento.ESPERANDO)
        dao.insertar(base)
        dao.insertar(base.copy(referenceCC = "AQ000000000002", llaveDeVenta = "venta-2", creadoEn = 2))
        dao.insertar(base.copy(referenceCC = "AQ000000000003", llaveDeVenta = "venta-3", venueId = "venue-2"))
        dao.insertar(base.copy(referenceCC = "AQ000000000004", llaveDeVenta = "venta-4", estado = EstadoDelIntento.PAGADO))
        val m = maquina()

        assertEquals("AQ000000000001", m.intentoDeLaVenta("venta-1")?.referenceCC)
        assertNull(m.intentoDeLaVenta("venta-4"))
    }

    @Test
    fun `P1 Codex r1 #8 un CANCELADO o GUARDADO que resolvio el gerente libera la venta, un PAGADO o uno sin saber no`() = runTest {
        // Resueltas sin evidencia pendiente (`dudaSubida`): lo último que vio la PC ya está en el SERVIDOR (Codex código r3 #1).
        val base = CobroEnMaquinaEntity(
            "AQ000000000001", "venue-1", "venta-1", "{}", 1_000, 0, 1_000, null, null, 1, EstadoDelIntento.RESUELTO, dudaSubida = true,
        )
        dao.insertar(base.copy(desenlaceDelServidor = "CANCELADO"))
        dao.insertar(base.copy(referenceCC = "AQ000000000002", llaveDeVenta = "venta-2", desenlaceDelServidor = "GUARDADO"))
        dao.insertar(base.copy(referenceCC = "AQ000000000003", llaveDeVenta = "venta-3", desenlaceDelServidor = "PAGADO"))
        dao.insertar(base.copy(referenceCC = "AQ000000000004", llaveDeVenta = "venta-4"))
        val m = maquina()

        assertFalse("la máquina devolvió todo: la tarjeta sí", m.ventaBloqueada("venta-1"))
        assertFalse(m.ventaBloqueada("venta-2"))
        assertTrue(m.ventaBloqueada("venta-3"))
        assertTrue("sin saber cómo terminó, sigue frenada", m.ventaBloqueada("venta-4"))
        // Codex código r3 #1: un CANCELADO con evidencia posterior que el SERVIDOR todavía no tiene sigue frenado.
        dao.insertar(base.copy(referenceCC = "AQ000000000005", llaveDeVenta = "venta-5", desenlaceDelServidor = "CANCELADO", dudaSubida = false))
        assertTrue(m.ventaBloqueada("venta-5"))
        // Codex código POS r4 #1: la evidencia ya llegó, pero la máquina contó MÁS de lo que dice la resolución: sigue frenada.
        dao.insertar(
            base.copy(
                referenceCC = "AQ000000000006", llaveDeVenta = "venta-6", desenlaceDelServidor = "CANCELADO", creditCents = 10_000,
                respuestaServidor = """{"outcome":"CANCELADO","creditCents":0,"changeDispensedCents":0}""",
            ),
        )
        assertTrue("la máquina contó \$100 que «Cancelado, \$0» no dice", m.ventaBloqueada("venta-6"))
        dao.insertar(
            base.copy(
                referenceCC = "AQ000000000007", llaveDeVenta = "venta-7", desenlaceDelServidor = "CANCELADO", creditCents = 10_000,
                respuestaServidor = """{"outcome":"CANCELADO","creditCents":10000,"changeDispensedCents":10000}""",
            ),
        )
        assertFalse("la resolución cubre lo que entró: la tarjeta sí", m.ventaBloqueada("venta-7"))
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
    fun `P1 Codex r1 #1 mientras cotiza la venta ya esta apartada, y si la cotizacion no deja cobrar se suelta`() = runTest {
        val respuesta = kotlinx.coroutines.CompletableDeferred<RespuestaDelServidor>()
        coEvery { api.cotizar(any(), any()) } coAnswers { respuesta.await() }
        val m = maquina()

        val inicio = async { m.cobrar(venta()) }
        runCurrent()

        assertTrue("todavía no hay fila, pero la venta ya no sale con tarjeta", m.ventaBloqueada("venta-1"))
        assertTrue(dao.todas().isEmpty())
        respuesta.complete(cotizacion(18_035, 0, quoteId = null, cobrable = false, motivo = "CENTAVOS"))
        assertEquals(InicioEnLaMaquina.NoSeCobra(TEXTO_SIN_CENTAVOS), inicio.await())
        assertFalse("no quedó nada en la máquina: la tarjeta vuelve", m.ventaBloqueada("venta-1"))
    }

    @Test
    fun `Codex r1 #1 al salir el paycash la fila toma el lugar del apartado`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns cotizacion(18_000, 750)
        val m = maquina()

        assertTrue(m.cobrar(venta()) is InicioEnLaMaquina.Iniciado)

        assertFalse(m.apartada("venta-1"))
        assertTrue(m.ventaBloqueada("venta-1"))
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
        assertEquals(LaMaquinaAhora(Conexion.CONECTADA, ocupada = true, cambio = NivelDeCambio.POCO, changeBar = "012220222000"), m.ahora.value)

        // Codex código r2 H12: con TODAS las monedas en 2 (la regla del plan) hay cambio suficiente.
        canal.llega("""{"comando":"sockets","cobrando":false,"Due":0,"Credit":0,"changeBar":"222222222000"}""")
        canal.conexion.value = Conexion.DESCONECTADA
        runCurrent()
        assertEquals(Conexion.DESCONECTADA, m.ahora.value.conexion)
        assertEquals(NivelDeCambio.SUFICIENTE, m.ahora.value.cambio)
    }

    @Test
    fun `la fila de la maquina`() {
        val bien = LaMaquinaAhora(Conexion.CONECTADA, ocupada = false, cambio = NivelDeCambio.SUFICIENTE, changeBar = "222222222000")
        assertEquals(FilaDeLaMaquina(visible = true, habilitada = true, motivo = null, avisoDeCambio = null),
            filaDeLaMaquina(bien, conProductos = true, ventaNueva = true, ventaEnDuda = false, cobroVivo = false))
        assertFalse("sin productos no aparece", filaDeLaMaquina(bien, false, true, false, false).visible)
        assertEquals(TEXTO_NO_RESPONDE, filaDeLaMaquina(bien.copy(conexion = Conexion.CONECTANDO), true, true, false, false).motivo)
        assertEquals(TEXTO_OCUPADA, filaDeLaMaquina(bien.copy(ocupada = true), true, true, false, false).motivo)
        assertEquals(TEXTO_OCUPADA, filaDeLaMaquina(bien, true, true, false, cobroVivo = true).motivo)
        assertEquals(TEXTO_VENTA_EN_DUDA, filaDeLaMaquina(bien, true, true, ventaEnDuda = true, cobroVivo = false).motivo)
        assertEquals(TEXTO_SOLO_VENTA_COMPLETA, filaDeLaMaquina(bien, true, ventaNueva = false, false, false).motivo)
        listOf("021220222000", "122222222000", null).forEach { barra ->
            val fila = filaDeLaMaquina(bien.copy(changeBar = barra), true, true, false, false)
            assertEquals("$barra avisa", TEXTO_POCO_CAMBIO, fila.avisoDeCambio)
            assertTrue("$barra deja seguir", fila.habilitada)
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

    // MARK: - Comprobantes (C7, R5#9, R3#11)

    private val pagadoSubido = CobroEnMaquinaEntity(
        "AQ000000000001", "venue-1", "venta-1", "{}", 18_000, 0, 18_000, null, "q-1", 1, EstadoDelIntento.PAGADO,
        creditCents = 20_000, changeDispensedCents = 2_000, subido = true, cierreEn = 5,
    )

    @Test
    fun `R5#9 el comprobante de diferencia sale del excedente del servidor, con el mismo folio`() {
        assertNull("sin respuesta todavía", comprobanteDeLaMaquina(pagadoSubido, "LG"))
        assertNull("sin excedente", comprobanteDeLaMaquina(pagadoSubido.copy(respuestaServidor = """{"outcome":"PAGADO","excedenteCents":0}"""), "LG"))

        val c = comprobanteDeLaMaquina(pagadoSubido.copy(respuestaServidor = """{"outcome":"PAGADO","excedenteCents":500}""", aDeberCents = 500), "LG")!!

        assertEquals("AQ000000000001", c.orderNumber)
        assertEquals(com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA, c.kind)
        assertEquals(
            com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine("AQ000000000001", 500, title = "COMPROBANTE DE DIFERENCIA"),
            c.cashMachine,
        )
        assertNull("GUARDADO nunca", comprobanteDeLaMaquina(pagadoSubido.copy(estado = EstadoDelIntento.GUARDADO, creditCents = 5_000, changeDispensedCents = 0, aDeberCents = 5_000), "LG"))
        assertEquals(1_000L, comprobanteDeLaMaquina(pagadoSubido.copy(estado = EstadoDelIntento.CANCELADO, creditCents = 5_000, changeDispensedCents = 4_000, aDeberCents = 1_000), "LG")!!.cashMachine!!.owedCents)
        assertEquals("el desenlace del gerente sale de su respuesta", 700L,
            comprobanteDeLaMaquina(pagadoSubido.copy(estado = EstadoDelIntento.RESUELTO, respuestaServidor = """{"outcome":"PAGADO","excedenteCents":700}""", aDeberCents = 700), "LG")!!.cashMachine!!.owedCents)
        val cambio = comprobanteDeLaMaquina(pagadoSubido.copy(aDeberCents = 500), "LG")!!
        assertEquals("Codex r1 #13: también el cambio físico que no dio", 500L, cambio.cashMachine!!.owedCents)
        assertEquals("COMPROBANTE DE LA MÁQUINA", cambio.cashMachine!!.title)
    }

    @Test
    fun `P1 Codex r1 #13 la deuda vieja no se pierde detras de 60 cobros que no deben, y se pagina`() = runTest {
        dao.insertar(pagadoSubido.copy(referenceCC = "AQ0000000000ZZ", creadoEn = 0, creditCents = 10_000, dueCents = 8_000, saleCents = 8_000, changeDispensedCents = 1_500, aDeberCents = 500))
        (1..60).forEach { i -> dao.insertar(pagadoSubido.copy(referenceCC = "AQ" + i.toString().padStart(12, '0'), creadoEn = i.toLong())) }
        val m = maquina()

        assertEquals(listOf("AQ0000000000ZZ" to 500), m.comprobantes().map { it.first.referenceCC to it.second })
        assertEquals(1, m.cuantosComprobantes())
        (1..60).forEach { i -> dao.insertar(pagadoSubido.copy(referenceCC = "AQD" + i.toString().padStart(11, '0'), creadoEn = 100L + i, aDeberCents = 100)) }
        assertEquals(50, m.comprobantes().size)
        assertEquals(61, m.cuantosComprobantes())
        assertEquals("con «Ver más» llega a la vieja", "AQ0000000000ZZ", m.comprobantes(limite = 100).last().first.referenceCC)
    }

    @Test
    fun `los comprobantes son los de la sucursal que deben algo y se reimprimen`() = runTest {
        dao.insertar(pagadoSubido.copy(respuestaServidor = """{"outcome":"PAGADO","excedenteCents":500}""", aDeberCents = 500))
        dao.insertar(pagadoSubido.copy(referenceCC = "AQ000000000002", respuestaServidor = """{"outcome":"PAGADO","excedenteCents":0}"""))
        dao.insertar(pagadoSubido.copy(referenceCC = "AQ000000000003", estado = EstadoDelIntento.CANCELADO, creditCents = 5_000, changeDispensedCents = 0, creadoEn = 2, aDeberCents = 5_000))
        dao.insertar(pagadoSubido.copy(referenceCC = "AQ000000000004", venueId = "venue-2", respuestaServidor = """{"outcome":"PAGADO","excedenteCents":900}""", aDeberCents = 900))
        coEvery { impresora.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.Printed(1)
        coEvery { impresora.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val m = maquina()

        assertEquals(listOf("AQ000000000003" to 5_000, "AQ000000000001" to 500), m.comprobantes().map { it.first.referenceCC to it.second })
        // Codex código POS r4 #10: el de diferencia de una venta pasa por el MISMO reclamo del folio: un solo original, luego «REIMPRESIÓN».
        assertEquals(PrinterService.PrintOutcome.Printed(1), m.imprimirComprobante("AQ000000000001"))
        assertEquals(PrinterService.PrintOutcome.Printed(1), m.imprimirComprobante("AQ000000000001"))
        assertNull("no debe nada", m.imprimirComprobante("AQ000000000002"))
        coVerify(exactly = 1) { impresora.imprimirOriginal(match { it.orderNumber == "AQ000000000001" }) }
        coVerify(exactly = 1) {
            impresora.manualPrintReceipt(
                match { it.orderNumber == "AQ000000000001" && it.reprintedAt != null && it.cashMachine?.title == "COMPROBANTE DE DIFERENCIA" },
            )
        }
        // Codex código r3 #11: el de un CANCELADO ES el papel de su folio: el original una vez, después copias marcadas.
        assertEquals(PrinterService.PrintOutcome.Printed(1), m.imprimirComprobante("AQ000000000003"))
        assertEquals(PrinterService.PrintOutcome.Printed(1), m.imprimirComprobante("AQ000000000003"))
        coVerify(exactly = 1) { impresora.imprimirOriginal(match { it.orderNumber == "AQ000000000003" }) }
        coVerify(exactly = 1) { impresora.manualPrintReceipt(match { it.orderNumber == "AQ000000000003" && it.reprintedAt != null }) }
    }

    @Test
    fun `r6-6 cada cobro congela el emparejamiento vigente al empezar`() = runTest {
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1", bindingId = "cbind0000000000000000000a")
        coEvery { api.cotizar(any(), any()) } returns cotizacion(18_000, 750)
        val m = maquina()

        val ref = (m.cobrar(venta()) as InicioEnLaMaquina.Iniciado).referenceCC
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1", bindingId = "cbind0000000000000000000b")

        assertEquals("cbind0000000000000000000a", dao.fila(ref)!!.bindingId)
    }

    @Test
    fun `P1 Codex r4 #10 si el ticket del folio ya salio, el comprobante de diferencia sale como REIMPRESION, nunca como otro original`() = runTest {
        dao.insertar(
            pagadoSubido.copy(
                ticketImpreso = TicketDelCobro.IMPRESO, aDeberCents = 500,
                respuestaServidor = """{"outcome":"PAGADO","excedenteCents":500}""",
            ),
        )
        coEvery { impresora.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val m = maquina()

        assertEquals(PrinterService.PrintOutcome.Printed(1), m.imprimirComprobante("AQ000000000001"))

        coVerify(exactly = 0) { impresora.imprimirOriginal(any()) }
        coVerify(exactly = 1) { impresora.manualPrintReceipt(match { it.reprintedAt != null && it.cashMachine?.owedCents == 500L }) }
    }

    @Test
    fun `P1 Codex r4 #11 el ticket armado de la fila lleva el IVA cobrado aparte y quien atendio, como el de la caja`() = runTest {
        coEvery { api.cotizar(any(), any()) } returns cotizacion(18_000, 750)
        val m = maquina()
        val ref = (
            m.cobrar(
                venta().copy(
                    renglonesLocales = """[{"name":"Café","quantity":1,"unitCents":15520,"totalCents":15520}]""",
                    impuestoAgregadoCents = 2_480, atendio = "Ana",
                ),
            ) as InicioEnLaMaquina.Iniciado
            ).referenceCC
        dao.cerrar(ref, EstadoDelIntento.PAGADO, 20_000, 1_250, 5)

        val t = ticketDeLaFila(dao.fila(ref)!!, "La Galeterie")

        assertEquals("Ana", t.cashierName)
        assertEquals("el IVA aparte, no en cero", 2_480, t.taxAmount)
        assertEquals(15_520 to 18_750, t.subtotal to t.total)
        assertEquals(listOf("Café"), t.items.map { it.name })
        assertEquals("los renglones se siguen leyendo igual", listOf("Café"), renglonesDelTicket(dao.fila(ref)!!.renglonesJson)!!.map { it.name })
    }

    @Test
    fun `P1 Codex r2 H9 la impresion automatica y dos toques del boton al mismo tiempo sacan UN solo original`() = runTest {
        dao.insertar(pagadoSubido.copy(subido = false))
        val papel = kotlinx.coroutines.CompletableDeferred<PrinterService.PrintOutcome>()
        coEvery { impresora.imprimirOriginal(any()) } coAnswers { papel.await() }
        val m = maquina()
        val ticket = ticketDeLaFila(dao.fila("AQ000000000001")!!, "La Galeterie")

        val automatica = async { m.imprimirPapelDelFolio("AQ000000000001", ticket) }
        val boton = async { m.imprimirTicketsPendientes() }
        val otraVez = async { m.imprimirTicketsPendientes() }
        runCurrent()
        assertEquals("mientras imprime, no se ofrece", 0, m.ticketsSinImprimir().first())
        papel.complete(PrinterService.PrintOutcome.Printed(1))
        automatica.await(); boton.await(); otraVez.await()

        coVerify(exactly = 1) { impresora.imprimirOriginal(any()) }
        assertEquals(TicketDelCobro.IMPRESO, dao.fila("AQ000000000001")!!.ticketImpreso)
        assertNull("ya salió: no hay otro original", m.imprimirPapelDelFolio("AQ000000000001", ticket))
    }

    @Test
    fun `P1 Codex r2 H9 si la PC murio imprimiendo, el ticket se ofrece como REIMPRESION, nunca como otro original`() = runTest {
        dao.insertar(pagadoSubido.copy(ticketImpreso = TicketDelCobro.IMPRIMIENDO))
        val impresos = mutableListOf<com.avoqado.pos.printing.data.model.ReceiptData>()
        coEvery { impresora.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.OutOfPaper andThen PrinterService.PrintOutcome.Printed(1)
        val m = maquina()
        assertEquals(1, m.ticketsSinImprimir().first())

        assertEquals(0, m.imprimirTicketsPendientes())
        assertEquals("sin papel: la copia se sigue ofreciendo", TicketDelCobro.IMPRIMIENDO, dao.fila("AQ000000000001")!!.ticketImpreso)
        assertEquals(1, m.imprimirTicketsPendientes())

        assertTrue("las dos salieron marcadas como copia", impresos.all { it.reprintedAt != null })
        assertEquals(TicketDelCobro.IMPRESO, dao.fila("AQ000000000001")!!.ticketImpreso)
        assertEquals(0, m.ticketsSinImprimir().first())
    }

    @Test
    fun `P1 Codex r2 H10 un PAGADO que el servidor dejo como incidencia no ofrece ticket de venta sino el comprobante del dinero`() = runTest {
        dao.insertar(
            pagadoSubido.copy(
                conflicto = "Registrado como incidencia: la venta ya no se pudo armar", aDeberCents = 500,
                respuestaServidor = """{"ack":"TERMINADO","incident":"NO_SE_PUDO_REGISTRAR","owedCents":500}""",
            ),
        )
        val impresos = mutableListOf<com.avoqado.pos.printing.data.model.ReceiptData>()
        coEvery { impresora.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val m = maquina()

        assertEquals(1, m.imprimirTicketsPendientes())

        val papel = impresos.single()
        assertEquals(com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA, papel.kind)
        assertTrue("sin renglones de venta", papel.items.isEmpty())
        assertEquals(500L, papel.cashMachine!!.owedCents)
        assertEquals(TicketDelCobro.IMPRESO, dao.fila("AQ000000000001")!!.ticketImpreso)
    }

    @Test
    fun `P1 Codex r1 #12 un PAGADO cuyo ticket no salio se ofrece con un boton y el que no imprime sigue pendiente`() = runTest {
        val pagado = pagadoSubido.copy(
            subido = false, creditCents = 20_000, changeDispensedCents = 1_000, quoteId = null,
            renglonesJson = """[{"name":"Café","quantity":2,"unitCents":9000,"totalCents":18000}]""",
        )
        dao.insertar(pagado)
        dao.insertar(pagado.copy(referenceCC = "AQ000000000002", ticketImpreso = TicketDelCobro.IMPRESO))
        val impresos = mutableListOf<com.avoqado.pos.printing.data.model.ReceiptData>()
        coEvery { impresora.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.OutOfPaper andThen PrinterService.PrintOutcome.Printed(1)
        val m = maquina()
        assertEquals(1, m.ticketsSinImprimir().first())

        assertEquals("sin papel: sigue pendiente", 0, m.imprimirTicketsPendientes())
        assertEquals(1, m.ticketsSinImprimir().first())
        assertEquals(1, m.imprimirTicketsPendientes())
        assertEquals(0, m.ticketsSinImprimir().first())

        val t = impresos.last()
        assertEquals("AQ000000000001", t.orderNumber)
        assertEquals(listOf("Café" to 18_000), t.items.map { it.name to it.totalPrice })
        assertEquals(20_000 to 1_000, t.cashTendered to t.changeAmount)
        assertEquals(com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine("AQ000000000001", 1_000, priceToConfirm = true), t.cashMachine)
    }

    @Test
    fun `P1 Codex r1 #14 la cotizacion congela sus renglones y una vencida antes del paycash se vuelve a pedir`() = runTest {
        var pedidas = 0
        coEvery { api.cotizar(any(), any()) } answers {
            pedidas++
            RespuestaDelServidor(200, JSONObject(cotizacion(18_000, 750).cuerpo)
                .put("renglones", org.json.JSONArray().put(JSONObject().put("name", "Café del servidor").put("quantity", 1).put("unitCents", 18_000).put("totalCents", 18_000)))
                .toString())
        }
        // La PC se pausa entre cotizar y mandar el cobro: pasa la vigencia (el reloj de la PC salta 11 min la primera vez).
        var ahora = 0L
        var saltos = 0
        coEvery { turno.turnoActual() } answers { if (saltos++ == 0) ahora += 11 * 60_000L; "turno-1" }
        val m = MaquinaDeEfectivo(
            prefs, boveda, dao, api, subida, turno, RoleManager(sesion), impresora, sesion, canal, backgroundScope, { ahora }, Random(7),
            monotonico = { ahora },
        )

        val r = m.cobrar(venta().copy(renglonesLocales = """[{"name":"Café local","quantity":1,"unitCents":17000,"totalCents":17000}]"""))

        assertTrue(r is InicioEnLaMaquina.Iniciado)
        assertEquals("se volvió a cotizar", 2, pedidas)
        assertEquals(listOf("Café del servidor"), renglonesDelTicket(dao.todas().single().renglonesJson)!!.map { it.name })
    }

    @Test
    fun `P1 Codex r2 H11 la vigencia se vuelve a ver DESPUES de escribir en disco, justo antes del paycash`() = runTest {
        var pedidas = 0
        coEvery { api.cotizar(any(), any()) } answers { pedidas++; cotizacion(18_000, 750) }
        var monotono = 0L
        var pausas = 0
        // La PC se pausa entre guardar el intento y mandar el `paycash` (sólo la primera vez): la cotización venció.
        dao.alInsertar = { if (pausas++ == 0) monotono += 10 * 60_000L }
        val m = MaquinaDeEfectivo(
            prefs, boveda, dao, api, subida, turno, RoleManager(sesion), impresora, sesion, canal, backgroundScope,
            // La hora de Windows se atrasa una hora: no alarga nada (la vigencia es del reloj monótono).
            { 1_000_000_000L - monotono * 6 }, Random(7), monotonico = { monotono },
        )

        val r = m.cobrar(venta())

        assertTrue(r is InicioEnLaMaquina.Iniciado)
        assertEquals("se volvió a cotizar", 2, pedidas)
        assertEquals("el intento vencido no deja fila", 1, dao.todas().size)
        assertEquals("y un solo paycash, el de la cotización vigente", 1, canal.enviados.count { JSONObject(it).getString("comando") == "paycash" })
    }

    // MARK: - Codex código r3

    @Test
    fun `P1 Codex r3 #12 con el 202 definitivo ya en la fila, el primer papel es el comprobante del dinero aunque la caja traiga un ticket`() = runTest {
        dao.insertar(
            pagadoSubido.copy(
                conflicto = "Registrado como incidencia: la venta ya no se pudo armar",
                respuestaServidor = """{"ack":"TERMINADO","incident":"NO_SE_PUDO_REGISTRAR"}""",
            ),
        )
        val impresos = mutableListOf<com.avoqado.pos.printing.data.model.ReceiptData>()
        coEvery { impresora.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val m = maquina()
        val ticketDeLaCaja = ticketDeLaFila(dao.fila("AQ000000000001")!!, "La Galeterie")

        m.imprimirPapelDelFolio("AQ000000000001", ticketDeLaCaja)

        assertEquals(com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA, impresos.single().kind)
    }
}
