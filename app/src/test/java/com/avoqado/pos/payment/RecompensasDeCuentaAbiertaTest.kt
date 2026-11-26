package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.orders.data.model.comoDetalle
import com.avoqado.pos.payment.presentation.motivoSinRecompensasDeCuenta
import com.avoqado.pos.giftcards.domain.OfertaDeLaCuenta
import com.avoqado.pos.tables.data.CanjeRechazado
import com.avoqado.pos.tables.data.CustomerLoyalty
import com.avoqado.pos.tables.data.LOYALTY_OFFER_CHANGED
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.PreBillBalance
import com.avoqado.pos.tables.data.RECOMPENSAS_SIN_RED
import com.avoqado.pos.tables.data.RedeemPointsData
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D39 fase 1: «Recompensas» en una cuenta abierta de Mostrador (las de «Pagar después»), con el canje de Mesas. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecompensasDeCuentaAbiertaTest {
    @get:Rule val main = MainDispatcherRule()

    private val conectado = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conectado
        every { isServerReachable } returns servidor
    }
    private val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns "v" }
    private val repo = mockk<TableServiceRepository>()
    private val locales = mockk<CobrosLocalesDeLaCuenta> {
        coEvery { hayPendiente(any(), any()) } returns false
        coEvery { dineroConocido(any(), any()) } returns com.avoqado.pos.payment.domain.DineroConocido.NADA
    }
    private var permisos = true
    private val roles = mockk<com.avoqado.pos.core.domain.RoleManager>(relaxed = true) {
        every { hasVenuePermission(any<String>()) } answers { permisos }
    }
    private val m = CobroDeCuentaViewModel(repo, storage, red, roles, mockk(relaxed = true), locales)

    private fun cuenta(version: Int, subtotal: Double = 100.0, cliente: String? = "ana", descuento: Double = 0.0, payLater: Boolean? = true) = OrderDetail(
        "o", esDeMesa = false, cobrosConSaldo = 0, status = "CONFIRMED", paymentStatus = "PENDING", subtotal = subtotal,
        total = subtotal - descuento, customerId = cliente, discountAmount = descuento, version = version, payLater = payLater,
        preBillBalance = PreBillBalance(((subtotal - descuento) * 100).toInt(), 0, ((subtotal - descuento) * 100).toInt(), 0),
    )

    private fun saldo(puntos: Int) = CustomerLoyalty(
        customerId = "ana", customerName = "Ana", active = true, balance = puntos, redemptionRate = 0.10, balanceValue = puntos * 0.10,
        minPointsRedeem = 10, maxRedeemablePoints = puntos, maxRedeemableValue = puntos * 0.10, canRedeem = true,
    )

    @Test fun `lee el saldo de la clienta de la cuenta y lo etiqueta con esa cuenta`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        val leida = m.lealtadDeLaCuenta()
        assertEquals(200, leida?.lealtad?.balance)
        assertEquals(3, leida?.version)
    }

    @Test fun `la lectura de una version vieja de la cuenta no se ofrece`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 4))
        m.ofertaParaCobrar("o")
        assertNull(m.lealtadDeLaCuenta())
    }

    @Test fun `una cuenta sin cliente no lee saldo`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3, cliente = null))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        coVerify(exactly = 0) { repo.getCustomerLoyalty(any(), any(), any()) }
        assertNull(m.lealtadDeLaCuenta())
    }

    @Test fun `canjear manda lo que enseña el dialogo, avisa lo aplicado por el SERVIDOR y relee la cuenta y el saldo`() = runTest {
        // La consulta inicial, la de abrir «Recompensas» y la de después del canje (ya con el descuento).
        coEvery { repo.getOrderDetail("v", "o") } returnsMany listOf(
            Result.success(cuenta(version = 3)), Result.success(cuenta(version = 3)), Result.success(cuenta(version = 4, descuento = 10.0)),
        )
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returnsMany listOf(
            Result.success(saldo(200)), Result.success(saldo(200)), Result.success(saldo(100)),
        )
        coEvery { repo.redeemLoyaltyPoints("v", "o", "ana", 100, 10.0, 3) } returns Result.success(RedeemPointsData(100, 10.0, 100))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        var actualizado = false
        m.abrirRecompensas("o")
        m.canjear("o", 100, 10.0) { actualizado = true }
        assertEquals("Recompensa aplicada — 100 puntos por \$10.00", m.avisoDeRecompensas.value)
        assertFalse(m.recompensasAbiertas.value)
        assertEquals(4, m.detalle.value?.version)
        assertEquals(100, m.lealtadDeLaCuenta()?.lealtad?.balance)
        assertTrue(actualizado)
        assertFalse(m.canjeEnVuelo.value)
    }

    @Test fun `un 409 de la oferta no quema nada, reabre el dialogo con la oferta nueva y dice que la cuenta cambio`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(CanjeRechazado(LOYALTY_OFFER_CHANGED, "La cuenta cambió"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.abrirRecompensas("o")
        m.canjear("o", 100, 10.0) {}
        assertEquals("La cuenta cambió: revisa los puntos.", m.avisoDelCanje.value)
        assertTrue(m.recompensasAbiertas.value)
        assertEquals(200, m.lealtadDeLaCuenta()?.lealtad?.balance)
    }

    @Test fun `otro rechazo se dice con el texto del SERVIDOR y cierra el dialogo`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(CanjeRechazado("X", "El programa de lealtad no está activo en esta sucursal"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.abrirRecompensas("o")
        m.canjear("o", 100, 10.0) {}
        assertEquals("El programa de lealtad no está activo en esta sucursal", m.avisoDeRecompensas.value)
        assertFalse(m.recompensasAbiertas.value)
    }

    @Test fun `sin red no se manda nada`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        servidor.value = false
        m.canjear("o", 100, 10.0) {}
        coVerify(exactly = 0) { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) }
        assertEquals(RECOMPENSAS_SIN_RED, m.avisoDeRecompensas.value)
    }

    @Test fun `un segundo toque mientras viaja el primero no canjea otra vez`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        val respuesta = CompletableDeferred<Result<RedeemPointsData>>()
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } coAnswers { respuesta.await() }
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.canjear("o", 100, 10.0) {}
        assertTrue(m.canjeEnVuelo.value)
        m.canjear("o", 100, 10.0) {}
        respuesta.complete(Result.success(RedeemPointsData(100, 10.0, 100)))
        coVerify(exactly = 1) { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `el motivo del boton gris dice la causa verdadera`() {
        val c = cuenta(version = 3)
        val leida = com.avoqado.pos.tables.data.LealtadLeida("o", "ana", 3, saldo(200))
        // Sin red manda primero.
        assertEquals(RECOMPENSAS_SIN_RED, motivoSinRecompensasDeCuenta(c, leida, enLinea = false, consultando = false, oferta = OfertaDeLaCuenta.OFRECER))
        // Con un cobro local sin subir, el saldo del SERVIDOR todavía no lo cuenta.
        assertEquals(
            com.avoqado.pos.payment.presentation.RECOMPENSAS_CON_COBRO_SIN_SUBIR,
            motivoSinRecompensasDeCuenta(c, leida, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR),
        )
        // Todo bien: se puede.
        assertNull(motivoSinRecompensasDeCuenta(c, leida, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.OFRECER))
        // Sin cliente.
        assertEquals(
            com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_CLIENTE,
            motivoSinRecompensasDeCuenta(cuenta(version = 3, cliente = null), null, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.OFRECER),
        )
    }

    @Test fun `una cuenta sin marca de «Pagar despues» (importada, de plataforma o servidor viejo) no lee saldo ni se ofrece`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3, payLater = null))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        coVerify(exactly = 0) { repo.getCustomerLoyalty(any(), any(), any()) }
        val order = com.avoqado.pos.orders.data.model.OrderDetail("o", esDeMesa = false, customerName = "Ana")
        assertFalse(com.avoqado.pos.payment.presentation.ofreceRecompensas(order, m.detalle.value, conCopia = false, elegible = true, cents = 10000))
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        m.ofertaParaCobrar("o")
        assertTrue(com.avoqado.pos.payment.presentation.ofreceRecompensas(order, m.detalle.value, conCopia = false, elegible = true, cents = 10000))
        // Ni de mesa, ni de tarjetas de regalo, ni sin saldo.
        assertFalse(com.avoqado.pos.payment.presentation.ofreceRecompensas(order, m.detalle.value, conCopia = false, elegible = true, cents = 0))
        assertFalse(com.avoqado.pos.payment.presentation.ofreceRecompensas(order, m.detalle.value?.copy(esDeMesa = true), conCopia = false, elegible = true, cents = 10000))
        assertFalse(com.avoqado.pos.payment.presentation.ofreceRecompensas(order, m.detalle.value?.copy(esCuentaDeTarjetas = true), conCopia = false, elegible = true, cents = 10000))
    }

    @Test fun `sin permiso no se canjea y el motivo lo dice`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        permisos = false
        m.canjear("o", 100, 10.0) {}
        coVerify(exactly = 0) { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) }
        assertEquals(
            com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_PERMISO,
            motivoSinRecompensasDeCuenta(m.detalle.value, m.lealtadLeida.value, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.OFRECER, puedeCanjear = false),
        )
    }

    @Test fun `un 403 al leer el saldo se explica como plan o permisos, no como red`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        val prohibido = retrofit2.HttpException(retrofit2.Response.error<Any>(403, okhttp3.ResponseBody.create(null, "{}")))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.failure(prohibido)
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        assertTrue(m.lealtadProhibida.value)
        assertEquals(
            com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_ACCESO,
            motivoSinRecompensasDeCuenta(m.detalle.value, m.lealtadLeida.value, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.OFRECER, prohibida = true),
        )
    }

    @Test fun `los puntos nunca dejan la cuenta en cero, y si el minimo la dejaria en cero el motivo lo dice`() {
        // $0.10 por punto; la cuenta debe $10.00: 99 puntos ($9.90) sí, 100 ($10.00) no.
        val l = saldo(500).copy(maxRedeemablePoints = 100, maxRedeemableValue = 10.0)
        assertEquals(99, com.avoqado.pos.payment.presentation.topeQueNoCierraLaCuenta(l, 1000))
        assertEquals(0, com.avoqado.pos.payment.presentation.topeQueNoCierraLaCuenta(l, 1))
        // Con saldo de sobra el tope del SERVIDOR manda.
        assertEquals(100, com.avoqado.pos.payment.presentation.topeQueNoCierraLaCuenta(l, 5000))
        // Una tasa chica y un saldo enorme no barre millones de números.
        val chica = saldo(5_000_000).copy(redemptionRate = 0.0001, maxRedeemablePoints = 5_000_000, maxRedeemableValue = 500.0)
        assertEquals(99_949, com.avoqado.pos.payment.presentation.topeQueNoCierraLaCuenta(chica, 1000))
        // Mínimo 100 con una cuenta de $10.00: no hay número que quepa.
        val c = cuenta(version = 3, subtotal = 10.0)
        val leida = com.avoqado.pos.tables.data.LealtadLeida("o", "ana", 3, saldo(500).copy(minPointsRedeem = 100, maxRedeemablePoints = 100, maxRedeemableValue = 10.0))
        assertEquals(
            com.avoqado.pos.payment.presentation.recompensasCubririanTodo(100),
            motivoSinRecompensasDeCuenta(c, leida, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.OFRECER),
        )
    }

    @Test fun `el canje manda la version de la cuenta que se ve`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 7))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.success(RedeemPointsData(100, 10.0, 100))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.canjear("o", 100, 10.0) {}
        coVerify(exactly = 1) { repo.redeemLoyaltyPoints("v", "o", "ana", 100, 10.0, 7) }
    }

    @Test fun `la cuenta en el carrito no se canjea desde Pedidos`() {
        val c = cuenta(version = 3)
        val leida = com.avoqado.pos.tables.data.LealtadLeida("o", "ana", 3, saldo(200))
        assertEquals(
            com.avoqado.pos.payment.presentation.RECOMPENSAS_EN_EL_CARRITO,
            motivoSinRecompensasDeCuenta(c, leida, enLinea = true, consultando = false, oferta = OfertaDeLaCuenta.NADA, laTraeUnCarrito = true),
        )
    }

    @Test fun `sin respuesta del canje no se ofrece uno nuevo, sólo repetirlo idéntico con la versión de entonces`() = runTest {
        // Lectura inicial (v3) y la relectura tras perder la respuesta (v4: el SERVIDOR sí lo aplicó).
        coEvery { repo.getOrderDetail("v", "o") } returnsMany listOf(Result.success(cuenta(version = 3)), Result.success(cuenta(version = 4, descuento = 10.0)))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.failure(java.net.SocketTimeoutException("timeout"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.canjear("o", 100, 10.0) {}
        assertEquals(com.avoqado.pos.payment.presentation.RECOMPENSAS_EN_DUDA, m.avisoDeRecompensas.value)
        assertEquals("o", m.canjeEnDuda.value)
        // Un canje nuevo (con la versión nueva) no sale mientras el anterior no tenga desenlace.
        m.canjear("o", 100, 10.0) {}
        coVerify(exactly = 1) { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) }
        // Reintentar manda EXACTAMENTE lo mismo (versión 3): el SERVIDOR, que ya subió la versión, contesta 409 y no quema otra vez.
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(CanjeRechazado(LOYALTY_OFFER_CHANGED, "La cuenta cambió"))
        m.reintentarCanje("o") {}
        coVerify(exactly = 2) { repo.redeemLoyaltyPoints("v", "o", "ana", 100, 10.0, 3) }
        assertEquals(com.avoqado.pos.payment.presentation.RECOMPENSAS_REINTENTO_YA_CAMBIO, m.avisoDeRecompensas.value)
        assertNull(m.canjeEnDuda.value)
        assertFalse(m.recompensasAbiertas.value)
    }

    @Test fun `el reintento de un canje que no entró lo aplica`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.canjear("o", 100, 10.0) {}
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.success(RedeemPointsData(100, 10.0, 100))
        m.reintentarCanje("o") {}
        assertEquals("Recompensa aplicada — 100 puntos por \$10.00", m.avisoDeRecompensas.value)
        assertNull(m.canjeEnDuda.value)
    }

    @Test fun `mientras viaja el canje y su relectura, canjeEnVuelo sigue prendido`() = runTest {
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.success(RedeemPointsData(100, 10.0, 100))
        val relectura = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        coEvery { repo.getOrderDetail("v", "o") } coAnswers { relectura.await() }
        m.canjear("o", 100, 10.0) {}
        // El POST ya volvió, pero la relectura no: «Cobrar» todavía espera (Codex r3 #1).
        assertTrue(m.canjeEnVuelo.value)
        relectura.complete(Result.success(cuenta(version = 4, descuento = 10.0)))
        assertFalse(m.canjeEnVuelo.value)
    }

    @Test fun `con un canje sin respuesta ningún cobro de la cuenta se abre y la copia sin red sale gris`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        assertFalse(m.cobroBloqueadoPorCanje("o"))
        m.canjear("o", 100, 10.0) {}
        assertTrue(m.cobroBloqueadoPorCanje("o"))
        // Sin red, la copia no ofrece recibir efectivo con el saldo de antes (Codex r4 #1).
        m.consultarSinRed("o")
        assertEquals(
            com.avoqado.pos.payment.domain.DecisionSinRed.Gris(com.avoqado.pos.payment.presentation.RECOMPENSAS_COBRO_ESPERA_AL_CANJE),
            m.sinRed.value,
        )
        assertEquals(
            com.avoqado.pos.payment.domain.DecisionSinRed.Gris(com.avoqado.pos.payment.presentation.RECOMPENSAS_COBRO_ESPERA_AL_CANJE),
            m.pedirCobroSinRed("o"),
        )
    }

    @Test fun `el reintento respeta lo que frena un canje - un cobro de esta caja sin subir`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.canjear("o", 100, 10.0) {}
        // Al volver, la cuenta tiene un cobro de esta caja que todavía no sube.
        coEvery { locales.hayPendiente("v", "o") } returns true
        m.ofertaParaCobrar("o")
        m.reintentarCanje("o") {}
        coVerify(exactly = 1) { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) }
        assertEquals(com.avoqado.pos.payment.presentation.RECOMPENSAS_CON_COBRO_SIN_SUBIR, m.avisoDeRecompensas.value)
        assertEquals("o", m.canjeEnDuda.value)
    }

    @Test fun `r5 #2 - el reintento relee la oferta - un pendiente que ya subio no lo frena`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        // La relectura de después del canje lee mal los cobros locales: la pantalla guarda «cobro sin subir» (frena de más).
        var discoFalla = true
        coEvery { locales.hayPendiente("v", "o") } answers { if (discoFalla) throw java.io.IOException("disco") else false }
        m.canjear("o", 100, 10.0) {}
        assertEquals(OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR, m.oferta.value)
        // El disco vuelve y no hay nada pendiente: el reintento NO usa la oferta guardada, la relee (Codex r5 #2).
        discoFalla = false
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.success(RedeemPointsData(100, 10.0, 100))
        m.reintentarCanje("o") {}
        coVerify(exactly = 2) { repo.redeemLoyaltyPoints("v", "o", "ana", 100, 10.0, 3) }
        assertNull(m.canjeEnDuda.value)
    }

    @Test fun `r5 #1 - un canje que si entro y la red cae antes de releer - la copia sin red no cobra el saldo de antes`() = runTest {
        val marcas = marcasEnMemoria()
        val vm = CobroDeCuentaViewModel(repo, storage, red, roles, mockk(relaxed = true), locales, cuentasAbiertasAqui = marcas)
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        vm.ofertaParaCobrar("o")
        vm.leerLealtad("o")
        // La marca se guarda ANTES de mandar el canje: si la app muere a media petición, también queda.
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } coAnswers {
            assertTrue(marcas.tieneCanje("v", "o"))
            Result.success(RedeemPointsData(100, 10.0, 100))
        }
        // La relectura no llega: se cayó la red.
        coEvery { repo.getOrderDetail("v", "o") } returns Result.failure(java.io.IOException("sin red"))
        vm.canjear("o", 100, 10.0) {}
        assertFalse(vm.cobroBloqueadoPorCanje("o"))
        val gris = com.avoqado.pos.payment.domain.DecisionSinRed.Gris(com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_RED_CON_PUNTOS)
        vm.consultarSinRed("o")
        assertEquals(gris, vm.sinRed.value)
        assertEquals(gris, vm.pedirCobroSinRed("o"))
        assertEquals(com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_RED_CON_PUNTOS, vm.revalidarCobroSinRed("o", 10_000))
    }

    @Test fun `r5 #1 - si la marca no se puede guardar, el canje no sale`() = runTest {
        val marcas = marcasEnMemoria(falla = true)
        val vm = CobroDeCuentaViewModel(repo, storage, red, roles, mockk(relaxed = true), locales, cuentasAbiertasAqui = marcas)
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        vm.ofertaParaCobrar("o")
        vm.leerLealtad("o")
        vm.canjear("o", 100, 10.0) {}
        coVerify(exactly = 0) { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) }
        assertEquals(com.avoqado.pos.payment.presentation.RECOMPENSAS_NO_SE_GUARDO, vm.avisoDeRecompensas.value)
        assertFalse(vm.canjeEnVuelo.value)
    }

    @Test fun `r6 #2 - un canje rechazado por el SERVIDOR no deja la cuenta gris sin red`() = runTest {
        val marcas = marcasEnMemoria()
        val vm = CobroDeCuentaViewModel(repo, storage, red, roles, mockk(relaxed = true), locales, cuentasAbiertasAqui = marcas)
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(CanjeRechazado(LOYALTY_OFFER_CHANGED, "La cuenta cambió"))
        vm.ofertaParaCobrar("o")
        vm.leerLealtad("o")
        vm.canjear("o", 100, 10.0) {}
        // El SERVIDOR dijo que no con ESTE envío y no había otro canje de aquí: la cuenta no trae puntos, así que la marca se quita.
        assertFalse(marcas.tieneCanje("v", "o"))
        assertTrue(vm.revalidarCobroSinRed("o", 10_000) != com.avoqado.pos.payment.presentation.RECOMPENSAS_SIN_RED_CON_PUNTOS)
    }

    @Test fun `r6 #2 - si ya habia un canje de aqui, un segundo canje rechazado conserva la marca`() = runTest {
        val marcas = marcasEnMemoria()
        val vm = CobroDeCuentaViewModel(repo, storage, red, roles, mockk(relaxed = true), locales, cuentasAbiertasAqui = marcas)
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns Result.success(RedeemPointsData(100, 10.0, 100))
        vm.ofertaParaCobrar("o")
        vm.leerLealtad("o")
        vm.canjear("o", 100, 10.0) {}
        assertTrue(marcas.tieneCanje("v", "o"))
        // El segundo canje se rechaza; el primero sí entró: la cuenta trae puntos y sin red no se cobra.
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(CanjeRechazado("X", "El programa de lealtad no está activo en esta sucursal"))
        vm.ofertaParaCobrar("o")
        vm.leerLealtad("o")
        vm.canjear("o", 100, 10.0) {}
        assertTrue(marcas.tieneCanje("v", "o"))
    }

    private fun marcasEnMemoria(falla: Boolean = false): com.avoqado.pos.orders.data.CuentasAbiertasAqui {
        val disco = mutableMapOf<String, com.avoqado.pos.core.data.local.PayloadCache.Cached>()
        val cache = mockk<com.avoqado.pos.core.data.local.PayloadCache> {
            coEvery { load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
            coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
            coEvery { saveDurable(any(), any(), any()) } answers {
                if (falla) throw IllegalStateException("disco lleno")
                disco["${firstArg<String>()}|${secondArg<String>()}"] = com.avoqado.pos.core.data.local.PayloadCache.Cached(thirdArg(), 0)
            }
        }
        val copia = com.avoqado.pos.orders.data.CopiaDeCuentas(cache).also { it.io = kotlinx.coroutines.Dispatchers.Unconfined }
        return com.avoqado.pos.orders.data.CuentasAbiertasAqui(cache, copia)
    }

    @Test fun `un rechazo del SERVIDOR no es duda - dice su motivo`() = runTest {
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(cuenta(version = 3))
        coEvery { repo.getCustomerLoyalty("v", "ana", "o") } returns Result.success(saldo(200))
        coEvery { repo.redeemLoyaltyPoints(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(CanjeRechazado("ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE", "Hay un cobro con tarjeta en curso en la terminal"))
        m.ofertaParaCobrar("o")
        m.leerLealtad("o")
        m.canjear("o", 100, 10.0) {}
        assertEquals("Hay un cobro con tarjeta en curso en la terminal", m.avisoDeRecompensas.value)
    }

    @Test fun `sin red, la cuenta de la copia de «Pagar despues» ofrece Recompensas (gris) y una sin la marca no`() {
        val deLaCopia = com.avoqado.pos.orders.data.model.OrderSummary("o", esDeMesa = false, customerName = "Ana", payLater = true).comoDetalle()
        assertTrue(com.avoqado.pos.payment.presentation.ofreceRecompensas(deLaCopia, detalle = null, conCopia = true, elegible = false, cents = null))
        // Una cuenta separada puede no traer el nombre: igual se ve (gris), Codex r3 #3.
        assertTrue(com.avoqado.pos.payment.presentation.ofreceRecompensas(deLaCopia.copy(customerName = null), detalle = null, conCopia = true, elegible = false, cents = null))
        val importada = deLaCopia.copy(payLater = null)
        assertFalse(com.avoqado.pos.payment.presentation.ofreceRecompensas(importada, detalle = null, conCopia = true, elegible = false, cents = null))
        assertEquals(RECOMPENSAS_SIN_RED, motivoSinRecompensasDeCuenta(null, null, enLinea = false, consultando = false, oferta = OfertaDeLaCuenta.NADA))
    }
}
