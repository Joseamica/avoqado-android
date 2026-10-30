package com.avoqado.pos.giftcards

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.giftcards.presentation.ResultadoDeVentaViewModel
import com.avoqado.pos.printing.data.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.Collections

class ResultadoDeVentaViewModelTest {
    private val venue = "c123456789012345678901234"
    private val orden = "c223456789012345678901234"
    private val tarjeta = "c323456789012345678901234"
    private val fake = SolicitudesDeTarjetaTest.Preferencias().apply { values["venueId"] = venue }
    private val storage = fake.almacen()
    private val store = SolicitudesDeTarjetaStore(storage)
    private val conectado = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor> { every { isConnected } returns conectado; every { isServerReachable } returns conectado }
    private val printer = mockk<PrinterService>()
    private val papel = mutableListOf<CertificadoDeTarjeta>()
    private lateinit var server: MockWebServer
    private lateinit var repo: GiftCardsRepository
    private val peticiones = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private var estados = listOf("ACTIVE")
    private var canal = "PRINTED"
    private var entrega = "PENDING"
    private var permiso = true
    private var gerente: Boolean? = false
    private var errorReveal: String? = null
    private var permisoCuentaDenegado = false
    private var gerenteDenegado = false
    private var statusDetalle = 200
    private var statusAccion = 200
    private var venceEl: String? = null
    private var liga: String? = "https://example.invalid/actual"
    private var outcome: PrinterService.PrintOutcome = PrinterService.PrintOutcome.Printed(1)
    private val solicitud get(): SolicitudDeTarjeta {
        val cuerpo=buildJsonObject {
            put("idempotencyKey","venta-an4");put("source","AVOQADO_ANDROID");put("iniciadoPor","c423456789012345678901234");put("iniciadoPorNombre","Ana")
            putJsonArray("sobres") { repeat(estados.size) { add(buildJsonObject {put("amount",500.01);put("deliveryChannel","PRINTED")}) } }
        }.toString()
        return SolicitudDeTarjeta(tipo="GIFT_CARD_SALE",llave="venta-an4",venueId=venue,creadaEn=1,orderId=orden,cuerpo=cuerpo)
    }
    @Before fun preparar() {
        server = MockWebServer()
        server.dispatcher = object: Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                peticiones.add(r)
                val path = r.path.orEmpty()
                if (path.endsWith("/orders/$orden/reveal") && permisoCuentaDenegado) return MockResponse().setResponseCode(403).setBody("{\"message\":\"Sin permiso para revelar\"}")
                if (path.endsWith("/reveal-manager") && gerenteDenegado) return MockResponse().setResponseCode(403).setBody("{\"message\":\"Autorización cancelada\"}")
                if (r.method == "GET") return if (statusDetalle == 200) ok(detalle()) else MockResponse().setResponseCode(statusDetalle).setBody("{}")
                if (path.endsWith("/orders/$orden/reveal") && errorReveal != null) return MockResponse().setResponseCode(if(errorReveal=="GIFT_CARD_SALE_NOT_PAID") 409 else 403).setBody("{\"code\":\"$errorReveal\",\"message\":\"Consulta al gerente\"}")
                if (statusAccion != 200) return MockResponse().setResponseCode(statusAccion).setBody("{\"code\":\"GIFT_CARD_NOT_DELIVERABLE\",\"message\":\"Texto exacto del servidor\"}")
                if (path.endsWith("/$tarjeta/reveal") || path.endsWith("/$tarjeta/reveal-manager")) {
                    val motivo=Json.parseToJsonElement(r.body.clone().readUtf8()).jsonObject["motivo"]?.jsonPrimitive?.content
                    if (motivo !in setOf("REPRINT","SHOW_QR")) return MockResponse().setResponseCode(400).setBody("{\"code\":\"VALIDATION_ERROR\",\"message\":\"El motivo individual debe ser REPRINT o SHOW_QR\"}")
                }
                return when {
                    path.endsWith("/orders/$orden/reveal") -> ok("{\"tarjetas\":[${revelado()}]}")
                    path.endsWith("/reveal") || path.endsWith("/reveal-manager") -> ok(revelado())
                    else -> ok("{\"giftCardId\":\"$tarjeta\",\"deliveryStatus\":\"PENDING\",\"repuesta\":true,\"ultimos4\":\"WXYZ\"}")
                }
            }
        }
        server.start(); System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString())
        repo = GiftCardsRepository(OkHttpClient(), storage)
        coEvery { printer.printGiftCardCertificate(any()) } coAnswers { papel.add(firstArg()); outcome }
    }
    @After fun terminar() { server.shutdown(); System.clearProperty("avoqado.test.baseUrl") }
    private fun ok(json: String) = MockResponse().setResponseCode(200).setBody("{\"success\":true,\"data\":$json}")
    private fun revelado(): String = "{\"id\":\"$tarjeta\",\"codigo\":\"CODIGO-VIGENTE-B\",\"pageToken\":\"secreto-B\",\"ultimos4\":\"WXYZ\",\"monto\":500.01,\"saldo\":500.01" + (liga?.let { ",\"pageUrl\":\"$it\"" } ?: "") + (venceEl?.let { ",\"venceEl\":\"$it\"" } ?: "") + "}"
    private fun detalle(): String {
        val total = if (estados.size == 2) "1000.02" else "500.01"
        val sobres = estados.mapIndexed { i,s -> "{\"id\":\"${if(i==0) tarjeta else "c423456789012345678901234"}\",\"ultimos4\":\"WXYZ\",\"monto\":500.01,\"saldo\":500.01,\"status\":\"$s\",\"deliveryChannel\":\"$canal\",\"deliveryStatus\":\"$entrega\",\"deliveryError\":\"No llegó el correo\",\"usada\":false,\"puedeReenviar\":$permiso,\"puedeCambiarDestino\":$permiso,\"puedeRevelar\":$permiso${gerente?.let { ",\"revelarPideGerente\":$it" } ?: ""}}" }.joinToString(",")
        return "{\"orderId\":\"$orden\",\"orderNumber\":\"ORD-4\",\"total\":$total,\"paymentStatus\":\"PAID\",\"orderStatus\":\"COMPLETED\",\"sobres\":[$sobres],\"cancel\":{\"allowed\":false}}"
    }
    private fun vm(viva: Boolean = true): ResultadoDeVentaViewModel {
        if (viva && store.delNegocio(venue).isEmpty()) assertTrue(store.guardar(solicitud))
        return ResultadoDeVentaViewModel(repo,store,storage,red,printer).also { it.abrir(if(viva) solicitud.llave else null,orden) }
    }
    private fun fila(vm: ResultadoDeVentaViewModel): com.avoqado.pos.giftcards.presentation.SobreEnResultado {
        val fila=vm.sobres.value.singleOrNull();assertNotNull("S4 debe publicar exactamente un sobre",fila);return fila!!
    }
    @Test fun activacionSoloConsultaS4SinReenviarVenta() = runBlocking {
        estados=listOf("PENDING"); val vm=vm(); vm.refrescar()
        assertTrue(fila(vm).activando)
        estados=listOf("ACTIVE"); vm.refrescar()
        assertFalse(fila(vm).activando)
        assertEquals(2, peticiones.count { it.method=="GET" })
        assertEquals(0, peticiones.count { it.method=="POST" && it.path!!.endsWith("/gift-cards/orders") })
    }
    @Test fun primeraImpresionUsaRevealActualYMarcaAntesDeLimpiar() = runBlocking {
        val vm=vm(); vm.refrescar(); vm.refrescar(); vm.refrescar()
        assertEquals(1,papel.size); assertEquals("CODIGO-VIGENTE-B",papel.single().codigo)
        assertEquals(liga,papel.single().liga)
        assertEquals(1,peticiones.count { it.path!!.endsWith("/orders/$orden/reveal") })
        assertTrue(store.delNegocio(venue).isEmpty())
        vm.cerrar(); val nueva=vm(false); nueva.refrescar(); assertEquals(1,papel.size)
        assertFalse(fake.values.values.any { it?.contains("CODIGO-VIGENTE-B")==true || it?.contains("secreto-B")==true })
    }
    @Test fun primeraActivaNoBorraMientrasOtroSobreEstaPendingYReabrirNoReimprime() = runBlocking {
        estados=listOf("ACTIVE","PENDING"); val vm=vm(); vm.refrescar()
        assertEquals(setOf(tarjeta),store.delNegocio(venue).single().impresos)
        vm.cerrar(); val otra=vm(); otra.refrescar(); assertEquals(1,papel.size)
        assertEquals(1,store.delNegocio(venue).size)
    }
    @Test fun noPrinterOutOfPaperYFailedNoMarcanNiLimpian() = runBlocking {
        for (r in listOf(PrinterService.PrintOutcome.NoPrinter,PrinterService.PrintOutcome.OutOfPaper,PrinterService.PrintOutcome.Failed("falló"))) {
            outcome=r; val vm=vm(); vm.refrescar()
            assertTrue(store.delNegocio(venue).single().impresos.isEmpty()); assertFalse(vm.aviso.value.isNullOrBlank()); vm.cerrar()
        }
    }
    @Test fun falloDurableDespuesDelPapelConservaSolicitudYNoRepiteEnElMismoVm() = runBlocking {
        val vm=vm(); fake.ok=false; vm.refrescar(); vm.refrescar()
        assertEquals(1,papel.size); assertTrue(store.delNegocio(venue).single().impresos.isEmpty())
        assertFalse(vm.aviso.value.isNullOrBlank())
        fake.ok=true; vm.abrirDialogo(tarjeta,"REPRINT"); vm.imprimir(tarjeta)
        assertEquals(2,papel.size); assertTrue(store.delNegocio(venue).isEmpty())
    }
    @Test fun historicaNoAutoimprimeYCanalEnManoOfreceQrVigente() = runBlocking {
        canal="HANDED_TO_BUYER"; val vm=vm(false); vm.refrescar(); assertTrue(papel.isEmpty())
        vm.abrirDialogo(tarjeta,"SHOW_QR"); vm.mostrarQr(tarjeta)
        assertEquals(liga,fila(vm).qr)
        assertEquals(1,peticiones.count { it.path!!.endsWith("/orders/$orden/reveal") })
    }
    @Test fun sinLigaNoInventaQrYAlCerrarBorraLosSecretos() = runBlocking {
        canal="HANDED_TO_BUYER";liga=null;val vm=vm(false);vm.refrescar();vm.mostrarQr(tarjeta)
        assertNull(fila(vm).qr);vm.cerrar();assertTrue(vm.sobres.value.isEmpty())
    }
    @Test fun despuesDelPrimerDialogoCadaReimpresionRevelaConMotivoYGerenteConservador() = runBlocking {
        gerente=null; val vm=vm();vm.refrescar();vm.abrirDialogo(tarjeta,"REPRINT");vm.imprimir(tarjeta)
        val r=peticiones.lastOrNull { it.method=="POST" };assertNotNull("La reimpresión debe revelar",r);assertTrue(r!!.path!!.endsWith("/$tarjeta/reveal-manager"));assertTrue(r.body.readUtf8().contains("REPRINT"))
    }
    @Test fun otroDiaUsaRevealManagerTrasFalloDeRevealDeCuenta() = runBlocking {
        errorReveal="GIFT_CARD_REVEAL_NOT_ALLOWED";val vm=vm();vm.refrescar()
        assertEquals(1,papel.size);assertTrue(peticiones.any { it.path!!.endsWith("/$tarjeta/reveal-manager") })
    }
    @Test fun noPagadaNoImprimeYConservaElMensaje() = runBlocking {
        errorReveal="GIFT_CARD_SALE_NOT_PAID";val vm=vm();vm.refrescar()
        assertTrue(papel.isEmpty());assertEquals("Consulta al gerente",vm.aviso.value);assertEquals(1,store.delNegocio(venue).size)
    }
    @Test fun canceladaYBanderaFalseNoPermitenAcciones() = runBlocking {
        canal="EMAIL";permiso=false;val vm=vm(false);vm.refrescar();assertEquals(1,vm.sobres.value.size);vm.imprimir(tarjeta);vm.mostrarQr(tarjeta);vm.reenviar(tarjeta);vm.cambiarDestino(tarjeta,"EMAIL","","a@example.test")
        assertTrue(papel.isEmpty());assertEquals(0,peticiones.count { it.method=="POST" })
        estados=listOf("DEACTIVATED");vm.refrescar();vm.imprimir(tarjeta);assertTrue(papel.isEmpty())
    }
    @Test fun entregaPorCanalNoConfundePrintedConEnviando() = runBlocking {
        val vm=vm(false);vm.refrescar();assertNull(fila(vm).textoDeEntrega)
        canal="EMAIL"
        for ((s,t) in listOf("PENDING" to "Enviando…","SENT" to "Enviada","FAILED" to "No se pudo enviar")) { entrega=s;vm.refrescar();assertEquals(t,fila(vm).textoDeEntrega) }
    }
    @Test fun reenviarSinCanalYCambiarDestinoConE164LlavePorDialogo() = runBlocking {
        canal="EMAIL";val vm=vm(false);vm.refrescar();vm.abrirDialogo(tarjeta,"RESEND");statusAccion=409
        vm.reenviar(tarjeta);vm.reenviar(tarjeta)
        val cuerpos=peticiones.filter { it.path!!.endsWith("/resend") }.map { it.body.clone().readUtf8() }
        assertEquals(2,cuerpos.size);assertEquals(cuerpos[0],cuerpos[1]);assertFalse(cuerpos[0].contains("deliveryChannel"));assertEquals("Texto exacto del servidor",vm.aviso.value)
        vm.cerrarDialogo();vm.abrirDialogo(tarjeta,"CHANGE_DESTINATION");statusAccion=200;vm.cambiarDestino(tarjeta,"WHATSAPP","5512345678","")
        val q=peticiones.last { it.path!!.endsWith("/change-destination") };val body=q.body.clone().readUtf8()
        assertTrue(body.contains("+525512345678"));assertTrue(body.contains("WHATSAPP"));assertFalse(body.contains("recipientEmail"))
    }
    @Test fun sinRedNiNegocioActualNoRevelaNiImprime() = runBlocking {
        val vm=vm();conectado.value=false;vm.refrescar();assertTrue(peticiones.isEmpty());assertFalse(vm.aviso.value.isNullOrBlank())
        conectado.value=true;fake.values["venueId"]="otro";vm.refrescar();assertTrue(peticiones.isEmpty())
    }
    @Test fun pollingActivacionDosSegundosHastaTreintaYEntregaCincoHastaCientoVeinte() = runBlocking {
        canal="HANDED_TO_BUYER";estados=listOf("PENDING");val vm=vm(false);val pausas=mutableListOf<Long>();var reloj=0L;vm.ahora={reloj};vm.esperar={pausas.add(it);reloj+=it};vm.vigilar()
        assertEquals(List(15){2000L},pausas);assertEquals(16,peticiones.count { it.method=="GET" })
        vm.cerrar();peticiones.clear();canal="EMAIL";estados=listOf("ACTIVE");val digital=vm(false);pausas.clear();reloj=0L;digital.ahora={reloj};digital.esperar={pausas.add(it);reloj+=it};digital.vigilar()
        assertEquals(List(24){5000L},pausas);assertEquals(25,peticiones.count { it.method=="GET" })
    }
    @Test fun dosPantallasNoAutoimprimenLaMismaSolicitud() = runBlocking {
        val uno=vm();val dos=vm();coroutineScope { awaitAll(async {uno.refrescar()},async {dos.refrescar()}) }
        assertEquals(1,papel.size);uno.cerrar();dos.cerrar()
    }
    @Test fun detalle404NoInventaEntregaNiError() = runBlocking {
        statusDetalle=404;val vm=vm(false);vm.refrescar();assertTrue(vm.sobres.value.isEmpty());assertNull(vm.aviso.value)
    }
    @Test fun vigenciaRespetaLaZonaDelNegocioSinInventarSinVencimiento() = runBlocking {
        venceEl="2027-02-01T02:00:00Z";fake.values["venueTimezone"]="zona-invalida"
        val vm=vm();vm.refrescar()
        assertEquals(1,papel.size);assertEquals("31/01/2027",papel.single().vence)
    }

    @Test fun recuperadorSueltaLaLlaveYElResultadoRetomaLaAutoimpresion() = runBlocking {
        assertTrue(store.guardar(solicitud));assertTrue(store.enVueloDeEsteProceso.add(solicitud.llave))
        val vm=vm();vm.refrescar();assertTrue(papel.isEmpty())
        store.enVueloDeEsteProceso.remove(solicitud.llave)
        vm.refrescar();assertEquals(1,papel.size);assertTrue(store.delNegocio(venue).isEmpty())
    }

    @Test fun cuentaAbiertaPagadaAbreElResultadoDeLaMismaOrden() {
        val abiertas = mutableListOf<String>(); var actualizaciones = 0
        com.avoqado.pos.payment.presentation.alSalirDeCuentaAbierta(
            com.avoqado.pos.payment.presentation.CierreDeCuenta.PAGADA, true, orden,
            { actualizaciones++ }, {}, { abiertas.add(it) })
        assertEquals(listOf(orden), abiertas); assertEquals(1, actualizaciones)
    }
    @Test fun cuentaNormalAbiertaOCanceladaNoAbreResultadoDeTarjetas() {
        for ((cierre, esTarjetas) in listOf(
            com.avoqado.pos.payment.presentation.CierreDeCuenta.PAGADA to false,
            com.avoqado.pos.payment.presentation.CierreDeCuenta.ABIERTA to true,
            com.avoqado.pos.payment.presentation.CierreDeCuenta.CANCELADA to true)) {
            val abiertas = mutableListOf<String>(); var canceladas = 0; var actualizaciones = 0
            com.avoqado.pos.payment.presentation.alSalirDeCuentaAbierta(cierre, esTarjetas, orden,
                { actualizaciones++ }, { canceladas++ }, { abiertas.add(it) })
            assertTrue(abiertas.isEmpty()); assertEquals(1,actualizaciones)
            assertEquals(if(cierre == com.avoqado.pos.payment.presentation.CierreDeCuenta.CANCELADA) 1 else 0,canceladas)
        }
    }
    @Test fun cerrarDuranteImpresionConservaElDuenoHastaQueTermina() = runBlocking {
        val dentro = CompletableDeferred<Unit>(); val terminar = CompletableDeferred<Unit>()
        coEvery { printer.printGiftCardCertificate(any()) } coAnswers {
            papel.add(firstArg()); if(papel.size == 1) { dentro.complete(Unit); terminar.await() }; outcome
        }
        val uno=vm(); val trabajo=launch { uno.refrescar() }
        withTimeout(10_000) { dentro.await() }
        uno.cerrar(); val dos=vm()
        try {
            dos.refrescar(); assertEquals("Cerrar no acredita cese del envío vivo",1,papel.size)
            assertTrue(store.enVueloDeEsteProceso.contains(solicitud.llave))
        } finally { terminar.complete(Unit); trabajo.join(); dos.cerrar() }
        assertFalse(store.enVueloDeEsteProceso.contains(solicitud.llave))
    }
    @Test fun primer403SinCodeConGerenteOfreceAutorizacionParaQr() = runBlocking {
        canal="HANDED_TO_BUYER"; gerente=true; permisoCuentaDenegado=true
        val vm=vm(false);vm.refrescar();vm.mostrarQr(tarjeta)
        assertEquals(1,peticiones.count { it.path!!.endsWith("/reveal-manager") })
        assertEquals(liga,fila(vm).qr);assertEquals("CODIGO-VIGENTE-B",vm.codigo.value)
        assertNull(peticiones.last().getHeader("X-Avoqado-Local-Error"))
    }
    @Test fun primer403SinCodeSinBanderaPideGerenteConservador() = runBlocking {
        canal="HANDED_TO_BUYER";gerente=null;permisoCuentaDenegado=true
        val vm=vm(false);vm.refrescar();vm.mostrarQr(tarjeta)
        assertEquals(1,peticiones.count { it.path!!.endsWith("/reveal-manager") });assertEquals(liga,fila(vm).qr)
    }
    @Test fun autoimpresionPrimer403SinCodeOfreceGerente() = runBlocking {
        gerente=true;permisoCuentaDenegado=true;val vm=vm();vm.refrescar()
        assertEquals(1,papel.size);assertEquals(1,peticiones.count { it.path!!.endsWith("/reveal-manager") })
        assertTrue(store.delNegocio(venue).isEmpty());vm.cerrar()
    }
    @Test fun primer403SinCodeSinGerenteExplicaPermisoDeRevelacion() = runBlocking {
        canal="HANDED_TO_BUYER";gerente=false;permisoCuentaDenegado=true
        val vm=vm(false);vm.refrescar();vm.mostrarQr(tarjeta)
        assertEquals("No tienes permiso para revelar el código. Pídeselo a tu gerente.",vm.aviso.value)
        assertEquals(0,peticiones.count { it.path!!.endsWith("/reveal-manager") });assertNull(vm.codigo.value);assertNull(fila(vm).qr)
    }
    @Test fun primer403SinCodeSinAutorizarGerenteNoPublicaSecretoNiImprime() = runBlocking {
        gerente=true;permisoCuentaDenegado=true;gerenteDenegado=true;val vm=vm();vm.refrescar()
        assertEquals(1,peticiones.count { it.path!!.endsWith("/reveal-manager") })
        assertTrue(papel.isEmpty());assertNull(vm.codigo.value);assertTrue(store.delNegocio(venue).single().impresos.isEmpty());vm.cerrar()
    }


    // Frontera física simulada: cancelar await no cancela el callback externo.
    private fun cancelacionConEnvioVivo(cierre: String, fallo: Boolean = false, explicita: Boolean = false, disco: Boolean = true) = runBlocking {
        estados = listOf("ACTIVE", "PENDING") // Conserva la fila para observar marca antes de limpieza.
        fake.values["accessToken"] = "fixture-con-sesion"
        val uno = vm()
        if (explicita) { outcome = PrinterService.PrintOutcome.NoPrinter; uno.refrescar() }
        val dentro = CompletableDeferred<Unit>(); val callback = CompletableDeferred<Unit>()
        coEvery { printer.printGiftCardCertificate(any()) } coAnswers {
            papel.add(firstArg()); if (!dentro.isCompleted) { dentro.complete(Unit); callback.await() }
            if (fallo) PrinterService.PrintOutcome.Failed("fallo físico") else PrinterService.PrintOutcome.Printed(1)
        }
        val antes = papel.size
        val padre = SupervisorJob()
        val tarea = CoroutineScope(coroutineContext + padre).launch {
            if (explicita) uno.imprimir(tarjeta) else uno.refrescar()
        }
        withTimeout(10_000) { dentro.await() }
        when (cierre) {
            "scope" -> { uno.cerrar(); padre.cancel() }
            "cleared" -> {
                ResultadoDeVentaViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(uno)
                padre.cancel()
            }
            "red" -> { conectado.value = false; tarea.cancel(); conectado.value = true }
            "pin" -> { fake.values["userId"] = "c623456789012345678901234"; uno.cerrar(); tarea.cancel() }
            else -> { uno.cerrar(); tarea.cancel() }
        }
        yield() // Deja que la cancelación alcance la espera cancelable del driver.
        val dos = vm()
        try {
            dos.refrescar(); dos.imprimir(tarjeta)
            val resolver = ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red)
            resolver.resolverAhora()
            assertEquals("Nadie puede reenviar mientras vive el callback", antes + 1, papel.size)
            assertTrue("El dueño físico sigue reclamado", store.enVueloDeEsteProceso.contains(solicitud.llave))
            fake.ok = disco
            callback.complete(Unit); withTimeout(10_000) { tarea.join() }
            assertEquals(if (!fallo && disco) setOf(tarjeta) else emptySet<String>(), store.delNegocio(venue).single().impresos)
            if (cierre != "red") assertFalse("El callback termina y libera al dueño cerrado", store.enVueloDeEsteProceso.contains(solicitud.llave))
            else assertTrue("El VM vigente mantiene su reclamo", store.enVueloDeEsteProceso.contains(solicitud.llave))
        } finally {
            callback.complete(Unit); withTimeout(10_000) { tarea.join() }
            fake.ok = true; dos.cerrar(); uno.cerrar(); padre.cancel()
        }
    }
    @Test fun cerrarYCancelarTareaRetieneDuenoYMarcaAlLlegarCallback() = cancelacionConEnvioVivo("cerrar")
    @Test fun desmontarPantallaYCancelarScopeRetieneDuenoFisico() = cancelacionConEnvioVivo("scope")
    @Test fun onClearedYCancelarScopeNoAutorizaOtroEnvio() = cancelacionConEnvioVivo("cleared")
    @Test fun cancelarEfectoPorRedConservaCallbackYMarcaConVmVigente() = cancelacionConEnvioVivo("red")
    @Test fun relevoPorPinNoSueltaEnvioDeLaSesionAnterior() = cancelacionConEnvioVivo("pin")
    @Test fun callbackDeFalloTrasCancelacionLiberaSinMarcar() = cancelacionConEnvioVivo("cerrar", fallo = true)
    @Test fun reimpresionExplicitaCanceladaEsperaSuCallback() = cancelacionConEnvioVivo("scope", explicita = true)
    @Test fun falloDeMarcaTrasCancelacionConservaFilaYLiberaAlTerminar() = cancelacionConEnvioVivo("cerrar", disco = false)

    @Test fun cierreTardioDeDuenoInvalidadoNoSueltaElReclamoNuevo() = runBlocking {
        val dentro = CompletableDeferred<Unit>(); val callback = CompletableDeferred<Unit>()
        coEvery { printer.printGiftCardCertificate(any()) } coAnswers {
            papel.add(firstArg()); if (!dentro.isCompleted) { dentro.complete(Unit); callback.await() }; PrinterService.PrintOutcome.Failed("fallo físico")
        }
        val uno = vm(); val tarea = launch { uno.refrescar() }
        withTimeout(10_000) { dentro.await() }
        fake.values["venueId"] = "c723456789012345678901234"
        callback.complete(Unit); withTimeout(10_000) { tarea.join() }
        assertFalse(store.enVueloDeEsteProceso.contains(solicitud.llave))
        fake.values["venueId"] = venue
        val dos = vm()
        try {
            assertTrue(store.enVueloDeEsteProceso.contains(solicitud.llave))
            uno.cerrar()
            assertTrue("Un VM que ya soltó no puede borrar al siguiente dueño", store.enVueloDeEsteProceso.contains(solicitud.llave))
        } finally { dos.cerrar(); uno.cerrar() }
    }


    private suspend fun ventanaAceptadaDeResultadoSeRecuperaAlReabrir(individual: Boolean) = coroutineScope {
        val r=mockk<GiftCardsRepository>()
        val dentro=CompletableDeferred<Unit>();val respuesta=CompletableDeferred<Unit>()
        var iniciosSinRed=0
        val sobre=SobreDeLaCuenta(tarjeta,"WXYZ",500.01,500.01,"ACTIVE",deliveryChannel="PRINTED",
            usada=false,puedeReenviar=true,puedeCambiarDestino=true,puedeRevelar=true)
        val cuenta=CuentaDeTarjetas(orden,"ORD-4",500.01,"PAID","COMPLETED",sobres=listOf(sobre),cancel=Cancelacion(false))
        val codigo=CodigoRevelado(tarjeta,"CODIGO-VIGENTE-B","secreto-B",liga,"WXYZ",500.01,500.01)
        coEvery {r.cuenta(any(),any())} coAnswers {
            if(!individual && !dentro.isCompleted) {dentro.complete(Unit);respuesta.await()}
            Respuesta.Ok(cuenta)
        }
        coEvery {r.revelarCuenta(any(),any(),any())} coAnswers {
            val enLinea=conectado.value
            if(!enLinea) iniciosSinRed++
            if(individual && !dentro.isCompleted) {dentro.complete(Unit);respuesta.await()}
            if(individual) Respuesta.Rechazo(FalloDeTarjeta(403,"GIFT_CARD_REVEAL_NOT_ALLOWED","Gerente",null,null))
            else if(enLinea) Respuesta.Ok(listOf(codigo)) else Respuesta.SinRespuesta
        }
        coEvery {r.revelar(any(),any(),any(),any(),any())} coAnswers {
            if(conectado.value) Respuesta.Ok(codigo) else {iniciosSinRed++;Respuesta.SinRespuesta}
        }
        assertTrue(store.guardar(solicitud))
        fun nuevo()=ResultadoDeVentaViewModel(r,store,storage,red,printer).also {it.abrir(solicitud.llave,orden)}
        val modelo=nuevo();var reabierto: ResultadoDeVentaViewModel?=null
        val lectura=launch {modelo.refrescar()}
        try {
            withTimeout(10_000) {dentro.await()}
            conectado.value=false;respuesta.complete(Unit);lectura.join()
            assertEquals("Ventana de monitor AN4 aceptada explícitamente por ROOT7r4",1,iniciosSinRed)
            assertTrue(papel.isEmpty());assertNull(modelo.codigo.value);assertNull(modelo.sobres.value.single().qr);assertNull(modelo.exito.value)
            assertEquals(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED,modelo.aviso.value)
            assertTrue(store.delNegocio(venue).single().impresos.isEmpty())
            conectado.value=true;modelo.refrescar()
            assertTrue("intentadas conserva el intento en este modelo; el gate aislado no puede rearmarlo",papel.isEmpty())
            assertEquals(1,store.delNegocio(venue).size)
            modelo.cerrar();reabierto=nuevo();reabierto!!.refrescar()
            assertEquals(1,papel.size);assertTrue(store.delNegocio(venue).isEmpty())
            assertEquals(1,iniciosSinRed)
        } finally {respuesta.complete(Unit);lectura.join();modelo.cerrar();reabierto?.cerrar()}
    }
    @Test fun limiteAceptadoR2CuentaSinExitoFingidoConservaPendienteYReabrirRecupera() = runBlocking { ventanaAceptadaDeResultadoSeRecuperaAlReabrir(false) }
    @Test fun limiteAceptadoR3IndividualSinExitoFingidoConservaPendienteYReabrirRecupera() = runBlocking { ventanaAceptadaDeResultadoSeRecuperaAlReabrir(true) }
}
