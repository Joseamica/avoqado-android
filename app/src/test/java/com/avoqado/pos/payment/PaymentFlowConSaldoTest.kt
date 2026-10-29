package com.avoqado.pos.payment

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.model.*
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.SolicitudesDeTarjetaTest
import com.avoqado.pos.giftcards.TablaDeLaCajaTest
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.*
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowConSaldoTest {
    @get:Rule val main = MainDispatcherRule()
    private val preferences = SolicitudesDeTarjetaTest.Preferencias()
    internal val storage = preferences.almacen()
    private val dao = mockk<PendingPaymentDao>(relaxed=true)
    private val filasDelEfectivo = linkedMapOf<String, PendingPaymentEntity>()
    private val roles = mockk<RoleManager>(relaxed = true)
    internal val connected = MutableStateFlow(true)
    private val reachable = MutableStateFlow(true)
    private val network = mockk<ConnectivityMonitor>()
    private val session = TableSession()
    private val subjects = mutableListOf<PaymentFlowViewModel>()
    private lateinit var server: MockWebServer
    private lateinit var store: SolicitudesDeTarjetaStore
    private lateinit var repo: GiftCardsRepository
    private lateinit var coordinator: CobroConSaldoCoordinator
    private lateinit var local: CobrosLocalesDeLaCuenta
    internal var cart = CartState(items = listOf(CartItem(id="line",type=CartItemType.ProductItem("catalog"), name="Servicio", unitPrice=10000)))
    internal val identified = TarjetaIdentificada(PruebaDeTarjeta.Codigo("proof-only-fixture"),TarjetaConsultada("g","1234",200.0,"ACTIVE",usable=true))
    private var cleared = 0
    private var restored = 0
    private var clearSawDurable = false
    private var restoreSawEmpty = false
    private var reservas = 0
    private var alCalcularEfectivo: (() -> Unit)? = null
    private var alCrearOrden: (() -> Unit)? = null
    private var antesDeRecibirEfectivo: (() -> Unit)? = null
    private val terminal=mockk<TerminalPaymentService>(relaxed=true)
    private val settings = AjustesDeTarjetas(true,emptyList(),1.0,10000.0,sale=VentaPermitida(true))
    @Before fun setUp() {
        val prefsField=storage.javaClass.getDeclaredField("prefs").apply {isAccessible=true}
        storage.javaClass.getDeclaredField("plainPrefs").apply {isAccessible=true}.set(storage,prefsField.get(storage))
        preferences.values["venueId"]="a";preferences.values["accessToken"]="fixture";preferences.values["userId"]="staff"
        storage.guardarAjustesDeTarjetas("a",GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(),settings))
        every { roles.canReadGiftCards } returns true;every { roles.canRedeemGiftCards } returns true
        every { roles.hasVenuePermission("orders:create") } returns true
        coEvery { dao.forVenue(any()) } coAnswers { filasDelEfectivo.values.filter { it.venueId == firstArg<String>() } }
        coEvery { dao.findForVenue(any(), any()) } coAnswers {
            filasDelEfectivo[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() }
        }
        coEvery { dao.findByIdQ15(any()) } coAnswers { filasDelEfectivo[firstArg<String>()] }
        coEvery { dao.enVueloDelVenue(any()) } coAnswers {
            filasDelEfectivo.values.filter { it.venueId == firstArg<String>() && it.syncStatus == "EN_VUELO" }
        }
        coEvery { dao.insert(any()) } coAnswers { reservas++; guardarFilaDelEfectivo(firstArg()) }
        coEvery { dao.reservarEnLugarDe(any(), any()) } coAnswers {
            secondArg<Collection<String>>().forEach(::descartarFilaDelEfectivo)
            reservas++; guardarFilaDelEfectivo(firstArg())
        }
        coEvery { dao.borrarSiEnVuelo(any()) } returns 0
        coEvery { dao.descartarReservaRechazada(any()) } coAnswers { descartarFilaDelEfectivo(firstArg()) }
        coEvery { dao.actualizarApartadoEnVuelo(any(), any(), any(), any()) } coAnswers {
            val fila = filasDelEfectivo[firstArg<String>()]?.takeIf { it.syncStatus == "EN_VUELO" }
            if (fila == null) 0 else {
                filasDelEfectivo[fila.id] = fila.copy(amountCents = secondArg(), orderId = thirdArg()); 1
            }
        }
        coEvery { dao.registrarResultadoQ15(any(), any(), any(), any(), any(), any()) } coAnswers {
            val fila = filasDelEfectivo[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() }
            if (fila == null) 0 else {
                val motivo = arg<String?>(3); val resultado = arg<String?>(4)
                filasDelEfectivo[fila.id] = fila.copy(
                    syncStatus = if (fila.syncStatus == "SYNCED" || arg<Boolean>(2)) "SYNCED" else "PENDING",
                    q15InitialResultJson = fila.q15InitialResultJson ?: resultado,
                    q15ResultJson = resultado ?: fila.q15ResultJson,
                    q15Reason = fila.q15Reason ?: motivo,
                    q15DetectedAt = if (fila.q15Reason != null || motivo != null) fila.q15DetectedAt ?: arg<Long>(5) else fila.q15DetectedAt,
                ); 1
            }
        }
        every { network.isConnected } returns connected;every { network.isServerReachable } returns reachable
        server=MockWebServer().also {it.start()};System.setProperty("avoqado.test.baseUrl",server.url("/api/v1").toString())
        store=SolicitudesDeTarjetaStore(storage);repo=GiftCardsRepository(OkHttpClient(),storage)
        local=CobrosLocalesDeLaCuenta(dao,storage,store);coordinator=CobroConSaldoCoordinator(repo,store,storage,local)
    }
    private fun guardarFilaDelEfectivo(fila: PendingPaymentEntity) {
        val anterior = filasDelEfectivo[fila.id]
        if (anterior?.syncStatus != "SYNCED") filasDelEfectivo[fila.id] = filaAlReemplazar(anterior, fila)
    }
    private fun descartarFilaDelEfectivo(id: String): Int {
        val fila = filasDelEfectivo[id] ?: return 0
        if (fila.syncStatus != "EN_VUELO" || fila.q15CaptureJson == null || fila.q15Reason != null ||
            fila.method != "CASH" || fila.tenderTypeId != null) return 0
        filasDelEfectivo.remove(id); return 1
    }

    private suspend fun cerrarScopes() {
        val trabajos = subjects.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        trabajos.forEach { it.cancel() }
        trabajos.joinAll()
    }
    @After fun tearDown() {
        runBlocking { withTimeout(5000) { cerrarScopes() } }
        server.shutdown();System.clearProperty("avoqado.test.baseUrl")
    }
    internal fun vm(orderId:String?=null, flags:Set<String> = emptySet(), efectivoReal:Boolean=false):PaymentFlowViewModel {
        coEvery {terminal.fetchOnlineTerminals(any())} returns TerminalListResult.Success(emptyList())
        val tpv=mockk<com.avoqado.pos.tpvsettings.data.TpvSettingsRepository>()
        every {tpv.getCurrentSettings()} returns TpvSettings(showReviewScreen=false,showTipScreen=false)
        val areas=mockk<com.avoqado.pos.areatickets.data.AreaTicketRepository>(relaxed=true)
        every { areas.session.current() } returns null
        val orders=if(efectivoReal) OrderRepository(storage,OkHttpClient.Builder().addInterceptor { chain ->
            val local=server.url(chain.request().url.encodedPath)
            check(java.net.InetAddress.getByName(local.host).isLoopbackAddress)
            chain.proceed(chain.request().newBuilder().url(local).build()).also {
                if (local.encodedPath.endsWith("/pay")) antesDeRecibirEfectivo?.invoke()
            }
        }.build()).apply { baseUrl=server.url("/api/v1").toString().trimEnd('/') } else mockk(relaxed=true)
        if(alCrearOrden!=null) coEvery { orders.createOrder(any(),any(),any(),any(),any(),any(),any(),any()) } coAnswers {
            alCrearOrden?.invoke()
            Result.success(com.avoqado.pos.payment.data.model.CreateOrderResponse(success=true,
                data=com.avoqado.pos.payment.data.model.OrderData(id="o",orderNumber="ORD",total=100.0)))
        }
        val efectivo=if(efectivoReal) spyk(CashPaymentRepository(storage,dao)) else mockk(relaxed=true)
        if(efectivoReal) every { efectivo.processCashPayment(any(),any()) } answers {
            val respuesta=callOriginal()
            alCalcularEfectivo?.invoke()
            respuesta
        }
        val result=PaymentFlowViewModel(orderRepository=orders,cashPaymentRepository=efectivo,
            tenderTypeRepository=mockk(relaxed=true),terminalPaymentService=terminal,tpvSettingsRepository=tpv,
            paymentSyncService=mockk(relaxed=true),cashDrawerRepository=mockk(relaxed=true),kdsRepository=mockk(relaxed=true),
            kdsOrderBus=mockk(relaxed=true),printerService=mockk(relaxed=true),secureStorage=storage,
            comandaDispatcher=mockk(relaxed=true),comandasPendientesStore=mockk(relaxed=true),replayDeComandas=mockk(relaxed=true),
            customerDisplay=com.avoqado.pos.customerdisplay.CustomerDisplayState(),areaTicketRepository=areas,
            cancelacionDeCobro=mockk(relaxed=true),savedStateHandle=androidx.lifecycle.SavedStateHandle(),tableSession=session,
            syncOutbox=mockk(relaxed=true),connectivityMonitor=network,cobroConSaldo=coordinator,tarjetasRepository=repo,
            rolesDeSaldo=roles,cobrosLocalesDeSaldo=local)
        result.startPaymentFlow(cart,resumeOrderId=orderId,cuentaDeTarjetas="regalo" in flags)
        result.configurarSaldo(ventaViva={cart},foto={cart.aFoto(null)},vaciar={
            val rows=store.delNegocio("a");clearSawDurable=rows.size==1 && rows.single().mandado && rows.single().foto!=null
            cleared++;cart=CartState()
        },restaurar={photo ->
            restoreSawEmpty=store.delNegocio("a").isEmpty();restored++
            if (!cart.isEmpty) null else photo.aCartState().also {cart=it}
        },desdeCuentas="lista" in flags)
        subjects+=result;return result
    }
    private fun response(status:Int=200,body:String=success())=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun success(rest:Int=0)="""{"success":true,"data":{"paymentId":"p","orderId":"o","orderNumber":"ORD","amount":80,"tipAmount":0,"redeemed":[],"orderRemaining":$rest,"orderPaymentStatus":"${if(rest>0) "PARTIAL" else "PAID"}"}}"""
    private fun active()=SolicitudDeTarjeta(tipo="GIFT_CARD_REDEEM",llave="existing",venueId="a",creadaEn=1,orderId="o",cuerpo="{}",mandado=true)

    @Test fun unionDaoActualizaSoloApartadoEnVueloYConservaCaptura() = runTest {
        val llave = "c000000000000000000000002"
        assertEquals(0, dao.actualizarApartadoEnVuelo(llave, 9000, "o"))
        val efectivo = CashPaymentRepository(storage, dao)
        efectivo.reservarCobro(com.avoqado.pos.payment.data.model.CreateOrderRequest(emptyList(),
            subtotal = 10000, total = 10000, paymentMethod = "CASH"), "staff", 10000, 0, null,
            orderId = "o", idempotencyKey = llave, venueId = "a")
        val original = dao.findForVenue("a", llave)
        assertNotNull("El DAO debe conservar la reserva escrita por el repositorio real", original)
        assertNotNull(original!!.q15CaptureJson)
        assertEquals(1, dao.actualizarApartadoEnVuelo(llave, 9000, "o"))
        val actualizada = dao.findForVenue("a", llave)!!
        assertEquals(9000, actualizada.amountCents)
        assertEquals("o", actualizada.orderId)
        assertEquals(original.q15CaptureJson, actualizada.q15CaptureJson)
        dao.insert(actualizada.copy(syncStatus = "PENDING", q15CaptureJson = null))
        assertEquals(0, dao.actualizarApartadoEnVuelo(llave, 7000, "otra"))
        val pendiente = dao.findForVenue("a", llave)!!
        assertEquals(9000, pendiente.amountCents)
        assertEquals("o", pendiente.orderId)
        assertEquals(original.q15CaptureJson, pendiente.q15CaptureJson)
        assertNull(dao.findForVenue("b", llave))
    }

    @Test fun laTablaEntera()=runTest {
        for (row in TablaDeLaCajaTest.casos()) {
            val label=row[0] as String;val expected=row[1] as FilaDeLaCaja
            @Suppress("UNCHECKED_CAST") val flags=row[2] as Set<String>
            connected.value="offline" !in flags
            every {roles.canReadGiftCards} returns ("sinRead" !in flags)
            every {roles.canRedeemGiftCards} returns ("sinRedeem" !in flags)
            every {roles.hasVenuePermission("orders:create")} returns ("sinCrear" !in flags)
            storage.guardarAjustesDeTarjetas("a",if ("noDisponible" in flags) null else GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(),settings.copy(enabled="apagado" !in flags,tieneTarjetas="tarjetas" in flags)))
            cart=CartState(items=if ("rapido" in flags) emptyList() else listOf(CartItem(type=if("membresia" in flags) CartItemType.CreditPack("pack") else CartItemType.ProductItem("catalog"),name="Artículo",unitPrice=10000,areaTicketId=if("vales" in flags) "vale" else null)),creacionIncierta=if("incierta" in flags) "x" else null,vinoDeCarritoGuardado="guardada" in flags)
            if("mesa" in flags || "provisional" in flags) session.start(TableSession.Active("t","1",null,"o","ORD",1,10000,TableSession.Mode.PAYING,isProvisional="provisional" in flags)) else session.clear()
            store.quitar("existing");if("trabada" in flags) assertTrue(store.guardar(active()))
            coEvery {dao.forVenue(any())} returns if("local" in flags) listOf(mockk(relaxed=true){every {this@mockk.orderId} returns "o";every {syncStatus} returns "PENDING"}) else emptyList()
            val subject=vm(if("ligada" in flags) "o" else null,flags)
            val behavior=subject.conductaDeSaldo();assertEquals(label,expected,behavior.fila)
            val before=server.requestCount
            if(behavior.habilitada) server.enqueue(response())
            val r=subject.cobrarConSaldo(identified,8000)
            assertEquals(label,if(behavior.habilitada) before+1 else before,server.requestCount)
            if(behavior.habilitada) {
                assertTrue(label,r is DesenlaceDeCobro.Cobrado)
                val req=server.takeRequest(1,TimeUnit.SECONDS)!!
                assertTrue(label,req.path!!.endsWith(if(expected==FilaDeLaCaja.T1) "/gift-cards/pay-new-order" else "/orders/o/pay-gift-card"))
                val body=Json.parseToJsonElement(req.body.readUtf8()).jsonObject
                assertEquals(80.0,body.getValue("amount").jsonPrimitive.double,0.0)
                assertEquals(identified.prueba.aJson(),body["proof"])
                assertFalse(body.containsKey("externalId"));assertTrue(store.delNegocio("a").isEmpty())
            } else assertTrue(label,r is DesenlaceDeCobro.NoSalio)
            subject.viewModelScope.cancel()
        }
    }
    @Test fun guardarOMarcarFalseNoVaciaNiManda()=runTest {
        val subject=vm();preferences.ok=false
        val r=subject.cobrarConSaldo(identified,8000)
        assertTrue(r is DesenlaceDeCobro.NoSalio);assertEquals(0,cleared);assertFalse(cart.isEmpty);assertEquals(0,server.requestCount)
    }
    @Test fun guardaYMarcaAntesDeVaciarYDelPost()=runTest {
        val subject=vm();server.enqueue(response());assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.Cobrado)
        assertTrue(clearSawDurable);assertEquals(1,cleared);assertTrue(cart.isEmpty)
    }
    @Test fun rechazoT1ActualizaLaIdentidadDelMismoCobroYLuegoEfectivoCreaUnaOrden()=runTest {
        val subject=vm();val identity=cart.identidadDeVenta
        server.enqueue(response(409,"""{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
        subject.cobrarConSaldo(identified,8000)
        assertEquals(1,restored);assertTrue(restoreSawEmpty);assertNotEquals(identity,cart.identidadDeVenta)
        assertEquals(FilaDeLaCaja.T1,subject.conductaDeSaldo().fila)
        assertEquals(10000,subject.buildPaymentContext().totalCents)
    }
    @Test fun unCobroQueEmpezoAntesNoSeMandaSiCambioLaVenta()=runTest {
        val subject=vm();cart=cart.copy(creacionIncierta="creation-in-flight")
        val r=subject.cobrarConSaldo(identified,8000);assertEquals(DesenlaceDeCobro.NoSalio(TextosDeTarjetas.VENTA_LOCAL_SIN_COBROS),r)
        assertEquals(0,server.requestCount);assertEquals(0,cleared)
    }
    @Test fun otroCobroLocalBloqueaSoloSaldo()=runTest {
        val subject=vm("o");store.guardar(active())
        assertEquals(FilaDeLaCaja.T13,subject.conductaDeSaldo().fila)
        assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.NoSalio);assertEquals(0,server.requestCount)
    }

    @Test fun marcarFalseNoVaciaNiManda()=runTest {
        val disk=spyk(storage);var writes=0
        every {disk.escribirSolicitudesDeTarjeta(any())} answers {if(++writes==2) false else callOriginal()}
        store=SolicitudesDeTarjetaStore(disk);local=CobrosLocalesDeLaCuenta(dao,disk,store)
        coordinator=CobroConSaldoCoordinator(repo,store,disk,local)
        val subject=vm();val r=subject.cobrarConSaldo(identified,8000)
        assertTrue(r is DesenlaceDeCobro.NoSalio);assertEquals(0,cleared);assertFalse(cart.isEmpty);assertEquals(0,server.requestCount)
    }
    @Test fun quitarFalseNoRestauraLaVenta()=runTest {
        store=spyk(store);every {store.reservarEntrega(any())} returns false
        coordinator=CobroConSaldoCoordinator(repo,store,storage,local)
        val subject=vm();server.enqueue(response(409,"""{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
        subject.cobrarConSaldo(identified,8000)
        assertEquals(0,restored);assertEquals(1,cleared);assertNotNull(store.delNegocio("a").single().foto)
    }
    @Test fun t2ConRestoQuedaPorTerminarSinFotoNiCarrito()=runTest {
        val subject=vm("o");server.enqueue(response(body=success(20)))
        assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.Cobrado)
        assertEquals(1,cleared);assertTrue(cart.isEmpty)
        val row=store.delNegocio("a").single();assertTrue(row.porTerminar);assertTrue(row.deMostrador);assertNull(row.foto);assertEquals("",row.cuerpo)
        assertEquals(0,restored)
    }
    @Test fun retryConsultaLaMismaLlaveAntesDeReenviar()=runTest {
        val subject=vm();server.enqueue(response(429,"""{"code":"RATE_LIMITED","message":"Too many requests","retryAfter":0}"""))
        assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.Reintentable)
        val first=server.takeRequest(1,TimeUnit.SECONDS)!!;val body=first.body.readUtf8()
        val row=store.delNegocio("a").single();assertEquals(body,row.cuerpo)
        server.enqueue(response(body="""{"success":true,"data":{"found":false}}"""));server.enqueue(response())
        assertTrue(subject.reintentarSaldo() is DesenlaceDeCobro.Cobrado)
        val get=server.takeRequest(1,TimeUnit.SECONDS)!!;val replay=server.takeRequest(1,TimeUnit.SECONDS)!!
        assertEquals("GET",get.method);assertTrue(get.path!!.endsWith("/payments/"+row.llave));assertEquals(body,replay.body.readUtf8());assertEquals(1,cleared)
    }
    @Test fun tocarPagarDosVecesMandaUnaSolaSolicitud()=runTest {
        val subject=vm("o");val gate=CompletableDeferred<List<com.avoqado.pos.core.data.local.database.PendingPaymentEntity>>()
        coEvery {dao.forVenue(any())} coAnswers {gate.await()}
        server.enqueue(response());val first=async {subject.cobrarConSaldo(identified,8000)};runCurrent()
        assertEquals(DesenlaceDeCobro.Incierto,subject.cobrarConSaldo(identified,8000))
        gate.complete(emptyList());assertTrue(first.await() is DesenlaceDeCobro.Cobrado);assertEquals(1,server.requestCount)
    }
    @Test fun activeRedeemQueLlegoDespuesDeElegirEfectivoLoBloquea()=runTest {
        val subject=vm("o");subject.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CASH)
        store.guardar(active());subject.processCashPayment(10000)
        val state=subject.state.value
        assertTrue(state is com.avoqado.pos.payment.data.model.PaymentFlowState.Error)
        assertEquals(TextosDeTarjetas.COMPROBANDO_COBRO,(state as com.avoqado.pos.payment.data.model.PaymentFlowState.Error).message)
    }
    @Test fun redeemLlegaEntreToqueYReservaDeEfectivoNoReservaNiManda()=runTest {
        alCalcularEfectivo={assertTrue(store.guardar(active()))}
        val subject=vm("o",efectivoReal=true)
        subject.processCashPayment(10000)
        runCurrent()
        assertEquals(0,reservas)
        assertEquals(0,server.requestCount)
        assertEquals(TextosDeTarjetas.COMPROBANDO_COBRO,(subject.state.value as? com.avoqado.pos.payment.data.model.PaymentFlowState.Error)?.message)
    }
    @Test fun redeemLlegaEntreToqueYPostDeTerminalNoManda()=runTest {
        alCrearOrden={assertTrue(store.guardar(active()))}
        val subject=vm()
        subject.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CARD)
        subject.selectTerminalAndPay("terminal")
        runCurrent()
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }
    @Test fun rechazoT1YEfectivoUsanLaIdentidadRestauradaConUnaOrden()=runTest {
        val subject=vm(efectivoReal=true)
        val anterior=cart.identidadDeVenta
        server.enqueue(response(409,"""{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
        subject.cobrarConSaldo(identified,8000)
        val nueva=cart.identidadDeVenta
        assertNotEquals(anterior,nueva)
        server.takeRequest(1,TimeUnit.SECONDS)
        server.enqueue(response(201,"""{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}"""))
        server.enqueue(response(200,"""{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}"""))
        subject.processCashPayment(10000)
        runCurrent()
        // Esperar el IO real del repositorio; nunca avanzar un reloj que tiene un poll infinito.
        withContext(Dispatchers.IO) {
            val creacion=server.takeRequest(5,TimeUnit.SECONDS)
            assertNotNull(creacion)
            assertTrue(creacion!!.path!!.endsWith("/orders"))
            val pago=server.takeRequest(5,TimeUnit.SECONDS)
            assertNotNull(pago)
            assertTrue(pago!!.path!!.contains("/orders/cash-order/"))
        }
        assertEquals(3,server.requestCount)
        assertEquals(1,reservas)
        assertEquals(nueva,cart.identidadDeVenta)
    }
    @Test fun otroPagoLocalNoBloqueaLosMediosNormales()=runTest {
        val subject=vm("o")
        coEvery {dao.forVenue(any())} returns listOf(mockk(relaxed=true){every {orderId} returns "o";every {syncStatus} returns "PENDING"})
        assertEquals(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR,subject.conductaDeSaldo().motivo)
        subject.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CASH)
        assertTrue(subject.state.value is com.avoqado.pos.payment.data.model.PaymentFlowState.CollectingCashAmount)
    }
    @Test fun entregaSinConfirmarBloqueaEfectivoTerminalYSaldo()=runTest {
        val subject=vm(efectivoReal=true)
        val fila=SolicitudDeTarjeta(tipo="GIFT_CARD_REDEEM",llave="entrega",venueId="a",creadaEn=1,
            cuerpo="",nuevaCuenta=true,porRecuperar=true,foto="{}")
        assertTrue(store.guardar(fila));assertTrue(store.reservarEntrega("entrega"))
        subject.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CASH)
        assertEquals(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR,(subject.state.value as? com.avoqado.pos.payment.data.model.PaymentFlowState.Error)?.message)
        subject.processCashPayment(10000);subject.selectTerminalAndPay("terminal");runCurrent()
        assertEquals(0,reservas);assertEquals(0,server.requestCount)
        coVerify(exactly=0) {terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any())}
        assertEquals(DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR),subject.cobrarConSaldo(identified,8000))
    }
    @Test fun Q101EntregaActualizaSnapshotDelHostSinOtraApertura()=runTest {
        val subject=vm();var host=cart
        subject.startPaymentFlow(host,apertura="una-apertura")
        subject.configurarSaldo(ventaViva={cart},foto={cart.aFoto(null)},vaciar={cart=CartState()},
            restaurar={photo ->photo.aCartState().also {cart=it}},alRestaurarSnapshot={host=it})
        val anterior=host.identidadDeVenta
        server.enqueue(response(409,"""{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
        subject.cobrarConSaldo(identified,8000)
        assertNotEquals(anterior,cart.identidadDeVenta)
        assertEquals("El host debe fotografiar la identidad entregada en el mismo acto",cart,host)
        assertTrue(subject.esElCobroEnCurso("una-apertura"))
        subject.startPaymentFlow(host,apertura="una-apertura")
        assertEquals(10000,subject.buildPaymentContext().totalCents)
    }
    @Test fun exitoConSaldoEntregaElRestoRealYSuReciboSinReconciliarElCarrito()=runTest {
        val subject=vm();server.enqueue(response(body=success(20)))
        subject.cobrarConSaldo(identified,8000)
        val completion=subject.consumeCompletion()
        assertNotNull("El saldo usa la misma entrega de resultado que los otros medios",completion)
        assertEquals("o",completion?.orderId);assertEquals(2000,completion?.remainingBalanceCents)
        assertNull(subject.consumeCompletion())
        assertTrue(cart.isEmpty);assertEquals(0,restored)
    }

    @Test fun regresarElSaldoSeOfreceTrasReiniciarPorCobrosConSaldo() = runTest {
        val sujeto = vm(orderId = "o")
        assertTrue(sujeto.ofreceRegresarSaldo(1, pagada = false))
        assertFalse(sujeto.ofreceRegresarSaldo(0, pagada = false))
        assertFalse(sujeto.ofreceRegresarSaldo(1, pagada = true))
    }
    @Test fun regresarElSaldoUsaUnaLlavePorDialogoYNoDuplicaConOtraVenta() = runTest {
        val sujeto = vm(orderId = "o")
        assertTrue("Debe preparar el diálogo de la cuenta canónica", sujeto.prepararRegresoDeSaldo("o", 1, false))
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
            .setBody("""{"success":false,"code":"ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE","message":"Ocupada"}"""))
        assertFalse(sujeto.regresarSaldoYCancelar())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":{"cancelled":true}}"""))
        assertTrue(sujeto.regresarSaldoYCancelar())
        val a = server.takeRequest(2, TimeUnit.SECONDS)!!; val b = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(a.path!!.endsWith("/orders/o/gift-balance-return-and-cancel"))
        val cuerpo = a.body.readUtf8(); assertEquals(cuerpo, b.body.readUtf8())
        val key = Json.parseToJsonElement(cuerpo).jsonObject["operationKey"]!!.jsonPrimitive.content
        assertTrue(key.length in 8..128)
        assertTrue(sujeto.prepararRegresoDeSaldo("otra", 1, false))
        preferences.values["venueId"] = "b"
        assertFalse(sujeto.regresarSaldoYCancelar()); assertEquals(2, server.requestCount)
        preferences.values["venueId"] = "a"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":{"cancelled":true}}"""))
        assertTrue(sujeto.regresarSaldoYCancelar())
        val c = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(c.path!!.endsWith("/orders/otra/gift-balance-return-and-cancel"))
        assertNotEquals(cuerpo, c.body.readUtf8())
    }


    @Test fun elCierreEsperaLaRespuestaDeEfectivoSuspendidaAntesDeSoltarMain() = runTest {
        val dentro = java.util.concurrent.CountDownLatch(1)
        val liberar = java.util.concurrent.CountDownLatch(1)
        antesDeRecibirEfectivo = { dentro.countDown(); check(liberar.await(5, TimeUnit.SECONDS)) }
        val sujeto = vm(efectivoReal = true)
        val trabajo = sujeto.viewModelScope.coroutineContext[Job]!!
        server.enqueue(response(201,"""{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}"""))
        server.enqueue(response(200,"""{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}"""))
        try {
            sujeto.processCashPayment(10000); runCurrent()
            withContext(Dispatchers.IO) { assertTrue("La respuesta real de efectivo debe estar suspendida", dentro.await(5, TimeUnit.SECONDS)) }
            assertEquals(2, server.requestCount)
            val cierre = async(start = CoroutineStart.UNDISPATCHED) { cerrarScopes() }
            runCurrent()
            assertFalse("El teardown no puede soltar Main mientras la respuesta sigue en IO", cierre.isCompleted)
            liberar.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { cierre.await() } }
            assertTrue("Todos los hijos propios terminaron antes de resetMain/shutdown", trabajo.isCompleted)
        } finally {
            liberar.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { trabajo.cancelAndJoin() } }
        }
    }

    @Test fun dialogoDeRegresoDeMesaRevalidaOrdenNegocioYEpocaAntesDelPost() = runTest {
        val llamadas = mutableListOf<Int>()
        for (caso in listOf("orden", "negocio", "epoca")) {
            session.clear(); session.sincronizarContexto("staff", "a")
            val original = TableSession.Active("t", "1", null, "o", "ORD", 1, 10000, TableSession.Mode.ORDERING)
            session.start(original, "a")
            val sujeto = vm(orderId = "o")
            assertTrue(sujeto.prepararRegresoDeSaldo("o", 1, false))
            when (caso) {
                "orden" -> session.start(original.copy(orderId = "otra"), "a")
                "negocio" -> session.sincronizarContexto("staff", "otra")
                else -> { session.clear(); session.sincronizarContexto("staff", "a"); session.start(original, "a") }
            }
            val antes = server.requestCount
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":{"cancelled":true}}"""))
            sujeto.regresarSaldoYCancelar()
            llamadas += server.requestCount - antes
        }
        assertEquals("Un diálogo viejo de Mesas no manda después de cambiar el contexto", listOf(0, 0, 0), llamadas)
    }

    @Test fun regresoDeMesaVigenteConservaLaLlaveEntreReintentos() = runTest {
        session.sincronizarContexto("staff", "a")
        session.start(TableSession.Active("t", "1", null, "o", "ORD", 1, 10000, TableSession.Mode.ORDERING), "a")
        val sujeto = vm(orderId = "o")
        assertTrue(sujeto.prepararRegresoDeSaldo("o", 1, false))
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
            .setBody("""{"success":false,"message":"Ocupada"}"""))
        assertFalse(sujeto.regresarSaldoYCancelar())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":{"cancelled":true}}"""))
        assertTrue(sujeto.regresarSaldoYCancelar())
        val a = server.takeRequest(2, TimeUnit.SECONDS)!!; val b = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(a.body.readUtf8(), b.body.readUtf8())
        assertTrue(b.path!!.endsWith("/orders/o/gift-balance-return-and-cancel"))
        assertEquals(2, server.requestCount)
    }

    @Test fun r17T1EnVueloRemontadoNoPermiteReservarNiEnviarEfectivo() = runTest {
        val retenido = java.util.concurrent.CountDownLatch(1)
        val liberar = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/gift-cards/pay-new-order")) {
                    retenido.countDown(); check(liberar.await(8, TimeUnit.SECONDS))
                    return response(503, "{\"message\":\"temporal\"}")
                }
                return response(201, """{"success":true,"data":{"id":"segunda","orderNumber":"SEG","total":100}}""")
            }
        }
        val original = cart
        val sujeto = vm(efectivoReal = true)
        sujeto.startPaymentFlow(original, apertura = "r17-misma-apertura")
        val saldo = async { sujeto.cobrarConSaldo(identified, 8000) }
        runCurrent()
        try {
            withContext(Dispatchers.IO) { assertTrue(retenido.await(5, TimeUnit.SECONDS)) }
            assertTrue(store.delNegocio("a").single().mandado)
            // El composable recreado pierde su hoja; el ViewModel y la apertura sobreviven.
            sujeto.startPaymentFlow(original, apertura = "r17-misma-apertura")
            sujeto.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CASH)
            sujeto.processCashPayment(10000); runCurrent()
            withContext(Dispatchers.IO) { Thread.sleep(250) }
            runCurrent()
            assertEquals("No reservar otro cobro mientras T1 sigue en vuelo", 0, reservas)
            assertEquals("Sólo el POST original de saldo", 1, server.requestCount)
            assertEquals(TextosDeTarjetas.COMPROBANDO_COBRO,
                (sujeto.state.value as? com.avoqado.pos.payment.data.model.PaymentFlowState.Error)?.message)
        } finally {
            liberar.countDown()
            saldo.await()
        }
    }


    @Test fun r18ReintentarEntregaAMismaAperturaYEfectivoVaciaLaVenta() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            cart = carrito.cartState.value
            var host = cart
            val subject = vm(efectivoReal = true)
            subject.startPaymentFlow(host, apertura = "r18-misma-apertura")
            subject.configurarSaldo(ventaViva = { carrito.cartState.value }, foto = { carrito.fotoDeLaVenta() },
                vaciar = { carrito.clearCart(); cart = carrito.cartState.value },
                restaurar = { foto ->
                    if (!carrito.restaurarFoto(foto)) null else carrito.cartState.value.also { cart = it }
                }, alRestaurarSnapshot = { host = it })
            val anterior = host.identidadDeVenta
            server.enqueue(response(503, """{"message":"temporal"}"""))
            assertTrue(subject.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Incierto)
            val primero = server.takeRequest(1, TimeUnit.SECONDS)!!
            val bytes = primero.body.readUtf8()
            val llave = store.delNegocio("a").single().llave
            server.enqueue(response(200, """{"success":true,"data":{"found":false}}"""))
            server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
            assertTrue(subject.reintentarSaldo() is DesenlaceDeCobro.Rechazado)
            val consulta = server.takeRequest(1, TimeUnit.SECONDS)!!
            val reenvio = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", consulta.method)
            assertTrue(consulta.path!!.endsWith("/payments/$llave"))
            assertEquals(bytes, reenvio.body.readUtf8())
            assertNotEquals("Reintentar entrega identidad nueva a ESTA apertura", anterior, carrito.cartState.value.identidadDeVenta)
            assertEquals(carrito.cartState.value, host)
            assertTrue(subject.esElCobroEnCurso("r18-misma-apertura"))
            assertTrue(store.delNegocio("a").isEmpty())
            assertEquals(10000, subject.buildPaymentContext().totalCents)
            assertEquals(3, server.requestCount)
            assertEquals(0, reservas)
            server.enqueue(response(201, """{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}"""))
            server.enqueue(response(200, """{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}"""))
            subject.processCashPayment(10000)
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    while (subject.state.value !is com.avoqado.pos.payment.data.model.PaymentFlowState.Success) delay(10)
                }
            }
            val completion = subject.consumeCompletion()!!
            assertEquals(host.identidadDeVenta, completion.identidadDeVenta)
            assertEquals("cash-order", completion.orderId)
            assertEquals(1, reservas)
            val aplicado = carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta)
            assertTrue(aplicado.aplicadoAlCarritoActual)
            assertTrue(carrito.cartState.value.isEmpty)
            val crear = server.takeRequest(1, TimeUnit.SECONDS)!!
            val pagar = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(crear.path!!.endsWith("/orders"))
            assertTrue(pagar.path!!.contains("/orders/cash-order/"))
            assertEquals(5, server.requestCount)
            assertNull(subject.consumeCompletion())
        } finally {
            val trabajo = carrito.viewModelScope.coroutineContext[Job]!!
            trabajo.cancel(); trabajo.join()
        }
    }

    @Test fun r18ResolutorSinPantallaConservaFotoPorRecuperar() = runTest {
        val subject = vm()
        server.enqueue(response(503, """{"message":"temporal"}"""))
        assertTrue(subject.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Incierto)
        val fila = store.delNegocio("a").single()
        server.enqueue(response(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
        assertTrue(coordinator.resolver(fila.llave) is DesenlaceDeCobro.Rechazado)
        assertTrue(cart.isEmpty)
        assertEquals(0, restored)
        val pendiente = store.delNegocio("a").single()
        assertTrue(pendiente.porRecuperar)
        assertEquals(fila.foto, pendiente.foto)
        assertEquals(0, reservas)
        assertEquals(3, server.requestCount)
    }

    @Test fun r21FondoRecuperaConAperturaVivaRemontaYCobraUnaVez() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            cart = carrito.cartState.value
            val original = cart.identidadDeVenta
            val subject = vm(efectivoReal = true)
            subject.startPaymentFlow(cart, apertura = "r21-apertura-viva")
            subject.configurarSaldo(ventaViva = { carrito.cartState.value }, foto = carrito::fotoDeLaVenta,
                vaciar = { carrito.clearCart(); cart = carrito.cartState.value },
                restaurar = { foto -> if (carrito.restaurarFoto(foto)) carrito.cartState.value else null })
            subject.vincularRecuperacionDeFoto(carrito::observarRestauracionDeFoto)
            server.enqueue(response(503, """{"message":"temporal"}"""))
            assertTrue(subject.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Incierto)
            val primero = server.takeRequest(1, TimeUnit.SECONDS)!!
            val bytes = primero.body.readUtf8()
            val llave = store.delNegocio("a").single().llave
            server.enqueue(response(200, """{"success":true,"data":{"found":false}}"""))
            server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
            val fondo = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, network, coordinator)
            fondo.resolverAhora()
            assertEquals(llave, fondo.porRecuperar.value.single().llave)
            assertTrue(carrito.cartState.value.isEmpty)
            val recuperar = com.avoqado.pos.giftcards.presentation.RecuperacionDeSaldoViewModel(store, storage, coordinator, local)
            try {
                assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar(llave, carrito))
                cart = carrito.cartState.value
                assertNotEquals(original, cart.identidadDeVenta)
                assertTrue(store.delNegocio("a").isEmpty())
                assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
                assertEquals(bytes, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
                // Remontaje de la misma apertura: el VM sigue vivo aunque desapareciera la hoja.
                subject.startPaymentFlow(cart, apertura = "r21-apertura-viva")
                server.enqueue(response(201, """{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}"""))
                server.enqueue(response(200, """{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}"""))
                subject.processCashPayment(10000)
                withContext(Dispatchers.Default) {
                    withTimeout(5000) {
                        while (subject.state.value !is com.avoqado.pos.payment.data.model.PaymentFlowState.Success) delay(10)
                    }
                }
                val completion = subject.consumeCompletion()!!
                assertEquals(1, reservas)
                assertEquals(5, server.requestCount)
                val crear = server.takeRequest(1, TimeUnit.SECONDS)!!
                val pagar = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertTrue(crear.path!!.endsWith("/orders"))
                assertTrue(pagar.path!!.contains("/orders/cash-order/"))
                val aplicado = carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                    completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta)
                assertTrue("H1: el efectivo de la apertura remontada debe consumir ESTA venta recuperada", aplicado.aplicadoAlCarritoActual)
                assertTrue(carrito.cartState.value.isEmpty)
                assertEquals(cart.identidadDeVenta, completion.identidadDeVenta)
                assertNull(subject.consumeCompletion())
            } finally {
                val trabajo = recuperar.viewModelScope.coroutineContext[Job]!!
                trabajo.cancel(); trabajo.join()
            }
        } finally { cerrarR21(carrito) }
    }

    private fun enlazarR21(subject: PaymentFlowViewModel,
        carrito: com.avoqado.pos.pos.presentation.cart.CartViewModel, despuesDeRestaurar: () -> Unit = {}) {
        subject.configurarSaldo(ventaViva = { carrito.cartState.value }, foto = carrito::fotoDeLaVenta,
            vaciar = { carrito.clearCart(); cart = carrito.cartState.value },
            restaurar = { foto ->
                if (!carrito.restaurarFoto(foto)) null else carrito.cartState.value.also {
                    cart = it; despuesDeRestaurar()
                }
            })
        subject.vincularRecuperacionDeFoto(carrito::observarRestauracionDeFoto)
    }

    private fun duenoR21(carrito: com.avoqado.pos.pos.presentation.cart.CartViewModel,
        apertura: String, efectivoReal: Boolean = false): PaymentFlowViewModel {
        cart = carrito.cartState.value
        return vm(efectivoReal = efectivoReal).also {
            it.startPaymentFlow(cart, apertura = apertura)
            enlazarR21(it, carrito)
        }
    }

    private suspend fun fondoR21(subject: PaymentFlowViewModel): String {
        server.enqueue(response(503, """{"message":"temporal"}"""))
        assertTrue(subject.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Incierto)
        val original = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
        val llave = store.delNegocio("a").single().llave
        server.enqueue(response(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
        val fondo = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, network, coordinator)
        fondo.resolverAhora()
        assertEquals(llave, fondo.porRecuperar.value.single().llave)
        assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertEquals(original, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        return llave
    }

    private suspend fun TestScope.cerrarR21(vararg modelos: androidx.lifecycle.ViewModel) {
        val trabajos = modelos.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        trabajos.forEach { it.cancel() }; trabajos.joinAll()
        cerrarScopes()
        // La pantalla del cliente tiene su propio temporizador; se drena con Main aún disponible.
        advanceUntilIdle()
    }

    private fun recuperadorR21() = com.avoqado.pos.giftcards.presentation.RecuperacionDeSaldoViewModel(
        store, storage, coordinator, local)

    @Test fun r21AntesDeRecuperarLaAperturaViejaNoCobraPorOtroMedio() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val subject = duenoR21(carrito, "r21-antes", efectivoReal = true)
            val llave = fondoR21(subject)
            assertTrue(carrito.cartState.value.isEmpty)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.path!!.endsWith("/orders") -> response(201, """{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}""")
                    else -> response(200, """{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}""")
                }
            }
            subject.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CASH)
            subject.processCashPayment(10000)
            runCurrent()
            withContext(Dispatchers.Default) { delay(250) }; runCurrent()
            assertEquals("La foto pendiente no habilita efectivo sobre una identidad vaciada", 0, reservas)
            subject.selectTerminalAndPay("terminal")
            subject.confirmPayment()
            subject.confirmManualChoice(com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(
                com.avoqado.pos.payment.domain.ManualPaymentMethod.TRANSFER))
            subject.cobrarConSaldo(identified, 8000)
            runCurrent()
            withContext(Dispatchers.Default) { delay(250) }; runCurrent()
            assertEquals(0, reservas)
            assertEquals(3, server.requestCount)
            coVerify(exactly = 0) { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
            assertTrue(store.delNegocio("a").single { it.llave == llave }.porRecuperar)
        } finally { cerrarR21(carrito) }
    }

    @Test fun r21DosAperturasVivasQuedanInvalidasAlRecuperar() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val recuperar = recuperadorR21()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val uno = duenoR21(carrito, "r21-uno", efectivoReal = true)
            val dos = duenoR21(carrito, "r21-dos", efectivoReal = true)
            val llave = fondoR21(uno)
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar(llave, carrito))
            assertFalse("No puede sobrevivir el primer snapshot cobrable", uno.esElCobroEnCurso("r21-uno"))
            assertFalse("Tampoco otro VM que vio los mismos artículos", dos.esElCobroEnCurso("r21-dos"))
            assertNull(uno.consumeCompletion()); assertNull(dos.consumeCompletion())
            for (subject in listOf(uno, dos)) {
                subject.processCashPayment(10000); subject.selectTerminalAndPay("terminal")
                subject.confirmManualChoice(com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(
                    com.avoqado.pos.payment.domain.ManualPaymentMethod.TRANSFER))
                assertEquals(DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR), subject.cobrarConSaldo(identified, 8000))
            }
            runCurrent()
            assertEquals(0, reservas); assertEquals(3, server.requestCount)
            coVerify(exactly = 0) { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
            uno.startPaymentFlow(carrito.cartState.value, apertura = "r21-uno")
            assertTrue(uno.esElCobroEnCurso("r21-uno")); assertFalse(dos.esElCobroEnCurso("r21-dos"))
            assertEquals(10000, uno.buildPaymentContext().totalCents)
        } finally { cerrarR21(carrito, recuperar) }
    }

    @Test fun r21RestauracionPropiaConservaSuAperturaEInvalidaLaOtra() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val propia = duenoR21(carrito, "r21-propia")
            val otra = duenoR21(carrito, "r21-otra")
            val identidad = carrito.cartState.value.identidadDeVenta
            server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
            assertTrue(propia.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Rechazado)
            assertTrue(propia.esElCobroEnCurso("r21-propia"))
            assertFalse(otra.esElCobroEnCurso("r21-otra"))
            assertNotEquals(identidad, carrito.cartState.value.identidadDeVenta)
            assertEquals(10000, propia.buildPaymentContext().totalCents)
            assertTrue(store.delNegocio("a").isEmpty()); assertEquals(1, server.requestCount)
        } finally { cerrarR21(carrito) }
    }

    @Test fun r21Reintento404InvalidaOtraAperturaYConservaBytes() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val propia = duenoR21(carrito, "r21-404")
            val otra = duenoR21(carrito, "r21-404-otra")
            server.enqueue(response(503, """{"message":"temporal"}"""))
            assertTrue(propia.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Incierto)
            val bytes = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            server.enqueue(response(404, """{"code":"NOT_FOUND","message":"No existe"}"""))
            server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
            assertTrue(propia.reintentarSaldo() is DesenlaceDeCobro.Rechazado)
            assertTrue(propia.esElCobroEnCurso("r21-404")); assertFalse(otra.esElCobroEnCurso("r21-404-otra"))
            assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertEquals(bytes, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(3, server.requestCount); assertTrue(store.delNegocio("a").isEmpty())
        } finally { cerrarR21(carrito) }
    }

    @Test fun r21ReusoDeLlaveInvalidaOtraAperturaSinOtraCuenta() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val propia = duenoR21(carrito, "r21-reuso")
            val otra = duenoR21(carrito, "r21-reuso-otra")
            server.enqueue(response(409, """{"code":"IDEMPOTENCY_KEY_REUSED","message":"Ya existe"}"""))
            server.enqueue(response(200, """{"success":true,"data":{"found":false}}"""))
            server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
            assertTrue(propia.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Rechazado)
            assertTrue(propia.esElCobroEnCurso("r21-reuso")); assertFalse(otra.esElCobroEnCurso("r21-reuso-otra"))
            val bytes = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertEquals(bytes, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(3, server.requestCount); assertTrue(store.delNegocio("a").isEmpty())
        } finally { cerrarR21(carrito) }
    }

    @Test fun r21ConfirmacionFallidaYDestinoPerdidoInvalidanTambienAlDuenoNuevo() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val recuperar = recuperadorR21()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val primero = duenoR21(carrito, "r21-confirmar-original")
            enlazarR21(primero, carrito, despuesDeRestaurar = { preferences.ok = false })
            server.enqueue(response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}"""))
            assertTrue(primero.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.Rechazado)
            val llave = coordinator.entregaPendienteDelNegocio()!!
            val identidadEntregada = carrito.cartState.value.identidadDeVenta
            assertEquals(10000, carrito.cartState.value.totalCents)
            val segundo = duenoR21(carrito, "r21-confirmar-nuevo", efectivoReal = true)
            segundo.processCashPayment(10000); runCurrent()
            assertEquals(0, reservas); assertEquals(1, server.requestCount)
            // El destino se perdió; la foto durable vuelve a ser recuperable. El segundo VM sigue vivo.
            carrito.clearCart()
            preferences.ok = true
            assertFalse(coordinator.confirmarEntrega(llave))
            assertTrue(store.delNegocio("a").single { it.llave == llave }.porRecuperar)
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar(llave, carrito))
            assertFalse("No basta invalidar al emisor original del POST", segundo.esElCobroEnCurso("r21-confirmar-nuevo"))
            assertFalse(primero.esElCobroEnCurso("r21-confirmar-original"))
            assertNotEquals(identidadEntregada, carrito.cartState.value.identidadDeVenta)
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertTrue(store.delNegocio("a").isEmpty())
            assertTrue(SolicitudesDeTarjeta.reservas(storage.giftCardRequestsJson).isEmpty())
            assertEquals(1, server.requestCount)
        } finally { preferences.ok = true; cerrarR21(carrito, recuperar) }
    }

    @Test fun r21ReservaFallidaYCarritoOcupadoNoInvalidanNiPierdenLaFoto() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val recuperar = recuperadorR21()
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val subject = duenoR21(carrito, "r21-no-entregada")
            val llave = fondoR21(subject)
            val foto = store.delNegocio("a").single().foto
            preferences.ok = false
            assertEquals(Recuperacion.NO_SE_PUDO_SOLTAR, recuperar.recuperar(llave, carrito))
            assertTrue(subject.esElCobroEnCurso("r21-no-entregada")); assertTrue(carrito.cartState.value.isEmpty)
            preferences.ok = true
            carrito.addCustomAmount("Otra venta", 2500)
            assertEquals(Recuperacion.CARRITO_OCUPADO, recuperar.recuperar(llave, carrito))
            assertTrue(subject.esElCobroEnCurso("r21-no-entregada"))
            assertEquals(2500, carrito.cartState.value.totalCents)
            assertEquals(foto, store.delNegocio("a").single().foto)
            carrito.clearCart()
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar(llave, carrito))
            assertFalse(subject.esElCobroEnCurso("r21-no-entregada"))
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertEquals(3, server.requestCount)
        } finally { preferences.ok = true; cerrarR21(carrito, recuperar) }
    }

    @Test fun r21DiarioRecreadoSinDuenoRecuperaFotoSinPost() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val foto = GiftCardsApi.json.encodeToString(FotoDeLaVenta.serializer(), cart.aFoto(null))
        assertTrue(store.guardar(SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "r21-proceso-muerto",
            venueId = "a", creadaEn = 1, cuerpo = "", nuevaCuenta = true, porRecuperar = true, mandado = true, foto = foto)))
        val reabierto = SolicitudesDeTarjetaStore(storage)
        val nuevoCoordinator = CobroConSaldoCoordinator(repo, reabierto, storage, local)
        val recuperar = com.avoqado.pos.giftcards.presentation.RecuperacionDeSaldoViewModel(reabierto, storage, nuevoCoordinator, local)
        try {
            val vacia = carrito.cartState.value.identidadDeVenta
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar("r21-proceso-muerto", carrito))
            assertNotEquals(vacia, carrito.cartState.value.identidadDeVenta)
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertTrue(reabierto.delNegocio("a").isEmpty()); assertEquals(0, server.requestCount)
        } finally { cerrarR21(carrito, recuperar) }
    }

    @Test fun r21CambiarElVinculoNoInvalidaElCobroDeOtroCarrito() = runTest {
        val fabrica = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }
        val uno = fabrica.createViewModel(); val dos = fabrica.createViewModel()
        try {
            val foto = cart.aFoto(null)
            assertTrue(uno.restaurarFoto(foto)); assertTrue(dos.restaurarFoto(foto))
            val movido = duenoR21(uno, "r21-vinculo-anterior")
            val vecino = duenoR21(dos, "r21-vecino")
            movido.startPaymentFlow(dos.cartState.value, apertura = "r21-vinculo-nuevo")
            enlazarR21(movido, dos)
            uno.clearCart(); assertTrue(uno.restaurarFoto(foto))
            assertTrue(movido.esElCobroEnCurso("r21-vinculo-nuevo"))
            assertTrue(vecino.esElCobroEnCurso("r21-vecino"))
            dos.clearCart(); assertTrue(dos.restaurarFoto(foto))
            assertFalse(movido.esElCobroEnCurso("r21-vinculo-nuevo"))
            assertFalse(vecino.esElCobroEnCurso("r21-vecino"))
            assertEquals(0, server.requestCount)
        } finally { cerrarR21(uno, dos) }
    }

    @Test fun r21NoPublicaLaFotoNiInvalidaSiOtroDuenoNoPuedeSoltar() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val recuperar = recuperadorR21()
        var puedeSoltar = false
        var desvincular: () -> Unit = {}
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val subject = duenoR21(carrito, "r21-dinero-en-vuelo")
            val llave = fondoR21(subject)
            desvincular = carrito.observarRestauracionDeFoto({ puedeSoltar }, { true }, {}).first
            val vacia = carrito.cartState.value.identidadDeVenta
            assertEquals(Recuperacion.CARRITO_OCUPADO, recuperar.recuperar(llave, carrito))
            assertTrue(carrito.cartState.value.isEmpty); assertEquals(vacia, carrito.cartState.value.identidadDeVenta)
            assertTrue(subject.esElCobroEnCurso("r21-dinero-en-vuelo"))
            assertTrue(store.delNegocio("a").single { it.llave == llave }.porRecuperar)
            puedeSoltar = true
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar(llave, carrito))
            assertFalse(subject.esElCobroEnCurso("r21-dinero-en-vuelo"))
            assertEquals(10000, carrito.cartState.value.totalCents); assertEquals(3, server.requestCount)
        } finally { desvincular(); cerrarR21(carrito, recuperar) }
    }

    @Test fun r21OtroCobroEnVueloImpideCrearT1YDuplicarSuFoto() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val entrada = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/orders") -> {
                    response(201, """{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}""")
                }
                request.path!!.endsWith("/gift-cards/pay-new-order") ->
                    response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}""")
                else -> {
                    entrada.countDown(); check(soltar.await(10, TimeUnit.SECONDS))
                    response(200, """{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}""")
                }
            }
        }
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val efectivo = duenoR21(carrito, "r21-efectivo-vivo", efectivoReal = true)
            val saldo = duenoR21(carrito, "r21-saldo-vecino")
            val identidad = carrito.cartState.value.identidadDeVenta
            efectivo.processCashPayment(10000)
            assertTrue(withContext(Dispatchers.Default) { entrada.await(5, TimeUnit.SECONDS) })
            assertEquals(DesenlaceDeCobro.NoSalio(TextosDeTarjetas.COMPROBANDO_COBRO), saldo.cobrarConSaldo(identified, 8000))
            assertEquals(identidad, carrito.cartState.value.identidadDeVenta)
            assertFalse(carrito.cartState.value.isEmpty)
            assertTrue(store.delNegocio("a").isEmpty())
            assertEquals(1, reservas); assertEquals(2, server.requestCount)
            soltar.countDown()
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    while (efectivo.state.value !is com.avoqado.pos.payment.data.model.PaymentFlowState.Success) delay(10)
                }
            }
            val completion = efectivo.consumeCompletion()!!
            assertTrue(carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta).aplicadoAlCarritoActual)
            assertTrue(carrito.cartState.value.isEmpty)
            assertEquals(2, server.requestCount)
        } finally { soltar.countDown(); cerrarR21(carrito) }
    }

    @Test fun r21UnaVentaNuevaNoHeredaElVueloDelCobroAnterior() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val entrada = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        var intento: Deferred<DesenlaceDeCobro>? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/gift-cards/pay-new-order") -> {
                    entrada.countDown(); check(soltar.await(10, TimeUnit.SECONDS))
                    response(409, """{"code":"GIFT_CARD_BLOCKED","message":"Blocked"}""")
                }
                request.path!!.endsWith("/fast") ->
                    response(200, """{"success":true,"data":{"id":"fast-payment","amount":25}}""")
                else -> response(500, """{"message":"Ruta inesperada en control de venta rápida"}""")
            }
        }
        try {
            assertTrue(carrito.restaurarFoto(cart.aFoto(null)))
            val anterior = duenoR21(carrito, "r21-venta-anterior")
            intento = async { anterior.cobrarConSaldo(identified, 8000) }
            assertTrue(withContext(Dispatchers.Default) { entrada.await(5, TimeUnit.SECONDS) })
            assertTrue(carrito.cartState.value.isEmpty)
            carrito.addCustomAmount("Otra venta", 2500)
            val nueva = duenoR21(carrito, "r21-venta-nueva", efectivoReal = true)
            nueva.processCashPayment(2500)
            runCurrent(); withContext(Dispatchers.Default) { delay(250) }; runCurrent()
            assertEquals("Una venta distinta no hereda el vuelo del saldo anterior", 1, reservas)
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    while (nueva.state.value !is com.avoqado.pos.payment.data.model.PaymentFlowState.Success) delay(10)
                }
            }
            assertEquals("fast-payment", (nueva.state.value as com.avoqado.pos.payment.data.model.PaymentFlowState.Success).paymentId)
            val completion = nueva.consumeCompletion()!!
            assertTrue(carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta).aplicadoAlCarritoActual)
            assertTrue(carrito.cartState.value.isEmpty)
            soltar.countDown()
            assertTrue(intento.await() is DesenlaceDeCobro.Rechazado)
            assertTrue(anterior.esElCobroEnCurso("r21-venta-anterior"))
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertTrue(store.delNegocio("a").isEmpty())
            assertTrue(nueva.esElCobroEnCurso("r21-venta-nueva"))
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.path!!.endsWith("/gift-cards/pay-new-order"))
            val rapido = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(rapido.path!!.endsWith("/fast"))
            assertEquals(2500.0, Json.parseToJsonElement(rapido.body.readUtf8()).jsonObject.getValue("amount").jsonPrimitive.double, 0.0)
        } finally { soltar.countDown(); intento?.cancelAndJoin(); cerrarR21(carrito) }
    }

    @Test fun r21RecuperarEnOtroNegocioNoTocaAlDuenoAnterior() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val entrada = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        var original: PaymentFlowViewModel? = null
        val recuperar = recuperadorR21()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entrada.countDown(); check(soltar.await(10, TimeUnit.SECONDS))
                return response(201, """{"success":true,"data":{"id":"cash-order","orderNumber":"ORD-CASH","total":100}}""")
            }
        }
        try {
            val foto = cart.aFoto(null)
            assertTrue(carrito.restaurarFoto(foto))
            val anterior = duenoR21(carrito, "r21-negocio-a", efectivoReal = true)
            original = anterior
            anterior.processCashPayment(10000)
            assertTrue(withContext(Dispatchers.Default) { entrada.await(5, TimeUnit.SECONDS) })
            preferences.values["venueId"] = "b"
            carrito.clearCart()
            assertTrue(store.guardar(SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "r21-negocio-b",
                venueId = "b", creadaEn = 1, cuerpo = "", nuevaCuenta = true, porRecuperar = true,
                mandado = true, foto = GiftCardsApi.json.encodeToString(FotoDeLaVenta.serializer(), foto))))
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar("r21-negocio-b", carrito))
            assertTrue("El dueño de otro negocio no se invalida ni bloquea esta entrega", anterior.esElCobroEnCurso("r21-negocio-a"))
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertTrue(store.delNegocio("b").isEmpty())
            assertEquals(1, server.requestCount)
        } finally {
            val trabajo = original?.viewModelScope?.coroutineContext?.get(Job)
            trabajo?.cancel(); soltar.countDown(); trabajo?.join()
            cerrarR21(carrito, recuperar)
        }
    }

    @Test fun r21CuentaConocidaVivaNoSeInvalidaNiConsumeLaFotoRecuperada() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val recuperar = recuperadorR21()
        val entrada = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entrada.countDown(); check(soltar.await(10, TimeUnit.SECONDS))
                return response(200, """{"success":true,"data":{"id":"cash-payment","remainingBalance":0,"orderPaymentStatus":"PAID"}}""")
            }
        }
        try {
            val foto = cart.aFoto(null)
            val conocida = vm("o", efectivoReal = true)
            conocida.startPaymentFlow(cart, resumeOrderId = "o", apertura = "r21-cuenta-conocida")
            enlazarR21(conocida, carrito)
            assertTrue(carrito.cartState.value.isEmpty)
            conocida.processCashPayment(10000)
            assertTrue(withContext(Dispatchers.Default) { entrada.await(5, TimeUnit.SECONDS) })
            assertTrue(store.guardar(SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "r21-otra-foto",
                venueId = "a", creadaEn = 1, cuerpo = "", nuevaCuenta = true, porRecuperar = true,
                mandado = true, foto = GiftCardsApi.json.encodeToString(FotoDeLaVenta.serializer(), foto))))
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar("r21-otra-foto", carrito))
            val identidad = carrito.cartState.value.identidadDeVenta
            assertTrue(conocida.esElCobroEnCurso("r21-cuenta-conocida"))
            soltar.countDown()
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    while (conocida.state.value !is com.avoqado.pos.payment.data.model.PaymentFlowState.Success) delay(10)
                }
            }
            val completion = conocida.consumeCompletion()!!
            assertEquals("o", completion.orderId)
            assertFalse(carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta).aplicadoAlCarritoActual)
            assertEquals(identidad, carrito.cartState.value.identidadDeVenta)
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertEquals(1, reservas); assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.path!!.contains("/orders/o/"))
            assertTrue(store.delNegocio("a").isEmpty())
        } finally { soltar.countDown(); cerrarR21(carrito, recuperar) }
    }

    @Test fun r21EfectivoEnVueloProtegeSuFotoYTerminaUnaVez() = runTest {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val recuperar = recuperadorR21()
        val entrada = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        var dejarDeObservar: (() -> Unit)? = null
        every { terminal.intentoEnVuelo() } returns null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entrada.countDown(); check(soltar.await(10, TimeUnit.SECONDS))
                return response(200, """{"success":true,"data":{"id":"cash-payment-a","remainingBalance":0,"orderPaymentStatus":"PAID"}}""")
            }
        }
        try {
            val foto = cart.aFoto(null)
            assertTrue(carrito.restaurarFoto(foto))
            cart = carrito.cartState.value
            val identidadA = cart.identidadDeVenta
            val subject = vm("o", efectivoReal = true)
            subject.startPaymentFlow(cart, resumeOrderId = "o", apertura = "r21-cuenta-a-tardia")
            enlazarR21(subject, carrito)
            runCurrent()
            val parent = subject.viewModelScope.coroutineContext[Job]!!
            val previos = parent.children.toSet()
            subject.processCashPayment(10000)
            assertTrue(withContext(Dispatchers.Default) { entrada.await(5, TimeUnit.SECONDS) })
            val productores = parent.children.filter { it !in previos }.toList()
            assertTrue("El productor real de efectivo sigue vivo", productores.isNotEmpty())
            subject.cancelarVenta(); runCurrent()
            assertFalse("D2 v6: el efectivo suspendido no permite salir", subject.debeSalir.value)
            val observador = carrito.observarRestauracionDeFoto({ true }, { true }, {})
            dejarDeObservar = observador.first
            assertFalse("Dinero en curso impide otro cobro sobre la misma foto", observador.second())
            assertEquals(identidadA, carrito.cartState.value.identidadDeVenta)
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertEquals("a", storage.venueId)
            soltar.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { productores.joinAll() } }
            runCurrent()
            val completion = subject.consumeCompletion()!!
            assertEquals(identidadA, completion.identidadDeVenta)
            assertEquals("o", completion.orderId)
            assertTrue(carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta).aplicadoAlCarritoActual)
            assertTrue(carrito.cartState.value.isEmpty)
            assertNull("El resultado de A se consume una sola vez", subject.consumeCompletion())
            assertEquals(1, reservas)
            assertTrue(store.guardar(SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "r21-foto-b-tardia",
                venueId = "a", creadaEn = 1, cuerpo = "", nuevaCuenta = true, porRecuperar = true,
                mandado = true, foto = GiftCardsApi.json.encodeToString(FotoDeLaVenta.serializer(), foto))))
            assertEquals(Recuperacion.RECUPERADA, recuperar.recuperar("r21-foto-b-tardia", carrito))
            cart = carrito.cartState.value
            val identidadB = cart.identidadDeVenta
            subject.startPaymentFlow(cart, apertura = "r21-foto-b-tardia")
            enlazarR21(subject, carrito)
            assertFalse("La finalización de A no puede consumir la foto posterior de B",
                carrito.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
                    completion.remainingBalanceCents, identidadDeVentaDelCobro = completion.identidadDeVenta).aplicadoAlCarritoActual)
            assertNull(subject.consumeCompletion())
            assertEquals(identidadB, carrito.cartState.value.identidadDeVenta)
            assertEquals(10000, carrito.cartState.value.totalCents)
            assertEquals("a", storage.venueId)
            assertTrue(subject.state.value is com.avoqado.pos.payment.data.model.PaymentFlowState.SelectingPaymentMethod)
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.path!!.contains("/orders/o/"))
        } finally { dejarDeObservar?.invoke(); soltar.countDown(); cerrarR21(carrito, recuperar) }
    }

    @Test fun r22ControlExitoObsoletoRegistraArqueoAutoritativoSinCambiarB() = runTest {
        controlarEfectivoObsoletoR22(encolable = false)
    }

    @Test fun r22ControlFalloObsoletoEncolaYRegistraArqueoSinCambiarB() = runTest {
        controlarEfectivoObsoletoR22(encolable = true)
    }

    private suspend fun TestScope.controlarEfectivoObsoletoR22(encolable: Boolean) {
        val carrito = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel()
        val arqueo = mockk<com.avoqado.pos.cashdrawer.data.CashDrawerRepository>(relaxed = true)
        val caja = CashDrawerSessionEntity("c000000000000000000000001", "a", "FULLTEST", "staff", "Ana A", 1, 0)
        val eventos = linkedMapOf<String, CashDrawerEventEntity>()
        val cajon = mockk<CashDrawerDao>(relaxed = true)
        coEvery { cajon.getSession(any()) } coAnswers { caja.takeIf { it.id == firstArg<String>() } }
        coEvery { cajon.getSessionEvents(any()) } coAnswers { eventos.values.filter { it.sessionId == firstArg<String>() } }
        coEvery { cajon.insertarEventoSiNoExiste(any()) } coAnswers {
            val evento = firstArg<CashDrawerEventEntity>()
            if (eventos.putIfAbsent(evento.id, evento) == null) 1L else -1L
        }
        coEvery { arqueo.getOpenSession() } returns caja
        every { arqueo.cajasQueEsperanSuApertura() } returns emptySet()
        val salida = SalidaDelApartado(dao, cajon, storage)
        val efectivo = spyk(CashPaymentRepository(storage, dao, salida))
        val filas = filasDelEfectivo.values
        val entrada = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        preferences.values["userFirstName"] = "Ana"
        preferences.values["userLastName"] = "A"
        every { terminal.intentoEnVuelo() } returns null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entrada.countDown(); check(soltar.await(10, TimeUnit.SECONDS))
                return if (encolable) response(503, """{"message":"temporal"}""")
                else response(200, """{"success":true,"payment":{"paymentId":"cash-a","orderId":"o","venueId":"a","status":"COMPLETED","method":"CASH","splitType":"FULLPAYMENT","amount":7500,"tipAmount":500,"remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}""")
            }
        }
        try {
            val subject = vmControlR22(efectivo, arqueo, salida)
            subject.startPaymentFlow(cart, resumeOrderId = "o", apertura = "r22-a")
            enlazarR21(subject, carrito)
            runCurrent()
            val parent = subject.viewModelScope.coroutineContext[Job]!!
            val previos = parent.children.toSet()
            subject.processCashPayment(10000)
            assertTrue(withContext(Dispatchers.Default) { entrada.await(5, TimeUnit.SECONDS) })
            val productores = parent.children.filter { it !in previos }.toList()
            assertTrue(productores.isNotEmpty())
            subject.cancelarVenta(); runCurrent()
            assertFalse("D2 v6 conserva A mientras su efectivo está en vuelo", subject.debeSalir.value)
            subject.consumirSalida()
            preferences.values["venueId"] = "b"
            preferences.values["userId"] = "staff-b"
            preferences.values["userFirstName"] = "Bea"
            preferences.values["userLastName"] = "B"
            val fotoB = cart.copy(items = listOf(CartItem(id = "b", type = CartItemType.ProductItem("catalog-b"),
                name = "Otro artículo", unitPrice = 2500))).aFoto(null)
            assertTrue(carrito.restaurarFoto(fotoB))
            cart = carrito.cartState.value
            val identidadB = cart.identidadDeVenta
            subject.startPaymentFlow(cart, apertura = "r22-b")
            enlazarR21(subject, carrito)
            runCurrent()
            val estadoB = subject.state.value
            soltar.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { productores.joinAll() } }
            runCurrent()
            assertEquals(1, eventos.size)
            coVerify(exactly = 1) { cajon.insertarEventoSiNoExiste(any()) }
            val evento = eventos.values.single()
            assertEquals(CashDrawerEventType.CASH_SALE.name, evento.type)
            assertEquals(caja.id, evento.sessionId)
            assertEquals("a", evento.venueId)
            assertEquals("staff", evento.staffId)
            assertEquals("Ana A", evento.staffName)
            assertEquals("o", evento.orderId)
            assertEquals(if (encolable) 10000 else 8000, evento.amountCents)
            coVerify(exactly = if (encolable) 1 else 0) {
                efectivo.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), cajaDelCobro = caja.id, lineasDelTicket = any())
            }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(request.path!!.contains("/venues/a/orders/o/pay"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("staff", body.getValue("staffId").jsonPrimitive.content)
            val llave = body.getValue("idempotencyKey").jsonPrimitive.content
            assertEquals(com.avoqado.pos.cashdrawer.data.CashDrawerRepository.idDeLaVentaEnEfectivo(llave), evento.id)
            assertEquals(filas.single { it.id == llave }.createdAt, evento.createdAt)
            if (encolable) {
                val pendiente = filas.single { it.syncStatus == "PENDING" }
                assertEquals(body.getValue("idempotencyKey").jsonPrimitive.content, pendiente.id)
                assertEquals("a", pendiente.venueId)
                assertEquals("staff", pendiente.staffId)
                assertEquals("o", pendiente.orderId)
                assertEquals(10000, pendiente.amountCents)
                assertEquals(0, pendiente.tipCents)
            } else {
                assertTrue(filas.none { it.syncStatus == "PENDING" })
                val confirmada = filas.single { it.id == llave }
                assertEquals("SYNCED", confirmada.syncStatus)
                assertEquals(10000, confirmada.amountCents)
                assertEquals(0, confirmada.tipCents)
                assertEquals(ConciliacionDelCobro.DIFERENCIA, confirmada.q15Reason)
            }
            assertEquals(estadoB, subject.state.value)
            assertTrue(estadoB is com.avoqado.pos.payment.data.model.PaymentFlowState.SelectingPaymentMethod)
            assertNull(subject.consumeCompletion())
            assertFalse(subject.debeSalir.value)
            assertEquals(identidadB, carrito.cartState.value.identidadDeVenta)
            assertEquals(2500, carrito.cartState.value.totalCents)
            assertEquals(1, server.requestCount)
        } finally { soltar.countDown(); cerrarR21(carrito) }
    }

    // Colaboradores observables sólo para los controles R22; las fixtures acreditadas quedan literales.
    private fun vmControlR22(efectivo: CashPaymentRepository,
        arqueo: com.avoqado.pos.cashdrawer.data.CashDrawerRepository, salida: SalidaDelApartado): PaymentFlowViewModel {
        val tpv = mockk<com.avoqado.pos.tpvsettings.data.TpvSettingsRepository>()
        every { tpv.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        val areas = mockk<com.avoqado.pos.areatickets.data.AreaTicketRepository>(relaxed = true)
        every { areas.session.current() } returns null
        val orders = OrderRepository(storage, OkHttpClient.Builder().addInterceptor { chain ->
            val destino = server.url(chain.request().url.encodedPath)
            check(java.net.InetAddress.getByName(destino.host).isLoopbackAddress)
            chain.proceed(chain.request().newBuilder().url(destino).build())
        }.build(), dao, salida)
        return PaymentFlowViewModel(orderRepository = orders, cashPaymentRepository = efectivo,
            tenderTypeRepository = mockk(relaxed = true), terminalPaymentService = terminal, tpvSettingsRepository = tpv,
            paymentSyncService = mockk(relaxed = true), cashDrawerRepository = arqueo, kdsRepository = mockk(relaxed = true),
            kdsOrderBus = mockk(relaxed = true), printerService = mockk(relaxed = true), secureStorage = storage,
            comandaDispatcher = mockk(relaxed = true), comandasPendientesStore = mockk(relaxed = true), replayDeComandas = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(), areaTicketRepository = areas,
            cancelacionDeCobro = mockk(relaxed = true), savedStateHandle = androidx.lifecycle.SavedStateHandle(), tableSession = session,
            syncOutbox = mockk(relaxed = true), connectivityMonitor = network, cobroConSaldo = coordinator, tarjetasRepository = repo,
            rolesDeSaldo = roles, cobrosLocalesDeSaldo = local).also { subjects += it }
    }

    @Test fun r7IdentificarBDescartaEl409DeAYMuestraSaldoDeB() = runTest {
        val subject=vm()
        server.enqueue(response(409,"""{"code":"GIFT_CARD_INSUFFICIENT_BALANCE","message":"Quedan 25","saldoDisponible":25}"""))
        assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.SaldoInsuficiente)
        val b=identified.copy(tarjeta=identified.tarjeta.copy(giftCardId="otra",last4="BBBB",balance=450.0))
        subject.descartarSaldoInsuficienteAlIdentificar()
        assertNull(subject.desenlaceDeSaldo.value)
        assertEquals(450.0,com.avoqado.pos.giftcards.presentation.saldoMostrado(b,subject.desenlaceDeSaldo.value),0.0)
        assertEquals(10000,subject.montoParaSaldo(b).montoCents)
        assertEquals(1,server.requestCount)
    }
    @Test fun r7IdentificarNoDescartaInciertoNiSuLlaveODiario() = runTest {
        val subject=vm("o")
        server.enqueue(response(503,"{}"))
        assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.Incierto)
        val diario=storage.giftCardRequestsJson
        val llave=subject.llaveDeLaSolicitudDeSaldo
        assertNotNull(llave)
        subject.descartarSaldoInsuficienteAlIdentificar()
        assertTrue(subject.desenlaceDeSaldo.value is DesenlaceDeCobro.Incierto)
        assertEquals(llave,subject.llaveDeLaSolicitudDeSaldo)
        assertEquals(diario,storage.giftCardRequestsJson)
        assertEquals(1,server.requestCount)
    }
    private suspend fun retirarCapacidadesUnionE() {
        server.enqueue(response(body="""{"success":true,"data":{"enabled":true,"suggestedAmounts":[],"minAmount":100,"maxAmount":10000,"sale":{"sellable":true}}}"""))
        assertEquals(EstadoDeAjustes.NoDisponible, repo.refrescarAjustes("a"))
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)?.path?.endsWith("/settings") == true)
    }
    @Test fun `union E monto abierto observa retiro 200 y no manda dinero nuevo`() = runTest {
        storage.guardarAjustesDeTarjetas("a", GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(), settings.copy(capacidades=setOf("PAGE_TOKEN"))))
        val subject=vm("o"); subject.actualizarFilaDeSaldo(); runCurrent()
        assertTrue(subject.conductaDelSaldo.value.visible)
        retirarCapacidadesUnionE(); runCurrent()
        assertFalse("El cobro abierto observa el retiro sin otro toque", subject.conductaDelSaldo.value.visible)
        assertTrue(subject.cobrarConSaldo(identified, 8000) is DesenlaceDeCobro.NoSalio)
        assertEquals(1, server.requestCount); assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun `union E saldo incierto mantiene llave y recupera despues de retirar capacidades`() = runTest {
        val subject=vm("o"); server.enqueue(response(503,"{}"))
        assertTrue(subject.cobrarConSaldo(identified,8000) is DesenlaceDeCobro.Incierto)
        server.takeRequest(1,TimeUnit.SECONDS)
        val diario=storage.giftCardRequestsJson;val llave=subject.llaveDeLaSolicitudDeSaldo
        retirarCapacidadesUnionE(); runCurrent()
        assertEquals(diario,storage.giftCardRequestsJson);assertEquals(llave,subject.llaveDeLaSolicitudDeSaldo)
        assertTrue(subject.desenlaceDeSaldo.value is DesenlaceDeCobro.Incierto)
        server.enqueue(response(body="""{"success":true,"data":{"found":false}}"""));server.enqueue(response())
        assertTrue(subject.reintentarSaldo() is DesenlaceDeCobro.Cobrado)
        val get=server.takeRequest(1,TimeUnit.SECONDS)!!;val replay=server.takeRequest(1,TimeUnit.SECONDS)!!
        assertEquals("GET",get.method);assertTrue(get.path!!.endsWith("/payments/"+llave))
        assertEquals(SolicitudesDeTarjeta.leer(diario).single().cuerpo,replay.body.readUtf8())
        assertEquals(4,server.requestCount);assertEquals(1,cleared)
    }

}
