package com.avoqado.pos.escritorio.impresion

import com.avoqado.pos.printing.data.model.PrinterException
import com.sun.jna.LastErrorException
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El orden de las llamadas a winspool/kernel32 y lo que se le dice al cajero, con un Windows falso: SistemaWindows sólo
 * traduce cada llamada a JNA (eso se verifica en un Windows real), las decisiones viven en ProtocoloDeWindows.kt.
 */
class ProtocoloDeWindowsTest {
    /** Un winspool falso: anota cada llamada en orden, lo que aceptó, y falla donde se le pida. */
    private class ColaFalsa : LlamadasDeCola<String> {
        val llamadas = mutableListOf<String>()
        /** Tras qué llamada se leyó el código de error: tiene que ser justo la que falló. */
        val errorLeidoTras = mutableListOf<String>()
        val recibido = ByteArrayOutputStream()
        var abre = true
        var iniciaDocumento = true
        var iniciaPagina = true
        var aceptaPorLlamada: Int? = null      // null = todo lo pedido; 0 = no acepta nada; -1 = WritePrinter falla
        var fallaAlEscribir: Throwable? = null
        var terminaPagina = true
        var terminaDocumento = true
        var cancela = true
        var fallaAlCancelar: Throwable? = null
        var error = 0
        override fun abrir(nombre: String): String? { llamadas += "OpenPrinter"; return if (abre) "h-$nombre" else null }
        override fun iniciarDocumento(h: String, documento: String): Boolean { llamadas += "StartDocPrinter"; return iniciaDocumento }
        override fun iniciarPagina(h: String): Boolean { llamadas += "StartPagePrinter"; return iniciaPagina }
        override fun escribir(h: String, bytes: ByteArray): Int {
            llamadas += "WritePrinter"
            fallaAlEscribir?.let { throw it }
            val aceptados = minOf(aceptaPorLlamada ?: bytes.size, bytes.size)   // WritePrinter nunca acepta más de lo pedido
            if (aceptados > 0) recibido.write(bytes, 0, aceptados)
            return aceptados
        }
        override fun terminarPagina(h: String): Boolean { llamadas += "EndPagePrinter"; return terminaPagina }
        override fun terminarDocumento(h: String): Boolean { llamadas += "EndDocPrinter"; return terminaDocumento }
        override fun abortar(h: String): Boolean {
            llamadas += "AbortPrinter"
            fallaAlCancelar?.let { throw it }
            return cancela
        }
        override fun cerrar(h: String) { llamadas += "ClosePrinter" }
        override fun ultimoError(): Int { errorLeidoTras += llamadas.lastOrNull().orEmpty(); return error }
    }

    private val ticket = byteArrayOf(0x1B, 0x40, 0x41, 0x0A)

    @Test fun `cola - trabajo completo en orden y se cierra`() {
        val w = ColaFalsa()
        imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket)
        assertEquals(
            listOf("OpenPrinter", "StartDocPrinter", "StartPagePrinter", "WritePrinter", "EndPagePrinter", "EndDocPrinter", "ClosePrinter"),
            w.llamadas,
        )
        assertContentEquals(ticket, w.recibido.toByteArray())
    }

    /** #9: un WritePrinter exitoso que acepta sólo una parte NO es una falla: se sigue desde el byte pendiente. */
    @Test fun `cola - WritePrinter que acepta una parte sigue en el MISMO trabajo hasta mandar todo`() {
        val largo = ByteArray(10) { (it + 1).toByte() }
        val w = ColaFalsa().apply { aceptaPorLlamada = 3 }
        imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", largo)
        assertContentEquals(largo, w.recibido.toByteArray())
        assertEquals(
            listOf(
                "OpenPrinter", "StartDocPrinter", "StartPagePrinter",
                "WritePrinter", "WritePrinter", "WritePrinter", "WritePrinter",
                "EndPagePrinter", "EndDocPrinter", "ClosePrinter",
            ),
            w.llamadas,
        )
    }

    /** #9: se INTENTA cancelar. No promete que no haya salido papel: Windows puede ir imprimiendo mientras recibe. */
    @Test fun `cola - WritePrinter que no acepta nada - se intenta cancelar el trabajo y se cierra`() {
        val w = ColaFalsa().apply { aceptaPorLlamada = 0 }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("La impresora «POS-80» no recibió el ticket completo. Revisa que esté encendida y vuelve a intentar."), e.message)
        assertFalse("bytes" in e.message!!, "el detalle técnico va a la bitácora, no al cajero")
        assertEquals(listOf("OpenPrinter", "StartDocPrinter", "StartPagePrinter", "WritePrinter", "AbortPrinter", "ClosePrinter"), w.llamadas)
    }

    @Test fun `cola - WritePrinter que falla tambien se intenta cancelar`() {
        val w = ColaFalsa().apply { aceptaPorLlamada = -1; error = 31 }
        assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue("AbortPrinter" in w.llamadas); assertFalse("EndDocPrinter" in w.llamadas)
        assertEquals("ClosePrinter", w.llamadas.last())
        assertEquals("WritePrinter", w.errorLeidoTras.first(), "el código de error es el de WritePrinter")
    }

    /** #17: el cajero no ve la clase de Java, la DLL ni el mensaje en inglés; eso va a la bitácora. */
    @Test fun `cola - un Error de JNA a media escritura se intenta cancelar, cierra y sale como PrinterException sin detalle tecnico`() {
        val w = ColaFalsa().apply { fallaAlEscribir = UnsatisfiedLinkError("Unable to load library 'winspool.drv'") }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("No se pudo hablar con la impresora en Windows. Vuelve a intentar; si sigue, reinicia el POS."), e.message)
        for (tecnico in listOf("UnsatisfiedLinkError", "winspool.drv", "detalle", "Unable")) assertFalse(tecnico in e.message!!, e.message)
        assertEquals(listOf("OpenPrinter", "StartDocPrinter", "StartPagePrinter", "WritePrinter", "AbortPrinter", "ClosePrinter"), w.llamadas)
    }

    @Test fun `cola - StartPage que falla se intenta cancelar`() {
        val w = ColaFalsa().apply { iniciaPagina = false }
        assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertEquals(listOf("OpenPrinter", "StartDocPrinter", "StartPagePrinter", "AbortPrinter", "ClosePrinter"), w.llamadas)
    }

    /** #7: EndDocPrinter también está dentro de lo protegido: si falla, se intenta cancelar. */
    @Test fun `cola - EndDocPrinter que falla - se intenta cancelar y se cierra`() {
        val w = ColaFalsa().apply { terminaDocumento = false }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("no recibió el ticket completo"), e.message)
        assertEquals(listOf("EndPagePrinter", "EndDocPrinter", "AbortPrinter", "ClosePrinter"), w.llamadas.takeLast(4))
    }

    /** #8: AbortPrinter que devuelve FALSE (o truena) no canceló: el cajero tiene que revisar la cola antes de reimprimir. */
    @Test fun `cola - si AbortPrinter no puede cancelar lo dice, y su error se lee en el acto`() {
        val w = ColaFalsa().apply { aceptaPorLlamada = 0; cancela = false; error = 6 }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(
            e.message!!.contains("No se pudo cancelar el trabajo en Windows; revisa la cola de la impresora antes de volver a imprimir."),
            e.message,
        )
        assertTrue("AbortPrinter" in w.errorLeidoTras, "el código de AbortPrinter se lee justo después: ${w.errorLeidoTras}")
        assertEquals("ClosePrinter", w.llamadas.last())

        val w2 = ColaFalsa().apply { aceptaPorLlamada = 0; fallaAlCancelar = IllegalStateException("spooler") }
        val e2 = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w2, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e2.message!!.contains("No se pudo cancelar el trabajo en Windows"), e2.message)
        assertEquals("ClosePrinter", w2.llamadas.last())
    }

    @Test fun `cola - driver que no acepta RAW (1804) lo dice con el remedio`() {
        val w = ColaFalsa().apply { iniciaDocumento = false; error = 1804 }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("La impresora «POS-80» no acepta tickets directos. Usa su driver de tickets o el driver «Generic / Text Only»."), e.message)
        assertEquals(listOf("OpenPrinter", "StartDocPrinter", "ClosePrinter"), w.llamadas)
    }

    /** #18: acceso denegado no es «ticket incompleto, revisa que esté encendida». */
    @Test fun `cola - StartDoc con acceso denegado (5) dice que revise los permisos`() {
        val w = ColaFalsa().apply { iniciaDocumento = false; error = 5 }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("Windows no permite imprimir en «POS-80» con este usuario. Revisa los permisos de la impresora."), e.message)
        assertFalse("error 5" in e.message!!)
    }

    @Test fun `cola - StartDoc rechazado por otra razon`() {
        val w = ColaFalsa().apply { iniciaDocumento = false; error = 87 }
        val e = assertFailsWith<PrinterException.PrintFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("no recibió el ticket completo"), e.message)
        assertFalse("87" in e.message!!)
    }

    @Test fun `cola - OpenPrinter con nombre que Windows ya no conoce (1801)`() {
        val w = ColaFalsa().apply { abre = false; error = 1801 }
        val e = assertFailsWith<PrinterException.ConnectionFailed> { imprimirTrabajoCrudo(w, "POS-80", "Avoqado POS", ticket) }
        assertTrue(e.message!!.contains("Windows ya no tiene la impresora «POS-80» (la borraron o le cambiaron el nombre). Bórrala de Avoqado y vuelve a buscarla."), e.message)
        assertEquals(listOf("OpenPrinter"), w.llamadas)
    }

    /** Un kernel32 falso para un puerto COM: anota las llamadas y los bytes que aceptó, en orden. */
    private class PuertoFalso : LlamadasDePuerto<String> {
        val llamadas = mutableListOf<String>()
        val trozos = mutableListOf<Int>()
        val recibido = ByteArrayOutputStream()
        var abre = true
        var configura = true
        var fallaAlConfigurar: Throwable? = null
        var fijaTiempos = true
        var aceptaPorLlamada: Int? = null      // null = todo el trozo
        var error = 0
        override fun abrir(puerto: String): String? { llamadas += "CreateFile"; return if (abre) "h-$puerto" else null }
        override fun configurar(h: String, baudios: Int?): Boolean {
            llamadas += "SetCommState($baudios)"
            fallaAlConfigurar?.let { throw it }
            return configura
        }
        override fun fijarTiempos(h: String): Boolean { llamadas += "SetCommTimeouts"; return fijaTiempos }
        override fun escribir(h: String, trozo: ByteArray): Int {
            llamadas += "WriteFile"
            val aceptados = minOf(aceptaPorLlamada ?: trozo.size, trozo.size)   // WriteFile nunca acepta más de lo pedido
            if (aceptados > 0) { trozos += aceptados; recibido.write(trozo, 0, aceptados) }
            return aceptados
        }
        override fun cerrar(h: String) { llamadas += "CloseHandle" }
        override fun ultimoError(): Int = error
    }

    @Test fun `com - abre, configura, fija tiempos y escribe en trozos`() {
        val p = PuertoFalso()
        val canal = abrirCanalCom(p, "COM3", 9600)
        val largo = ByteArray(10_000) { (it % 251).toByte() }
        canal.escribir(largo)
        assertEquals(listOf(4_096, 4_096, 1_808), p.trozos)
        assertContentEquals(largo, p.recibido.toByteArray())
        canal.close(); canal.close()
        assertEquals(listOf("CreateFile", "SetCommState(9600)", "SetCommTimeouts", "WriteFile", "WriteFile", "WriteFile", "CloseHandle"), p.llamadas)
    }

    /** #14: sin velocidad en la dirección, configurar recibe null (se queda la de Windows). */
    @Test fun `com - sin velocidad en la direccion se configura con la de Windows`() {
        val p = PuertoFalso()
        abrirCanalCom(p, "COM3", null)
        assertEquals(listOf("CreateFile", "SetCommState(null)", "SetCommTimeouts"), p.llamadas)
    }

    /** #22: con bytes DISTINTOS, para que repetir el principio no pase por bueno. */
    @Test fun `com - escritura parcial sigue desde donde se quedo`() {
        val p = PuertoFalso().apply { aceptaPorLlamada = 3 }
        val ticket = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        abrirCanalCom(p, "COM3", 9600).escribir(ticket)
        assertEquals(listOf(3, 3, 1), p.trozos)
        assertContentEquals(ticket, p.recibido.toByteArray())
    }

    @Test fun `com - si no se puede configurar, el handle se cierra y lo dice`() {
        val p = PuertoFalso().apply { configura = false; error = 87 }
        val e = assertFailsWith<PrinterException.ConnectionFailed> { abrirCanalCom(p, "COM3", 115200) }
        assertTrue(e.message!!.contains("No se pudo preparar el puerto COM3 para imprimir."), e.message)
        assertEquals(listOf("CreateFile", "SetCommState(115200)", "CloseHandle"), p.llamadas)
    }

    @Test fun `com - si fijar los tiempos falla, el handle se cierra`() {
        val p = PuertoFalso().apply { fijaTiempos = false }
        assertFailsWith<PrinterException.ConnectionFailed> { abrirCanalCom(p, "COM3", 9600) }
        assertEquals("CloseHandle", p.llamadas.last())
    }

    @Test fun `com - un Error de JNA al configurar cierra el handle y sale como ConnectionFailed`() {
        val p = PuertoFalso().apply { fallaAlConfigurar = UnsatisfiedLinkError("kernel32") }
        val e = assertFailsWith<PrinterException.ConnectionFailed> { abrirCanalCom(p, "COM3", 9600) }
        assertTrue(e.message!!.contains("No se pudo hablar con la impresora en Windows"), e.message)
        assertFalse("kernel32" in e.message!!, e.message)
        assertEquals(listOf("CreateFile", "SetCommState(9600)", "CloseHandle"), p.llamadas)
    }

    /** #15 y #18: el remedio corresponde al error, y en Bluetooth dice cuál de los dos COM elegir. */
    @Test fun `com - CreateFile que falla dice que hacer, sin el numero de error como unico dato`() {
        fun mensaje(error: Int) = assertFailsWith<PrinterException.ConnectionFailed> {
            abrirCanalCom(PuertoFalso().apply { abre = false; this.error = error }, "COM5", 9600)
        }.message!!
        assertTrue(mensaje(2).contains("El puerto COM5 ya no existe."))
        val ocupado = mensaje(5)
        assertTrue(ocupado.contains("El puerto COM5 está ocupado por otro programa o Windows no dejó abrirlo."), ocupado)
        val otro = mensaje(121)
        assertTrue(otro.contains("No se pudo abrir el puerto COM5."), otro)
        assertTrue(
            otro.contains("Si es Bluetooth, elige el puerto COM SALIENTE de esa impresora (Windows › Bluetooth › Más opciones › Puertos COM) y confírmalo con la página de prueba."),
            otro,
        )
        assertFalse("121" in otro, otro)
    }

    @Test fun `com - si deja de responder a media escritura lo dice`() {
        val p = PuertoFalso().apply { aceptaPorLlamada = 0 }
        val e = assertFailsWith<PrinterException.PrintFailed> { abrirCanalCom(p, "COM3", 9600).escribir(byteArrayOf(1, 2)) }
        assertTrue(e.message!!.contains("La impresora en COM3 dejó de responder."), e.message)
    }

    // --- por cada impresión se pregunta por ESA cola, no se enumeran todas ---

    @Test fun `SistemaWindows - cola por nombre pregunta por esa cola y no enumera todas`() {
        val pedidas = mutableListOf<String>()
        val s = SistemaWindows(
            leerTodasLasColas = { error("imprimir no debe enumerar TODAS las impresoras de Windows") },
            leerUnaCola = { nombre -> pedidas += nombre; ColaInfo(nombre, "USB001", "Generic / Text Only", 0, 0) },
        )
        assertEquals("POS-80", s.cola("POS-80")?.nombre)
        assertEquals(listOf("POS-80"), pedidas)
    }

    /** #11: sólo ERROR_INVALID_PRINTER_NAME (1801) quiere decir «Windows ya no la tiene». */
    @Test fun `SistemaWindows - una cola que Windows no conoce (1801) da null`() {
        val s = SistemaWindows(leerTodasLasColas = { emptyList() }, leerUnaCola = { throw LastErrorException(1801) })
        assertNull(s.cola("Desconectada"))
    }

    /** #11: acceso denegado, spooler caído o lo que sea NO es «la borraron»: el remedio sería borrar una impresora buena. */
    @Test fun `SistemaWindows - cualquier otro fallo al consultar no es «la borraron»`() {
        for (falla in listOf(LastErrorException(5), LastErrorException(1722), IllegalStateException("spooler"))) {
            val s = SistemaWindows(leerTodasLasColas = { emptyList() }, leerUnaCola = { throw falla })
            val e = assertFailsWith<PrinterException.ConnectionFailed>(falla.toString()) { s.cola("POS-80") }
            assertTrue(e.message!!.contains("No se pudo consultar la impresora «POS-80» en Windows. Vuelve a intentar."), e.message)
        }
    }

    /** #12: GetPrinter que no trae nombre es una consulta fallida, nunca una cola sana con el nombre que se pidió. */
    @Test fun `SistemaWindows - una respuesta sin nombre es una consulta fallida, no una cola sana`() {
        assertFailsWith<IllegalStateException> { colaInfoDeWindows(null, null, null, 0, 0) }
        assertFailsWith<IllegalStateException> { colaInfoDeWindows(" ", "USB001", "Generic / Text Only", 0, 0) }
        assertEquals(
            ColaInfo("POS-80", "USB001", "Generic / Text Only", 0x80, 0x400),
            colaInfoDeWindows("POS-80", "USB001", "Generic / Text Only", 0x80, 0x400),
        )
        val s = SistemaWindows(leerTodasLasColas = { emptyList() }, leerUnaCola = { colaInfoDeWindows(null, null, null, 0, 0) })
        val e = assertFailsWith<PrinterException.ConnectionFailed> { s.cola("POS-80") }
        assertTrue(e.message!!.contains("No se pudo consultar la impresora «POS-80» en Windows."), e.message)
    }

    @Test fun `SistemaWindows - buscar si enumera todas y una falla da lista vacia`() {
        val todas = listOf(ColaInfo("POS-80", "USB001", "Generic / Text Only", 0, 0))
        assertEquals(todas, SistemaWindows(leerTodasLasColas = { todas }, leerUnaCola = { null }).colas())
        assertEquals(emptyList(), SistemaWindows(leerTodasLasColas = { throw UnsatisfiedLinkError("winspool.drv") }, leerUnaCola = { null }).colas())
    }
}
