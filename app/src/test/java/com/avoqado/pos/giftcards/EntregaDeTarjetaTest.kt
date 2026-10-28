package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.textoDeEntrega
import org.junit.Assert.*
import org.junit.Test

class EntregaDeTarjetaTest {
    @Test fun tablaDeEntrega() {
        for (canal in listOf("EMAIL", "WHATSAPP")) {
            assertEquals("Enviando…", textoDeEntrega(canal, "PENDING"))
            assertEquals("Enviada", textoDeEntrega(canal, "SENT"))
            assertEquals("No se pudo enviar", textoDeEntrega(canal, "FAILED"))
            assertNull(textoDeEntrega(canal, "OTRO"))
        }
        for (canal in listOf("PRINTED", "HANDED_TO_BUYER")) assertNull(textoDeEntrega(canal, "PENDING"))
    }
}
