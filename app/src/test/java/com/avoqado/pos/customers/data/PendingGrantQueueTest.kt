package com.avoqado.pos.customers.data

import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.RespuestaDeEntrega
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.PendingGrant.Estado
import com.avoqado.pos.payment.data.ConsultaDeEstado
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4 (5-nov, promesa en `docs/audit/2026-11-05-paquete-pos/`): un paquete vendido se entrega UNA vez, y nunca se entrega si
 * no hubo cobro. La fila nace antes de cobrar; cada intento de cobro se anota antes de salir; lo que sigue lo decide su evidencia.
 */
class PendingGrantQueueTest {

    private val disco = PrefsEnMemoria()
    private val venue = "venue-A"
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns venue }
    private val articulos = mockk<ArticlesRepository>(relaxed = true).also {
        every { it.creditPacks } returns MutableStateFlow(
            listOf(CreditPack(id = "pack-1", name = "10 clases", price = 500.0, saleProof = "firma-1")),
        )
    }
    private val pagos = mockk<PendingPaymentDao>(relaxed = true).also { coEvery { it.findForVenue(any(), any()) } returns null }
    private val terminal = mockk<TerminalPaymentService>(relaxed = true)
    private val conRed = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns conRed
        every { it.isServerReachable } returns conRed
    }

    private var ahora = 1_000_000L

    private fun cola() = PendingGrantQueue(disco.context, articulos, storage, pagos, terminal, red).apply {
        io = Dispatchers.Unconfined
        reloj = { ahora }
    }

    private val linea = LineaDePaquete(linea = "linea-1", packId = "pack-1", nombre = "10 clases", precioCents = 50000)

    private fun PendingGrantQueue.armada(venta: String = "venta-1"): PendingGrant {
        assertTrue(armar(venta, listOf(linea), "cli-1", "Ana"))
        return filas.value.single { it.venta == venta }
    }

    private fun entrega(code: Int?, mensaje: String? = null) {
        coEvery { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) } returns RespuestaDeEntrega(code, mensaje)
    }

    private fun tarjeta(status: String, paymentId: String? = null) {
        coEvery { terminal.consultarEstado(any(), any(), any()) } returns
            ConsultaDeEstado(ChargeStatusProbe.Known(status = status, inProgress = false, paymentId = paymentId), sinRespuesta = false)
    }

    private fun filaDeEfectivo(id: String, estado: PaymentSyncStatus) = PendingPaymentEntity(
        id = id, venueId = venue, staffId = "s", amountCents = 50000, tipCents = 0, method = "CASH", paymentType = "FAST", syncStatus = estado.name,
    )

    /** La app muere: en el siguiente arranque sólo queda lo que estaba en disco. */
    private fun reiniciar(): PendingGrantQueue {
        disco.guardado.clear()
        disco.guardado.putAll(disco.enDisco)
        return cola()
    }

    // MARK: - La fila nace antes de cobrar

    @Test
    fun `armar escribe en disco la obligacion completa antes de cobrar`() {
        val q = cola()
        val fila = q.armada()

        assertEquals(Estado.POR_COBRAR, fila.estado)
        assertEquals("pack-1", fila.packId)
        assertEquals("cli-1", fila.customerId)
        assertEquals(venue, fila.venueId)
        assertEquals("10 clases", fila.nombre)
        assertEquals(50000, fila.precioCents)
        assertEquals("firma-1", fila.saleProof)
        assertTrue(fila.id.isNotBlank())
        // commit, no apply: sobrevive a que el proceso muera un instante después.
        assertEquals(1, reiniciar().filas.value.size)
    }

    @Test
    fun `si el disco no acepta la obligacion, no se puede abrir el cobro`() {
        disco.discoSano = false

        assertFalse(cola().armar("venta-1", listOf(linea), "cli-1", "Ana"))
        assertTrue(cola().filas.value.isEmpty())
    }

    @Test
    fun `reabrir el cobro de la misma venta conserva la llave y los intentos`() {
        val q = cola()
        val primera = q.armada()
        assertTrue(q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1")))

        val segunda = q.armada()

        assertEquals(primera.id, segunda.id)
        assertEquals(listOf(IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1")), segunda.intentos)
    }

    @Test
    fun `otra venta borra la anterior si nunca intento cobrar`() {
        val q = cola()
        q.armada("venta-1")

        q.armar("venta-2", emptyList(), null, null)

        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `otra venta conserva la anterior si ya intento cobrar`() {
        val q = cola()
        q.armada("venta-1")
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))

        q.armar("venta-2", emptyList(), null, null)

        assertEquals(1, q.filas.value.size)
    }

    @Test
    fun `quitar el paquete del carrito antes de cobrar lo borra`() {
        val q = cola()
        q.armada("venta-1")

        q.armar("venta-1", emptyList(), "cli-1", "Ana")

        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `anotar un intento de una venta sin paquetes no toca nada`() {
        val q = cola()
        assertTrue(q.anotarIntento("venta-sin-paquetes", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave")))
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `una venta con paquetes sin su fila no deja salir el cobro`() {
        val q = cola()

        assertFalse(q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"), llevaPaquetes = true))
        assertTrue(q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"), llevaPaquetes = false))
    }

    @Test
    fun `un barrido que cruza con abrir el cobro no borra la fila recien armada`() = runTest {
        val q = cola()
        // El barrido corre en el instante en que `armar` publica la fila (antes, la venta quedaba en pantalla después).
        backgroundScope.launch(Dispatchers.Unconfined) {
            q.filas.collect { if (it.isNotEmpty()) q.drenar() }
        }

        assertTrue(q.armar("venta-1", listOf(linea), "cli-1", "Ana"))

        assertEquals(1, q.filas.value.size)
        assertTrue(q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"), llevaPaquetes = true))
    }

    @Test
    fun `si el disco no acepta el intento, el cobro no sale`() {
        val q = cola()
        q.armada()
        disco.discoSano = false

        assertFalse(q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave")))
    }

    // MARK: - La entrega

    @Test
    fun `cobrada, se entrega con su llave y su foto, y sale de la cola`() = runTest {
        val q = cola()
        val fila = q.armada()
        entrega(201)

        q.marcarCobrada("venta-1")
        val desenlaces = q.entregarVenta("venta-1")

        assertEquals(listOf(DesenlaceDeEntrega.ENTREGADA), desenlaces)
        coVerify(exactly = 1) { articulos.sellPackToCustomer("pack-1", "cli-1", true, fila.id, "firma-1", true) }
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `se perdio la respuesta - el reintento lleva LA MISMA llave`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.marcarCobrada("venta-1")
        entrega(null)

        assertEquals(listOf(DesenlaceDeEntrega.SIN_RED), q.entregarVenta("venta-1"))
        assertEquals(Estado.POR_ENTREGAR, q.filas.value.single().estado)

        // La app muere y vuelve la red: la cola la termina sola, con la llave de siempre.
        val otra = reiniciar()
        entrega(201)
        otra.drenar()

        coVerify(exactly = 2) { articulos.sellPackToCustomer("pack-1", "cli-1", true, fila.id, "firma-1", true) }
        assertTrue(otra.filas.value.isEmpty())
    }

    @Test
    fun `sin red no se manda nada y la fila espera`() = runTest {
        val q = cola()
        q.armada()
        q.marcarCobrada("venta-1")
        conRed.value = false

        q.drenar()

        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
        assertEquals(1, q.porEntregar.first())
    }

    @Test
    fun `una respuesta tardia de fallo no revive una fila ya entregada`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.marcarCobrada("venta-1")
        coEvery { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) } coAnswers {
            q.borrarParaPruebas(fila.id)
            RespuestaDeEntrega(503, null)
        }

        q.entregarVenta("venta-1")

        assertTrue(q.filas.value.isEmpty())
    }

    // MARK: - Los tres desenlaces, por código

    @Test
    fun `la clasificacion no confunde un transitorio con un rechazo`() {
        assertEquals(DesenlaceDeEntrega.ENTREGADA, clasificarEntrega(200))
        assertEquals(DesenlaceDeEntrega.ENTREGADA, clasificarEntrega(201))
        assertEquals(DesenlaceDeEntrega.SIN_RED, clasificarEntrega(null))
        assertEquals(DesenlaceDeEntrega.SESION_VENCIDA, clasificarEntrega(401))
        for (c in listOf(408, 429, 500, 502, 503)) assertEquals("$c", DesenlaceDeEntrega.REINTENTAR, clasificarEntrega(c))
        for (c in listOf(400, 403, 404, 409, 422)) assertEquals("$c", DesenlaceDeEntrega.RECHAZADA, clasificarEntrega(c))
    }

    @Test
    fun `un rechazo de verdad no se pierde - lo ve una persona con su motivo y no se reintenta solo`() = runTest {
        val q = cola()
        q.armada()
        q.marcarCobrada("venta-1")
        entrega(400, "El paquete no está activo")

        q.drenar()
        q.drenar()

        val fila = q.filas.value.single()
        assertEquals(Estado.RECHAZADA, fila.estado)
        assertEquals("El paquete no está activo", fila.motivo)
        assertEquals(1, q.porRevisar.first())
        coVerify(exactly = 1) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Reintentar de la cuarentena lleva la misma llave`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.marcarCobrada("venta-1")
        entrega(400, "No activo")
        q.drenar()
        entrega(201)

        assertEquals(DesenlaceDeEntrega.ENTREGADA, q.reintentar(fila.id))

        coVerify(exactly = 2) { articulos.sellPackToCustomer("pack-1", "cli-1", true, fila.id, "firma-1", true) }
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `Descartar de la cuarentena sale de la cola`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.marcarCobrada("venta-1")
        entrega(404, "Cliente no encontrado")
        q.drenar()

        assertTrue(q.descartar(fila.id))

        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `Descartar no toca lo que no esta por revisar`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.marcarCobrada("venta-1")

        assertFalse(q.descartar(fila.id))
        assertEquals(1, q.filas.value.size)
    }

    // MARK: - Cómo se sabe si el cobro sucedió (la app murió antes de marcarla)

    @Test
    fun `murio al abrir el cobro sin intentar nada - se borra y no se entrega`() = runTest {
        val q = cola()
        q.armada()

        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `murio con el efectivo guardado en la cola - se cobro y se entrega, tambien sin red`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { pagos.findForVenue(venue, "llave") } returns filaDeEfectivo("llave", PaymentSyncStatus.PENDING)
        conRed.value = false

        val otra = reiniciar()
        otra.drenar()

        assertEquals(Estado.POR_ENTREGAR, otra.filas.value.single().estado)
        conRed.value = true
        entrega(201)
        otra.drenar()
        assertTrue(otra.filas.value.isEmpty())
    }

    @Test
    fun `efectivo apartado sin resolver - se espera, no se entrega ni se borra`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { pagos.findForVenue(venue, "llave") } returns filaDeEfectivo("llave", PaymentSyncStatus.EN_VUELO)

        val otra = reiniciar()
        otra.drenar()

        assertEquals(Estado.POR_COBRAR, otra.filas.value.single().estado)
        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sin fila de efectivo y el servidor dice que no hay cobro - se borra`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { articulos.cobroEnEfectivoPorLlave(venue, "llave") } returns false

        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
    }

    @Test
    fun `sin fila de efectivo y el servidor dice que si hay cobro - se entrega`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { articulos.cobroEnEfectivoPorLlave(venue, "llave") } returns true
        entrega(201)

        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
        coVerify(exactly = 1) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sin fila de efectivo y sin poder preguntar - no se borra`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { articulos.cobroEnEfectivoPorLlave(venue, "llave") } returns null

        val otra = reiniciar()
        otra.drenar()

        assertEquals(1, otra.filas.value.size)
    }

    @Test
    fun `otra forma de pago sin fila y sin cobro en efectivo - no se concluye que no se cobro`() = runTest {
        // by-key sólo prueba efectivo puro: que no encuentre un pago de «Uber Eats» no dice nada.
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.OTRA_FORMA, "llave"))
        coEvery { articulos.cobroEnEfectivoPorLlave(venue, "llave") } returns false

        val otra = reiniciar()
        otra.drenar()

        assertEquals(1, otra.filas.value.size)
    }

    @Test
    fun `tarjeta cobrada mientras la app estaba muerta - se entrega`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        tarjeta("COMPLETED", paymentId = "pay-1")
        entrega(201)

        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
        coVerify(exactly = 1) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tarjeta rechazada o cancelada - se borra sin entregar`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        tarjeta("FAILED")

        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tarjeta 404 no prueba que no se cobro - la fila sigue`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        coEvery { terminal.consultarEstado(any(), any(), any()) } returns ConsultaDeEstado(ChargeStatusProbe.NotFound, sinRespuesta = false)

        val otra = reiniciar()
        otra.drenar()

        assertEquals(Estado.POR_COBRAR, otra.filas.value.single().estado)
    }

    @Test
    fun `con varios intentos basta un SI`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "rechazada"))
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        tarjeta("FAILED")
        coEvery { pagos.findForVenue(venue, "llave") } returns filaDeEfectivo("llave", PaymentSyncStatus.SYNCED)
        entrega(201)

        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
        coVerify(exactly = 1) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `el orden no importa - un NO primero no borra si despues hay un SI`() {
        assertEquals(Cobro.SI, decidirCobro(listOf(Cobro.NO, Cobro.SI)))
        assertEquals(Cobro.SI, decidirCobro(listOf(Cobro.NO_SE_SABE, Cobro.SI)))
        assertEquals(Cobro.NO_SE_SABE, decidirCobro(listOf(Cobro.NO, Cobro.NO_SE_SABE)))
        assertEquals(Cobro.NO, decidirCobro(listOf(Cobro.NO, Cobro.NO)))
        assertEquals(Cobro.NO, decidirCobro(emptyList()))
    }

    // MARK: - Mientras la venta sigue en pantalla

    @Test
    fun `con la venta en pantalla un NO no borra - la cajera puede reintentar con otro metodo`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        tarjeta("FAILED")

        q.drenar()

        assertEquals(fila.id, q.filas.value.single().id)
        assertEquals(Estado.POR_COBRAR, q.filas.value.single().estado)
    }

    @Test
    fun `con la venta en pantalla sin intentos no se borra`() = runTest {
        val q = cola()
        q.armada()

        q.drenar()

        assertEquals(1, q.filas.value.size)
    }

    @Test
    fun `salir con la tarjeta en duda y que luego cobre - la venta siguiente no la pierde`() = runTest {
        val q = cola()
        q.armada("venta-1")
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        tarjeta("PROCESSING")
        q.armar("venta-2", emptyList(), null, null)
        q.drenar()
        assertEquals(1, q.filas.value.size)

        tarjeta("COMPLETED", paymentId = "pay-1")
        entrega(201)
        q.drenar()

        assertTrue(q.filas.value.isEmpty())
        coVerify(exactly = 1) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `el intento que el servidor rechazo sin crearlo se quita - su 404 no es una duda`() = runTest {
        val q = cola()
        q.armada()
        val intento = IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1")
        q.anotarIntento("venta-1", intento)

        q.quitarIntento("venta-1", intento)

        assertTrue(q.filas.value.single().intentos.isEmpty())
    }

    @Test
    fun `una evidencia que truena no tumba la pasada ni borra la fila`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { pagos.findForVenue(any(), any()) } throws IllegalStateException("base ocupada")

        val otra = reiniciar()
        otra.drenar()

        assertEquals(Estado.POR_COBRAR, otra.filas.value.single().estado)
    }

    @Test
    fun `entregar la venta no manda lo de otra sucursal por la sesion de ahora`() = runTest {
        val q = cola()
        q.armada()
        q.marcarCobrada("venta-1")
        every { storage.venueId } returns "venue-B"

        assertTrue(q.entregarVenta("venta-1").isEmpty())

        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    // MARK: - Codex r1

    @Test
    fun `un NO viejo no borra lo que ya consta como cobrado mientras se preguntaba`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        q.soltarVenta()
        coEvery { articulos.cobroEnEfectivoPorLlave(venue, "llave") } coAnswers {
            q.marcarCobrada("venta-1")
            false
        }
        conRed.value = true
        entrega(null)

        q.drenar()

        assertEquals(Estado.POR_ENTREGAR, q.filas.value.single().estado)
    }

    @Test
    fun `un NO viejo no borra una venta que volvio a la pantalla mientras se preguntaba`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        q.soltarVenta()
        coEvery { terminal.consultarEstado(any(), any(), any()) } coAnswers {
            q.armada()
            ConsultaDeEstado(ChargeStatusProbe.Known(status = "FAILED", inProgress = false), sinRespuesta = false)
        }

        q.drenar()

        assertEquals(1, q.filas.value.size)
    }

    @Test
    fun `reabrir con OTRO cliente no le entrega a ese cliente el cobro del primero`() = runTest {
        val q = cola()
        val deAna = q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-ana"))

        assertTrue(q.armar("venta-1", listOf(linea), "cli-beatriz", "Beatriz"))
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave-beatriz"))

        val ana = q.filas.value.single { it.id == deAna.id }
        val beatriz = q.filas.value.single { it.id != deAna.id }
        assertEquals("cli-1", ana.customerId)
        assertEquals(listOf(IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-ana")), ana.intentos)
        assertNull(ana.venta)
        assertEquals("cli-beatriz", beatriz.customerId)
        assertEquals(listOf(IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave-beatriz")), beatriz.intentos)

        // El cobro de Beatriz se confirma: sólo lo suyo pasa a entregarse; lo de Ana lo decide SU evidencia.
        q.marcarCobrada("venta-1")
        assertEquals(Estado.POR_COBRAR, q.filas.value.single { it.id == deAna.id }.estado)
    }

    @Test
    fun `quitar el paquete despues de intentar cobrar - el cobro del resto no lo entrega`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "rechazada"))

        q.armar("venta-1", emptyList(), "cli-1", "Ana")
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave-del-resto"))
        q.marcarCobrada("venta-1")

        val suelta = q.filas.value.single { it.id == fila.id }
        assertEquals(Estado.POR_COBRAR, suelta.estado)
        assertEquals(listOf(IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "rechazada")), suelta.intentos)
    }

    @Test
    fun `salir del cobro suelta la venta - su duda llega a revision aunque la app siga viva`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        coEvery { terminal.consultarEstado(any(), any(), any()) } returns ConsultaDeEstado(ChargeStatusProbe.NotFound, sinRespuesta = false)

        q.soltarVenta()
        q.drenar()
        ahora += PendingGrantQueue.DUDA_MS + 1
        q.drenar()

        assertEquals(Estado.EN_DUDA, q.filas.value.single().estado)
    }

    @Test
    fun `lo cobrado por entregar o por revisar cierra el paso a otra sucursal`() = runTest {
        val q = cola()
        q.armada("venta-1")
        assertEquals(0, q.bloqueantes(venue))
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        assertEquals(1, q.bloqueantes(venue))
        q.marcarCobrada("venta-1")
        assertEquals(1, q.bloqueantes(venue))
        assertEquals(0, q.bloqueantes("venue-B"))
    }

    // MARK: - Codex r2

    private fun tarjetaLiberada(failureCode: String?, evidencia: String?) {
        coEvery { terminal.consultarEstado(any(), any(), any()) } returns ConsultaDeEstado(
            ChargeStatusProbe.Known(status = "FAILED", inProgress = false, outcomeEvidence = evidencia, failureCode = failureCode),
            sinRespuesta = false,
        )
    }

    @Test
    fun `tarjeta liberada sin respuesta - no se borra sola y la decide una persona`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        q.soltarVenta()
        tarjetaLiberada("NO_EVIDENCE_AFTER_WINDOW", "NO_EVIDENCE_AFTER_WINDOW")

        q.drenar()
        ahora += 30L * 24 * 60 * 60 * 1000
        q.drenar()

        val fila = q.filas.value.single()
        assertEquals(Estado.EN_DUDA, fila.estado)
        assertEquals(PAQUETE_TARJETA_NO_PASO, fila.motivo)
        assertEquals(1, q.porRevisar.first())
        assertEquals(1, q.bloqueantes(venue))

        // Llega la aprobación tardía, aunque sea días después: se entrega.
        tarjeta("COMPLETED", paymentId = "pay-1")
        entrega(201)
        q.drenar()
        assertTrue(q.filas.value.isEmpty())
        coVerify(exactly = 1) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `el rechazo del banco y la declaracion de la cajera tampoco se borran solos`() = runTest {
        for ((codigo, evidencia) in listOf("BANK_DECLINED" to "PROCESSOR_DECLINED", "OPERATOR_RECONCILED_NO_CHARGE" to "OPERATOR_RECONCILED")) {
            disco.guardado.clear()
            val q = cola()
            q.armada()
            q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
            q.soltarVenta()
            tarjetaLiberada(codigo, evidencia)

            q.drenar()

            assertEquals(codigo, Estado.EN_DUDA, q.filas.value.single().estado)
        }
    }

    @Test
    fun `con el cobro en pantalla, un rechazo reversible no molesta - la cajera sigue cobrando`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        tarjetaLiberada("BANK_DECLINED", "PROCESSOR_DECLINED")

        q.drenar()

        assertEquals(Estado.POR_COBRAR, q.filas.value.single().estado)
    }

    @Test
    fun `una cancelacion aceptada por la terminal si es definitiva`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        q.soltarVenta()
        coEvery { terminal.consultarEstado(any(), any(), any()) } returns ConsultaDeEstado(
            ChargeStatusProbe.Known(status = "CANCELLED", inProgress = false, cancelDisposition = "ACCEPTED"),
            sinRespuesta = false,
        )

        q.drenar()

        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `reabrir tras refrescar el catalogo conserva la foto del cobro ya intentado`() = runTest {
        val q = cola()
        val conFoto = linea.copy(saleProof = "foto-al-vender")
        assertTrue(q.armar("venta-1", listOf(conFoto), "cli-1", "Ana"))
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))

        assertTrue(q.armar("venta-1", listOf(conFoto.copy(saleProof = "foto-nueva", precioCents = 99)), "cli-1", "Ana"))

        val fila = q.filas.value.single()
        assertEquals("foto-al-vender", fila.saleProof)
        assertEquals(50000, fila.precioCents)
    }

    @Test
    fun `la foto de la linea gana a la del catalogo de ahora`() {
        val q = cola()
        assertTrue(q.armar("venta-1", listOf(linea.copy(saleProof = "foto-al-vender")), "cli-1", "Ana"))

        assertEquals("foto-al-vender", q.filas.value.single().saleProof)
    }

    // MARK: - Codex r6: toda decisión sobre la venta en pantalla, en el candado en que se escribe

    @Test
    fun `si el disco no acepta marcarla cobrada, la venta sigue en pantalla y un NO no la borra`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        disco.discoSano = false
        assertFalse(q.marcarCobrada("venta-1"))
        disco.discoSano = true
        tarjeta("FAILED")

        q.drenar()

        assertEquals(1, q.filas.value.size)
    }

    @Test
    fun `Si se cobro sobre una fila que ya no esta en duda no manda nada`() = runTest {
        val q = cola()
        val fila = q.enDuda()
        q.borrarParaPruebas(fila.id)

        assertNull(q.siSeCobro(fila.id))

        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    // MARK: - En duda: lo decide una persona

    private suspend fun PendingGrantQueue.enDuda(): PendingGrant {
        armada()
        anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        coEvery { terminal.consultarEstado(any(), any(), any()) } returns ConsultaDeEstado(ChargeStatusProbe.NotFound, sinRespuesta = false)
        armar("venta-2", emptyList(), null, null)
        drenar()
        ahora += PendingGrantQueue.DUDA_MS + 1
        drenar()
        return filas.value.single()
    }

    @Test
    fun `sin saber por mas de media hora con red - pasa a revision`() = runTest {
        val q = cola()
        val fila = q.enDuda()

        assertEquals(Estado.EN_DUDA, fila.estado)
        assertEquals(1, q.porRevisar.first())
        assertNotEquals(null, fila.motivo)
    }

    @Test
    fun `el tiempo sin red no cuenta para la duda`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1"))
        q.armar("venta-2", emptyList(), null, null)
        conRed.value = false
        q.drenar()
        ahora += PendingGrantQueue.DUDA_MS * 10
        q.drenar()

        assertEquals(Estado.POR_COBRAR, q.filas.value.single().estado)
        assertEquals(0, q.porRevisar.first())
    }

    @Test
    fun `Si se cobro - se entrega con su llave`() = runTest {
        val q = cola()
        val fila = q.enDuda()
        entrega(201)

        assertEquals(DesenlaceDeEntrega.ENTREGADA, q.siSeCobro(fila.id))

        coVerify(exactly = 1) { articulos.sellPackToCustomer("pack-1", "cli-1", true, fila.id, "firma-1", true) }
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `No se cobro - se borra sin entregar`() = runTest {
        val q = cola()
        val fila = q.enDuda()

        assertTrue(q.descartar(fila.id))

        assertTrue(q.filas.value.isEmpty())
        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `en duda se sigue preguntando - si llega la evidencia se resuelve solo`() = runTest {
        val q = cola()
        q.enDuda()
        tarjeta("COMPLETED", paymentId = "pay-1")
        entrega(201)

        q.drenar()

        assertTrue(q.filas.value.isEmpty())
    }

    // MARK: - La ficha del cliente

    @Test
    fun `la venta de la ficha nace por entregar, con llave, antes de la red`() = runTest {
        val q = cola()
        val pack = CreditPack(id = "pack-1", name = "10 clases", price = 500.0, saleProof = "firma-1")

        val fila = q.guardarVentaDeFicha(pack, "cli-1", "Ana")!!

        assertEquals(Estado.POR_ENTREGAR, fila.estado)
        assertNull(fila.venta)
        assertEquals(50000, fila.precioCents)
        assertEquals(1, reiniciar().filas.value.size)
    }

    // D17 (hz:433063d4:D17): la del carrito ya se cobró en una orden, cuyo cobro sumó al «Total gastado»: se avisa al servidor
    // para que la entrega no lo vuelva a sumar. La de la ficha se cobra a mano, sin orden: ahí sí suma.
    @Test
    fun `D17 - la venta de la ficha se entrega SIN la marca de cobrada en orden`() = runTest {
        val q = cola()
        val fila = q.guardarVentaDeFicha(CreditPack(id = "pack-1", saleProof = "firma-1"), "cli-1", "Ana")!!
        entrega(201)

        q.entregarDeFicha(fila.id)

        coVerify(exactly = 1) { articulos.sellPackToCustomer("pack-1", "cli-1", false, fila.id, "firma-1", false) }
    }

    @Test
    fun `D17 - una fila del carrito sigue siendo del carrito aunque se suelte de su venta`() {
        val base = PendingGrant(id = "f", venueId = venue, packId = "pack-1", customerId = "cli-1", createdAt = 1)
        assertTrue(base.copy(venta = "venta-1", linea = "l#1").cobradaEnOrden)
        // `armar` la suelta de la venta (se quitó el paquete tras intentar cobrar): la orden cobrada ya lo cuenta.
        assertTrue(base.copy(venta = null, linea = "l#1").cobradaEnOrden)
        // Ficha, o fila de la app de antes: suma en la entrega.
        assertFalse(base.cobradaEnOrden)
    }

    @Test
    fun `la ficha sin disco no vende`() = runTest {
        disco.discoSano = false
        assertNull(cola().guardarVentaDeFicha(CreditPack(id = "pack-1"), "cli-1", "Ana"))
    }

    // MARK: - Lo que dejó la app de antes

    @Test
    fun `una fila vieja sin estado se entrega y su id se vuelve la llave`() = runTest {
        disco.guardado["queue"] = """[{"id":"vieja-1","venueId":"$venue","packId":"pack-1","customerId":"cli-1","createdAt":5,"attempts":3}]"""
        disco.enDisco["queue"] = disco.guardado.getValue("queue")
        entrega(201)

        val q = cola()
        q.drenar()

        coVerify(exactly = 1) { articulos.sellPackToCustomer("pack-1", "cli-1", true, "vieja-1", null) }
        assertTrue(q.filas.value.isEmpty())
    }

    @Test
    fun `lo de otra sucursal se ve en la revision aunque la sesion haya entrado sola en otra`() = runTest {
        val q = cola()
        q.armada()
        q.marcarCobrada("venta-1")
        every { storage.venueId } returns "venue-B"
        q.alCambiarLaSesion()

        val vistas = q.porRevisarEnLaSucursal.first()
        assertEquals(1, vistas.size)
        assertEquals(venue, vistas.single().venueId)
        // Pero no se resuelve desde aquí: sólo con su sucursal activa.
        assertFalse(q.descartar(vistas.single().id))
    }

    @Test
    fun `lo de otra sucursal que nunca intento cobrar no estorba`() = runTest {
        val q = cola()
        q.armada()
        every { storage.venueId } returns "venue-B"
        q.alCambiarLaSesion()

        assertTrue(q.porRevisarEnLaSucursal.first().isEmpty())
    }

    @Test
    fun `una fila de otra sucursal espera a su sucursal`() = runTest {
        val q = cola()
        q.armada()
        q.marcarCobrada("venta-1")
        every { storage.venueId } returns "venue-B"
        q.alCambiarLaSesion()

        q.drenar()

        coVerify(exactly = 0) { articulos.sellPackToCustomer(any(), any(), any(), any(), any(), any()) }
        assertEquals(1, q.filas.value.size)
        assertEquals(0, q.porEntregar.first())
    }

    // MARK: - «Cobros sin confirmar» dice qué se vendió (D2, 6-nov)

    @Test
    fun `cobro sin confirmar de un paquete - dice que se vendio y a quien, tambien despues de entregarlo`() = runTest {
        val q = cola()
        val fila = q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        val titulo = tituloDePaquete(fila)

        assertEquals("Vendido: $titulo.", q.loVendidoConElCobro("llave"))

        // El servidor rechazó el efectivo al subir (FAILED): el billete está en el cajón y el paquete se entrega.
        coEvery { pagos.findForVenue(venue, "llave") } returns filaDeEfectivo("llave", PaymentSyncStatus.FAILED)
        entrega(201)
        val otra = reiniciar()
        otra.drenar()

        assertTrue(otra.filas.value.isEmpty())
        assertEquals("Vendido: $titulo. El paquete ya se entregó al cliente.", reiniciar().loVendidoConElCobro("llave"))
        assertNull(otra.loVendidoConElCobro("otra-llave"))
    }

    @Test
    fun `lo entregado se olvida a los 30 dias`() = runTest {
        val q = cola()
        q.armada()
        q.anotarIntento("venta-1", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave"))
        coEvery { pagos.findForVenue(venue, "llave") } returns filaDeEfectivo("llave", PaymentSyncStatus.FAILED)
        entrega(201)
        q.drenar()
        assertTrue(q.loVendidoConElCobro("llave") != null)

        ahora += 31L * 24 * 60 * 60 * 1000
        q.armada("venta-2")
        q.anotarIntento("venta-2", IntentoDeCobro(IntentoDeCobro.Tipo.EFECTIVO, "llave-2"))
        coEvery { pagos.findForVenue(venue, "llave-2") } returns filaDeEfectivo("llave-2", PaymentSyncStatus.SYNCED)
        q.drenar()

        assertNull(q.loVendidoConElCobro("llave"))
        assertTrue(q.loVendidoConElCobro("llave-2") != null)
    }
}
