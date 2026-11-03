package com.avoqado.pos.giftcards

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*

class ResolutorDeSolicitudesDeTarjetaTest {
    private val fake = SolicitudesDeTarjetaTest.Preferencias().apply {
        values["venueId"] = "a"; values["accessToken"] = "fixture-con-sesion"
    }
    private val storage = fake.almacen()
    private val store = SolicitudesDeTarjetaStore(storage)
    private val conectado = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conectado
        every { isServerReachable } returns servidor
    }
    private val repo = mockk<GiftCardsRepository>()
    private fun fila(orderId: String? = "o", venue: String = "a") = SolicitudDeTarjeta(
        tipo = "GIFT_CARD_SALE", llave = "k", venueId = venue, creadaEn = 1, orderId = orderId,
        cuerpo = "{ \"iniciadoPor\":\"Ana\", \"amount\":500.00 }", iniciadoPor = "Ana")
    private fun cuenta(status: String) = CuentaDeTarjetas("o", "n", 500.0, "PENDING", "CONFIRMED",
        sobres = listOf(SobreDeLaCuenta("g", "1234", 500.0, 500.0, status, deliveryChannel = "PRINTED",
            usada = false, puedeReenviar = false, puedeCambiarDestino = false, puedeRevelar = false)), cancel = Cancelacion(true))
    private fun vm() = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red)
    @Test fun `P1 orden anotada consulta S4 nunca reenvia venta y ACTIVE publica`() = runBlocking {
        assertTrue(store.guardar(fila()))
        coEvery { repo.cuenta("a", "o") } returns Respuesta.Ok(cuenta("ACTIVE"))
        val vm = vm(); vm.resolverAhora()
        assertEquals(listOf("k"), vm.ventasListas.value)
        assertEquals(1, store.delNegocio("a").size)
        coVerify(exactly = 1) { repo.cuenta("a", "o") }
        coVerify(exactly = 0) { repo.vender(any(), any()) }
    }
    @Test fun `P1 sin orden reenvia bytes originales y anota PENDING`() = runBlocking {
        val original = fila(null); assertTrue(store.guardar(original))
        coEvery { repo.vender("a", original.cuerpo) } returns Respuesta.Ok(VentaDeTarjetas("o", "n", 500.0,
            sobres = listOf(SobreVendido("g", ultimos4 = "1234", monto = 500.0, deliveryChannel = "PRINTED", status = "PENDING"))))
        val vm = vm(); vm.resolverAhora()
        assertEquals("o", store.delNegocio("a").single().orderId)
        assertEquals(original.cuerpo, store.delNegocio("a").single().cuerpo)
        assertTrue(vm.ventasListas.value.isEmpty())
        coVerify(exactly = 1) { repo.vender("a", original.cuerpo) }
    }
    @Test fun `DEACTIVATED solo quita con escritura confirmada y PENDING conserva`() = runBlocking {
        assertTrue(store.guardar(fila()))
        coEvery { repo.cuenta("a", "o") } returns Respuesta.Ok(cuenta("PENDING"))
        val vm = vm(); vm.resolverAhora(); assertEquals(1, store.delNegocio("a").size)
        coEvery { repo.cuenta("a", "o") } returns Respuesta.Ok(cuenta("DEACTIVATED"))
        fake.ok = false; vm.resolverAhora(); assertEquals(1, store.delNegocio("a").size)
        fake.ok = true; vm.resolverAhora(); assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun `negocio ajeno en vuelo y falta una bandera de red no se tocan`() = runBlocking {
        assertTrue(store.guardar(fila(venue = "b")))
        val vm = vm(); vm.resolverAhora()
        assertTrue(store.quitar("k")); assertTrue(store.guardar(fila()))
        store.enVueloDeEsteProceso.add("k"); vm.resolverAhora(); store.enVueloDeEsteProceso.remove("k")
        conectado.value = false; vm.resolverAhora()
        conectado.value = true; servidor.value = false; vm.resolverAhora()
        assertEquals(1, store.delNegocio("a").size)
        coVerify(exactly = 0) { repo.cuenta(any(), any()) }
        coVerify(exactly = 0) { repo.vender(any(), any()) }
    }
    @Test fun `P1 dos pasadas concurrentes no mandan la misma fila a la vez`() = runBlocking {
        assertTrue(store.guardar(fila(null)))
        var concurrentes = 0; var maximo = 0
        coEvery { repo.vender(any(), any()) } coAnswers {
            concurrentes++; maximo = maxOf(maximo, concurrentes); delay(20); concurrentes--
            Respuesta.SinRespuesta
        }
        val vm = vm(); coroutineScope { awaitAll(async { vm.resolverAhora() }, async { vm.resolverAhora() }) }
        assertEquals(1, maximo)
        assertEquals(1, store.delNegocio("a").size)
    }
    @Test fun `start al recuperar ambas banderas publica venta y es idempotente`() = runBlocking {
        assertTrue(store.guardar(fila())); conectado.value = false; servidor.value = false
        coEvery { repo.cuenta("a", "o") } returns Respuesta.Ok(cuenta("ACTIVE"))
        val vm = vm(); vm.start(); vm.start()
        conectado.value = true
        assertTrue(vm.ventasListas.value.isEmpty())
        servidor.value = true
        val resultado = withTimeoutOrNull(2000) { vm.ventasListas.first { it.isNotEmpty() } }
        assertEquals(listOf("k"), resultado)
        coVerify(exactly = 1) { repo.cuenta("a", "o") }
    }

    @Test fun `R5 K2 anotada mientras K1 espera consulta S4 sin repetir POST`() = runBlocking {
        assertTrue(store.guardar(fila("o1").copy(llave = "k1")))
        assertTrue(store.guardar(fila(null).copy(llave = "k2")))
        val consultadas = mutableListOf<String>()
        val ventas = mutableListOf<String>()
        val esperandoK1 = CompletableDeferred<Unit>()
        val continuarK1 = CompletableDeferred<Unit>()
        coEvery { repo.cuenta("a", "o1") } coAnswers {
            consultadas += "o1"
            esperandoK1.complete(Unit)
            continuarK1.await()
            Respuesta.Ok(cuenta("PENDING"))
        }
        coEvery { repo.cuenta("a", "o2") } coAnswers {
            consultadas += "o2"
            Respuesta.Ok(cuenta("ACTIVE"))
        }
        coEvery { repo.vender(any(), any()) } coAnswers {
            ventas += secondArg<String>()
            Respuesta.SinRespuesta
        }
        val vm = vm()
        val pasada = async { vm.resolverAhora() }
        withTimeout(2000) { esperandoK1.await() }
        assertTrue(store.anotarOrden("k2", "o2", "n2"))
        continuarK1.complete(Unit)
        pasada.await()

        assertEquals("K2 ya tiene folio: sólo GET S4", listOf("o1", "o2"), consultadas)
        assertEquals("No repetir creación de K2", 0, ventas.size)
        assertEquals(listOf("k2"), vm.ventasListas.value)
        assertEquals("o2", store.delNegocio("a").single { it.llave == "k2" }.orderId)
    }
    @Test fun `AN4 mezcla activa y pending abre resultado sin limpiar`() = runBlocking {
        assertTrue(store.guardar(fila()))
        val a=cuenta("ACTIVE")
        coEvery { repo.cuenta("a","o") } returns Respuesta.Ok(a.copy(sobres=a.sobres + a.sobres.single().copy(id="g2",status="PENDING")))
        val vm=vm();vm.resolverAhora()
        assertEquals(listOf("k"),vm.ventasListas.value)
        assertEquals(1,store.delNegocio("a").size)
    }
    @Test fun `AN4 rechazo de S4 con folio no destruye la evidencia`() = runBlocking {
        assertTrue(store.guardar(fila()))
        coEvery { repo.cuenta("a","o") } returns Respuesta.Rechazo(FalloDeTarjeta(409,"GIFT_CARDS_DISABLED","Apagadas",null,null))
        val vm=vm();vm.resolverAhora()
        assertEquals(1,store.delNegocio("a").size)
        assertEquals("o",store.delNegocio("a").single().orderId)
    }

    private fun redeem(orderId: String? = null, mandado: Boolean = false, mostrador: Boolean = true) = SolicitudDeTarjeta(
        tipo = "GIFT_CARD_REDEEM", llave = "red", venueId = "a", creadaEn = 1, orderId = orderId,
        nuevaCuenta = orderId == null, deMostrador = mostrador, cuerpo = "secreto", mandado = mandado)

    @Test fun T1InerteSeLimpiaAunqueNoHayaRed() = runBlocking {
        assertTrue(store.guardar(redeem())); conectado.value = false; servidor.value = false
        vm().resolverAhora()
        assertTrue(store.delNegocio("a").isEmpty())
        coVerify(exactly = 0) { repo.recuperarCobro(any(), any()) }
    }
    @Test fun T2InerteSoloSeConvierteAlArrancar() = runBlocking {
        assertTrue(store.guardar(redeem("o"))); conectado.value = false
        val vm = vm(); vm.resolverAhora()
        assertFalse(store.delNegocio("a").single().porTerminar)
        vm.resolverAhora(arranque = true)
        val s = store.delNegocio("a").single()
        assertTrue(s.porTerminar); assertEquals("", s.cuerpo); assertEquals("o", s.orderId)
        assertTrue(store.cuentasTrabadas.value.isEmpty())
        coVerify(exactly = 0) { repo.recuperarCobro(any(), any()) }
    }
    @Test fun inerteEnVueloNoSeTocaNiAlArrancar() = runBlocking {
        val original = redeem("o"); assertTrue(store.guardar(original))
        store.enVueloDeEsteProceso.add("red")
        vm().resolverAhora(arranque = true)
        assertEquals(original, store.delNegocio("a").single())
    }
    @Test fun resuelveRedeemDelNegocioActualYRespetaEnVuelo() = runBlocking {
        assertTrue(store.guardar(redeem("o", true)))
        val dao = mockk<com.avoqado.pos.core.data.local.database.PendingPaymentDao>()
        coEvery { dao.forVenue(any()) } returns emptyList()
        val coordinador = CobroConSaldoCoordinator(repo, store, storage, CobrosLocalesDeLaCuenta(dao, storage, store))
        val vm = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red, coordinador)
        val cobrado = CobroConSaldo("p", "o", "n", 80.0, 0.0, emptyList(), 0.0, "PAID", null, null)
        coEvery { repo.recuperarCobro("a", "red") } returns Respuesta.Ok(cobrado)
        store.enVueloDeEsteProceso.add("red"); vm.resolverAhora()
        coVerify(exactly = 0) { repo.recuperarCobro(any(), any()) }
        store.enVueloDeEsteProceso.remove("red"); vm.resolverAhora()
        assertTrue(store.delNegocio("a").isEmpty())
        coVerify(exactly = 1) { repo.recuperarCobro("a", "red") }
    }

    @Test fun vuelveCadaCincoSegundosSoloMientrasHayDuda() = runBlocking {
        assertTrue(store.guardar(redeem("o", true)))
        val dao = mockk<com.avoqado.pos.core.data.local.database.PendingPaymentDao>()
        coEvery { dao.forVenue(any()) } returns emptyList()
        val coordinador = CobroConSaldoCoordinator(repo, store, storage, CobrosLocalesDeLaCuenta(dao, storage, store))
        val vm = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red, coordinador)
        val llamadas = java.util.concurrent.atomic.AtomicInteger()
        val segunda = CompletableDeferred<Unit>()
        coEvery { repo.recuperarCobro("a", "red") } coAnswers {
            if (llamadas.incrementAndGet() >= 2) segunda.complete(Unit)
            Respuesta.SinRespuesta
        }
        vm.start()
        val volvio = withTimeoutOrNull(6500) { segunda.await(); true } ?: false
        assertTrue("Debe volver a consultar tras cinco segundos", volvio)
        store.dejarPorTerminar("red", "o", "n", 50000)
        val antes = llamadas.get()
        delay(5500)
        assertEquals("porTerminar no sostiene sondeo de cobro", antes, llamadas.get())
    }

    @Test fun cambiarSucursalLimpiaLasPublicacionesDeCobroAnterior() = runBlocking {
        assertTrue(store.guardar(redeem("o", true)))
        val dao = mockk<com.avoqado.pos.core.data.local.database.PendingPaymentDao>()
        coEvery { dao.forVenue(any()) } returns emptyList()
        val coordinador = CobroConSaldoCoordinator(repo, store, storage, CobrosLocalesDeLaCuenta(dao, storage, store))
        val vm = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red, coordinador)
        coEvery { repo.recuperarCobro("a", "red") } returns Respuesta.Ok(CobroConSaldo("p", "o", "n", 80.0, 0.0, emptyList(), 0.0, "PAID", null, null))
        vm.resolverAhora(); assertEquals(1, vm.recuperados.value.size)
        storage.venueId = "b"; vm.onSessionChanged(true)
        assertTrue(vm.recuperados.value.isEmpty())
        assertTrue(vm.porRecuperar.value.isEmpty()); assertTrue(vm.porOfrecerAlArrancar.value.isEmpty())
    }

    @Test fun limpiaInerteSinRedYReconectarDespiertaLoMandado() = runBlocking {
        assertTrue(store.guardar(redeem().copy(llave = "inerte")))
        assertTrue(store.guardar(redeem("o", true)))
        conectado.value = false
        val dao = mockk<com.avoqado.pos.core.data.local.database.PendingPaymentDao>()
        coEvery { dao.forVenue(any()) } returns emptyList()
        val coordinador = CobroConSaldoCoordinator(repo, store, storage, CobrosLocalesDeLaCuenta(dao, storage, store))
        val vm = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red, coordinador)
        coEvery { repo.recuperarCobro("a", "red") } returns Respuesta.Ok(CobroConSaldo("p", "o", "n", 80.0, 0.0, emptyList(), 0.0, "PAID", null, null))
        vm.start()
        withTimeout(2000) { store.solicitudes.first { rows -> rows.none { it.llave == "inerte" } } }
        coVerify(exactly = 0) { repo.recuperarCobro(any(), any()) }
        conectado.value = true
        val resuelto = withTimeoutOrNull(2500) { vm.recuperados.first { it.isNotEmpty() } }
        assertNotNull("Reconectar debe despertar la misma llave", resuelto)
    }

    @Test fun porTerminarReleeRestoYSoloSaleAlTerminarLaCuenta() = runBlocking {
        assertTrue(store.guardar(redeem("o", true).copy(porTerminar = true, cuerpo = "")))
        val cuentas = mockk<com.avoqado.pos.tables.data.TableServiceRepository>()
        val detalle = com.avoqado.pos.tables.data.OrderDetail("o", status = "CONFIRMED", paymentStatus = "PARTIAL",
            preBillBalance = com.avoqado.pos.tables.data.PreBillBalance(100000,20000,80000,0))
        coEvery { cuentas.getOrderDetail("a", "o") } returns Result.success(detalle)
        val vm = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red, cuentas = cuentas)
        vm.resolverAhora()
        assertEquals(80000, store.delNegocio("a").single().restanteCents)
        assertTrue(store.cuentasTrabadas.value.isEmpty())
        coEvery { cuentas.getOrderDetail("a", "o") } returns Result.success(detalle.copy(paymentStatus = "PAID"))
        vm.resolverAhora(); assertTrue(store.delNegocio("a").isEmpty())
        coVerify(exactly = 0) { repo.recuperarCobro(any(), any()) }
    }

}
