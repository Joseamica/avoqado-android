package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class CancelacionDurableConSaldoTest {
    @Test fun intencionEnBorrarOrdenEsperaAlRedeem()=runTest {
        val store=CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar())
        val transporte=CancelacionTransportFalso()
        var bloqueada=true
        val vistos=mutableListOf<Pair<String,String>>()
        val c=CancelacionDeCobroCoordinator(store,transporte,MutableStateFlow(true),MutableStateFlow(true),backgroundScope,
            reloj={testScheduler.currentTime},saldoPendiente={venue,id -> vistos+=venue to id;bloqueada})
        assertTrue(c.registrar(IntencionDeCancelarCobro(id="intent",requestId=null,venueId="a",terminalId=null,orderId="o",
            borrarOrden=true,actorStaffId="ana",montoCents=1000,creadaEn=1,fase=FaseDeCancelacion.BORRAR_ORDEN.name)))
        c.procesar("intent")
        assertTrue(transporte.llamadasBorrar.isEmpty())
        assertEquals(listOf("a" to "o"),vistos)
        assertEquals(FaseDeCancelacion.BORRAR_ORDEN,store.leer("intent")!!.faseActual)
        bloqueada=false
        c.procesar("intent")
        assertEquals(listOf("DELETE:o"),transporte.llamadasBorrar)
        assertTrue(store.todas().isEmpty())
    }
}
