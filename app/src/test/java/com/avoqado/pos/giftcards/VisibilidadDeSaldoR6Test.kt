package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.giftcards.presentation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VisibilidadDeSaldoR6Test {
    private val contexto = "staff" to "venue"
    private val cobro = CobroConSaldo("payment", "order", "V123", 250.0, 0.0,
        listOf(Redimido("card", "8BMV", 250.0, 50.0)), 100.0, "PARTIAL", null, null)
    private val cuenta = SolicitudDeTarjeta(tipo="GIFT_CARD_REDEEM", llave="key", venueId="venue", creadaEn=1,
        cuerpo="{}", orderId="order", porTerminar=true, restanteCents=10000)
    private val tarjeta = TarjetaIdentificada(PruebaDeTarjeta.Codigo("fixture"),
        TarjetaConsultada("card", "8BMV", 500.0, "ACTIVE", usable=true))
    @Test fun `cobro recuperado produce aviso con importe tarjeta y venta una vez`() {
        val aviso = siguienteAvisoDeSaldo(contexto, contexto, listOf(cobro), emptyList(), emptyList())
        assertTrue(aviso is AvisoDeSaldo.Cobro)
        assertEquals(cobro, (aviso as AvisoDeSaldo.Cobro).cobro)
        assertNull(siguienteAvisoDeSaldo(contexto, contexto, listOf(cobro), emptyList(), listOf(aviso.llave)))
    }
    @Test fun `cuenta por ofrecer conserva restante y no se duplica con su cobro`() {
        val aviso = siguienteAvisoDeSaldo(contexto, contexto, emptyList(), listOf(cuenta), emptyList())
        assertTrue(aviso is AvisoDeSaldo.Cuenta)
        assertEquals(10000, (aviso as AvisoDeSaldo.Cuenta).cuenta.restanteCents)
        assertNull(siguienteAvisoDeSaldo(contexto, contexto, emptyList(), listOf(cuenta), listOf(aviso.llave)))
        assertNull(siguienteAvisoDeSaldo(contexto, contexto, listOf(cobro), listOf(cuenta), listOf("cobro:payment")))
    }
    @Test fun `otra sesion otro negocio y cuenta ajena no ofrecen datos`() {
        assertNull(siguienteAvisoDeSaldo(contexto, "other" to "venue", listOf(cobro), listOf(cuenta), emptyList()))
        assertNull(siguienteAvisoDeSaldo(contexto, "staff" to "other", listOf(cobro), listOf(cuenta), emptyList()))
        assertNull(siguienteAvisoDeSaldo(contexto, contexto, emptyList(), listOf(cuenta.copy(venueId="other")), emptyList()))
    }
    @Test fun `hoja resuelta nunca sigue comprobando ni permite reintentar`() {
        assertFalse(hojaDeSaldoVigente(DesenlaceDeCobro.Cobrado(cobro), false, "key", "venue", emptyList()))
        for (resultado in listOf(DesenlaceDeCobro.NoSalio("No se cobró"), DesenlaceDeCobro.Rechazado("Rechazado"))) {
            assertTrue(hojaDeSaldoVigente(resultado, false, "key", "venue", emptyList()))
        }
        assertFalse(hojaDeSaldoVigente(DesenlaceDeCobro.Incierto, false, "key", "venue", listOf(cuenta)))
        assertFalse(hojaDeSaldoVigente(DesenlaceDeCobro.Incierto, false, "key", "venue", emptyList()))
        assertTrue(hojaDeSaldoVigente(DesenlaceDeCobro.Incierto, false, "key", "venue", listOf(cuenta.copy(porTerminar=false, mandado=true))))
        assertFalse(hojaDeSaldoVigente(DesenlaceDeCobro.Incierto, false, "key", "venue", listOf(cuenta.copy(porTerminar=false, mandado=true, venueId="other"))))
        assertTrue(hojaDeSaldoVigente(null, true, null, "venue", emptyList()))
    }
    @Test fun `saldo visible toma el 409 y el cobro confirmado de esa tarjeta`() {
        assertEquals(100.0, saldoMostrado(tarjeta, DesenlaceDeCobro.SaldoInsuficiente(100.0, "Quedan 100")), 0.0)
        assertEquals(50.0, saldoMostrado(tarjeta, DesenlaceDeCobro.Cobrado(cobro)), 0.0)
        assertEquals(500.0, saldoMostrado(tarjeta, null), 0.0)
    }
    @Test fun `resultado cancelado no reaparece y cierre ajeno no lo oculta`() {
        assertFalse(resultadoDeSaldoVigente("order", listOf("order")))
        assertTrue(resultadoDeSaldoVigente("order", listOf("other")))
        assertTrue(resultadoDeSaldoVigente(null, listOf("order")))
    }
    @Test fun `reconsultar solo con un aviso que se puede ver`() {
        for (aviso in listOf(null, "", "   ")) assertFalse(mostrarReconsultaDeTarjetas(aviso, true, true))
        assertFalse(mostrarReconsultaDeTarjetas("Error", false, true))
        assertFalse(mostrarReconsultaDeTarjetas("Error", true, false))
        assertTrue(mostrarReconsultaDeTarjetas("No se pudo consultar", true, true))
    }
    @Test fun `texto de recuperado explica importe tarjeta y folio`() {
        val texto=TextosDeTarjetas.cobroRecuperado(25000, "8BMV", "V123")
        for(dato in listOf("250.00", "8BMV", "V123")) assertTrue(dato, texto.contains(dato))
        assertFalse(texto.contains("cerrada"))
        assertTrue(texto.startsWith("Se terminó un cobro con saldo que había quedado pendiente:"))
    }
    @Test fun `rechazo propio no usa el callback de cobro anterior y conserva salida r5`() {
        val pago=fuente("payment/presentation/PaymentFlowScreen")
        val efecto=pago.substringAfter("LaunchedEffect(resultadoDeSaldo,").substringBefore("if (muestraSaldo && !hojaVigente")
        assertFalse(efecto.contains("onPreviousChargeResolved"))
        assertTrue(pago.contains("else viewModel.cuentaParaContinuarConSaldo?.let { onCuentaConSaldo?.invoke(it) }"))
        assertTrue(pago.contains("viewModel.descartarSaldoInsuficienteAlIdentificar(); identificadaDeSaldo = it"))
        assertEquals(2, Regex("viewModel\\.descartarSaldoInsuficienteAlIdentificar\\(\\)").findAll(pago).count())
    }
    private fun fuente(nombre: String): String {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while(d != null) {
            val f=File(d,"app/src/main/java/com/avoqado/pos/$nombre.kt")
            if(f.isFile) return f.readText()
            d=d.parentFile
        }
        throw AssertionError("No se encontró $nombre")
    }
    @Test fun `raiz conecta ambos flujos y las decisiones se usan en las pantallas`() {
        val raiz=fuente("navigation/AvoqadoNavGraph")
        assertTrue(raiz.contains("resolutorDeTarjetas.recuperados.collectAsState()"))
        assertTrue(raiz.contains("resolutorDeTarjetas.porOfrecerAlArrancar.collectAsState()"))
        val aviso=fuente("giftcards/presentation/AvisoDeCobroRecuperado")
        assertTrue(aviso.contains("rememberSaveable"))
        assertTrue(aviso.contains("siguienteAvisoDeSaldo("))
        val pago=fuente("payment/presentation/PaymentFlowScreen")
        assertTrue(pago.contains("hojaDeSaldoVigente("))
        assertTrue(pago.contains("resultadoDeSaldoVigente("))
        for(f in listOf("ResultadoDeVentaScreen", "CuentaDeTarjetasScreen"))
            assertTrue(fuente("giftcards/presentation/$f").contains("mostrarReconsultaDeTarjetas("))
    }
    @Test fun `union E monto antes de enviar cierra sin capacidades y conserva dinero en vuelo`() {
        for (resultado in listOf(null, DesenlaceDeCobro.NoSalio("No salió"), DesenlaceDeCobro.Rechazado("No cobrado"))) {
            assertFalse(hojaDeSaldoVigente(resultado, false, null, "venue", emptyList(), entradaDisponible = false))
        }
        assertTrue(hojaDeSaldoVigente(null, true, null, "venue", emptyList(), entradaDisponible = false))
        val durable = cuenta.copy(porTerminar=false, mandado=true)
        for (resultado in listOf(DesenlaceDeCobro.Incierto, DesenlaceDeCobro.Reintentable("Espera", null))) {
            assertTrue(hojaDeSaldoVigente(resultado, false, "key", "venue", listOf(durable), entradaDisponible = false))
        }
        assertEquals(cobro, (siguienteAvisoDeSaldo(contexto, contexto, listOf(cobro), emptyList(), emptyList()) as AvisoDeSaldo.Cobro).cobro)
    }
    @Test fun `union E todas las entradas observan capacidades y dinero mantiene sus pantallas`() {
        val checkout = fuente("pos/presentation/checkout/CheckoutScreen")
        assertTrue(checkout.contains("if (!hojaDeTarjetas.mosaicoVisible) {"))
        assertTrue(checkout.contains("consultaDeTarjeta = null"))
        val identificar = fuente("giftcards/presentation/IdentificarTarjetaSheet")
        assertFalse(identificar.contains("val ajustesAlAbrir = remember"))
        assertTrue(identificar.contains("viewModel.cambiosDeAjustes.collectAsState()"))
        assertTrue(identificar.contains("viewModel.ajustesVigentes(venueId)"))
        val vender = fuente("giftcards/presentation/VenderTarjetaScreen")
        assertTrue(vender.contains("viewModel.cambiosDeAjustes.collectAsState()"))
        assertTrue(vender.contains("viewModel.conservaOperacion"))
        val pago = fuente("payment/presentation/PaymentFlowScreen")
        assertTrue(pago.contains("entradaDisponible = filaDeSaldo.visible"))
        for (pantalla in listOf("CuentaDeTarjetasScreen", "ResultadoDeVentaScreen", "VentaPorRecuperar", "AvisoDeCobroRecuperado")) {
            assertFalse("El dinero no se oculta por capacidades: $pantalla", fuente("giftcards/presentation/$pantalla").contains("mosaicoVisible"))
        }
    }

}
