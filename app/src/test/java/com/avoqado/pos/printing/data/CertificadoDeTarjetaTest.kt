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
            for (t in listOf("IQ", "TARJETA DE REGALO", "$500.01", "Para: Ana", "De: Luis", "\"Felicidades\"", "Código: K7QZ-ABCD-EFGH-JKMN-P", "Folio: JKMN", "Vence: Sin vencimiento", "Escanea el QR para ver tu saldo y los términos.", "No se cambia por efectivo. Quien tenga el código puede usarla: cuídala como efectivo.")) assertTrue(t, papel.replace('\n', ' ').contains(t))
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

    @Test fun unionRenglonesCabenEn58Y80() {
        val largo = c.copy(negocio = "FULLTEST R1 Boutique Tarjetas 202610280557",
            para = "Ana María de los Ángeles Hernández García",
            de = "Luis Fernando de la Fuente Fernández",
            mensaje = "Que disfrutes mucho este regalo y tengas un cumpleaños lleno de alegría")
        for (ancho in listOf(PaperWidth.MM58, PaperWidth.MM80)) {
            val bytes = ESCPOSPrinter(ancho).generateGiftCardCertificate(largo)
            val lineas = renglones(bytes)
            assertTrue("$ancho: $lineas", lineas.all { it.length <= ancho.charsPerLine })
            val texto = lineas.joinToString(" ")
            for (t in listOf(largo.negocio!!, "Para: ${largo.para}", "De: ${largo.de}", "\"${largo.mensaje}\""))
                assertTrue(t, texto.contains(t))
            val inicio = "\u001ba\u0001" + lineas.takeWhile { it != "TARJETA DE REGALO" }.joinToString("\n", postfix = "\n")
            assertTrue("negocio centrado", bytes.toString(Charsets.ISO_8859_1).contains(inicio))
        }
    }

    @Test fun unionAvisoNoPartePalabrasEn58Y80() {
        val aviso = "No se cambia por efectivo. Quien tenga el código puede usarla: cuídala como efectivo."
        for (ancho in listOf(PaperWidth.MM58, PaperWidth.MM80)) {
            val lineas = renglones(ESCPOSPrinter(ancho).generateGiftCardCertificate(c))
                .dropWhile { !it.startsWith("No se cambia") }
            assertTrue("$ancho: $lineas", lineas.all { it.length <= ancho.charsPerLine })
            assertEquals(aviso, lineas.joinToString(" "))
        }
    }

    @Test fun unionBarrasExactasEn72Y80ConYSinLiga() {
        for (ancho in listOf(PaperWidth.MM72, PaperWidth.MM80)) for (liga in listOf(c.liga, null)) {
            val bytes = ESCPOSPrinter(ancho).generateGiftCardCertificate(c.copy(liga = liga))
            val papel = bytes.toString(Charsets.ISO_8859_1)
            // GS k 73 19 {B + 17 caracteres; GS H 0 evita duplicar el texto visible.
            val bloque = "\u001dH\u0000\u001dkI\u0013{Bk7qzabcdefghjkmnp"
            assertTrue("$ancho", papel.contains(bloque))
            assertTrue(renglones(bytes).joinToString(" ").contains("Mismo código en barras, para lectores de rayas."))
            if (liga != null) assertTrue(papel.indexOf("\u001d(k") < papel.indexOf("\u001dk"))
            val i = papel.indexOf("\u001dk")
            val contenido = papel.substring(i + 6, i + 4 + papel[i + 3].code)
            assertEquals(com.avoqado.pos.giftcards.data.PruebaDeTarjeta.Codigo("K7QZABCDEFGHJKMNP"),
                com.avoqado.pos.giftcards.domain.ReconocedorDeTarjeta.reconocer(contenido))
            assertTrue(papel.contains("\u001dw\u0002"))
        }
    }

    @Test fun unionEn58ConservaQrSinBarrasNiLeyenda() {
        val papel = ESCPOSPrinter(PaperWidth.MM58).generateGiftCardCertificate(c).toString(Charsets.ISO_8859_1)
        assertFalse(papel.contains("\u001dk"))
        assertFalse(papel.contains("Mismo código"))
        assertTrue(papel.contains("\u001d(k"))
        assertTrue(papel.contains("Código: ${c.codigo}"))
    }

    @Test fun unionFBarraEnMinusculasConCodigoVisibleIntacto() {
        for (ancho in listOf(PaperWidth.MM72, PaperWidth.MM80)) {
            val bytes = ESCPOSPrinter(ancho).generateGiftCardCertificate(c)
            val i = bytes.toList().windowed(3).indexOfFirst { it == listOf<Byte>(29, 107, 73) }
            assertTrue("GS k Code128-B", i >= 0)
            val n = bytes[i + 3].toInt() and 255
            assertEquals(19, n)
            assertArrayEquals("{Bk7qzabcdefghjkmnp".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(i + 4, i + 4 + n))
            assertTrue(renglones(bytes).contains("Código: K7QZ-ABCD-EFGH-JKMN-P"))
        }
    }

    private fun renglones(bytes: ByteArray): List<String> {
        val texto = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 255
            i += when (b) {
                27 -> if (bytes[i + 1].toInt() == 64) 2 else 3
                29 -> when (bytes[i + 1].toInt()) {
                    40 -> 5 + (bytes[i + 3].toInt() and 255) + 256 * (bytes[i + 4].toInt() and 255)
                    107 -> 4 + (bytes[i + 3].toInt() and 255)
                    76, 87 -> 4
                    else -> 3
                }
                else -> { texto.append(b.toChar()); 1 }
            }
        }
        return texto.toString().lines().filter { it.isNotEmpty() }
    }
}
