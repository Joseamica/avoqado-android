package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.sync.*
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RevisionR16Test {
    private val prefs = SolicitudesDeTarjetaTest.Preferencias().apply {
        values["venueId"] = "a"; values["userId"] = "staff"; values["accessToken"] = "fixture"
    }
    private val storage = prefs.almacen()
    private val store = SolicitudesDeTarjetaStore(storage)
    private val dao = mockk<PendingPaymentDao> { coEvery { forVenue(any()) } returns emptyList() }
    private val outbox = mockk<SyncOutbox>()
    private val repo = mockk<GiftCardsRepository> {
        coEvery { cobrar(any(), any(), any()) } returns Respuesta.SinRespuesta
        coEvery { recuperarCobro(any(), any()) } returns Respuesta.SinRespuesta
    }
    private val local = CobrosLocalesDeLaCuenta(dao, storage, store, outbox)
    private val cobro = CobroConSaldoCoordinator(repo, store, storage, local)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns MutableStateFlow(true)
        every { isServerReachable } returns MutableStateFlow(true)
    }
    private fun duda(key: String) = SolicitudDeTarjeta(tipo="GIFT_CARD_REDEEM", llave=key,
        venueId="a", creadaEn=1, orderId=key, cuerpo="{}", mandado=true)

    @Test fun efectivoDelOutboxBloqueaSaldoSinPost() = runBlocking {
        coEvery { outbox.trabajoSinResolverDeLaOrden("a", setOf("o")) } returns TrabajoSinResolver(1,0,setOf("PAY_CASH"),2000)
        assertTrue("El efectivo pendiente también es cobro local", local.hayPendiente("a","o"))
        assertTrue(cobro.cobrar("a","o",PruebaDeTarjeta.Codigo("fixture"),8000,0) is DesenlaceDeCobro.NoSalio)
        coVerify(exactly=0) { repo.cobrar(any(),any(),any()) }
    }
    @Test fun lecturaFallidaDeOutboxBloqueaSaldoSinPost() = runBlocking {
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } throws IllegalStateException("lectura fallida")
        assertTrue("Una lectura fallida no acredita que esté limpia", local.hayPendiente("a","o"))
        assertTrue(cobro.cobrar("a","o",PruebaDeTarjeta.Codigo("fixture"),8000,0) is DesenlaceDeCobro.NoSalio)
        coVerify(exactly=0) { repo.cobrar(any(),any(),any()) }
    }
    @Test fun ordenLimpiaNoSeBloqueaYLaLecturaUsaSuIdentidad() = runBlocking {
        coEvery { outbox.trabajoSinResolverDeLaOrden("a",setOf("o")) } returns TrabajoSinResolver(1,0,setOf("ADD_ITEMS"),0)
        assertFalse(local.hayPendiente("a","o"))
        coVerify(exactly=1) { outbox.trabajoSinResolverDeLaOrden("a",setOf("o")) }
    }
    @Test fun diarioVacioLuegoDudaConRedEstableDespiertaSolo() = runBlocking {
        val vm = ResolutorDeSolicitudesDeTarjeta(repo,store,storage,red,cobro)
        vm.start()
        delay(250)
        val consultada = CompletableDeferred<Unit>()
        coEvery { repo.recuperarCobro("a","nueva") } coAnswers { consultada.complete(Unit); Respuesta.SinRespuesta }
        assertTrue(store.guardar(duda("nueva")))
        try {
            assertEquals("La duda debe despertar sin cambiar la red", true,
                withTimeoutOrNull(2000) { consultada.await(); true })
        } finally { store.quitar("nueva"); vm.onSessionChanged(false) }
    }
    @Test fun sondeoDeDudaNoReleeLaCuentaPorTerminar() = runBlocking {
        val cuentas = mockk<TableServiceRepository>()
        val leidas = java.util.concurrent.atomic.AtomicInteger()
        coEvery { cuentas.getOrderDetail("a","resto") } coAnswers {
            leidas.incrementAndGet()
            Result.success(OrderDetail("resto",status="CONFIRMED",paymentStatus="PARTIAL",
                preBillBalance=PreBillBalance(10000,2000,8000,0)))
        }
        assertTrue(store.guardar(duda("resto").copy(porTerminar=true,cuerpo="")))
        assertTrue(store.guardar(duda("incierta")))
        val vm = ResolutorDeSolicitudesDeTarjeta(repo,store,storage,red,cobro,cuentas)
        vm.start()
        try {
            delay(5600)
            assertEquals("porTerminar sólo se consulta en eventos, nunca en el sondeo",1,leidas.get())
            coVerify(atLeast=2) { repo.recuperarCobro("a","incierta") }
        } finally { store.quitar("incierta"); store.quitar("resto"); vm.onSessionChanged(false) }
    }
}
