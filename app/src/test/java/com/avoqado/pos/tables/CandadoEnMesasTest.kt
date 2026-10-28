package com.avoqado.pos.tables

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.tables.data.*
import com.avoqado.pos.tables.presentation.*
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class CandadoEnMesasTest(private val action:String,private val mode:String) {
    @get:Rule val main=MainDispatcherRule(StandardTestDispatcher())
    private val repo=mockk<TableServiceRepository>(relaxed=true)
    private val storage=mockk<SecureStorage>(relaxed=true)
    private val outbox=mockk<SyncOutbox>(relaxed=true)
    private val session=TableSession()
    private var raw:String?=null
    private var venue="a"
    private var writes=0
    private var intents=0
    private val table=DiningTable("t","1",openOrders=listOf(OpenCheckSummary("o",total=100.0)))
    private val source=DiningTable("t2","2",openOrders=listOf(OpenCheckSummary("source",total=50.0)))
    private val detalle=OrderDetail("o",total=100.0,customerId="customer",items=listOf(
        OrderDetailItem(id="line",productName="A",quantity=1,unitPrice=50.0,total=50.0,seat=1),
        OrderDetailItem(id="line2",productName="B",quantity=1,unitPrice=50.0,total=50.0,seat=2)))
    private fun pending() {
        raw=SolicitudesDeTarjeta.agregar(null,SolicitudDeTarjeta(tipo="GIFT_CARD_REDEEM",llave="k",venueId=if(mode=="otraSucursal") "b" else "a",
            creadaEn=1,orderId=if(mode=="origen") "source" else "o",cuerpo="{}",mandado=mode!="inerte",porTerminar=mode=="porTerminar"))
    }
    @Before fun prepare() {
        every {storage.venueId} answers {venue};every {storage.giftCardRequestsJson} answers {raw}
        every {repo.tables} returns MutableStateFlow(listOf(table,source));every {repo.isLoading} returns MutableStateFlow(false)
        every {repo.ownership} returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every {outbox.acks} returns MutableSharedFlow();every {outbox.pendingCount} returns MutableStateFlow(0)
        coEvery {outbox.trabajoSinResolverDeLaOrden(any(),any())} returns com.avoqado.pos.core.data.sync.TrabajoSinResolver(0,0,emptySet(),0)
        coEvery {outbox.enqueue(any(),any(),any(),any(),any(),any())} answers {intents++;arg<String>(3)}
        coEvery {repo.getOrderDetail(any(),any())} returns Result.success(detalle)
        coEvery {repo.getCustomerLoyalty(any(),any(),any())} returns Result.failure(IllegalStateException("sin lealtad en esta prueba"))
        coEvery {repo.compItem(any(),any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.compWholeOrder(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.cancelOrder(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.clearTable(any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.mergeOrders(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.splitOrderBySeat(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.splitOrder(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.applyOrderDiscount(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.removeOrderDiscount(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.applyServiceCharge(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.removeServiceCharge(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.redeemLoyaltyPoints(any(),any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.moveOrder(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.assignOrder(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.updateOrderDetails(any(),any(),any())} answers {writes++;Result.success(Unit)}
        coEvery {repo.addRound(any(),any(),any(),any())} answers {writes++;Result.success(UpdatedOrder("o",2,110.0))}
        session.start(TableSession.Active("t","1",null,"o","ORD",1,10000,TableSession.Mode.ORDERING))
    }
    @Test fun cadaEscritorDeMesaConsultaElCandado()=runTest {
        val network=mockk<ConnectivityMonitor>(relaxed=true)
        every {network.isConnected} returns MutableStateFlow(mode!="offline")
        val config=mockk<PrintConfigRepository>(relaxed=true);every {config.getCurrentConfig()} returns PrintConfig()
        val order=TableOrderViewModel(repo,session,config,mockk(relaxed=true),mockk(relaxed=true),storage,outbox,
            mockk(relaxed=true),network,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        val floor=TablesViewModel(repo,mockk(relaxed=true),session,mockk(relaxed=true),storage,outbox,mockk(relaxed=true),
            mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        try {
            order.loadCheck();advanceUntilIdle()
            if(mode!="posterior" && mode!="cambioSucursal") pending()
            when(action) {
                "compSentItem"->order.compSentItem("line","reason")
                "compWholeCheck"->order.compWholeCheck("reason")
                "splitBySeat"->order.splitBySeat {_,_->}
                "splitItems"->order.splitItems(listOf("line"))
                "mergeFrom"->order.mergeFrom("source"){_,_->}
                "applyDiscount"->order.applyDiscount("discount")
                "removeDiscount"->order.removeDiscount("discount")
                "applyServiceCharge"->order.applyServiceCharge("charge")
                "removeServiceCharge"->order.removeServiceCharge("charge")
                "redeemPoints"->order.redeemPoints(10)
                "anularCuenta"->order.anularCuenta("reason"){_,_->}
                "moveTable"->order.moveTable("target"){_,_->}
                "assignWaiter"->order.assignWaiter("staff","Name")
                "updateDetails"->order.updateDetails(notes="notes")
                "sendRound"->{order.addCustomAmount("Servicio",1000);order.sendRound()}
                "floor.compItem"->floor.compItem(table,"line","reason")
                "floor.anularCuenta"->floor.anularCuenta(table,"reason")
                "finishTableAfterPayment"->floor.finishTableAfterPayment()
                "bulkAnular"->floor.bulkAnular(listOf(table),"reason"){_,_,_->}
                "bulkCortesia"->floor.bulkCortesia(listOf(table),"reason"){_,_->}
                "bulkUnir"->floor.bulkUnir(table,listOf(source)){_,_,_->}
                "bulkMover"->floor.bulkMover(table,"target"){_,_->}
                "bulkAsignar"->floor.bulkAsignar(listOf(table),"staff"){_,_->}
            }
            if(mode=="posterior") pending()
            if(mode=="cambioSucursal") venue="b"
            advanceUntilIdle()
            val allowed=action in setOf("sendRound","moveTable","assignWaiter","updateDetails","bulkMover","bulkAsignar")
            val pendingHere=mode in setOf("activa","posterior","offline") || mode=="origen" && action in setOf("mergeFrom","bulkUnir")
            val blocked=mode=="cambioSucursal" || !allowed && pendingHere
            val ordinary=if(action in setOf("anularCuenta","floor.anularCuenta","bulkAnular")) 2 else 1
            assertEquals("$action/$mode",if(blocked) 0 else ordinary,writes)
            if(action!="sendRound") assertEquals("no intent for $action/$mode",0,intents)
            if(mode=="offline" && action in setOf("compSentItem","removeDiscount","removeServiceCharge","redeemPoints"))
                assertEquals("T8 antes del aviso de red: $action",com.avoqado.pos.giftcards.domain.TextosDeTarjetas.COMPROBANDO_COBRO,
                    order.blockedNotice.value)
        } finally {order.viewModelScope.cancel();floor.viewModelScope.cancel()}
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}/{1}") fun cases():List<Array<Any>> =
            listOf("compSentItem","compWholeCheck","splitBySeat","splitItems","mergeFrom","applyDiscount","removeDiscount",
                "applyServiceCharge","removeServiceCharge","redeemPoints","anularCuenta","moveTable","assignWaiter","updateDetails","sendRound",
                "floor.compItem","floor.anularCuenta","finishTableAfterPayment","bulkAnular","bulkCortesia","bulkUnir","bulkMover","bulkAsignar")
                .flatMap {action -> listOf("activa","posterior","inerte","porTerminar","otraSucursal","origen","cambioSucursal","offline").map {arrayOf<Any>(action,it)}}
    }
}
