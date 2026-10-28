package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.*
import org.junit.Test

class CertificadoDeTarjetaTest {
    private val c = CertificadoDeTarjeta("IQ", 500.01, "Ana", "Luis", "Felicidades", "https://example.invalid/ñ?q=á", "K7QZ-ABCD-EFGH-JKMN-P", "JKMN", null)
    @Test fun todosLosRenglonesEnAmbosPapelesSinAbrirCajon() {
        for (ancho in PaperWidth.entries) {
            val bytes = ESCPOSPrinter(ancho).generateGiftCardCertificate(c)
            val papel = bytes.toString(Charsets.ISO_8859_1)
            for (t in listOf("IQ", "TARJETA DE REGALO", "$500.01", "Para: Ana", "De: Luis", "\"Felicidades\"", "Código: K7QZ-ABCD-EFGH-JKMN-P", "Folio: JKMN", "Vence: Sin vencimiento", "Escanea el QR para ver tu saldo y los términos.", "No se cambia por efectivo. Quien tenga el código puede usarla: cuídala como efectivo.")) assertTrue(t, papel.contains(t))
            assertFalse(papel.contains("\u001bp"))
            assertTrue(papel.contains("\u001dV"))
        }
    }
    @Test fun qrUtf8ConLongitudYModuloExactos() {
        for ((ancho, modulo) in listOf(PaperWidth.MM58 to 6, PaperWidth.MM80 to 7)) {
            val bytes = ESCPOSPrinter(ancho).generateGiftCardCertificate(c)
            val datos = c.liga!!.toByteArray(Charsets.UTF_8)
            val bloque = byteArrayOf(29,40,107,((datos.size+3) % 256).toByte(),((datos.size+3)/256).toByte(),49,80,48) + datos
            assertTrue(bytes.toList().windowed(bloque.size).any { it == bloque.toList() })
            val size = byteArrayOf(29,40,107,3,0,49,67,modulo.toByte())
            assertTrue(bytes.toList().windowed(size.size).any { it == size.toList() })
        }
    }
    @Test fun sinOpcionalesNoInventaQrNiRenglones() {
        val papel = ESCPOSPrinter(PaperWidth.MM58).generateGiftCardCertificate(c.copy(para=null,de=null,mensaje=null,liga=null,vence="31/01/2027")).toString(Charsets.ISO_8859_1)
        assertTrue(papel.contains("Vence: 31/01/2027"))
        for (t in listOf("Para:", "De:", "Escanea el QR", "\u001d(k")) assertFalse(t, papel.contains(t))
        assertFalse(c.toString().contains(c.codigo)); assertFalse(c.toString().contains(c.liga!!))
    }
    @Test fun textoDelClienteNoInyectaComandosDeCajon() {
        val mensaje = "Feliz" + "\u001bp\u0000\u0019\u00fa" + " cumpleaños"
        for (ancho in PaperWidth.entries) {
            val bytes = ESCPOSPrinter(ancho).generateGiftCardCertificate(c.copy(mensaje=mensaje))
            val papel = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(papel.contains("Feliz")); assertTrue(papel.contains("cumpleaños"))
            assertFalse("El texto libre no puede producir ESC p", papel.contains("\u001bp"))
        }
    }
}
