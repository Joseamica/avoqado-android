package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class CuentasAbiertasConSaldoTest {
    @Test fun saldoCanonicoSinPropinaConservaElResto() {
        val lectura = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<OrderDetail>(
            """{"id":"o","total":1000.0,"preBillBalance":{"orderTotalCents":100000,"totalPaidCents":20000,"remainingBalanceCents":80000}}"""
        ) }
        assertTrue("El saldo canónico debe leerse aunque falte la propina: ${lectura.exceptionOrNull()?.javaClass?.simpleName}", lectura.isSuccess)
        val detalle = lectura.getOrThrow()
        assertEquals(80000, restanteCents(detalle))
        assertEquals(0, detalle.preBillBalance?.tipAmountCents)
    }

    @Test fun detalleReleeYNoOfreceConPendiente()=runTest {
        val repo=mockk<TableServiceRepository>()
        val storage=mockk<SecureStorage>();every {storage.venueId} returns "a"
        val locales=mockk<CobrosLocalesDeLaCuenta>()
        var pendiente=true;var reads=0
        coEvery {locales.hayPendiente("a","o")} answers {pendiente}
        var cuenta=OrderDetail("o",esDeMesa=false,cobrosConSaldo=1,total=100.0,preBillBalance=PreBillBalance(10000,2000,8000,0))
        coEvery {repo.getOrderDetail("a","o")} answers {reads++;Result.success(cuenta)}
        val vm=CobroDeCuentaViewModel(repo,storage,mockk<ConnectivityMonitor>(),mockk(relaxed=true),mockk(relaxed=true),locales)
        assertEquals(OfertaDeLaCuenta.COBRO_SIN_SINCRONIZAR,vm.ofertaParaCobrar("o"))
        pendiente=false
        assertEquals(OfertaDeLaCuenta.OFRECER,vm.ofertaParaCobrar("o"))
        assertEquals(2,reads)
        cuenta=cuenta.copy(paymentStatus="PAID")
        assertEquals(OfertaDeLaCuenta.NADA,vm.ofertaParaCobrar("o"))
        assertEquals(3,reads)
        cuenta=cuenta.copy(paymentStatus="PARTIAL",esDeMesa=true)
        assertEquals(OfertaDeLaCuenta.NADA,vm.ofertaParaCobrar("o"))
        cuenta=cuenta.copy(esDeMesa=false)
        assertEquals(OfertaDeLaCuenta.NADA,vm.ofertaParaCobrar("o",laTraeUnCarrito=true))
    }
    @Test fun avisosSonUnoPorCuentaYNuncaIncluyenT1NiOtroNegocio() {
        val fila=SolicitudDeTarjeta(tipo="GIFT_CARD_REDEEM",llave="uno",venueId="a",creadaEn=1,cuerpo="",orderId="o",porTerminar=true,restanteCents=2000)
        val rows=listOf(fila,fila.copy(llave="dos"),fila.copy(llave="t1",orderId=null,nuevaCuenta=true,porTerminar=false,porRecuperar=true))
        val avisos=com.avoqado.pos.giftcards.presentation.avisosDeCuentasConSaldo(rows)
        assertEquals(listOf("o"),avisos.map {it.orderId})
        assertTrue(avisos.all {it.cuerpo.isEmpty()})
    }

}
