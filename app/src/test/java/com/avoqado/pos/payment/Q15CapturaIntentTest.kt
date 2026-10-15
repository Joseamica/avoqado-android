package com.avoqado.pos.payment
import com.avoqado.pos.payment.data.*
import org.junit.Test
import org.junit.Assert.*

class Q15CapturaIntentTest {
    @Test fun captura_fresca_conserva_payload_local_tender_y_dinero_explicito() {
        val raw="""{"localOrderId":"L1","orderId":"R1","amountCents":10005,"tipCents":500,"tenderTypeId":"t1","tenderRevision":7,"customerId":"c1"}"""
        val capture=ConciliacionDelCobro.capturarIntent("FULLTEST-A","p1",raw,12000,1495,"PARTIALPAYMENT")
        val c=requireNotNull(ConciliacionDelCobro.leerCaptura(capture,"FULLTEST-A","p1"))
        assertEquals("L1",c.localOrderId);assertEquals("R1",c.orderId);assertEquals(10005,c.amountCents);assertEquals(500,c.tipCents)
        assertEquals("t1",c.tenderTypeId);assertEquals(7,c.tenderRevision);assertNull(c.method)
        assertEquals(12000,c.cashTenderedCents);assertEquals(1495,c.changeCents);assertEquals("PARTIALPAYMENT",c.splitType)
        assertEquals(raw,c.intentoPayloadJson);assertNull(c.orderRequestJson)
    }
    @Test fun ausente_no_crea_cero_o_full_ni_coerciona_importe() {
        assertNull(ConciliacionDelCobro.capturarIntent("FULLTEST-A","p1","""{"localOrderId":"L1","amountCents":100}"""))
        assertNull(ConciliacionDelCobro.capturarIntent("FULLTEST-A","p1","""{"orderId":123,"amountCents":100,"tipCents":0}"""))
        assertNull(ConciliacionDelCobro.capturarIntent("FULLTEST-A","p1","""{"orderId":"R1","amountCents":"100","tipCents":0}"""))
        val c=requireNotNull(ConciliacionDelCobro.leerCaptura(ConciliacionDelCobro.capturarIntent("FULLTEST-A","p1","""{"orderId":"R1","amountCents":0,"tipCents":0}"""),"FULLTEST-A","p1"))
        assertEquals(0,c.amountCents);assertNull(c.splitType)
    }
    @Test fun captura_fuera_de_rango_no_se_persiste() {
        assertThrows(IllegalArgumentException::class.java) { ConciliacionDelCobro.capturarIntent("FULLTEST-A","p1","""{"orderId":"R1","amountCents":2147483647,"tipCents":1}""") }
    }
}
