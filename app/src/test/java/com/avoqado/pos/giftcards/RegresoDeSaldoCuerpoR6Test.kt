package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** RULING8r6b: contenido honesto sin datos que el contrato no entrega. QA física queda con ROOT. */
class RegresoDeSaldoCuerpoR6Test {
    @Test fun `confirmacion tiene cuerpo no vacio`() {
        assertTrue("La confirmación no puede quedar muda", TextosDeTarjetas.REGRESAR_SALDO_CUERPO.isNotBlank())
    }
    @Test fun `cuerpo explica misma tarjeta cancelacion y que no entrega efectivo`() {
        val cuerpo=TextosDeTarjetas.REGRESAR_SALDO_CUERPO.lowercase()
        assertTrue("Debe decir adónde vuelve el saldo", cuerpo.contains("regresa a su misma tarjeta"))
        assertTrue("Debe explicar la cancelación", cuerpo.contains("la cuenta se cancela"))
        assertTrue("No se devuelve efectivo", cuerpo.contains("no se entrega efectivo"))
    }
    @Test fun `cuerpo no inventa importe ni tarjeta`() {
        val cuerpo=TextosDeTarjetas.REGRESAR_SALDO_CUERPO
        assertFalse(cuerpo.contains('$'))
        assertFalse(cuerpo.contains('•'))
        assertFalse(cuerpo.any(Char::isDigit))
    }
    @Test fun `dialogo consume el cuerpo comun como descripcion`() {
        var dir: File?=File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dialogo: String?=null
        while(dir!=null) {
            val f=File(dir,"app/src/main/java/com/avoqado/pos/payment/presentation/CobroDeCuentaScreen.kt")
            if(f.isFile) {
                dialogo=f.readText().substringAfter("fun DialogoDeRegresoDeSaldo(")
                    .substringBefore("internal fun controlDeRedDeCuenta")
                    .replace(Regex("\\s+"), "")
                break
            }
            dir=dir.parentFile
        }
        assertNotNull("Falta el diálogo real", dialogo)
        assertTrue("El cuerpo debe llegar al componente visible", dialogo!!.contains("description=TextosDeTarjetas.REGRESAR_SALDO_CUERPO"))
    }
}
