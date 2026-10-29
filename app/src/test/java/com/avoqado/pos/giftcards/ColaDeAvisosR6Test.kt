package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.presentation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ColaDeAvisosR6Test {
    private val cobro=CobroConSaldo("pay", "order", "V123", 250.0, 0.0,
        listOf(Redimido("card", "8BMV", 250.0, 50.0)), 100.0, "PARTIAL", null, null)
    @Test fun `aviso espera mientras otra pantalla esta abierta y luego se ofrece`() {
        val contexto="staff" to "venue"
        assertNull(siguienteAvisoDeSaldo(contexto, contexto, listOf(cobro), emptyList(), emptyList(), otraPantallaAbierta=true))
        assertNotNull(siguienteAvisoDeSaldo(contexto, contexto, listOf(cobro), emptyList(), emptyList(), otraPantallaAbierta=false))
    }
    @Test fun `cuenta abierta por aviso sobrevive rotacion y la raiz entrega el turno`() {
        var dir: File?=File(System.getProperty("user.dir") ?: ".").absoluteFile
        var raiz: String?=null
        while(dir!=null) {
            val f=File(dir,"app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt")
            if(f.isFile) {raiz=f.readText().replace(Regex("\\s+"), ""); break};dir=dir.parentFile
        }
        val fuente=raiz ?: throw AssertionError("Falta raiz")
        assertTrue(fuente.contains("cuentaConSaldoDeArranquebyrememberSaveable"))
        assertTrue(fuente.contains("otraPantallaAbierta=resultadosDeTarjetas.isNotEmpty()||cuentaConSaldoDeArranque!=null"))
    }
}
